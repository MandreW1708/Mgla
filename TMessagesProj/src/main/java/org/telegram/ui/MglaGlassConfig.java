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
    public static final String PREF_PREDICTIVE_BACK_STYLE = "predictive_back_style";
    /** Classic Telegram edge-slide. */
    public static final int PREDICTIVE_BACK_DEFAULT = 0;
    /** Material 3 shrinking card. */
    public static final int PREDICTIVE_BACK_MD3 = 1;
    /** Flat page slide left/right. */
    public static final int PREDICTIVE_BACK_PAGES = 2;
    /** Both screens shrink; previous pushes the active one out. */
    public static final int PREDICTIVE_BACK_PUSH = 3;
    public static final String PREF_MD3_SWITCHES = "md3_switches";
    public static final String PREF_EDITED_ICON = "edited_icon_enabled";
    public static final String PREF_CLEAN_HEADER = "clean_header_enabled";
    public static final String PREF_CLEAN_HEADER_HIDE_BACK_BTN = "clean_header_hide_back_btn";
    public static final String PREF_CLEAN_HEADER_HIDE_TITLE_BLOCK = "clean_header_hide_title_block";
    public static final String PREF_CLEAN_HEADER_HIDE_PINNED_BLOCK = "clean_header_hide_pinned_block";
    public static final String PREF_CLEAN_HEADER_HIDE_TRANSLATION_PANEL = "clean_header_hide_translation_panel";
    public static final String PREF_DIALOGS_AVATAR_HERO = "dialogs_avatar_hero";
    public static final String PREF_DIALOGS_SEARCH_EXPAND = "dialogs_search_expand";
    public static final String PREF_DIALOGS_FAB_MORPH = "dialogs_fab_morph";
    public static final String PREF_REVERSE_CHAT_OPEN = "reverse_chat_open";
    public static final String PREF_CHAT_SEND_BUTTON_MORPH = "chat_send_button_morph";
    public static final String PREF_CHAT_REACTION_SPRING = "chat_reaction_spring";
    public static final String PREF_PROFILE_MEDIA_GROW = "profile_media_grow";
    public static final String PREF_SETTINGS_PRESS_SPRING = "settings_press_spring";

    // In-memory cache — prefs are hit from Switch/ActionBar draw paths every frame.
    private static boolean cacheLoaded;
    private static int glassDarkeningLevel = 100;
    private static boolean md3PredictiveBack;
    private static int predictiveBackStyle = PREDICTIVE_BACK_DEFAULT;
    private static boolean md3Switches;
    private static boolean editedIcon;
    private static boolean cleanHeader;
    private static boolean cleanHeaderHideBackBtn = true;
    private static boolean cleanHeaderHideTitleBlock = true;
    private static boolean cleanHeaderHidePinnedBlock = true;
    private static boolean cleanHeaderHideTranslationPanel = true;
    private static boolean dialogsAvatarHero = true;
    private static boolean dialogsSearchExpand = true;
    private static boolean dialogsFabMorph = true;
    private static boolean reverseChatOpen = true;
    private static boolean chatSendButtonMorph = true;
    private static boolean chatReactionSpring = true;
    private static boolean profileMediaGrow = true;
    private static boolean settingsPressSpring = true;

    private static void ensureCache() {
        if (cacheLoaded) {
            return;
        }
        SharedPreferences p = getPrefs();
        glassDarkeningLevel = p.getInt(PREF_GLASS_DARKENING_LEVEL, 100);
        md3PredictiveBack = p.getBoolean(PREF_MD3_PREDICTIVE_BACK, false);
        if (p.contains(PREF_PREDICTIVE_BACK_STYLE)) {
            predictiveBackStyle = clampPredictiveBackStyle(p.getInt(PREF_PREDICTIVE_BACK_STYLE, PREDICTIVE_BACK_DEFAULT));
        } else {
            predictiveBackStyle = md3PredictiveBack ? PREDICTIVE_BACK_MD3 : PREDICTIVE_BACK_DEFAULT;
        }
        md3PredictiveBack = predictiveBackStyle == PREDICTIVE_BACK_MD3;
        md3Switches = p.getBoolean(PREF_MD3_SWITCHES, false);
        editedIcon = p.getBoolean(PREF_EDITED_ICON, false);
        cleanHeader = p.getBoolean(PREF_CLEAN_HEADER, false);
        cleanHeaderHideBackBtn = p.getBoolean(PREF_CLEAN_HEADER_HIDE_BACK_BTN, true);
        cleanHeaderHideTitleBlock = p.getBoolean(PREF_CLEAN_HEADER_HIDE_TITLE_BLOCK, true);
        cleanHeaderHidePinnedBlock = p.getBoolean(PREF_CLEAN_HEADER_HIDE_PINNED_BLOCK, true);
        cleanHeaderHideTranslationPanel = p.getBoolean(PREF_CLEAN_HEADER_HIDE_TRANSLATION_PANEL, true);
        dialogsAvatarHero = p.getBoolean(PREF_DIALOGS_AVATAR_HERO, true);
        dialogsSearchExpand = p.getBoolean(PREF_DIALOGS_SEARCH_EXPAND, true);
        dialogsFabMorph = p.getBoolean(PREF_DIALOGS_FAB_MORPH, true);
        reverseChatOpen = p.getBoolean(PREF_REVERSE_CHAT_OPEN, true);
        chatSendButtonMorph = p.getBoolean(PREF_CHAT_SEND_BUTTON_MORPH, true);
        chatReactionSpring = p.getBoolean(PREF_CHAT_REACTION_SPRING, true);
        profileMediaGrow = p.getBoolean(PREF_PROFILE_MEDIA_GROW, true);
        settingsPressSpring = p.getBoolean(PREF_SETTINGS_PRESS_SPRING, true);
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
        return getPredictiveBackStyle() == PREDICTIVE_BACK_MD3;
    }

    public static void setMd3PredictiveBackEnabled(boolean enabled) {
        setPredictiveBackStyle(enabled ? PREDICTIVE_BACK_MD3 : PREDICTIVE_BACK_DEFAULT);
    }

    public static int getPredictiveBackStyle() {
        ensureCache();
        return predictiveBackStyle;
    }

    public static void setPredictiveBackStyle(int style) {
        ensureCache();
        style = clampPredictiveBackStyle(style);
        if (predictiveBackStyle == style) {
            return;
        }
        predictiveBackStyle = style;
        md3PredictiveBack = style == PREDICTIVE_BACK_MD3;
        getPrefs().edit()
                .putInt(PREF_PREDICTIVE_BACK_STYLE, style)
                .putBoolean(PREF_MD3_PREDICTIVE_BACK, md3PredictiveBack)
                .apply();
    }

    public static boolean isPredictiveBackSpatial() {
        int style = getPredictiveBackStyle();
        return style == PREDICTIVE_BACK_MD3 || style == PREDICTIVE_BACK_PAGES || style == PREDICTIVE_BACK_PUSH;
    }

    public static String getPredictiveBackStyleTitle(int style) {
        switch (clampPredictiveBackStyle(style)) {
            case PREDICTIVE_BACK_MD3:
                return "В стиле MD3";
            case PREDICTIVE_BACK_PAGES:
                return "Страницы";
            case PREDICTIVE_BACK_PUSH:
                return "Выталкивание";
            default:
                return "Обычная";
        }
    }

    private static int clampPredictiveBackStyle(int style) {
        if (style < PREDICTIVE_BACK_DEFAULT || style > PREDICTIVE_BACK_PUSH) {
            return PREDICTIVE_BACK_DEFAULT;
        }
        return style;
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

    public static boolean isDialogsAvatarHeroEnabled() {
        ensureCache();
        return dialogsAvatarHero;
    }

    public static void setDialogsAvatarHeroEnabled(boolean enabled) {
        ensureCache();
        if (dialogsAvatarHero == enabled) {
            return;
        }
        dialogsAvatarHero = enabled;
        getPrefs().edit().putBoolean(PREF_DIALOGS_AVATAR_HERO, enabled).apply();
    }

    public static boolean isDialogsSearchExpandEnabled() {
        ensureCache();
        return dialogsSearchExpand;
    }

    public static void setDialogsSearchExpandEnabled(boolean enabled) {
        ensureCache();
        if (dialogsSearchExpand == enabled) {
            return;
        }
        dialogsSearchExpand = enabled;
        getPrefs().edit().putBoolean(PREF_DIALOGS_SEARCH_EXPAND, enabled).apply();
    }

    public static boolean isDialogsFabMorphEnabled() {
        ensureCache();
        return dialogsFabMorph;
    }

    public static void setDialogsFabMorphEnabled(boolean enabled) {
        ensureCache();
        if (dialogsFabMorph == enabled) {
            return;
        }
        dialogsFabMorph = enabled;
        getPrefs().edit().putBoolean(PREF_DIALOGS_FAB_MORPH, enabled).apply();
        notifyGlassAppearanceChanged();
    }

    public static boolean isReverseChatOpenEnabled() {
        ensureCache();
        return reverseChatOpen;
    }

    public static void setReverseChatOpenEnabled(boolean enabled) {
        ensureCache();
        if (reverseChatOpen == enabled) {
            return;
        }
        reverseChatOpen = enabled;
        getPrefs().edit().putBoolean(PREF_REVERSE_CHAT_OPEN, enabled).apply();
    }

    public static boolean isChatSendButtonMorphEnabled() {
        ensureCache();
        return chatSendButtonMorph;
    }

    public static void setChatSendButtonMorphEnabled(boolean enabled) {
        ensureCache();
        if (chatSendButtonMorph == enabled) {
            return;
        }
        chatSendButtonMorph = enabled;
        getPrefs().edit().putBoolean(PREF_CHAT_SEND_BUTTON_MORPH, enabled).apply();
    }

    public static boolean isChatReactionSpringEnabled() {
        ensureCache();
        return chatReactionSpring;
    }

    public static void setChatReactionSpringEnabled(boolean enabled) {
        ensureCache();
        if (chatReactionSpring == enabled) {
            return;
        }
        chatReactionSpring = enabled;
        getPrefs().edit().putBoolean(PREF_CHAT_REACTION_SPRING, enabled).apply();
    }

    public static boolean isProfileMediaGrowEnabled() {
        ensureCache();
        return profileMediaGrow;
    }

    public static void setProfileMediaGrowEnabled(boolean enabled) {
        ensureCache();
        if (profileMediaGrow == enabled) {
            return;
        }
        profileMediaGrow = enabled;
        getPrefs().edit().putBoolean(PREF_PROFILE_MEDIA_GROW, enabled).apply();
    }

    public static boolean isSettingsPressSpringEnabled() {
        ensureCache();
        return settingsPressSpring;
    }

    public static void setSettingsPressSpringEnabled(boolean enabled) {
        ensureCache();
        if (settingsPressSpring == enabled) {
            return;
        }
        settingsPressSpring = enabled;
        getPrefs().edit().putBoolean(PREF_SETTINGS_PRESS_SPRING, enabled).apply();
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
