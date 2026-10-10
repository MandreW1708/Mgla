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

    // In-memory cache — prefs are hit from Switch/ActionBar draw paths every frame.
    private static boolean cacheLoaded;
    private static int glassDarkeningLevel = 100;
    private static boolean md3PredictiveBack;
    private static boolean md3Switches;
    private static boolean editedIcon;
    private static boolean cleanHeader;
    private static boolean cleanHeaderHideBackBtn = true;
    private static boolean cleanHeaderHideTitleBlock = true;
    private static boolean cleanHeaderHidePinnedBlock = true;
    private static boolean cleanHeaderHideTranslationPanel = true;

    private static void ensureCache() {
        if (cacheLoaded) {
            return;
        }
        SharedPreferences p = getPrefs();
        glassDarkeningLevel = p.getInt(PREF_GLASS_DARKENING_LEVEL, 100);
        md3PredictiveBack = p.getBoolean(PREF_MD3_PREDICTIVE_BACK, false);
        md3Switches = p.getBoolean(PREF_MD3_SWITCHES, false);
        editedIcon = p.getBoolean(PREF_EDITED_ICON, false);
        cleanHeader = p.getBoolean(PREF_CLEAN_HEADER, false);
        cleanHeaderHideBackBtn = p.getBoolean(PREF_CLEAN_HEADER_HIDE_BACK_BTN, true);
        cleanHeaderHideTitleBlock = p.getBoolean(PREF_CLEAN_HEADER_HIDE_TITLE_BLOCK, true);
        cleanHeaderHidePinnedBlock = p.getBoolean(PREF_CLEAN_HEADER_HIDE_PINNED_BLOCK, true);
        cleanHeaderHideTranslationPanel = p.getBoolean(PREF_CLEAN_HEADER_HIDE_TRANSLATION_PANEL, true);
        cacheLoaded = true;
    }

    public static int getGlassDarkeningLevel() {
        ensureCache();
        return glassDarkeningLevel;
    }

    public static void setGlassDarkeningLevel(int level) {
        ensureCache();
        if (glassDarkeningLevel == level) {
            return;
        }
        glassDarkeningLevel = level;
        getPrefs().edit().putInt(PREF_GLASS_DARKENING_LEVEL, level).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderEnabled() {
        ensureCache();
        return cleanHeader;
    }

    public static void setCleanHeaderEnabled(boolean enabled) {
        ensureCache();
        if (cleanHeader == enabled) {
            return;
        }
        cleanHeader = enabled;
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderHideBackBtnEnabled() {
        ensureCache();
        return cleanHeaderHideBackBtn;
    }

    public static void setCleanHeaderHideBackBtnEnabled(boolean enabled) {
        ensureCache();
        if (cleanHeaderHideBackBtn == enabled) {
            return;
        }
        cleanHeaderHideBackBtn = enabled;
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER_HIDE_BACK_BTN, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderHideTitleBlockEnabled() {
        ensureCache();
        return cleanHeaderHideTitleBlock;
    }

    public static void setCleanHeaderHideTitleBlockEnabled(boolean enabled) {
        ensureCache();
        if (cleanHeaderHideTitleBlock == enabled) {
            return;
        }
        cleanHeaderHideTitleBlock = enabled;
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER_HIDE_TITLE_BLOCK, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderHidePinnedBlockEnabled() {
        ensureCache();
        return cleanHeaderHidePinnedBlock;
    }

    public static void setCleanHeaderHidePinnedBlockEnabled(boolean enabled) {
        ensureCache();
        if (cleanHeaderHidePinnedBlock == enabled) {
            return;
        }
        cleanHeaderHidePinnedBlock = enabled;
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER_HIDE_PINNED_BLOCK, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isCleanHeaderHideTranslationPanelEnabled() {
        ensureCache();
        return cleanHeaderHideTranslationPanel;
    }

    public static void setCleanHeaderHideTranslationPanelEnabled(boolean enabled) {
        ensureCache();
        if (cleanHeaderHideTranslationPanel == enabled) {
            return;
        }
        cleanHeaderHideTranslationPanel = enabled;
        getPrefs().edit().putBoolean(PREF_CLEAN_HEADER_HIDE_TRANSLATION_PANEL, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isMd3PredictiveBackEnabled() {
        ensureCache();
        return md3PredictiveBack;
    }

    public static void setMd3PredictiveBackEnabled(boolean enabled) {
        ensureCache();
        if (md3PredictiveBack == enabled) {
            return;
        }
        md3PredictiveBack = enabled;
        getPrefs().edit().putBoolean(PREF_MD3_PREDICTIVE_BACK, enabled).apply();
    }

    public static boolean isMd3SwitchesEnabled() {
        ensureCache();
        return md3Switches;
    }

    public static void setMd3SwitchesEnabled(boolean enabled) {
        ensureCache();
        if (md3Switches == enabled) {
            return;
        }
        md3Switches = enabled;
        getPrefs().edit().putBoolean(PREF_MD3_SWITCHES, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isEditedIconEnabled() {
        if (!org.telegram.messenger.MglaFeatureFlags.isAllowed(PREF_EDITED_ICON)) {
            return false;
        }
        ensureCache();
        return editedIcon;
    }

    public static void setEditedIconEnabled(boolean enabled) {
        if (enabled && !org.telegram.messenger.MglaFeatureFlags.isAllowed(PREF_EDITED_ICON)) {
            enabled = false;
        }
        ensureCache();
        if (editedIcon == enabled) {
            return;
        }
        editedIcon = enabled;
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
