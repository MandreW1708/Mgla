package org.telegram.messenger;

/** JNI facade is intentionally tiny: native code owns model memory and never exposes it to Java. */
public final class MglaLlamaNative {
    private static boolean available;
    private static String loadFailure;

    public interface TokenSink {
        /** @return false to abort generation */
        boolean onToken(String piece);

        /** Optional progress: "prompt" while evaluating input, "generate" while sampling. */
        default void onPhase(String phase) {
        }
    }

    static {
        try {
            System.loadLibrary("mglallama");
            available = true;
        } catch (Throwable e) {
            available = false;
            loadFailure = e.getMessage();
            FileLog.e(e);
        }
    }

    private MglaLlamaNative() {
    }

    public static boolean isAvailable() {
        return available;
    }

    public static String libraryError() {
        return loadFailure;
    }

    public static long load(String path, int context, int threads) {
        if (!available) {
            return 0;
        }
        return nativeLoad(path, context, threads);
    }

    /** Last native failure reason after {@link #load} returned 0 (may be null). */
    public static String lastError() {
        if (!available) {
            return loadFailure != null ? loadFailure : "библиотека mglallama не загружена";
        }
        try {
            return nativeLastError();
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * Stream tokens via {@link TokenSink}. Returns generated token count, or -1 on failure.
     */
    public static int generate(long handle, String prompt, int maxTokens, float temperature, TokenSink sink) {
        if (!available) {
            return -1;
        }
        return nativeGenerate(handle, prompt, maxTokens, temperature, sink);
    }

    public static String complete(long handle, String prompt, int maxTokens, float temperature) {
        return available ? nativeComplete(handle, prompt, maxTokens, temperature) : null;
    }

    /** Ask in-flight generate/complete to stop; safe before {@link #free}. */
    public static void abort(long handle) {
        if (available && handle != 0) {
            try {
                nativeAbort(handle);
            } catch (Throwable ignore) {
            }
        }
    }

    public static void free(long handle) {
        if (available && handle != 0) {
            nativeFree(handle);
        }
    }

    private static native long nativeLoad(String path, int context, int threads);

    private static native String nativeLastError();

    private static native int nativeGenerate(long handle, String prompt, int maxTokens, float temperature, TokenSink sink);

    private static native String nativeComplete(long handle, String prompt, int maxTokens, float temperature);

    private static native void nativeAbort(long handle);

    private static native void nativeFree(long handle);
}
