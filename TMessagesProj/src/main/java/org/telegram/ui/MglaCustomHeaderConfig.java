package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Preferences for {@link MglaGlassConfig#HEADER_STYLE_CUSTOM} chat header constructor.
 */
public final class MglaCustomHeaderConfig {

    public static final int PRESET_STANDARD = 0;
    public static final int PRESET_TELEGRAM = 1;
    public static final int PRESET_CENTER = 2;
    public static final int PRESET_COMPACT = 3;
    public static final int PRESET_MINIMAL = 4;
    public static final int PRESET_HERO = 5;

    public static final int AVATAR_SIZE_S = 0;
    public static final int AVATAR_SIZE_M = 1;
    public static final int AVATAR_SIZE_L = 2;
    public static final int AVATAR_SIZE_XL = 3;

    public static final int TITLE_SIZE_NORMAL = 0;
    public static final int TITLE_SIZE_LARGE = 1;

    public static final int TITLE_ALIGN_START = 0;
    public static final int TITLE_ALIGN_CENTER = 1;

    public static final int AVATAR_SIDE_START = 0;
    public static final int AVATAR_SIDE_END = 1;
    public static final int AVATAR_SIDE_HIDDEN = 2;

    public static final int HEIGHT_COMPACT = 0;
    public static final int HEIGHT_NORMAL = 1;
    public static final int HEIGHT_TALL = 2;

    public static final int RADIUS_ROUND = 0;
    public static final int RADIUS_SOFT = 1;
    public static final int RADIUS_SQUARE = 2;

    private static final String K_PRESET = "custom_header_preset";
    private static final String K_SHOW_BACK = "custom_header_show_back";
    private static final String K_SHOW_AVATAR = "custom_header_show_avatar";
    private static final String K_SHOW_TITLE = "custom_header_show_title";
    private static final String K_SHOW_SUBTITLE = "custom_header_show_subtitle";
    private static final String K_SHOW_MENU = "custom_header_show_menu";
    private static final String K_SHOW_COMMUNITY = "custom_header_show_community";
    private static final String K_SHOW_TIMER = "custom_header_show_timer";
    private static final String K_GLASS_BACK = "custom_header_glass_back";
    private static final String K_GLASS_TITLE = "custom_header_glass_title";
    private static final String K_GLASS_MENU = "custom_header_glass_menu";
    private static final String K_GLASS_PINNED = "custom_header_glass_pinned";
    private static final String K_GLASS_TRANSLATION = "custom_header_glass_translation";
    private static final String K_SHOW_PINNED = "custom_header_show_pinned";
    private static final String K_SHOW_TRANSLATION = "custom_header_show_translation";
    private static final String K_AVATAR_SIZE = "custom_header_avatar_size";
    private static final String K_TITLE_SIZE = "custom_header_title_size";
    private static final String K_TITLE_ALIGN = "custom_header_title_align";
    private static final String K_AVATAR_SIDE = "custom_header_avatar_side";
    private static final String K_HEIGHT = "custom_header_height";
    private static final String K_RADIUS = "custom_header_radius";
    private static final String K_GAP = "custom_header_gap";
    private static final String K_PAD = "custom_header_pad";

    private static boolean cacheLoaded;
    private static int layoutPreset = PRESET_STANDARD;
    private static boolean showBack = true;
    private static boolean showAvatar = true;
    private static boolean showTitle = true;
    private static boolean showSubtitle = true;
    private static boolean showMenu = true;
    private static boolean showCommunity = true;
    private static boolean showTimer = true;
    private static boolean glassBack = true;
    private static boolean glassTitle = true;
    private static boolean glassMenu = true;
    private static boolean glassPinned = true;
    private static boolean glassTranslation = true;
    private static boolean showPinned = true;
    private static boolean showTranslation = true;
    private static int avatarSize = AVATAR_SIZE_M;
    private static int titleSize = TITLE_SIZE_NORMAL;
    private static int titleAlign = TITLE_ALIGN_CENTER;
    private static int avatarSide = AVATAR_SIDE_END;
    private static int headerHeight = HEIGHT_NORMAL;
    private static int glassRadius = RADIUS_ROUND;
    private static int gapDp = 4;
    private static int padDp = 10;

    private MglaCustomHeaderConfig() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(MglaGlassConfig.PREFS, Context.MODE_PRIVATE);
    }

    /** After cloud settings apply — drop in-memory cache and reload from prefs. */
    public static void reloadCacheForSync() {
        cacheLoaded = false;
        ensureCache();
    }

    private static void ensureCache() {
        if (cacheLoaded) {
            return;
        }
        SharedPreferences p = prefs();
        layoutPreset = clamp(p.getInt(K_PRESET, PRESET_STANDARD), PRESET_STANDARD, PRESET_HERO);
        showBack = p.getBoolean(K_SHOW_BACK, true);
        showAvatar = p.getBoolean(K_SHOW_AVATAR, true);
        showTitle = p.getBoolean(K_SHOW_TITLE, true);
        showSubtitle = p.getBoolean(K_SHOW_SUBTITLE, true);
        showMenu = p.getBoolean(K_SHOW_MENU, true);
        showCommunity = p.getBoolean(K_SHOW_COMMUNITY, true);
        showTimer = p.getBoolean(K_SHOW_TIMER, true);
        glassBack = p.getBoolean(K_GLASS_BACK, true);
        glassTitle = p.getBoolean(K_GLASS_TITLE, true);
        glassMenu = p.getBoolean(K_GLASS_MENU, true);
        glassPinned = p.getBoolean(K_GLASS_PINNED, true);
        glassTranslation = p.getBoolean(K_GLASS_TRANSLATION, true);
        showPinned = p.getBoolean(K_SHOW_PINNED, true);
        showTranslation = p.getBoolean(K_SHOW_TRANSLATION, true);
        avatarSize = clamp(p.getInt(K_AVATAR_SIZE, AVATAR_SIZE_M), AVATAR_SIZE_S, AVATAR_SIZE_XL);
        titleSize = clamp(p.getInt(K_TITLE_SIZE, TITLE_SIZE_NORMAL), TITLE_SIZE_NORMAL, TITLE_SIZE_LARGE);
        titleAlign = clamp(p.getInt(K_TITLE_ALIGN, TITLE_ALIGN_CENTER), TITLE_ALIGN_START, TITLE_ALIGN_CENTER);
        avatarSide = clamp(p.getInt(K_AVATAR_SIDE, AVATAR_SIDE_END), AVATAR_SIDE_START, AVATAR_SIDE_HIDDEN);
        headerHeight = clamp(p.getInt(K_HEIGHT, HEIGHT_NORMAL), HEIGHT_COMPACT, HEIGHT_TALL);
        glassRadius = clamp(p.getInt(K_RADIUS, RADIUS_ROUND), RADIUS_ROUND, RADIUS_SQUARE);
        gapDp = clamp(p.getInt(K_GAP, 4), 0, 16);
        padDp = clamp(p.getInt(K_PAD, 10), 4, 20);
        cacheLoaded = true;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void putInt(String key, int value) {
        prefs().edit().putInt(key, value).apply();
        MglaGlassConfig.notifyGlassAppearanceChanged();
    }

    private static void putBool(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        MglaGlassConfig.notifyGlassAppearanceChanged();
    }

    public static int getLayoutPreset() {
        ensureCache();
        return layoutPreset;
    }

    public static void setLayoutPreset(int preset) {
        ensureCache();
        preset = clamp(preset, PRESET_STANDARD, PRESET_HERO);
        if (layoutPreset == preset) {
            return;
        }
        layoutPreset = preset;
        applyPresetDefaults(preset);
        SharedPreferences.Editor e = prefs().edit();
        e.putInt(K_PRESET, layoutPreset);
        writeAll(e);
        e.apply();
        MglaGlassConfig.notifyGlassAppearanceChanged();
    }

    /** Apply preset field values without writing (caller persists). */
    private static void applyPresetDefaults(int preset) {
        showBack = true;
        showMenu = true;
        showTitle = true;
        showSubtitle = true;
        showAvatar = true;
        showCommunity = true;
        showTimer = true;
        showPinned = true;
        showTranslation = true;
        glassBack = true;
        glassTitle = true;
        glassMenu = true;
        glassPinned = true;
        glassTranslation = true;
        avatarSize = AVATAR_SIZE_M;
        titleSize = TITLE_SIZE_NORMAL;
        titleAlign = TITLE_ALIGN_CENTER;
        avatarSide = AVATAR_SIDE_END;
        headerHeight = HEIGHT_NORMAL;
        glassRadius = RADIUS_ROUND;
        gapDp = 4;
        padDp = 10;

        switch (preset) {
            case PRESET_TELEGRAM:
                titleAlign = TITLE_ALIGN_START;
                avatarSide = AVATAR_SIDE_START;
                glassMenu = true;
                break;
            case PRESET_CENTER:
                titleAlign = TITLE_ALIGN_CENTER;
                avatarSide = AVATAR_SIDE_HIDDEN;
                showAvatar = false;
                break;
            case PRESET_COMPACT:
                avatarSize = AVATAR_SIZE_S;
                headerHeight = HEIGHT_COMPACT;
                showSubtitle = false;
                gapDp = 2;
                break;
            case PRESET_MINIMAL:
                showTitle = false;
                showSubtitle = false;
                showAvatar = false;
                avatarSide = AVATAR_SIDE_HIDDEN;
                glassTitle = false;
                showPinned = false;
                showTranslation = false;
                break;
            case PRESET_HERO:
                avatarSize = AVATAR_SIZE_XL;
                titleSize = TITLE_SIZE_LARGE;
                headerHeight = HEIGHT_TALL;
                titleAlign = TITLE_ALIGN_CENTER;
                avatarSide = AVATAR_SIDE_START;
                break;
            case PRESET_STANDARD:
            default:
                break;
        }
    }

    private static void writeAll(SharedPreferences.Editor e) {
        e.putBoolean(K_SHOW_BACK, showBack);
        e.putBoolean(K_SHOW_AVATAR, showAvatar);
        e.putBoolean(K_SHOW_TITLE, showTitle);
        e.putBoolean(K_SHOW_SUBTITLE, showSubtitle);
        e.putBoolean(K_SHOW_MENU, showMenu);
        e.putBoolean(K_SHOW_COMMUNITY, showCommunity);
        e.putBoolean(K_SHOW_TIMER, showTimer);
        e.putBoolean(K_GLASS_BACK, glassBack);
        e.putBoolean(K_GLASS_TITLE, glassTitle);
        e.putBoolean(K_GLASS_MENU, glassMenu);
        e.putBoolean(K_GLASS_PINNED, glassPinned);
        e.putBoolean(K_GLASS_TRANSLATION, glassTranslation);
        e.putBoolean(K_SHOW_PINNED, showPinned);
        e.putBoolean(K_SHOW_TRANSLATION, showTranslation);
        e.putInt(K_AVATAR_SIZE, avatarSize);
        e.putInt(K_TITLE_SIZE, titleSize);
        e.putInt(K_TITLE_ALIGN, titleAlign);
        e.putInt(K_AVATAR_SIDE, avatarSide);
        e.putInt(K_HEIGHT, headerHeight);
        e.putInt(K_RADIUS, glassRadius);
        e.putInt(K_GAP, gapDp);
        e.putInt(K_PAD, padDp);
    }

    public static void resetToPresetDefaults() {
        ensureCache();
        applyPresetDefaults(layoutPreset);
        SharedPreferences.Editor e = prefs().edit();
        e.putInt(K_PRESET, layoutPreset);
        writeAll(e);
        e.apply();
        MglaGlassConfig.notifyGlassAppearanceChanged();
    }

    public static boolean isShowBack() { ensureCache(); return showBack; }
    public static void setShowBack(boolean v) { ensureCache(); if (showBack == v) return; showBack = v; putBool(K_SHOW_BACK, v); }

    public static boolean isShowAvatar() { ensureCache(); return showAvatar && avatarSide != AVATAR_SIDE_HIDDEN; }
    public static boolean isShowAvatarRaw() { ensureCache(); return showAvatar; }
    public static void setShowAvatar(boolean v) {
        ensureCache();
        if (showAvatar == v) return;
        showAvatar = v;
        if (!v) {
            avatarSide = AVATAR_SIDE_HIDDEN;
            prefs().edit().putBoolean(K_SHOW_AVATAR, false).putInt(K_AVATAR_SIDE, avatarSide).apply();
        } else {
            if (avatarSide == AVATAR_SIDE_HIDDEN) {
                avatarSide = titleAlign == TITLE_ALIGN_START ? AVATAR_SIDE_START : AVATAR_SIDE_END;
            }
            prefs().edit().putBoolean(K_SHOW_AVATAR, true).putInt(K_AVATAR_SIDE, avatarSide).apply();
        }
        MglaGlassConfig.notifyGlassAppearanceChanged();
    }

    public static boolean isShowTitle() { ensureCache(); return showTitle; }
    public static void setShowTitle(boolean v) { ensureCache(); if (showTitle == v) return; showTitle = v; putBool(K_SHOW_TITLE, v); }

    public static boolean isShowSubtitle() { ensureCache(); return showSubtitle; }
    public static void setShowSubtitle(boolean v) { ensureCache(); if (showSubtitle == v) return; showSubtitle = v; putBool(K_SHOW_SUBTITLE, v); }

    public static boolean isShowMenu() { ensureCache(); return showMenu; }
    public static void setShowMenu(boolean v) { ensureCache(); if (showMenu == v) return; showMenu = v; putBool(K_SHOW_MENU, v); }

    public static boolean isShowCommunity() { ensureCache(); return showCommunity; }
    public static void setShowCommunity(boolean v) { ensureCache(); if (showCommunity == v) return; showCommunity = v; putBool(K_SHOW_COMMUNITY, v); }

    public static boolean isShowTimer() { ensureCache(); return showTimer; }
    public static void setShowTimer(boolean v) { ensureCache(); if (showTimer == v) return; showTimer = v; putBool(K_SHOW_TIMER, v); }

    public static boolean isGlassBack() { ensureCache(); return glassBack; }
    public static void setGlassBack(boolean v) { ensureCache(); if (glassBack == v) return; glassBack = v; putBool(K_GLASS_BACK, v); }

    public static boolean isGlassTitle() { ensureCache(); return glassTitle; }
    public static void setGlassTitle(boolean v) { ensureCache(); if (glassTitle == v) return; glassTitle = v; putBool(K_GLASS_TITLE, v); }

    public static boolean isGlassMenu() { ensureCache(); return glassMenu; }
    public static void setGlassMenu(boolean v) { ensureCache(); if (glassMenu == v) return; glassMenu = v; putBool(K_GLASS_MENU, v); }

    public static boolean isGlassPinned() { ensureCache(); return glassPinned; }
    public static void setGlassPinned(boolean v) { ensureCache(); if (glassPinned == v) return; glassPinned = v; putBool(K_GLASS_PINNED, v); }

    public static boolean isGlassTranslation() { ensureCache(); return glassTranslation; }
    public static void setGlassTranslation(boolean v) { ensureCache(); if (glassTranslation == v) return; glassTranslation = v; putBool(K_GLASS_TRANSLATION, v); }

    public static boolean isShowPinned() { ensureCache(); return showPinned; }
    public static void setShowPinned(boolean v) { ensureCache(); if (showPinned == v) return; showPinned = v; putBool(K_SHOW_PINNED, v); }

    public static boolean isShowTranslation() { ensureCache(); return showTranslation; }
    public static void setShowTranslation(boolean v) { ensureCache(); if (showTranslation == v) return; showTranslation = v; putBool(K_SHOW_TRANSLATION, v); }

    public static int getAvatarSize() { ensureCache(); return avatarSize; }
    public static void setAvatarSize(int v) {
        ensureCache();
        v = clamp(v, AVATAR_SIZE_S, AVATAR_SIZE_XL);
        if (avatarSize == v) return;
        avatarSize = v;
        putInt(K_AVATAR_SIZE, v);
    }

    public static int getTitleSize() { ensureCache(); return titleSize; }
    public static void setTitleSize(int v) {
        ensureCache();
        v = clamp(v, TITLE_SIZE_NORMAL, TITLE_SIZE_LARGE);
        if (titleSize == v) return;
        titleSize = v;
        putInt(K_TITLE_SIZE, v);
    }

    public static int getTitleAlign() { ensureCache(); return titleAlign; }
    public static void setTitleAlign(int v) {
        ensureCache();
        v = clamp(v, TITLE_ALIGN_START, TITLE_ALIGN_CENTER);
        if (titleAlign == v) return;
        titleAlign = v;
        putInt(K_TITLE_ALIGN, v);
    }

    public static int getAvatarSide() { ensureCache(); return avatarSide; }
    public static void setAvatarSide(int v) {
        ensureCache();
        v = clamp(v, AVATAR_SIDE_START, AVATAR_SIDE_HIDDEN);
        if (avatarSide == v) return;
        avatarSide = v;
        showAvatar = v != AVATAR_SIDE_HIDDEN;
        prefs().edit().putInt(K_AVATAR_SIDE, v).putBoolean(K_SHOW_AVATAR, showAvatar).apply();
        MglaGlassConfig.notifyGlassAppearanceChanged();
    }

    public static int getHeaderHeight() { ensureCache(); return headerHeight; }
    public static void setHeaderHeight(int v) {
        ensureCache();
        v = clamp(v, HEIGHT_COMPACT, HEIGHT_TALL);
        if (headerHeight == v) return;
        headerHeight = v;
        putInt(K_HEIGHT, v);
    }

    public static int getGlassRadius() { ensureCache(); return glassRadius; }
    public static void setGlassRadius(int v) {
        ensureCache();
        v = clamp(v, RADIUS_ROUND, RADIUS_SQUARE);
        if (glassRadius == v) return;
        glassRadius = v;
        putInt(K_RADIUS, v);
    }

    public static int getGapDp() { ensureCache(); return gapDp; }
    public static void setGapDp(int v) {
        ensureCache();
        v = clamp(v, 0, 16);
        if (gapDp == v) return;
        gapDp = v;
        putInt(K_GAP, v);
    }

    public static int getPadDp() { ensureCache(); return padDp; }
    public static void setPadDp(int v) {
        ensureCache();
        v = clamp(v, 4, 20);
        if (padDp == v) return;
        padDp = v;
        putInt(K_PAD, v);
    }

    public static String presetLabel(int preset) {
        switch (clamp(preset, PRESET_STANDARD, PRESET_HERO)) {
            case PRESET_TELEGRAM: return "Telegram";
            case PRESET_CENTER: return "Центр";
            case PRESET_COMPACT: return "Компакт";
            case PRESET_MINIMAL: return "Минимум";
            case PRESET_HERO: return "Hero";
            case PRESET_STANDARD:
            default: return "Стандарт";
        }
    }
}
