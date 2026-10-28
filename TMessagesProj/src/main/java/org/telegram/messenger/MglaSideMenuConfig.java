package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

public class MglaSideMenuConfig {

    public static final String PREFS = "mgla_config";
    public static final String KEY_ENABLED = "side_menu_enabled";
    public static final String KEY_GLASS_ENABLED = "side_menu_glass_enabled";
    public static final String KEY_BACKGROUND_OPACITY = "side_menu_background_opacity";
    public static final String KEY_COLORED_ICONS = "side_menu_colored_icons";

    /** Fully opaque solid panel when liquid glass is off. */
    public static final int DEFAULT_BACKGROUND_OPACITY = 100;

    public static boolean isEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
            notifyChanged();
        }
    }

    public static boolean isGlassEnabled() {
        SharedPreferences prefs = prefs();
        return prefs == null || prefs.getBoolean(KEY_GLASS_ENABLED, true);
    }

    public static void setGlassEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_GLASS_ENABLED, enabled).apply();
            notifyChanged();
        }
    }

    /**
     * Solid-panel opacity when liquid glass is off, 0–100 (100 = fully opaque).
     * Ignored while liquid glass is enabled — that uses appearance «Затемнение стекла».
     */
    public static int getBackgroundOpacity() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return DEFAULT_BACKGROUND_OPACITY;
        }
        return Math.max(0, Math.min(100, prefs.getInt(KEY_BACKGROUND_OPACITY, DEFAULT_BACKGROUND_OPACITY)));
    }

    public static void setBackgroundOpacity(int opacity) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        int clamped = Math.max(0, Math.min(100, opacity));
        if (prefs.getInt(KEY_BACKGROUND_OPACITY, DEFAULT_BACKGROUND_OPACITY) == clamped) {
            return;
        }
        prefs.edit().putInt(KEY_BACKGROUND_OPACITY, clamped).apply();
        notifyChanged();
    }

    public static float getBackgroundOpacityAlpha() {
        return getBackgroundOpacity() / 100f;
    }

    public static boolean isColoredIconsEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(KEY_COLORED_ICONS, false);
    }

    public static void setColoredIconsEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_COLORED_ICONS, enabled).apply();
            notifyChanged();
        }
    }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaHeaderSettingsChanged)
        );
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
