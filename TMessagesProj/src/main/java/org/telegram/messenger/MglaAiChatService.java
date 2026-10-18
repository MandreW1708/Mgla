package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conversation transport.  OpenRouter is consumed as SSE, therefore callers receive
 * tokens as they arrive instead of waiting for a whole answer.  One request owns one
 * cancellable object; this is important when a fragment is destroyed or the user taps stop.
 */
public final class MglaAiChatService {
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_LOCAL = "local";
    /** Empty model = hub auto-fallback across free OpenRouter models. */
    public static final String MODEL_AUTO = "";
    public static final String DEFAULT_MODEL = MODEL_AUTO;
    public static final String STATUS_UP = "up";
    public static final String STATUS_DEGRADED = "degraded";
    public static final String STATUS_DOWN = "down";
    private static final String URL_CHAT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String PREFS = "mgla_config";
    private static final String KEY_MODELS_CACHE = "ai_openrouter_models_cache_v3";
    private static final String KEY_MODELS_CACHE_AT = "ai_openrouter_models_cache_at_v3";
    private static final String KEY_MODELS_CACHE_PLAN = "ai_openrouter_models_cache_plan_v3";
    private static final long MODELS_CACHE_TTL_MS = 5L * 60L * 1000L;
    public static final String[] FALLBACK_MODELS = {
            "nvidia/nemotron-3-super-120b-a12b:free",
            "nvidia/nemotron-3-ultra-550b-a55b:free",
            "nvidia/nemotron-3.5-lightning:free",
            "nex-agi/nex-n2.5-pro:free",
            "google/gemma-4-31b-it:free"
    };

    public interface Callback {
        void onDelta(String text);
        void onComplete();
        void onError(String message);
        /** Optional local-GGUF progress («Читаю запрос…» / «Пишу ответ…»). */
        default void onStatus(String status) {
        }
    }

    public static final class ModelInfo {
        public final String id;
        /** up / degraded / down */
        public final String status;
        public final boolean available;
        /** From Premium Ultra catalog — show ★ and count toward 20/day. */
        public final boolean ultra;
        public final String sourcePlan;

        public ModelInfo(String id, String status) {
            this(id, status, false, "");
        }

        public ModelInfo(String id, String status, boolean ultra, String sourcePlan) {
            this.id = id;
            this.status = normalizeStatus(status);
            this.available = !STATUS_DOWN.equals(this.status);
            this.ultra = ultra || MglaAiLimits.isUltraCatalogModel(id);
            this.sourcePlan = !TextUtils.isEmpty(sourcePlan)
                    ? sourcePlan
                    : (this.ultra ? MglaSubscription.PLAN_PREMIUM_ULTRA : MglaSubscription.PLAN_FREE);
        }

        public ModelInfo(String id, boolean available) {
            this(id, available ? STATUS_UP : STATUS_DOWN);
        }
    }

    public static String normalizeStatus(String status) {
        if (STATUS_DOWN.equals(status)) {
            return STATUS_DOWN;
        }
        if (STATUS_DEGRADED.equals(status)) {
            return STATUS_DEGRADED;
        }
        return STATUS_UP;
    }

    public static String statusDot(String status) {
        status = normalizeStatus(status);
        if (STATUS_DOWN.equals(status)) {
            return "🔴  ";
        }
        if (STATUS_DEGRADED.equals(status)) {
            return "🟡  ";
        }
        return "🟢  ";
    }

    public interface ModelsCallback {
        void onResult(ArrayList<ModelInfo> models);
        void onError(String message);
    }

    public static final class Request {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpURLConnection connection;
        private volatile long localHandle;
        public void cancel() {
            cancelled.set(true);
            HttpURLConnection c = connection;
            if (c != null) {
                c.disconnect();
            }
            long h = localHandle;
            if (h != 0) {
                MglaLlamaNative.abort(h);
            }
        }
        void bindLocalHandle(long handle) {
            localHandle = handle;
        }
        boolean isCancelled() { return cancelled.get(); }
    }

    private MglaAiChatService() { }

    public static Request send(String provider, String model, ArrayList<MglaAiChatStorage.Message> messages, ArrayList<String> imagePaths, float temperature, int maxTokens, Callback callback) {
        Request request = new Request();
        new Thread(() -> {
            try {
                if (PROVIDER_LOCAL.equals(provider)) {
                    if (!MglaSubscription.canUseLocalModels()) {
                        throw new Exception("Локальные модели доступны с Mgla Premium");
                    }
                    // A local model is loaded lazily by the native runtime, never while scrolling the chat.
                    if (imagePaths != null && !imagePaths.isEmpty()) {
                        throw new Exception("Выбранная локальная GGUF-модель не поддерживает изображения. Выберите vision-модель в OpenRouter.");
                    }
                    MglaLocalChatRuntime.stream(messages, temperature, maxTokens, request, callback);
                } else {
                    if (!MglaSubscription.canUseOpenRouter()) {
                        throw new Exception("Облачные модели доступны с Premium+ и Ultra");
                    }
                    if (imagePaths != null && !imagePaths.isEmpty() && !MglaSubscription.canAttachMedia()) {
                        throw new Exception("Вложения доступны только на Premium Ultra");
                    }
                    if (!MglaAiLimits.canSendCloudRequest(model)) {
                        throw new Exception("Дневной лимит Ultra-моделей исчерпан (20/день). Выберите модель без ★.");
                    }
                    MglaAiLimits.ModelBudget budget = MglaAiLimits.budgetFor(model);
                    int capped = Math.min(Math.max(32, maxTokens), budget.outputTokens);
                    streamOpenRouter(model, messages, imagePaths, temperature, capped, request, callback);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                String message = readableError(e);
                if (!PROVIDER_LOCAL.equals(provider)) {
                    reportModelFailure(model, message);
                }
                if (!request.isCancelled()) uiError(callback, message);
            }
        }, "MglaAiChat").start();
        return request;
    }

    /**
     * Crowd-sourced availability: clients report timeouts / dead endpoints.
     * 1 reporter → yellow (degraded), 2+ → red (down).
     */
    public static void reportModelFailure(String model, String errorMessage) {
        if (TextUtils.isEmpty(model) || !MglaHubHttp.isConfigured()) {
            return;
        }
        final String kind = classifyFailureKind(errorMessage);
        if (kind == null) {
            return;
        }
        // Drop local cache so the next picker refresh sees the updated status.
        modelsPrefs().edit().putLong(KEY_MODELS_CACHE_AT, 0).apply();
        postModelReport(model, kind);
    }

    /** Clear this install's failure reports after a successful reply. */
    public static void reportModelSuccess(String model) {
        if (TextUtils.isEmpty(model) || !MglaHubHttp.isConfigured()) {
            return;
        }
        modelsPrefs().edit().putLong(KEY_MODELS_CACHE_AT, 0).apply();
        postModelReport(model, "ok");
    }

    private static void postModelReport(String model, String kind) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("install_id", MglaStats.getInstallId());
                body.put("kind", kind);
                MglaHubHttp.post(
                        "/mgla-ai/v1/models/report",
                        body.toString().getBytes(StandardCharsets.UTF_8),
                        4_000,
                        8_000
                );
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, "MglaAiModelReport").start();
    }

    private static String classifyFailureKind(String errorMessage) {
        if (TextUtils.isEmpty(errorMessage)) {
            return null;
        }
        String m = errorMessage.toLowerCase();
        if (m.contains("timeout") || m.contains("timed out") || m.contains("time out")) {
            return "timeout";
        }
        if (m.contains("no endpoints") || m.contains("not found") || m.contains("http 404")) {
            return "no_endpoints";
        }
        if (m.contains("unavailable") || m.contains("http 502") || m.contains("http 503") || m.contains("http 429")) {
            return "unavailable";
        }
        return null;
    }

    /** Fresh local cache for the model picker (respects TTL; no network). */
    public static ArrayList<ModelInfo> peekCachedOpenRouterModels() {
        return readCachedModels(false);
    }

    /**
     * Models from mglabot. Cache-first so the picker opens immediately;
     * network refresh updates the cache for the next open (single callback).
     */
    public static void fetchOpenRouterModels(ModelsCallback callback) {
        if (callback == null) {
            return;
        }
        if (!MglaSubscription.canUseOpenRouter()) {
            AndroidUtilities.runOnUIThread(() -> callback.onError("Облачные модели доступны с Premium+ и Ultra"));
            return;
        }
        ArrayList<ModelInfo> cached = readCachedModels(false);
        if (cached != null && !cached.isEmpty()) {
            final ArrayList<ModelInfo> instant = cached;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(instant));
            new Thread(() -> {
                try {
                    ArrayList<ModelInfo> remote = downloadModelsFromHub();
                    if (remote != null && !remote.isEmpty()) {
                        writeCachedModels(remote);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }, "MglaAiModelsRefresh").start();
            return;
        }
        new Thread(() -> {
            try {
                ArrayList<ModelInfo> remote = downloadModelsFromHub();
                if (remote == null || remote.isEmpty()) {
                    remote = fallbackModelsList();
                }
                writeCachedModels(remote);
                final ArrayList<ModelInfo> result = remote;
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            } catch (Throwable e) {
                FileLog.e(e);
                ArrayList<ModelInfo> fallback = fallbackModelsList();
                final ArrayList<ModelInfo> result = fallback;
                final String error = !TextUtils.isEmpty(e.getMessage()) ? e.getMessage() : "Не удалось загрузить модели";
                AndroidUtilities.runOnUIThread(() -> {
                    if (result.isEmpty()) {
                        callback.onError(error);
                    } else {
                        callback.onResult(result);
                    }
                });
            }
        }, "MglaAiModels").start();
    }

    private static ArrayList<ModelInfo> fallbackModelsList() {
        ArrayList<ModelInfo> out = new ArrayList<>();
        for (String m : FALLBACK_MODELS) {
            String status = m.contains("lightning") ? STATUS_DEGRADED : STATUS_UP;
            out.add(new ModelInfo(m, status, false, MglaSubscription.PLAN_FREE));
        }
        return out;
    }

    private static SharedPreferences modelsPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static ArrayList<ModelInfo> readCachedModels(boolean ignoreTtl) {
        SharedPreferences p = modelsPrefs();
        String plan = MglaSubscription.openRouterPlanId();
        if (TextUtils.isEmpty(plan)) {
            return null;
        }
        if (!plan.equals(p.getString(KEY_MODELS_CACHE_PLAN, ""))) {
            return null;
        }
        long at = p.getLong(KEY_MODELS_CACHE_AT, 0);
        if (!ignoreTtl && (at <= 0 || System.currentTimeMillis() - at > MODELS_CACHE_TTL_MS)) {
            return null;
        }
        String raw = p.getString(KEY_MODELS_CACHE, "");
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            ArrayList<ModelInfo> out = parseModelsArray(arr, false, "");
            return out.isEmpty() ? null : out;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static void writeCachedModels(ArrayList<ModelInfo> models) {
        if (models == null || models.isEmpty()) {
            return;
        }
        String plan = MglaSubscription.openRouterPlanId();
        if (TextUtils.isEmpty(plan)) {
            return;
        }
        JSONArray arr = new JSONArray();
        for (ModelInfo m : models) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("status", m.status);
                o.put("available", m.available);
                o.put("ultra", m.ultra);
                o.put("plan", m.sourcePlan);
                arr.put(o);
            } catch (Throwable ignore) {
                arr.put(m.id);
            }
        }
        modelsPrefs().edit()
                .putString(KEY_MODELS_CACHE, arr.toString())
                .putString(KEY_MODELS_CACHE_PLAN, plan)
                .putLong(KEY_MODELS_CACHE_AT, System.currentTimeMillis())
                .apply();
    }

    private static ArrayList<ModelInfo> downloadModelsFromHub() throws Exception {
        if (!MglaHubHttp.isConfigured()) {
            return null;
        }
        String plan = MglaSubscription.openRouterPlanId();
        if (TextUtils.isEmpty(plan)) {
            return null;
        }
        // Crowd status is local on the hub — keep timeouts short so the picker stays snappy.
        MglaHubHttp.Response response = MglaHubHttp.get("/mgla-ai/v1/models", 4_000, 8_000);
        if (response.code != 200) {
            throw new Exception("Хаба моделей: HTTP " + response.code);
        }
        JSONObject json = new JSONObject(response.body);
        JSONObject plans = json.optJSONObject("plans");
        ArrayList<ModelInfo> out;
        if (MglaSubscription.PLAN_PREMIUM_ULTRA.equals(plan) && plans != null) {
            // Ultra sees Ultra ★ first, then Plus, then Free (deduped).
            out = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            appendPlanModels(out, seen, plans, MglaSubscription.PLAN_PREMIUM_ULTRA, true);
            appendPlanModels(out, seen, plans, MglaSubscription.PLAN_PREMIUM_PLUS, false);
            appendPlanModels(out, seen, plans, MglaSubscription.PLAN_FREE, false);
        } else {
            JSONArray arr = null;
            if (plans != null) {
                JSONObject entry = plans.optJSONObject(plan);
                if (entry != null) {
                    arr = entry.optJSONArray("models");
                }
            }
            if (arr == null || arr.length() == 0) {
                arr = json.optJSONArray("models");
            }
            out = parseModelsArray(arr, false, plan);
        }
        return out == null || out.isEmpty() ? null : out;
    }

    private static void appendPlanModels(ArrayList<ModelInfo> out, java.util.HashSet<String> seen,
                                         JSONObject plans, String planId, boolean ultra) {
        JSONObject entry = plans.optJSONObject(planId);
        if (entry == null) {
            return;
        }
        ArrayList<ModelInfo> chunk = parseModelsArray(entry.optJSONArray("models"), ultra, planId);
        for (ModelInfo info : chunk) {
            if (seen.add(info.id)) {
                out.add(info);
            }
        }
    }

    private static ArrayList<ModelInfo> parseModelsArray(JSONArray arr, boolean ultra, String sourcePlan) {
        ArrayList<ModelInfo> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            if (item instanceof JSONObject) {
                JSONObject o = (JSONObject) item;
                String id = o.optString("id", "").trim();
                if (!TextUtils.isEmpty(id)) {
                    String status = o.optString("status", "").trim();
                    if (TextUtils.isEmpty(status)) {
                        status = o.optBoolean("available", true) ? STATUS_UP : STATUS_DOWN;
                    }
                    boolean isUltra = ultra || o.optBoolean("ultra", false);
                    String plan = o.optString("plan", sourcePlan);
                    out.add(new ModelInfo(id, status, isUltra, plan));
                }
            } else if (item instanceof String) {
                String id = ((String) item).trim();
                if (!TextUtils.isEmpty(id)) {
                    out.add(new ModelInfo(id, STATUS_UP, ultra, sourcePlan));
                }
            } else {
                String id = arr.optString(i, "").trim();
                if (!TextUtils.isEmpty(id)) {
                    out.add(new ModelInfo(id, STATUS_UP, ultra, sourcePlan));
                }
            }
        }
        return out;
    }

    private static final String TRUNCATION_NOTICE =
            "\n\n…ответ обрезан (лимит длины). Спросите «продолжи».";
    private static final String INTERRUPTED_NOTICE =
            "\n\n⚠️ Соединение оборвалось — ответ может быть неполным.";

    private static void streamOpenRouter(String model, ArrayList<MglaAiChatStorage.Message> messages, ArrayList<String> imagePaths, float temperature, int maxTokens, Request request, Callback callback) throws Exception {
        if (TextUtils.isEmpty(BuildConfig.OPENROUTER_API_KEY)) {
            if (MglaHubHttp.isConfigured()) {
                streamOpenRouterViaHub(model, messages, request, callback);
                return;
            }
            throw new Exception("Для чата OpenRouter нужен ключ в конфигурации сборки");
        }
        JSONObject body = new JSONObject();
        // Direct OpenRouter path has no auto-fallback list — use first free model if empty.
        body.put("model", TextUtils.isEmpty(model) ? FALLBACK_MODELS[0] : model);
        body.put("stream", true);
        body.put("temperature", Math.max(0f, Math.min(2f, temperature)));
        MglaAiLimits.ModelBudget budget = MglaAiLimits.budgetFor(model);
        body.put("max_tokens", Math.min(budget.outputTokens, Math.max(32, maxTokens)));
        JSONArray list = clipMessagesForOpenRouter(messages, imagePaths, budget);
        body.put("messages", list);

        HttpURLConnection conn = (HttpURLConnection) new URL(URL_CHAT).openConnection();
        request.connection = conn;
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30_000);
        // Idle gap between SSE tokens (slow free models) — was easy to hit 120s mid-reply.
        conn.setReadTimeout(180_000);
        conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.OPENROUTER_API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("HTTP-Referer", "https://mgla.app");
        conn.setRequestProperty("X-Title", "Mgla");
        try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (code >= 400) throw new Exception("OpenRouter: HTTP " + code + " — " + readLimited(stream, 1200));
        boolean sawDone = false;
        String finishReason = null;
        int contentChars = 0;
        String streamFatal = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while (!request.isCancelled() && streamFatal == null && (line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    sawDone = true;
                    break;
                }
                try {
                    JSONObject json = new JSONObject(data);
                    // OpenRouter may put the error in the SSE payload instead of HTTP status.
                    if (json.has("error")) {
                        Object err = json.opt("error");
                        String msg = err instanceof JSONObject
                                ? ((JSONObject) err).optString("message", err.toString())
                                : String.valueOf(err);
                        streamFatal = TextUtils.isEmpty(msg) ? "Ошибка стрима OpenRouter" : msg;
                        break;
                    }
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject choice = choices.optJSONObject(0);
                    if (choice == null) continue;
                    String fr = choice.optString("finish_reason", null);
                    if (!TextUtils.isEmpty(fr) && !"null".equals(fr)) {
                        finishReason = fr;
                    }
                    JSONObject delta = choice.optJSONObject("delta");
                    String token = delta == null ? null : delta.optString("content", null);
                    if (!TextUtils.isEmpty(token)) {
                        contentChars += token.length();
                        AndroidUtilities.runOnUIThread(() -> callback.onDelta(token));
                    }
                } catch (Throwable ignored) {
                    // Providers occasionally send non-content SSE events; they are harmless.
                }
            }
        } finally { conn.disconnect(); request.connection = null; }
        if (streamFatal != null) {
            throw new Exception(streamFatal);
        }
        if (request.isCancelled()) {
            return;
        }
        if (contentChars == 0 && !sawDone) {
            throw new Exception("Соединение оборвалось — ответ не получен");
        }
        String notice = null;
        if ("length".equals(finishReason)) {
            notice = TRUNCATION_NOTICE;
        } else if (!sawDone && TextUtils.isEmpty(finishReason)) {
            notice = INTERRUPTED_NOTICE;
        }
        if (notice != null) {
            final String n = notice;
            AndroidUtilities.runOnUIThread(() -> callback.onDelta(n));
        }
        reportModelSuccess(model);
        MglaAiLimits.consumeCloudRequest(model);
        AndroidUtilities.runOnUIThread(callback::onComplete);
    }

    private static void streamOpenRouterViaHub(String model, ArrayList<MglaAiChatStorage.Message> messages, Request request, Callback callback) throws Exception {
        String transcript = buildClippedTranscript(messages, model);
        JSONObject body = new JSONObject();
        body.put("message", transcript);
        body.put("lang", "Russian");
        // Ultra subscribers: send ultra so hub may fall back across all plans.
        // Explicit Free/Plus pick: send that model's owning plan for the right API key.
        String plan = resolveRequestPlan(model);
        if (!TextUtils.isEmpty(plan)) {
            body.put("plan", plan);
        }
        if (!TextUtils.isEmpty(model)) {
            body.put("model", model);
        } else if (MglaSubscription.PLAN_PREMIUM_ULTRA.equals(MglaSubscription.getPlanId())
                && MglaAiLimits.remainingToday() <= 0) {
            // Auto with exhausted ★ quota — hub must skip Ultra catalog models.
            body.put("exclude_ultra", true);
        }
        MglaHubHttp.Response response = MglaHubHttp.post(
                "/mgla-ai/v1/chat",
                body.toString().getBytes(StandardCharsets.UTF_8),
                30_000,
                150_000
        );
        if (request.isCancelled()) {
            return;
        }
        if (response.code != 200) {
            String err = null;
            try {
                err = new JSONObject(response.body).optString("error", null);
            } catch (Throwable ignore) {
                err = MglaHubHttp.extractJsonStringField(response.body, "error");
            }
            throw new Exception(!TextUtils.isEmpty(err) ? err : ("Хаб ИИ: HTTP " + response.code));
        }
        String text;
        String usedModel;
        try {
            JSONObject json = new JSONObject(response.body);
            text = json.optString("content", "");
            usedModel = json.optString("model", model);
        } catch (Throwable parseErr) {
            // Fallback for odd payloads — prefer real JSONObject when possible.
            text = MglaHubHttp.extractJsonStringField(response.body, "content");
            usedModel = MglaHubHttp.extractJsonStringField(response.body, "model");
        }
        if (TextUtils.isEmpty(text)) {
            throw new Exception("пустой ответ хаба");
        }
        if (TextUtils.isEmpty(usedModel)) {
            usedModel = model;
        }
        // Drip in larger chunks so long replies don't "freeze" mid-animation for many seconds.
        final int chunk = 48;
        long delay = 0;
        for (int i = 0; i < text.length(); i += chunk) {
            final String part = text.substring(i, Math.min(text.length(), i + chunk));
            AndroidUtilities.runOnUIThread(() -> {
                if (!request.isCancelled()) {
                    callback.onDelta(part);
                }
            }, delay);
            delay += 12;
        }
        reportModelSuccess(usedModel);
        MglaAiLimits.consumeCloudRequest(usedModel);
        final long completeDelay = delay;
        AndroidUtilities.runOnUIThread(() -> {
            if (!request.isCancelled()) {
                callback.onComplete();
            }
        }, completeDelay);
    }

    /** Plan bucket for hub key selection. */
    private static String resolveRequestPlan(String model) {
        if (MglaAiLimits.isUltraCatalogModel(model)) {
            return MglaSubscription.PLAN_PREMIUM_ULTRA;
        }
        if (TextUtils.isEmpty(model)) {
            // Auto: Ultra may try every tier; Plus stays on plus list.
            return MglaSubscription.openRouterPlanId();
        }
        // Non-ultra explicit pick under Ultra sub → free/plus key via owning plan.
        if (MglaSubscription.PLAN_PREMIUM_ULTRA.equals(MglaSubscription.getPlanId())) {
            ArrayList<ModelInfo> cached = peekCachedOpenRouterModels();
            if (cached != null) {
                for (ModelInfo info : cached) {
                    if (model.equals(info.id) && !TextUtils.isEmpty(info.sourcePlan)) {
                        return info.sourcePlan;
                    }
                }
            }
            return MglaSubscription.PLAN_FREE;
        }
        return MglaSubscription.openRouterPlanId();
    }

    /** Build hub transcript and silently keep only the recent tail within the input budget. */
    private static String buildClippedTranscript(ArrayList<MglaAiChatStorage.Message> messages, String model) {
        StringBuilder transcript = new StringBuilder("Продолжай диалог, учитывая предыдущие сообщения.\n\n");
        if (messages != null) {
            for (MglaAiChatStorage.Message m : messages) {
                if (TextUtils.isEmpty(m.text)) {
                    continue;
                }
                transcript.append("assistant".equals(m.role) ? "Ассистент: " : "Пользователь: ")
                        .append(m.text).append('\n');
            }
        }
        MglaAiLimits.ModelBudget budget = MglaAiLimits.budgetFor(model);
        int limit = Math.min(budget.inputChars(), budget.contextChars());
        return clipInputTail(transcript.toString(), limit);
    }

    private static JSONArray clipMessagesForOpenRouter(ArrayList<MglaAiChatStorage.Message> messages, ArrayList<String> imagePaths, MglaAiLimits.ModelBudget budget) throws Exception {
        JSONArray list = new JSONArray();
        if (messages == null || messages.isEmpty()) {
            return list;
        }
        final int maxChars = Math.min(budget.inputChars(), budget.contextChars());
        int lastUserMessage = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role)) {
                lastUserMessage = i;
                break;
            }
        }
        // Walk from the end so recent turns stay; drop oldest once budget is exceeded.
        ArrayList<JSONObject> reversed = new ArrayList<>();
        int used = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MglaAiChatStorage.Message m = messages.get(i);
            if (TextUtils.isEmpty(m.text)) {
                continue;
            }
            String text = m.text;
            int cost = text.length();
            if (used > 0 && used + cost > maxChars) {
                break;
            }
            if (cost > maxChars) {
                text = clipInputTail(text, maxChars);
                cost = text.length();
            }
            JSONObject item = new JSONObject();
            item.put("role", m.role);
            if (i == lastUserMessage && imagePaths != null && !imagePaths.isEmpty() && MglaSubscription.canAttachMedia()) {
                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "text").put("text", text));
                for (String path : imagePaths) {
                    content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", imageDataUrl(path))));
                }
                item.put("content", content);
            } else {
                item.put("content", text);
            }
            reversed.add(item);
            used += cost;
        }
        for (int i = reversed.size() - 1; i >= 0; i--) {
            list.put(reversed.get(i));
        }
        return list;
    }

    private static String clipInputTail(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        return "…\n" + text.substring(text.length() - (limit - 2));
    }

    private static String readLimited(InputStream input, int max) {
        if (input == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] b = new char[max]; int n = r.read(b); return n > 0 ? new String(b, 0, n) : "";
        } catch (Throwable ignore) { return ""; }
    }

    /** Bounded conversion prevents a few camera originals from exhausting the app heap. */
    private static String imageDataUrl(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) throw new Exception("Не удалось прочитать выбранное изображение");
        if (file.length() > 6L * 1024L * 1024L) throw new Exception("Изображение больше 6 МБ. Выберите файл поменьше.");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != bytes.length) throw new Exception("Не удалось прочитать выбранное изображение");
        }
        String lower = file.getName().toLowerCase();
        String mime = lower.endsWith(".png") ? "image/png" : lower.endsWith(".webp") ? "image/webp" : "image/jpeg";
        return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static void uiError(Callback callback, String message) { AndroidUtilities.runOnUIThread(() -> callback.onError(message)); }
    private static String readableError(Throwable e) { return !TextUtils.isEmpty(e.getMessage()) ? e.getMessage() : "Не удалось получить ответ"; }
}
