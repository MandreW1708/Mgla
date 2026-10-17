package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;

/** Lifecycle and safety gate for the on-device llama.cpp runtime. */
public final class MglaLocalChatRuntime {
    private static final String PREFS = "mgla_config";
    private static final Object LOCK = new Object();
    private static long handle;
    private static String loadedPath;
    private static long lastUse;
    private static Runnable unloadTask;

    private MglaLocalChatRuntime() { }

    public static void stream(ArrayList<MglaAiChatStorage.Message> messages, float temperature, int maxTokens, MglaAiChatService.Request request, MglaAiChatService.Callback callback) {
        Context context = ApplicationLoader.applicationContext;
        File model = MglaLocalModelsManager.getLoadedModel(context);
        if (model == null) throw new IllegalStateException("Сначала скачайте и выберите локальную GGUF-модель");
        if (!MglaLocalModelsManager.isGgufSupported(context)) throw new IllegalStateException("Для локальной модели нужны 64-битный процессор и не менее 4 ГБ ОЗУ");
        int contextTokens = prefs().getInt("ai_local_context", 2048);
        int threads = Math.max(1, Math.min(6, prefs().getInt("ai_local_threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 1))));
        assertEnoughMemory(context, model.length(), contextTokens);
        synchronized (LOCK) {
            if (handle == 0 || !model.getAbsolutePath().equals(loadedPath)) {
                unloadLocked();
                handle = MglaLlamaNative.load(model.getAbsolutePath(), contextTokens, threads);
                if (handle == 0) throw new IllegalStateException("Не удалось загрузить GGUF-модель");
                loadedPath = model.getAbsolutePath();
            }
        }
        String prompt = buildPrompt(messages);
        String answer = MglaLlamaNative.complete(handle, prompt, Math.max(16, Math.min(2048, maxTokens)), temperature);
        if (request.isCancelled()) return;
        if (TextUtils.isEmpty(answer)) throw new IllegalStateException("Локальная модель вернула пустой ответ");
        // JNI completes off the UI thread.  Drip compact chunks so the renderer remains smooth on long replies.
        long delay = 0;
        for (int i = 0; i < answer.length() && !request.isCancelled(); i += 18) {
            String part = answer.substring(i, Math.min(answer.length(), i + 18));
            final long chunkDelay = delay;
            AndroidUtilities.runOnUIThread(() -> { if (!request.isCancelled()) callback.onDelta(part); }, chunkDelay);
            delay += 18;
        }
        lastUse = System.currentTimeMillis();
        scheduleUnload();
        final long completeDelay = delay;
        if (!request.isCancelled()) AndroidUtilities.runOnUIThread(() -> { if (!request.isCancelled()) callback.onComplete(); }, completeDelay);
    }

    private static void assertEnoughMemory(Context context, long modelBytes, int contextTokens) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) am.getMemoryInfo(info);
        // Quantized weights plus KV/cache and a conservative headroom for Telegram itself.
        long required = modelBytes + Math.max(384L * 1024 * 1024, contextTokens * 192L * 1024) + 256L * 1024 * 1024;
        if (info.availMem > 0 && info.availMem < required) throw new IllegalStateException("Недостаточно свободной памяти для этой модели. Освободите память или уменьшите контекст.");
    }

    private static String buildPrompt(ArrayList<MglaAiChatStorage.Message> messages) {
        StringBuilder p = new StringBuilder("Ты полезный ассистент. Отвечай на языке пользователя.\n\n");
        int start = Math.max(0, messages.size() - 18);
        for (int i = start; i < messages.size(); i++) {
            MglaAiChatStorage.Message m = messages.get(i);
            p.append("assistant".equals(m.role) ? "Ассистент: " : "Пользователь: ").append(m.text).append('\n');
        }
        return p.append("Ассистент: ").toString();
    }

    private static void scheduleUnload() {
        synchronized (LOCK) {
            if (unloadTask != null) AndroidUtilities.cancelRunOnUIThread(unloadTask);
            int seconds = Math.max(15, Math.min(3600, prefs().getInt("ai_local_unload_seconds", 120)));
            unloadTask = () -> { synchronized (LOCK) { if (System.currentTimeMillis() - lastUse >= seconds * 1000L) unloadLocked(); } };
            AndroidUtilities.runOnUIThread(unloadTask, seconds * 1000L);
        }
    }

    public static void unload() { synchronized (LOCK) { unloadLocked(); } }
    private static void unloadLocked() { if (handle != 0) MglaLlamaNative.free(handle); handle = 0; loadedPath = null; }
    private static SharedPreferences prefs() { return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
