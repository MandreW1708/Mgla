package org.telegram.messenger;

/** JNI facade is intentionally tiny: native code owns model memory and never exposes it to Java. */
public final class MglaLlamaNative {
    private static boolean available;
    static { try { System.loadLibrary("mglallama"); available = true; } catch (Throwable e) { FileLog.e(e); } }
    private MglaLlamaNative() { }
    public static long load(String path, int context, int threads) { return available ? nativeLoad(path, context, threads) : 0; }
    public static String complete(long handle, String prompt, int maxTokens, float temperature) { return available ? nativeComplete(handle, prompt, maxTokens, temperature) : null; }
    public static void free(long handle) { if (available && handle != 0) nativeFree(handle); }
    private static native long nativeLoad(String path, int context, int threads);
    private static native String nativeComplete(long handle, String prompt, int maxTokens, float temperature);
    private static native void nativeFree(long handle);
}
