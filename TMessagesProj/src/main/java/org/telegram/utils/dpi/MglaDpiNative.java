package org.telegram.utils.dpi;

import org.telegram.messenger.FileLog;

/**
 * JNI-мост к движку ByeDPI (jni/mgla_dpi_jni.c + jni/byedpi, библиотека libmgladpi.so).
 * Одновременно работает один экземпляр движка: повторный {@link #nativeStart} перезапускает его.
 */
public final class MglaDpiNative {

    public static final int ERR_ARGS = -1;
    public static final int ERR_INIT = -2;
    public static final int ERR_BIND = -3;
    public static final int ERR_THREAD = -4;

    private static Boolean available;

    /**
     * Запустить (или перезапустить) прокси на 127.0.0.1 с аргументами ciadpi.
     * @param port желаемый порт; 0 или занятый порт — любой свободный
     * @return фактический порт или код ошибки ERR_*
     */
    static native int nativeStart(String[] args, int port);

    static native void nativeStop();

    static native boolean nativeIsRunning();

    public static synchronized boolean isAvailable() {
        if (available == null) {
            try {
                System.loadLibrary("mgladpi");
                available = true;
            } catch (Throwable e) {
                FileLog.e(e);
                available = false;
            }
        }
        return available;
    }

    public static String describeError(int code) {
        switch (code) {
            case ERR_ARGS:
                return "ошибка в стратегии (неизвестный или неверный параметр)";
            case ERR_INIT:
                return "не удалось инициализировать движок";
            case ERR_BIND:
                return "не удалось открыть локальный порт";
            case ERR_THREAD:
                return "не удалось запустить поток движка";
            default:
                return "движок недоступен";
        }
    }

    private MglaDpiNative() {
    }
}
