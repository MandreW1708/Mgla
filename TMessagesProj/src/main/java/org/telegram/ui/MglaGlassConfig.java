package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.NotificationCenter;

public class MglaGlassConfig {

    public static final String PREFS = "mgla_config";
    public static final String PREF_GLASS_DARKENING_LEVEL = "glass_darkening_level";
    public static final String PREF_MD3_PREDICTIVE_BACK = "md3_predictive_back";
    public static final String PREF_MD3_SWITCHES = "md3_switches";
    public static final String PREF_EDITED_ICON = "edited_icon_enabled";
    public static final String PREF_CLEAN_HEADER = "clean_header_enabled";
    public static final String PREF_CLEAN_HEADER_HIDE_BACK_BTN = "clean_header_hide_back_btn";
    public static final String PREF_CLEAN_HEADER_HIDE_TITLE_BLOCK = "clean_header_hide_title_block";
    public static final String PREF_CLEAN_HEADER_HIDE_PINNED_BLOCK = "clean_header_hide_pinned_block";
    public static final String PREF_CLEAN_HEADER_HIDE_TRANSLATION_PANEL = "clean_header_hide_translation_panel";

    public static int getGlassDarkeningLevel() {
        return getPrefs().getInt(PREF_GLASS_DARKENING_LEVEL, 100);
    }

    public static void setGlassDarkeningLevel(int level) {
        if (getGlassDarkeningLevel() == level) {
            return;
        }
        getPrefs().edit().putInt(PREF_GLASS_DARKENING_LEVEL, level).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderEnabled() {
        return getPrefs().getBoolean(PREF_CLEAN_HEADER, false);
    }

    public static void setCleanHeaderEnabled(boolean enabled) {
        if (isCleanHeaderEnabled() == enabled) {
            return;
        }
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderHideBackBtnEnabled() {
        return getPrefs().getBoolean(PREF_CLEAN_HEADER_HIDE_BACK_BTN, true);
    }

    public static void setCleanHeaderHideBackBtnEnabled(boolean enabled) {
        if (isCleanHeaderHideBackBtnEnabled() == enabled) return;
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER_HIDE_BACK_BTN, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderHideTitleBlockEnabled() {
        return getPrefs().getBoolean(PREF_CLEAN_HEADER_HIDE_TITLE_BLOCK, true);
    }

    public static void setCleanHeaderHideTitleBlockEnabled(boolean enabled) {
        if (isCleanHeaderHideTitleBlockEnabled() == enabled) return;
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER_HIDE_TITLE_BLOCK, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderHidePinnedBlockEnabled() {
        return getPrefs().getBoolean(PREF_CLEAN_HEADER_HIDE_PINNED_BLOCK, true);
    }

    public static void setCleanHeaderHidePinnedBlockEnabled(boolean enabled) {
        if (isCleanHeaderHidePinnedBlockEnabled() == enabled) return;
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER_HIDE_PINNED_BLOCK, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderHideTranslationPanelEnabled() {
        return getPrefs().getBoolean(PREF_CLEAN_HEADER_HIDE_TRANSLATION_PANEL, true);
    }

    public static void setCleanHeaderHideTranslationPanelEnabled(boolean enabled) {
        if (isCleanHeaderHideTranslationPanelEnabled() == enabled) return;
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER_HIDE_TRANSLATION_PANEL, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isMd3PredictiveBackEnabled() {
        return getPrefs().getBoolean(PREF_MD3_PREDICTIVE_BACK, false);
    }

    public static void setMd3PredictiveBackEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_MD3_PREDICTIVE_BACK, enabled).apply();
    }

    public static boolean isMd3SwitchesEnabled() {
        return getPrefs().getBoolean(PREF_MD3_SWITCHES, false);
    }

    public static void setMd3SwitchesEnabled(boolean enabled) {
        if (isMd3SwitchesEnabled() == enabled) return;
        getPrefs().edit().putBoolean(PREF_MD3_SWITCHES, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isEditedIconEnabled() {
        return getPrefs().getBoolean(PREF_EDITED_ICON, false);
    }

    public static void setEditedIconEnabled(boolean enabled) {
        if (isEditedIconEnabled() == enabled) return;
        getPrefs().edit().putBoolean(PREF_EDITED_ICON, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static void notifyGlassAppearanceChanged() {
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetNewTheme, false, false)
        );
    }

    public static float getGlassBackgroundAlpha() {
        float baseAlpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
        if (!LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
            return baseAlpha;
        }
        return baseAlpha * (getGlassDarkeningLevel() / 100f);
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
