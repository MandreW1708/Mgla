package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle gate for the on-device llama.cpp runtime.
 * Model must be loaded explicitly via {@link #loadAsync} before {@link #stream}.
 */
public final class MglaLocalChatRuntime {
    private static final String PREFS = "mgla_config";
    private static final Object LOCK = new Object();
    private static long handle;
    private static String loadedPath;
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static final AtomicBoolean unloading = new AtomicBoolean(false);
    /** User dismissed the «model still in RAM» banner until next load. */
    private static volatile boolean ramWarningDismissed;

    /** Tuned for phone-sized instruct GGUF; higher max for reasoning models (Qwen3…). */
    public static final float DEFAULT_TEMPERATURE = 0f; // greedy — стабильнее на 0.5B
    public static final int DEFAULT_MAX_TOKENS = 1024;
    public static final int DEFAULT_CONTEXT = 1536;
    private static final int DEFAULTS_VERSION = 7;

    public interface LoadCallback {
        void onProgress(String status);

        void onReady();

        void onError(String message);
    }

    private MglaLocalChatRuntime() {
    }

    public static int defaultThreads() {
        // 1–2 threads: fewer hangs from oversubscription on mid-range phones.
        return Math.min(2, Math.max(1, Runtime.getRuntime().availableProcessors() > 2 ? 2 : 1));
    }

    /**
     * Force-apply phone-friendly local inference defaults once per version.
     * Fixes empty replies from too-hot sampling / oversized context on tiny models.
     */
    public static void ensureLocalDefaults() {
        // Always scrub retired GGUFs (SmolLM2 135M etc.) — cheap if nothing to delete.
        MglaLocalModelsManager.purgeBannedModels(ApplicationLoader.applicationContext);
        SharedPreferences p = prefs();
        if (p.getInt("ai_local_defaults_ver", 0) >= DEFAULTS_VERSION) {
            return;
        }
        p.edit()
                .putFloat("ai_chat_temperature", DEFAULT_TEMPERATURE)
                .putInt("ai_chat_max_tokens", DEFAULT_MAX_TOKENS)
                .putInt("ai_local_context", DEFAULT_CONTEXT)
                .putInt("ai_local_threads", defaultThreads())
                .putInt("ai_local_defaults_ver", DEFAULTS_VERSION)
                .apply();
    }

    public static void resetLocalDefaults() {
        prefs().edit()
                .putFloat("ai_chat_temperature", DEFAULT_TEMPERATURE)
                .putInt("ai_chat_max_tokens", DEFAULT_MAX_TOKENS)
                .putInt("ai_local_context", DEFAULT_CONTEXT)
                .putInt("ai_local_threads", defaultThreads())
                .putInt("ai_local_defaults_ver", DEFAULTS_VERSION)
                .apply();
    }

    /** Show the chat-list banner while weights are mapped and the user hasn't dismissed it. */
    public static boolean shouldShowRamWarning() {
        return isLoaded() && !ramWarningDismissed;
    }

    public static void dismissRamWarning() {
        ramWarningDismissed = true;
        notifyRamChanged();
    }

    /** Ask DialogsActivity / listeners to refresh the «model in RAM» hint. */
    public static void notifyRamChanged() {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaLocalModelRamChanged));
    }

    public static float temperature() {
        ensureLocalDefaults();
        return Math.max(0f, Math.min(1.0f, prefs().getFloat("ai_chat_temperature", DEFAULT_TEMPERATURE)));
    }

    public static int maxTokens() {
        ensureLocalDefaults();
        // Reasoning models spend many tokens on <think> before the answer.
        return Math.max(64, Math.min(2048, prefs().getInt("ai_chat_max_tokens", DEFAULT_MAX_TOKENS)));
    }

    public static int contextTokens() {
        ensureLocalDefaults();
        return Math.max(512, Math.min(4096, prefs().getInt("ai_local_context", DEFAULT_CONTEXT)));
    }

    public static int threads() {
        ensureLocalDefaults();
        return Math.max(1, Math.min(2, prefs().getInt("ai_local_threads", defaultThreads())));
    }

    /** Abort in-flight native generate for the currently loaded handle (if any). */
    public static void abortActive() {
        long h;
        synchronized (LOCK) {
            h = handle;
        }
        if (h != 0) {
            MglaLlamaNative.abort(h);
        }
    }

    public static boolean isLoaded() {
        synchronized (LOCK) {
            return handle != 0 && loadedPath != null;
        }
    }

    public static boolean isLoading() {
        return loading.get();
    }

    public static boolean isUnloading() {
        return unloading.get();
    }

    public static boolean isBusy() {
        return loading.get() || unloading.get();
    }

    /** Absolute path of the model currently mapped in RAM, or null. */
    public static String loadedPath() {
        synchronized (LOCK) {
            return loadedPath;
        }
    }

    /** True if the currently selected runnable model is already mapped. */
    public static boolean isSelectedModelLoaded(Context context) {
        File model = MglaLocalModelsManager.getRunnableModel(context);
        if (model == null) {
            return false;
        }
        synchronized (LOCK) {
            return handle != 0 && model.getAbsolutePath().equals(loadedPath);
        }
    }

    /**
     * Load the selected GGUF into RAM on a background thread.
     * Safe to call when already loaded for the same file (no-op success).
     */
    public static void loadAsync(LoadCallback callback) {
        if (callback == null) {
            return;
        }
        ensureLocalDefaults();
        if (isBusy()) {
            AndroidUtilities.runOnUIThread(() -> callback.onProgress(unloading.get() ? "Выгрузка…" : "Уже загружается…"));
            return;
        }
        if (!loading.compareAndSet(false, true)) {
            AndroidUtilities.runOnUIThread(() -> callback.onProgress("Уже загружается…"));
            return;
        }
        new Thread(() -> {
            try {
                Context context = ApplicationLoader.applicationContext;
                AndroidUtilities.runOnUIThread(() -> callback.onProgress("Проверка модели…"));
                File model = MglaLocalModelsManager.getRunnableModel(context);
                if (model == null) {
                    File raw = MglaLocalModelsManager.getLoadedModel(context);
                    if (raw != null) {
                        String unsupported = MglaLocalModelsManager.unsupportedReason(raw);
                        throw new IllegalStateException(unsupported != null
                                ? unsupported
                                : "Выбранная модель не подходит");
                    }
                    throw new IllegalStateException("Сначала скачайте и выберите локальную GGUF-модель");
                }
                if (!MglaLocalModelsManager.isGgufSupported(context)) {
                    throw new IllegalStateException("Нужны 64-битный процессор и не менее 4 ГБ ОЗУ");
                }
                int ctxTokens = contextTokens();
                int nThreads = threads();
                assertEnoughMemory(context, model.length(), ctxTokens);

                long stale = 0;
                synchronized (LOCK) {
                    if (handle != 0 && model.getAbsolutePath().equals(loadedPath)) {
                        loading.set(false);
                        AndroidUtilities.runOnUIThread(callback::onReady);
                        return;
                    }
                    stale = handle;
                    handle = 0;
                    loadedPath = null;
                }
                if (stale != 0) {
                    MglaLlamaNative.abort(stale);
                    MglaLlamaNative.free(stale);
                }

                if (!MglaLlamaNative.isAvailable()) {
                    String libErr = MglaLlamaNative.libraryError();
                    throw new IllegalStateException(libErr != null
                            ? ("Нативная библиотека ИИ не загружена: " + libErr)
                            : "Нативная библиотека ИИ (mglallama) недоступна");
                }

                AndroidUtilities.runOnUIThread(() -> callback.onProgress("Загрузка в память…"));
                long h = MglaLlamaNative.load(model.getAbsolutePath(), ctxTokens, nThreads);
                if (h == 0) {
                    String detail = MglaLlamaNative.lastError();
                    throw new IllegalStateException(detail != null && detail.length() > 0
                            ? detail
                            : "Не удалось загрузить GGUF-модель");
                }
                synchronized (LOCK) {
                    handle = h;
                    loadedPath = model.getAbsolutePath();
                }
                ramWarningDismissed = false;
                loading.set(false);
                AndroidUtilities.runOnUIThread(() -> {
                    notifyRamChanged();
                    callback.onReady();
                });
            } catch (Throwable e) {
                FileLog.e(e);
                loading.set(false);
                String msg = !TextUtils.isEmpty(e.getMessage()) ? e.getMessage() : "Ошибка загрузки модели";
                AndroidUtilities.runOnUIThread(() -> callback.onError(msg));
            }
        }, "MglaLocalLoad").start();
    }

    /**
     * Free native weights on a background thread — never call {@link #unload()} on the UI thread
     * (llama_model_free of a non-mmap GGUF can stall for seconds and ANR the app).
     */
    public static void unloadAsync(Runnable onDone) {
        long h;
        synchronized (LOCK) {
            h = handle;
            handle = 0;
            loadedPath = null;
        }
        if (h == 0) {
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
            return;
        }
        unloading.set(true);
        ramWarningDismissed = false;
        MglaLlamaNative.abort(h);
        new Thread(() -> {
            try {
                MglaLlamaNative.free(h);
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                unloading.set(false);
                AndroidUtilities.runOnUIThread(() -> {
                    notifyRamChanged();
                    if (onDone != null) {
                        onDone.run();
                    }
                });
            }
        }, "MglaLocalUnload").start();
    }

    /** @deprecated Prefer {@link #unloadAsync}; kept for callers that already run off the UI thread. */
    public static void unload() {
        unloadAsync(null);
    }

    public static void stream(ArrayList<MglaAiChatStorage.Message> messages, float temperature, int maxTokens, MglaAiChatService.Request request, MglaAiChatService.Callback callback) {
        ensureLocalDefaults();
        Context context = ApplicationLoader.applicationContext;
        File model = MglaLocalModelsManager.getRunnableModel(context);
        if (model == null) {
            throw new IllegalStateException("Сначала скачайте и выберите локальную GGUF-модель");
        }
        final long h;
        synchronized (LOCK) {
            if (handle == 0 || !model.getAbsolutePath().equals(loadedPath)) {
                throw new IllegalStateException("Сначала нажмите «Загрузить модель» в шапке чата");
            }
            h = handle;
        }
        request.bindLocalHandle(h);

        // Trailing empty assistant stub (UI placeholder) must not enter the prompt.
        // System seeds are never part of AI-chat turns — each thread is user/assistant only.
        // Drop prior assistant loops/garbage so tiny models don't continue the salad.
        ArrayList<MglaAiChatStorage.Message> forPrompt = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            MglaAiChatStorage.Message m = messages.get(i);
            if (m == null || TextUtils.isEmpty(m.text) || "system".equals(m.role)) {
                continue;
            }
            if ("assistant".equals(m.role) && looksLikeLoopGarbage(m.text)) {
                continue;
            }
            forPrompt.add(m);
        }
        String prompt = buildPrompt(h, model, forPrompt);
        FileLog.d("MglaLocal prompt chars=" + prompt.length() + " msgs=" + forPrompt.size());

        float temp = Math.max(0f, Math.min(0.8f, temperature));
        int tokens = Math.max(64, Math.min(2048, maxTokens));
        // Tiny instruct GGUFs collapse into loops/salad if allowed to run long.
        String modelName = model.getName().toLowerCase(java.util.Locale.US);
        if (modelName.contains("135m") || modelName.contains("360m")
                || modelName.contains("0.5b") || modelName.contains("500m")) {
            tokens = Math.min(tokens, 192);
        }
        final AtomicBoolean gotToken = new AtomicBoolean(false);

        int generated = MglaLlamaNative.generate(h, prompt, tokens, temp, new MglaLlamaNative.TokenSink() {
            @Override
            public void onPhase(String phase) {
                if (request.isCancelled()) {
                    return;
                }
                final String label = "prompt".equals(phase) ? "Читаю запрос…" : "Пишу ответ…";
                FileLog.d("MglaLocal phase=" + phase);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!request.isCancelled() && !gotToken.get()) {
                        callback.onStatus(label);
                    }
                });
            }

            @Override
            public boolean onToken(String piece) {
                if (request.isCancelled()) {
                    MglaLlamaNative.abort(h);
                    return false;
                }
                if (TextUtils.isEmpty(piece)) {
                    return true;
                }
                gotToken.set(true);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!request.isCancelled()) {
                        callback.onDelta(piece);
                    }
                });
                return true;
            }
        });

        if (request.isCancelled()) {
            return;
        }
        if (generated < 0 || !gotToken.get()) {
            String detail = MglaLlamaNative.lastError();
            throw new IllegalStateException(!TextUtils.isEmpty(detail)
                    ? detail
                    : "Локальная модель вернула пустой ответ");
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!request.isCancelled()) {
                callback.onComplete();
            }
        });
    }

    private static void assertEnoughMemory(Context context, long modelBytes, int contextTokens) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            am.getMemoryInfo(info);
        }
        long required = modelBytes + Math.max(256L * 1024 * 1024, contextTokens * 192L * 1024) + 192L * 1024 * 1024;
        if (info.availMem > 0 && info.availMem < required) {
            throw new IllegalStateException("Недостаточно свободной памяти для этой модели. Освободите память или уменьшите контекст.");
        }
    }

    private static final String CHAT_SYSTEM =
            "Ты полезный ассистент. Отвечай кратко на языке пользователя. "
                    + "Не повторяй сообщение пользователя — дай свой ответ.";

    /**
     * SmolLM2 / Qwen / … ship as ChatML Instruct but GGUF {@code general.architecture}
     * is often {@code llama}. Using Llama-3 headers then makes the model echo the user.
     */
    private static boolean prefersChatMl(File modelFile, String arch) {
        String name = modelFile != null ? modelFile.getName().toLowerCase(java.util.Locale.US) : "";
        if (name.contains("smol") || name.contains("qwen") || name.contains("chatml")
                || name.contains("openchat") || name.contains("zephyr") || name.contains("tinyllama")
                || name.contains("dolphin") || name.contains("hermes") || name.contains("openhermes")) {
            return true;
        }
        String a = arch != null ? arch.toLowerCase(java.util.Locale.US) : "";
        return a.contains("qwen");
    }

    private static boolean isValidChatMlPrompt(String prompt) {
        return !TextUtils.isEmpty(prompt)
                && prompt.contains("<|im_start|>")
                && prompt.contains("<|im_start|>assistant")
                && !prompt.contains("<|start_header_id|>");
    }

    /**
     * Prefer the GGUF-embedded chat template (Gemma / Llama-3 / ChatML / Phi / Nemotron).
     * Fall back to an architecture-aware Java template if native apply fails.
     */
    private static String buildPrompt(long handle, File modelFile, ArrayList<MglaAiChatStorage.Message> messages) {
        int start = Math.max(0, messages.size() - 12);
        ArrayList<String> roles = new ArrayList<>();
        ArrayList<String> texts = new ArrayList<>();
        for (int i = start; i < messages.size(); i++) {
            MglaAiChatStorage.Message m = messages.get(i);
            if (m == null || TextUtils.isEmpty(m.text) || "system".equals(m.role)) {
                continue;
            }
            String role = "assistant".equals(m.role) ? "assistant" : "user";
            // Don't burn context on prior CoT — only the final answer matters for history.
            String turn = "assistant".equals(m.role)
                    ? MglaAiReasoning.stripForContext(m.text)
                    : m.text.trim();
            if (TextUtils.isEmpty(turn)) {
                continue;
            }
            roles.add(role);
            texts.add(turn);
        }
        String arch = MglaLocalModelsManager.readGgufArchitecture(modelFile);
        final boolean chatMl = prefersChatMl(modelFile, arch);

        if (!roles.isEmpty()) {
            String nativePrompt = MglaLlamaNative.applyChatTemplate(
                    handle,
                    roles.toArray(new String[0]),
                    texts.toArray(new String[0]),
                    true
            );
            if (!TextUtils.isEmpty(nativePrompt)) {
                // SmolLM2 mis-tagged as llama: reject Llama-3 shaped native output.
                if (chatMl && !isValidChatMlPrompt(nativePrompt)) {
                    FileLog.d("MglaLocal: reject non-ChatML native template for " + modelFile.getName());
                } else if (!chatMl || isValidChatMlPrompt(nativePrompt)) {
                    return nativePrompt;
                }
            }
        }
        return buildPromptFallback(arch, modelFile, messages, start);
    }

    private static String buildChatMlPrompt(ArrayList<MglaAiChatStorage.Message> messages, int start) {
        StringBuilder p = new StringBuilder();
        p.append("<|im_start|>system\n").append(CHAT_SYSTEM).append("<|im_end|>\n");
        for (int i = start; i < messages.size(); i++) {
            MglaAiChatStorage.Message m = messages.get(i);
            if (m == null || TextUtils.isEmpty(m.text) || "system".equals(m.role)) {
                continue;
            }
            boolean assistant = "assistant".equals(m.role);
            String turn = assistant ? MglaAiReasoning.stripForContext(m.text) : m.text.trim();
            if (TextUtils.isEmpty(turn)) {
                continue;
            }
            p.append("<|im_start|>").append(assistant ? "assistant" : "user").append('\n')
                    .append(turn)
                    .append("<|im_end|>\n");
        }
        p.append("<|im_start|>assistant\n");
        return p.toString();
    }

    /** Manual templates when llama_chat_apply_template cannot detect the GGUF jinja. */
    private static String buildPromptFallback(String arch, File modelFile, ArrayList<MglaAiChatStorage.Message> messages, int start) {
        String lower = arch != null ? arch.toLowerCase(java.util.Locale.US) : "";

        // Filename beats architecture — SmolLM2 is ChatML despite arch=llama.
        if (prefersChatMl(modelFile, arch)) {
            return buildChatMlPrompt(messages, start);
        }

        if (lower.contains("gemma")) {
            StringBuilder p = new StringBuilder();
            p.append("<start_of_turn>user\n").append(CHAT_SYSTEM).append("<end_of_turn>\n");
            for (int i = start; i < messages.size(); i++) {
                MglaAiChatStorage.Message m = messages.get(i);
                if (m == null || TextUtils.isEmpty(m.text) || "system".equals(m.role)) {
                    continue;
                }
                boolean assistant = "assistant".equals(m.role);
                String turn = assistant ? MglaAiReasoning.stripForContext(m.text) : m.text.trim();
                if (TextUtils.isEmpty(turn)) {
                    continue;
                }
                p.append("<start_of_turn>").append(assistant ? "model" : "user").append('\n')
                        .append(turn).append("<end_of_turn>\n");
            }
            p.append("<start_of_turn>model\n");
            return p.toString();
        }

        if (lower.contains("llama") || lower.contains("nemotron") || lower.contains("granite")) {
            StringBuilder p = new StringBuilder();
            p.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
                    .append(CHAT_SYSTEM).append("<|eot_id|>");
            for (int i = start; i < messages.size(); i++) {
                MglaAiChatStorage.Message m = messages.get(i);
                if (m == null || TextUtils.isEmpty(m.text) || "system".equals(m.role)) {
                    continue;
                }
                boolean assistant = "assistant".equals(m.role);
                String turn = assistant ? MglaAiReasoning.stripForContext(m.text) : m.text.trim();
                if (TextUtils.isEmpty(turn)) {
                    continue;
                }
                p.append("<|start_header_id|>").append(assistant ? "assistant" : "user")
                        .append("<|end_header_id|>\n\n")
                        .append(turn).append("<|eot_id|>");
            }
            p.append("<|start_header_id|>assistant<|end_header_id|>\n\n");
            return p.toString();
        }

        if (lower.contains("phi")) {
            StringBuilder p = new StringBuilder();
            p.append("<|system|>\n").append(CHAT_SYSTEM).append("<|end|>\n");
            for (int i = start; i < messages.size(); i++) {
                MglaAiChatStorage.Message m = messages.get(i);
                if (m == null || TextUtils.isEmpty(m.text) || "system".equals(m.role)) {
                    continue;
                }
                boolean assistant = "assistant".equals(m.role);
                String turn = assistant ? MglaAiReasoning.stripForContext(m.text) : m.text.trim();
                if (TextUtils.isEmpty(turn)) {
                    continue;
                }
                p.append(assistant ? "<|assistant|>\n" : "<|user|>\n")
                        .append(turn).append("<|end|>\n");
            }
            p.append("<|assistant|>\n");
            return p.toString();
        }

        return buildChatMlPrompt(messages, start);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Detect greedy-loop / UTF-8 salad so it is not fed back into the next prompt
     * (SmolLM 135M otherwise keeps generating gibberish forever).
     */
    public static boolean looksLikeLoopGarbage(String s) {
        if (TextUtils.isEmpty(s) || s.length() < 8) {
            return false;
        }
        int replacement = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') {
                replacement++;
            }
        }
        if (replacement >= 3) {
            return true;
        }
        return collapseRepeatedUnit(s) != null;
    }

    /**
     * If {@code s} is the same short unit repeated (e.g. "Привет. Привет. Привет."),
     * return one copy; otherwise null.
     */
    private static String collapseRepeatedUnit(String s) {
        String t = s.trim();
        if (t.length() < 6) {
            return null;
        }
        for (int len = 3; len <= Math.min(64, t.length() / 2); len++) {
            if (t.length() < len * 2) {
                continue;
            }
            String unit = t.substring(0, len);
            int count = 0;
            int pos = 0;
            while (pos + len <= t.length() && t.regionMatches(pos, unit, 0, len)) {
                count++;
                pos += len;
            }
            // Allow a short leftover (whitespace) after exact repeats.
            if (count >= 2 && pos >= t.length() - 2) {
                return unit.trim();
            }
            // Also match from the end (partial first chunk).
            unit = t.substring(t.length() - len);
            count = 0;
            pos = t.length();
            while (pos >= len && t.regionMatches(pos - len, unit, 0, len)) {
                count++;
                pos -= len;
            }
            if (count >= 2 && pos <= 2) {
                return unit.trim();
            }
        }
        return null;
    }

    /** Trim a looped assistant reply down to a single phrase. */
    public static String sanitizeGeneratedText(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return raw;
        }
        String collapsed = collapseRepeatedUnit(raw);
        if (collapsed != null) {
            return collapsed;
        }
        int replacement = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\uFFFD') {
                replacement++;
            }
        }
        if (replacement >= 3) {
            int cut = Math.min(160, raw.length());
            return raw.substring(0, cut).trim() + "…";
        }
        return raw;
    }
}
