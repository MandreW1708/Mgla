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
    /** Material 3 shrinking floating card. */
    public static final int PREDICTIVE_BACK_MD3 = 1;
    /** Flat page slide left/right. */
    public static final int PREDICTIVE_BACK_PAGES = 2;
    /** Both screens shrink; previous pushes the active one out. */
    public static final int PREDICTIVE_BACK_PUSH = 3;
    /** Google full-screen surface: exit 100→90%, enter 110→100%, fade-through @35%. */
    public static final int PREDICTIVE_BACK_SURFACE = 4;
    /** Google shared-element: detach, scale 90%, X/Y shift with margins. */
    public static final int PREDICTIVE_BACK_SHARED = 5;
    /** Material Fade Through (crossfade + slight scale). */
    public static final int PREDICTIVE_BACK_FADE_THROUGH = 6;
    /** Material Shared Axis X. */
    public static final int PREDICTIVE_BACK_AXIS_X = 7;
    /** Material Shared Axis Y (vertical). */
    public static final int PREDICTIVE_BACK_AXIS_Y = 8;
    /** Material Shared Axis Z (depth). */
    public static final int PREDICTIVE_BACK_AXIS_Z = 9;
    /** Front slides full-speed; previous lags (parallax). */
    public static final int PREDICTIVE_BACK_PARALLAX = 10;
    /** Previous sits deep and scaled; front slides off as a card. */
    public static final int PREDICTIVE_BACK_DEPTH = 11;
    /** Strong zoom-out of the active screen. */
    public static final int PREDICTIVE_BACK_ZOOM = 12;
    /** 3D cube rotation around Y. */
    public static final int PREDICTIVE_BACK_CUBE = 13;
    /** 3D flip around Y. */
    public static final int PREDICTIVE_BACK_FLIP = 14;
    /** Front covers and slides away; previous stays put. */
    public static final int PREDICTIVE_BACK_COVER = 15;
    /** Vertical dismiss upward (sheet-like). */
    public static final int PREDICTIVE_BACK_SLIDE_UP = 16;
    /** Stacked cards with slight tilt. */
    public static final int PREDICTIVE_BACK_CARDS = 17;
    /** Elastic / springy page slide. */
    public static final int PREDICTIVE_BACK_ELASTIC = 18;
    /** Soft fade of the active screen (Material Fade). */
    public static final int PREDICTIVE_BACK_FADE = 19;
    public static final int PREDICTIVE_BACK_LAST = PREDICTIVE_BACK_FADE;
    /** All selectable styles in display order. */
    public static final int[] PREDICTIVE_BACK_STYLES = {
            PREDICTIVE_BACK_DEFAULT,
            PREDICTIVE_BACK_MD3,
            PREDICTIVE_BACK_PAGES,
            PREDICTIVE_BACK_PUSH,
            PREDICTIVE_BACK_SURFACE,
            PREDICTIVE_BACK_SHARED,
            PREDICTIVE_BACK_FADE_THROUGH,
            PREDICTIVE_BACK_AXIS_X,
            PREDICTIVE_BACK_AXIS_Y,
            PREDICTIVE_BACK_AXIS_Z,
            PREDICTIVE_BACK_DEPTH,
            PREDICTIVE_BACK_ZOOM,
            PREDICTIVE_BACK_CUBE,
            PREDICTIVE_BACK_COVER,
            PREDICTIVE_BACK_CARDS,
            PREDICTIVE_BACK_FADE
    };
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
    public static final String PREF_FOLDER_ACTIVE_OUTLINE = "folder_active_outline";
    /** false = Telegram plane, true = custom status-bar notification icon. */
    public static final String PREF_CUSTOM_NOTIFICATION_ICON = "custom_notification_icon";

    // In-memory cache — prefs are hit from Switch/ActionBar draw paths every frame.
    private static boolean cacheLoaded;
    private static int glassDarkeningLevel = 100;
    private static boolean md3PredictiveBack;
    private static int predictiveBackStyle = PREDICTIVE_BACK_DEFAULT;
    private static boolean md3Switches;
    private static boolean editedIcon;
    private static boolean customNotificationIcon = true;
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
    private static boolean folderActiveOutline;

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
        folderActiveOutline = p.getBoolean(PREF_FOLDER_ACTIVE_OUTLINE, false);
        customNotificationIcon = p.getBoolean(PREF_CUSTOM_NOTIFICATION_ICON, true);
        cacheLoaded = true;
    }

    public static boolean isCustomNotificationIconEnabled() {
        ensureCache();
        return customNotificationIcon;
    }

    public static void setCustomNotificationIconEnabled(boolean enabled) {
        ensureCache();
        if (customNotificationIcon == enabled) {
            return;
        }
        customNotificationIcon = enabled;
        getPrefs().edit().putBoolean(PREF_CUSTOM_NOTIFICATION_ICON, enabled).apply();
    }

    /** Status-bar / notification {@code setSmallIcon} resource for message notifications. */
    public static int getNotificationSmallIconRes() {
        // Standard = stock Telegram paper-plane (drawable*/notification.webp).
        return isCustomNotificationIconEnabled()
                ? org.telegram.messenger.R.drawable.notific_icon_custom
                : org.telegram.messenger.R.drawable.notification;
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
        return isPredictiveBackSpatial(getPredictiveBackStyle());
    }

    /** Everything except classic Telegram slide uses canvas spatial transforms. */
    public static boolean isPredictiveBackSpatial(int style) {
        return clampPredictiveBackStyle(style) != PREDICTIVE_BACK_DEFAULT;
    }

    /** Fill gaps between layers with the theme surface — no opaque black dim. */
    public static boolean usesThemedPredictiveStage(int style) {
        return isPredictiveBackSpatial(style);
    }

    /** Soft floating-card shadow under the active layer — disabled (looks abrupt on exit). */
    public static boolean usesPredictiveFrontShadow(int style) {
        return false;
    }

    /** Target corner radius (dp) for the active card during spatial back; 0 = square/page. */
    public static float predictiveFrontRadiusDp(int style) {
        switch (clampPredictiveBackStyle(style)) {
            case PREDICTIVE_BACK_PAGES:
            case PREDICTIVE_BACK_FADE_THROUGH:
            case PREDICTIVE_BACK_FADE:
            case PREDICTIVE_BACK_AXIS_X:
            case PREDICTIVE_BACK_AXIS_Y:
                return 0f;
            case PREDICTIVE_BACK_PUSH:
            case PREDICTIVE_BACK_COVER:
                return 20f;
            case PREDICTIVE_BACK_CUBE:
                return 12f;
            default:
                return 28f;
        }
    }

    public static String getPredictiveBackStyleTitle(int style) {
        switch (clampPredictiveBackStyle(style)) {
            case PREDICTIVE_BACK_MD3:
                return "В стиле MD3";
            case PREDICTIVE_BACK_PAGES:
                return "Страницы";
            case PREDICTIVE_BACK_PUSH:
                return "Выталкивание";
            case PREDICTIVE_BACK_SURFACE:
                return "Поверхность";
            case PREDICTIVE_BACK_SHARED:
                return "Отрыв";
            case PREDICTIVE_BACK_FADE_THROUGH:
                return "Проявление";
            case PREDICTIVE_BACK_AXIS_X:
                return "Ось X";
            case PREDICTIVE_BACK_AXIS_Y:
                return "Ось Y";
            case PREDICTIVE_BACK_AXIS_Z:
                return "Ось Z";
            case PREDICTIVE_BACK_DEPTH:
                return "Глубина";
            case PREDICTIVE_BACK_ZOOM:
                return "Зум";
            case PREDICTIVE_BACK_CUBE:
                return "3D страницы";
            case PREDICTIVE_BACK_COVER:
                return "Укрытие";
            case PREDICTIVE_BACK_CARDS:
                return "Карточки";
            case PREDICTIVE_BACK_FADE:
                return "Исчезновение";
            default:
                return "Обычная";
        }
    }

    private static int clampPredictiveBackStyle(int style) {
        // Удалённые из списка варианты: параллакс ≈ ось X, остальное — обычная.
        if (style == PREDICTIVE_BACK_PARALLAX) {
            return PREDICTIVE_BACK_AXIS_X;
        }
        if (style == PREDICTIVE_BACK_FLIP
                || style == PREDICTIVE_BACK_SLIDE_UP
                || style == PREDICTIVE_BACK_ELASTIC) {
            return PREDICTIVE_BACK_DEFAULT;
        }
        if (style < PREDICTIVE_BACK_DEFAULT || style > PREDICTIVE_BACK_LAST) {
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

    public static boolean isFolderActiveOutlineEnabled() {
        ensureCache();
        return folderActiveOutline;
    }

    public static void setFolderActiveOutlineEnabled(boolean enabled) {
        ensureCache();
        if (folderActiveOutline == enabled) {
            return;
        }
        folderActiveOutline = enabled;
        getPrefs().edit().putBoolean(PREF_FOLDER_ACTIVE_OUTLINE, enabled).apply();
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
