package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

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
    /** Chat action-bar style: glass standard / glass clean / stock Telegram. */
    public static final String PREF_CHAT_HEADER_STYLE = "chat_header_style";
    /** Floating liquid-glass nick pill (current default). */
    public static final int HEADER_STYLE_STANDARD = 0;
    /** Same glass pill with optional element hiding («Чистая»). */
    public static final int HEADER_STYLE_CLEAN = 1;
    /** Stock Telegram liquid-glass header: back circle + avatar/title pill + menu pill. */
    public static final int HEADER_STYLE_TELEGRAM = 2;
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
    /** Cached style for {@link #styleCachedForAccount}; reloaded on account switch. */
    private static int predictiveBackStyle = PREDICTIVE_BACK_DEFAULT;
    private static int styleCachedForAccount = Integer.MIN_VALUE;
    private static boolean md3Switches;
    private static boolean editedIcon;
    private static boolean customNotificationIcon = true;
    private static boolean cleanHeader;
    private static int chatHeaderStyle = HEADER_STYLE_STANDARD;
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
        // Predictive back style is per-account — loaded in ensurePredictiveBackStyle().
        md3Switches = p.getBoolean(PREF_MD3_SWITCHES, false);
        editedIcon = p.getBoolean(PREF_EDITED_ICON, false);
        cleanHeader = p.getBoolean(PREF_CLEAN_HEADER, false);
        if (p.contains(PREF_CHAT_HEADER_STYLE)) {
            chatHeaderStyle = clampHeaderStyle(p.getInt(PREF_CHAT_HEADER_STYLE, HEADER_STYLE_STANDARD));
        } else {
            // Migrate from the old boolean: clean on → CLEAN, otherwise STANDARD.
            chatHeaderStyle = cleanHeader ? HEADER_STYLE_CLEAN : HEADER_STYLE_STANDARD;
        }
        cleanHeader = chatHeaderStyle == HEADER_STYLE_CLEAN;
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

    private static int clampHeaderStyle(int style) {
        if (style < HEADER_STYLE_STANDARD || style > HEADER_STYLE_TELEGRAM) {
            return HEADER_STYLE_STANDARD;
        }
        return style;
    }

    public static int getChatHeaderStyle() {
        ensureCache();
        return chatHeaderStyle;
    }

    public static void setChatHeaderStyle(int style) {
        ensureCache();
        style = clampHeaderStyle(style);
        if (chatHeaderStyle == style) {
            return;
        }
        chatHeaderStyle = style;
        cleanHeader = style == HEADER_STYLE_CLEAN;
        getPrefs().edit()
                .putInt(PREF_CHAT_HEADER_STYLE, style)
                .putBoolean(PREF_CLEAN_HEADER, cleanHeader)
                .apply();
        notifyGlassAppearanceChanged();
    }

    /** Mgla floating liquid-glass header (Standard / Clean) — custom nick-pill layout. */
    public static boolean isGlassChatHeaderEnabled() {
        final int style = getChatHeaderStyle();
        return style == HEADER_STYLE_STANDARD || style == HEADER_STYLE_CLEAN;
    }

    /** Stock Telegram chat header (avatar + left title inside the glass pill). */
    public static boolean isTelegramHeaderEnabled() {
        return getChatHeaderStyle() == HEADER_STYLE_TELEGRAM;
    }

    /** Any floating glass header (Standard, Clean, or stock Telegram). */
    public static boolean isFloatingGlassHeaderEnabled() {
        return isGlassChatHeaderEnabled() || isTelegramHeaderEnabled();
    }

    public static boolean isCleanHeaderEnabled() {
        ensureCache();
        return chatHeaderStyle == HEADER_STYLE_CLEAN;
    }

    public static void setCleanHeaderEnabled(boolean enabled) {
        setChatHeaderStyle(enabled ? HEADER_STYLE_CLEAN : HEADER_STYLE_STANDARD);
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

    /** Styles from «Поверхность» onward require any paid Mgla plan. */
    public static boolean isPremiumPredictiveBackStyle(int style) {
        return clampPredictiveBackStyle(style) >= PREDICTIVE_BACK_SURFACE;
    }

    private static String predictiveBackStyleKey(long userId) {
        return PREF_PREDICTIVE_BACK_STYLE + "_" + userId;
    }

    private static long currentUserId() {
        UserConfig uc = UserConfig.getInstance(UserConfig.selectedAccount);
        if (uc == null || !uc.isClientActivated()) {
            return 0L;
        }
        return uc.getClientUserId();
    }

    /** Load (and migrate legacy global) style for the currently selected account. */
    private static void ensurePredictiveBackStyle() {
        ensureCache();
        int account = UserConfig.selectedAccount;
        if (styleCachedForAccount == account) {
            return;
        }
        styleCachedForAccount = account;
        predictiveBackStyle = readStyleForAccount(account);
        md3PredictiveBack = predictiveBackStyle == PREDICTIVE_BACK_MD3;
    }

    private static int readStyleForAccount(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return PREDICTIVE_BACK_DEFAULT;
        }
        UserConfig uc = UserConfig.getInstance(account);
        if (uc == null || !uc.isClientActivated()) {
            return PREDICTIVE_BACK_DEFAULT;
        }
        long userId = uc.getClientUserId();
        if (userId <= 0) {
            return PREDICTIVE_BACK_DEFAULT;
        }
        SharedPreferences p = getPrefs();
        String key = predictiveBackStyleKey(userId);
        if (p.contains(key)) {
            return clampPredictiveBackStyle(p.getInt(key, PREDICTIVE_BACK_DEFAULT));
        }
        // One-shot migrate from install-wide keys into this account.
        int legacy = PREDICTIVE_BACK_DEFAULT;
        if (p.contains(PREF_PREDICTIVE_BACK_STYLE)) {
            legacy = clampPredictiveBackStyle(p.getInt(PREF_PREDICTIVE_BACK_STYLE, PREDICTIVE_BACK_DEFAULT));
        } else if (p.getBoolean(PREF_MD3_PREDICTIVE_BACK, false)) {
            legacy = PREDICTIVE_BACK_MD3;
        }
        p.edit().putInt(key, legacy).apply();
        return legacy;
    }

    /**
     * Effective style for animations on the current account: premium styles
     * fall back to default when this account has no subscription.
     */
    public static int getPredictiveBackStyle() {
        ensurePredictiveBackStyle();
        int style = clampPredictiveBackStyle(predictiveBackStyle);
        if (isPremiumPredictiveBackStyle(style) && !MglaSubscription.canUsePremiumAnimations()) {
            return PREDICTIVE_BACK_DEFAULT;
        }
        return style;
    }

    /** Persist style for the currently selected account only. */
    public static void setPredictiveBackStyle(int style) {
        ensurePredictiveBackStyle();
        style = clampPredictiveBackStyle(style);
        if (isPremiumPredictiveBackStyle(style) && !MglaSubscription.canUsePremiumAnimations()) {
            return;
        }
        long userId = currentUserId();
        if (userId <= 0) {
            return;
        }
        if (predictiveBackStyle == style) {
            return;
        }
        predictiveBackStyle = style;
        md3PredictiveBack = style == PREDICTIVE_BACK_MD3;
        getPrefs().edit()
                .putInt(predictiveBackStyleKey(userId), style)
                .apply();
    }

    /** Drop sticky premium style for the current account after plan expiry. */
    public static void clearPremiumBackStyleIfUnauthorized() {
        clearPremiumBackStyleForUser(currentUserId());
    }

    /** Drop sticky premium style for a specific Telegram user (plan revoked). */
    public static void clearPremiumBackStyleForUser(long userId) {
        if (userId <= 0) {
            return;
        }
        if (!MglaSubscription.PLAN_FREE.equals(MglaSubscription.getPlanIdForUser(userId))) {
            return;
        }
        SharedPreferences p = getPrefs();
        String key = predictiveBackStyleKey(userId);
        int style = PREDICTIVE_BACK_DEFAULT;
        if (p.contains(key)) {
            style = clampPredictiveBackStyle(p.getInt(key, PREDICTIVE_BACK_DEFAULT));
        } else if (p.contains(PREF_PREDICTIVE_BACK_STYLE)) {
            style = clampPredictiveBackStyle(p.getInt(PREF_PREDICTIVE_BACK_STYLE, PREDICTIVE_BACK_DEFAULT));
        }
        if (!isPremiumPredictiveBackStyle(style)) {
            return;
        }
        p.edit().putInt(key, PREDICTIVE_BACK_DEFAULT).apply();
        if (currentUserId() == userId) {
            predictiveBackStyle = PREDICTIVE_BACK_DEFAULT;
            md3PredictiveBack = false;
            styleCachedForAccount = UserConfig.selectedAccount;
        } else {
            // Force reload if that account becomes selected later.
            styleCachedForAccount = Integer.MIN_VALUE;
        }
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
