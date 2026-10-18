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
    private static final String KEY_MODELS_CACHE = "ai_openrouter_models_cache_v2";
    private static final String KEY_MODELS_CACHE_AT = "ai_openrouter_models_cache_at_v2";
    private static final String KEY_MODELS_CACHE_PLAN = "ai_openrouter_models_cache_plan_v2";
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
    }

    public static final class ModelInfo {
        public final String id;
        /** up / degraded / down */
        public final String status;
        public final boolean available;

        public ModelInfo(String id, String status) {
            this.id = id;
            this.status = normalizeStatus(status);
            this.available = !STATUS_DOWN.equals(this.status);
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
        public void cancel() {
            cancelled.set(true);
            HttpURLConnection c = connection;
            if (c != null) c.disconnect();
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
                    if (!MglaAiLimits.canSendCloudRequest()) {
                        throw new Exception("Дневной лимит запросов исчерпан (20/день на Ultra)");
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
            out.add(new ModelInfo(m, status));
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
            ArrayList<ModelInfo> out = parseModelsArray(arr);
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
        JSONArray arr = null;
        JSONObject plans = json.optJSONObject("plans");
        if (plans != null) {
            JSONObject entry = plans.optJSONObject(plan);
            if (entry != null) {
                arr = entry.optJSONArray("models");
            }
        }
        if (arr == null || arr.length() == 0) {
            arr = json.optJSONArray("models");
        }
        if (arr == null || arr.length() == 0) {
            return null;
        }
        ArrayList<ModelInfo> out = parseModelsArray(arr);
        return out.isEmpty() ? null : out;
    }

    private static ArrayList<ModelInfo> parseModelsArray(JSONArray arr) {
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
                    out.add(new ModelInfo(id, status));
                }
            } else if (item instanceof String) {
                String id = ((String) item).trim();
                if (!TextUtils.isEmpty(id)) {
                    out.add(new ModelInfo(id, STATUS_UP));
                }
            } else {
                String id = arr.optString(i, "").trim();
                if (!TextUtils.isEmpty(id)) {
                    out.add(new ModelInfo(id, STATUS_UP));
                }
            }
        }
        return out;
    }

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
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.OPENROUTER_API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("HTTP-Referer", "https://mgla.app");
        conn.setRequestProperty("X-Title", "Mgla");
        try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (code >= 400) throw new Exception("OpenRouter: HTTP " + code + " — " + readLimited(stream, 1200));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while (!request.isCancelled() && (line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                try {
                    JSONObject json = new JSONObject(data);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.optJSONObject(0).optJSONObject("delta");
                    String token = delta == null ? null : delta.optString("content", null);
                    if (!TextUtils.isEmpty(token)) AndroidUtilities.runOnUIThread(() -> callback.onDelta(token));
                } catch (Throwable ignored) {
                    // Providers occasionally send non-content SSE events; they are harmless.
                }
            }
        } finally { conn.disconnect(); request.connection = null; }
        if (!request.isCancelled()) {
            reportModelSuccess(model);
            MglaAiLimits.consumeCloudRequest();
            AndroidUtilities.runOnUIThread(callback::onComplete);
        }
    }

    private static void streamOpenRouterViaHub(String model, ArrayList<MglaAiChatStorage.Message> messages, Request request, Callback callback) throws Exception {
        String transcript = buildClippedTranscript(messages, model);
        JSONObject body = new JSONObject();
        body.put("message", transcript);
        body.put("lang", "Russian");
        String plan = MglaSubscription.openRouterPlanId();
        if (!TextUtils.isEmpty(plan)) {
            body.put("plan", plan);
        }
        if (!TextUtils.isEmpty(model)) {
            body.put("model", model);
        }
        MglaHubHttp.Response response = MglaHubHttp.post(
                "/mgla-ai/v1/chat",
                body.toString().getBytes(StandardCharsets.UTF_8),
                30_000,
                90_000
        );
        if (request.isCancelled()) {
            return;
        }
        if (response.code != 200) {
            String err = MglaHubHttp.extractJsonStringField(response.body, "error");
            throw new Exception(!TextUtils.isEmpty(err) ? err : ("Хаб ИИ: HTTP " + response.code));
        }
        String text = MglaHubHttp.extractJsonStringField(response.body, "content");
        if (TextUtils.isEmpty(text)) {
            throw new Exception("пустой ответ хаба");
        }
        long delay = 0;
        for (int i = 0; i < text.length(); i += 18) {
            final String part = text.substring(i, Math.min(text.length(), i + 18));
            AndroidUtilities.runOnUIThread(() -> {
                if (!request.isCancelled()) {
                    callback.onDelta(part);
                }
            }, delay);
            delay += 18;
        }
        reportModelSuccess(model);
        MglaAiLimits.consumeCloudRequest();
        final long completeDelay = delay;
        AndroidUtilities.runOnUIThread(() -> {
            if (!request.isCancelled()) {
                callback.onComplete();
            }
        }, completeDelay);
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
