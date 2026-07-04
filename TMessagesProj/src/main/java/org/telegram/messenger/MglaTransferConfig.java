package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.math.MathUtils;

public class MglaTransferConfig {

    public static final String PREFS = "mgla_config";
    public static final String KEY_DOWNLOAD_MODE = "transfer_download_mode";
    public static final String KEY_UPLOAD_MODE = "transfer_upload_mode";

    public static final int MODE_STANDARD = 0;
    public static final int MODE_ACCELERATED = 1;
    public static final int MODE_FAST = 2;

    public static final String[] MODE_NAMES = {
        "Стандартно",
        "Ускорено",
        "Быстро"
    };

    public static int getDownloadMode() {
        return getMode(KEY_DOWNLOAD_MODE);
    }

    public static int getUploadMode() {
        return getMode(KEY_UPLOAD_MODE);
    }

    public static void setDownloadMode(int mode) {
        setMode(KEY_DOWNLOAD_MODE, mode);
    }

    public static void setUploadMode(int mode) {
        setMode(KEY_UPLOAD_MODE, mode);
    }

    public static String getModeName(int mode) {
        if (mode < 0 || mode >= MODE_NAMES.length) {
            return MODE_NAMES[MODE_STANDARD];
        }
        return MODE_NAMES[mode];
    }

    public static int getMaxDownloadRequests() {
        switch (getDownloadMode()) {
            case MODE_ACCELERATED:
                return 8;
            case MODE_FAST:
                return 16;
            default:
                return 4;
        }
    }

    public static int getDownloadChunkSizeBig() {
        switch (getDownloadMode()) {
            case MODE_ACCELERATED:
                return 1024 * 256;
            case MODE_FAST:
                return 1024 * 512;
            default:
                return 1024 * 128;
        }
    }

    public static int getMaxUploadKBytes(boolean slowNetwork) {
        if (slowNetwork) {
            return 32;
        }
        switch (getUploadMode()) {
            case MODE_ACCELERATED:
                return 1024 * 4;
            case MODE_FAST:
                return 1024 * 8;
            default:
                return 1024 * 2;
        }
    }

    public static int getInitialUploadRequestsCount(boolean slowNetwork) {
        if (slowNetwork) {
            return 1;
        }
        switch (getUploadMode()) {
            case MODE_ACCELERATED:
                return 12;
            case MODE_FAST:
                return 16;
            default:
                return 8;
        }
    }

    private static int getMode(String key) {
        if (ApplicationLoader.applicationContext == null) {
            return MODE_STANDARD;
        }
        return MathUtils.clamp(prefs().getInt(key, MODE_STANDARD), MODE_STANDARD, MODE_FAST);
    }

    private static void setMode(String key, int mode) {
        mode = MathUtils.clamp(mode, MODE_STANDARD, MODE_FAST);
        prefs().edit().putInt(key, mode).apply();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
