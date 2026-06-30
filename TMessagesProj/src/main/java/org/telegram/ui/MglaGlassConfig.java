package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.NotificationCenter;

public class MglaGlassConfig {

    public static final String PREFS = "mgla_config";
    public static final String PREF_GLASS_DARKENING = "glass_darkening_enabled";

    public static boolean isGlassDarkeningEnabled() {
        return getPrefs().getBoolean(PREF_GLASS_DARKENING, true);
    }

    public static void setGlassDarkeningEnabled(boolean enabled) {
        if (isGlassDarkeningEnabled() == enabled) {
            return;
        }
        getPrefs().edit().putBoolean(PREF_GLASS_DARKENING, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static void notifyGlassAppearanceChanged() {
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetNewTheme, false, false)
        );
    }

    public static float getGlassBackgroundAlpha() {
        if (LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) && !isGlassDarkeningEnabled()) {
            return 0f;
        }
        return LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
    }

    public static float getGlassBackgroundAlpha(boolean isDark) {
        if (LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
            return getGlassBackgroundAlpha();
        }
        return isDark ? 0.85f : 0.76f;
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
