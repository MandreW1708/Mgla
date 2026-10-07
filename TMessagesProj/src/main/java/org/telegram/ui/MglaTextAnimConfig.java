package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MglaFeatureFlags;
import org.telegram.ui.ActionBar.Theme;

/** Settings of the typing animation in the chat input field («Анимация текста»). */
public final class MglaTextAnimConfig {

    private static final String PREFS = "mgla_config";
    private static final String KEY_ENABLED = "text_anim_enabled";
    private static final String KEY_EFFECT = "text_anim_effect";
    private static final String KEY_DURATION = "text_anim_duration";
    private static final String KEY_STRENGTH = "text_anim_strength";
    private static final String KEY_EASING = "text_anim_easing";
    private static final String KEY_COLOR = "text_anim_color";
    private static final String KEY_CASCADE = "text_anim_cascade";
    private static final String KEY_CASCADE_DELAY = "text_anim_cascade_delay";

    public static final int EFFECT_FADE = 0;
    public static final int EFFECT_RISE = 1;
    public static final int EFFECT_DROP = 2;
    public static final int EFFECT_SPRING = 3;
    public static final int EFFECT_WAVE = 4;
    public static final int EFFECT_GLOW = 5;
    public static final int EFFECT_FLASH = 6;
    public static final int EFFECT_MARKER = 7;
    public static final int EFFECT_DISSOLVE = 8;
    public static final int EFFECT_SKEW = 9;
    public static final int EFFECT_MIX = 10;

    public static final String[] EFFECT_NAMES = {
        "Появление", "Всплытие", "Падение", "Пружина", "Волна", "Свечение",
        "Вспышка", "Маркер", "Растворение", "Наклон", "Микс"
    };

    public static final int EASING_SMOOTH = 0;
    public static final int EASING_SPRING = 1;
    public static final int EASING_SOFT = 2;
    public static final int EASING_ELASTIC = 3;
    public static final int EASING_LINEAR = 4;

    public static final String[] EASING_NAMES = {"Плавная", "Пружинная", "Мягкая", "Упругая", "Линейная"};

    /** Index 0 means "theme accent"; the rest are fixed colors. */
    public static final int[] COLORS = {
        0, 0xFF3D8BFF, 0xFF8E5CFF, 0xFFFF4FA3, 0xFFFF5252, 0xFFFF9800, 0xFFFFC93C, 0xFF2ECC71, 0xFF1DD3C9
    };

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_EFFECT = EFFECT_RISE;
    public static final int DEFAULT_DURATION = 380;
    public static final int DEFAULT_STRENGTH = 100;
    public static final int DEFAULT_EASING = EASING_SMOOTH;
    public static final int DEFAULT_COLOR = 0;
    public static final boolean DEFAULT_CASCADE = true;
    public static final int DEFAULT_CASCADE_DELAY = 22;

    private static boolean loaded;
    private static boolean enabled;
    private static int effect;
    private static int duration;
    private static int strength;
    private static int easing;
    private static int color;
    private static boolean cascade;
    private static int cascadeDelay;

    private MglaTextAnimConfig() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        SharedPreferences p = prefs();
        enabled = p.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
        effect = clamp(p.getInt(KEY_EFFECT, DEFAULT_EFFECT), 0, EFFECT_NAMES.length - 1);
        duration = clamp(p.getInt(KEY_DURATION, DEFAULT_DURATION), 120, 1500);
        strength = clamp(p.getInt(KEY_STRENGTH, DEFAULT_STRENGTH), 10, 250);
        easing = clamp(p.getInt(KEY_EASING, DEFAULT_EASING), 0, EASING_NAMES.length - 1);
        color = clamp(p.getInt(KEY_COLOR, DEFAULT_COLOR), 0, COLORS.length - 1);
        cascade = p.getBoolean(KEY_CASCADE, DEFAULT_CASCADE);
        cascadeDelay = clamp(p.getInt(KEY_CASCADE_DELAY, DEFAULT_CASCADE_DELAY), 0, 120);
        loaded = true;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static boolean isEnabled() {
        if (!MglaFeatureFlags.isAllowed(KEY_ENABLED)) {
            return false;
        }
        ensureLoaded();
        return enabled;
    }

    public static int getEffect() {
        ensureLoaded();
        return effect;
    }

    public static int getDuration() {
        ensureLoaded();
        return duration;
    }

    /** Effect strength in percent (100 = default). */
    public static int getStrength() {
        ensureLoaded();
        return strength;
    }

    public static int getEasing() {
        ensureLoaded();
        return easing;
    }

    public static int getColorIndex() {
        ensureLoaded();
        return color;
    }

    public static int getAccentColor() {
        return resolveColor(getColorIndex());
    }

    public static int resolveColor(int index) {
        int c = index > 0 && index < COLORS.length ? COLORS[index] : 0;
        return c != 0 ? c : Theme.getColor(Theme.key_featuredStickers_addButton);
    }

    public static boolean isCascadeEnabled() {
        ensureLoaded();
        return cascade;
    }

    public static int getCascadeDelay() {
        ensureLoaded();
        return cascadeDelay;
    }

    public static void setEnabled(boolean value) {
        if (value && !MglaFeatureFlags.isAllowed(KEY_ENABLED)) {
            value = false;
        }
        ensureLoaded();
        enabled = value;
        prefs().edit().putBoolean(KEY_ENABLED, value).apply();
    }

    public static void setEffect(int value) {
        ensureLoaded();
        effect = value;
        prefs().edit().putInt(KEY_EFFECT, value).apply();
    }

    public static void setDuration(int value) {
        ensureLoaded();
        duration = value;
        prefs().edit().putInt(KEY_DURATION, value).apply();
    }

    public static void setStrength(int value) {
        ensureLoaded();
        strength = value;
        prefs().edit().putInt(KEY_STRENGTH, value).apply();
    }

    public static void setEasing(int value) {
        ensureLoaded();
        easing = value;
        prefs().edit().putInt(KEY_EASING, value).apply();
    }

    public static void setColorIndex(int value) {
        ensureLoaded();
        color = value;
        prefs().edit().putInt(KEY_COLOR, value).apply();
    }

    public static void setCascadeEnabled(boolean value) {
        ensureLoaded();
        cascade = value;
        prefs().edit().putBoolean(KEY_CASCADE, value).apply();
    }

    public static void setCascadeDelay(int value) {
        ensureLoaded();
        cascadeDelay = value;
        prefs().edit().putInt(KEY_CASCADE_DELAY, value).apply();
    }

    public static void reset() {
        prefs().edit()
            .remove(KEY_ENABLED).remove(KEY_EFFECT).remove(KEY_DURATION).remove(KEY_STRENGTH)
            .remove(KEY_EASING).remove(KEY_COLOR).remove(KEY_CASCADE).remove(KEY_CASCADE_DELAY)
            .apply();
        loaded = false;
    }

    public static String getSummary() {
        return isEnabled() ? EFFECT_NAMES[getEffect()] : "Выкл.";
    }
}
