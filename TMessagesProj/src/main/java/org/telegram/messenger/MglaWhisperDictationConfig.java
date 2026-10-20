package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки «Из голоса в текст» (локальный Whisper-диктовка в поле ввода).
 * Использует те же модели/язык, что и ИИ-расшифровка голосовых.
 */
public final class MglaWhisperDictationConfig {

    public static final String PREF_ENABLED = "ai_dictation_enabled";
    public static final String PREF_LIVE = "ai_dictation_live";

    private MglaWhisperDictationConfig() {
    }

    private static SharedPreferences prefs() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return null;
        }
        return ctx.getSharedPreferences(MglaWhisperModelsManager.PREFS, Context.MODE_PRIVATE);
    }

    /** Только пользовательский переключатель — для показа кнопки в чате. */
    public static boolean isPrefEnabled() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(PREF_ENABLED, false);
    }

    public static boolean isEnabled() {
        SharedPreferences p = prefs();
        return MglaFeatureFlags.isAllowed("ai_enabled")
            && MglaFeatureFlags.isAllowed(PREF_ENABLED)
            && p != null
            && p.getBoolean("ai_enabled", true)
            && p.getBoolean(PREF_ENABLED, false)
            && MglaWhisperNative.isAvailable();
    }

    public static void setEnabled(boolean enabled) {
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putBoolean(PREF_ENABLED, enabled).apply();
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                NotificationCenter.getGlobalInstance()
                    .postNotificationName(NotificationCenter.mglaHeaderSettingsChanged);
            } catch (Throwable ignore) {
            }
        });
    }

    /** Постепенная расшифровка во время записи (дороже по CPU). */
    public static boolean isLiveEnabled() {
        SharedPreferences p = prefs();
        return p == null || p.getBoolean(PREF_LIVE, true);
    }

    public static void setLiveEnabled(boolean enabled) {
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putBoolean(PREF_LIVE, enabled).apply();
        }
    }
}
