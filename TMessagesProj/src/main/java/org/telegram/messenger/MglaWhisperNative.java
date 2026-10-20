package org.telegram.messenger;

/**
 * JNI bridge to {@code libmglawhisper.so} (whisper.cpp, CPU).
 */
public final class MglaWhisperNative {

    private static volatile boolean loaded;
    private static volatile boolean loadFailed;

    private MglaWhisperNative() {
    }

    public static boolean isAvailable() {
        ensureLoaded();
        if (loadFailed) {
            return false;
        }
        try {
            return nativeIsAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized long init(String modelPath) {
        ensureLoaded();
        if (loadFailed || modelPath == null) {
            return 0;
        }
        try {
            return nativeInit(modelPath);
        } catch (Throwable t) {
            FileLog.e(t);
            return 0;
        }
    }

    public static synchronized void free(long handle) {
        if (handle == 0 || loadFailed) {
            return;
        }
        try {
            nativeFree(handle);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static String transcribe(long handle, float[] pcm16kMono, String language, int threads) {
        return transcribe(handle, pcm16kMono, language, threads, 0);
    }

    /**
     * @param maxTokens 0 = default (224); for short dictation pass ~48–64
     */
    public static String transcribe(long handle, float[] pcm16kMono, String language, int threads, int maxTokens) {
        ensureLoaded();
        if (loadFailed || handle == 0 || pcm16kMono == null) {
            return null;
        }
        try {
            return nativeTranscribe(handle, pcm16kMono, language, threads, maxTokens);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /** Ogg/Opus → mono float PCM 16 kHz. Returns null if not an Opus file. */
    public static float[] decodeOpusToPcm16k(String path) {
        ensureLoaded();
        if (loadFailed || path == null) {
            return null;
        }
        try {
            return nativeDecodeOpusToPcm16k(path);
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static void ensureLoaded() {
        if (loaded || loadFailed) {
            return;
        }
        synchronized (MglaWhisperNative.class) {
            if (loaded || loadFailed) {
                return;
            }
            try {
                System.loadLibrary("mglawhisper");
                loaded = true;
            } catch (Throwable t) {
                loadFailed = true;
                FileLog.e("MglaWhisperNative: failed to load mglawhisper", t);
            }
        }
    }

    private static native boolean nativeIsAvailable();

    private static native long nativeInit(String modelPath);

    private static native void nativeFree(long handle);

    private static native String nativeTranscribe(long handle, float[] audio, String language, int threads, int maxTokens);

    private static native float[] nativeDecodeOpusToPcm16k(String path);
}
