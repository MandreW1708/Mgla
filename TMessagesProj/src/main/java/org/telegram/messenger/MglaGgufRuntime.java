package org.telegram.messenger;

import android.text.TextUtils;

import java.io.File;

/**
 * One-shot GGUF inference for Chat DNA.
 *
 * The native call owns the model and context for its whole duration and frees
 * both before returning. Nothing is kept resident between Chat DNA launches.
 */
public final class MglaGgufRuntime {

    private static boolean nativeLoaded;

    /** Прогресс нативной генерации. Дёргается в UI-потоке. */
    public interface ProgressListener {
        /** generated < 0 — загрузка модели; иначе токены из maxTokens.
            textSoFar — накопленный текст генерации (null при загрузке модели). */
        void onProgress(int generated, int maxTokens, String textSoFar);
    }

    private static volatile ProgressListener progressListener;

    private MglaGgufRuntime() {
    }

    public static void setProgressListener(ProgressListener listener) {
        progressListener = listener;
    }

    /** Вызывается из натива на каждый N-й сгенерированный токен. */
    public static void onNativeProgress(int generated, int maxTokens, String textSoFar) {
        ProgressListener listener = progressListener;
        if (listener == null) {
            return;
        }
        if (generated >= 0 && generated % 3 != 0 && generated < maxTokens) {
            return;
        }
        final int g = generated;
        final int m = Math.max(1, maxTokens);
        final String t = textSoFar;
        AndroidUtilities.runOnUIThread(() -> listener.onProgress(g, m, t));
    }

    public static boolean isAvailable() {
        if (nativeLoaded) {
            return true;
        }
        try {
            System.loadLibrary("tmessages.49");
            nativeLoaded = true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return nativeLoaded;
    }

    /** Returns generated text, or null when a model cannot be run. */
    public static String generateTopics(File model, String prompt) {
        if (model == null || !model.isFile() || TextUtils.isEmpty(prompt) || !isAvailable()) {
            FileLog.e("MglaGgufRuntime: not runnable (model=" + model + ", promptLen="
                + (prompt == null ? 0 : prompt.length()) + ", nativeLoaded=" + nativeLoaded + ")");
            return null;
        }
        try {
            String result = nativeGenerateTopics(model.getAbsolutePath(), prompt, 96);
            if (result == null) {
                FileLog.e("MglaGgufRuntime: native returned null for " + model.getName());
            }
            return result;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static native String nativeGenerateTopics(String modelPath, String prompt, int maxTokens);
}
