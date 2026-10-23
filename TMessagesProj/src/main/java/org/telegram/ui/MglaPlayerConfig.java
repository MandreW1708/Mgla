package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Appearance and animation preferences for {@link MglaMusicPlayerSheet}.
 */
public final class MglaPlayerConfig {

    private static final String PREFS = MglaGlassConfig.PREFS;

    public static final String PREF_LARGE_COVER = "player_large_cover";
    public static final String PREF_DYNAMIC_COLORS = "player_dynamic_colors";
    public static final String PREF_ANIM_OPEN = "player_anim_open";
    public static final String PREF_ANIM_TRACK_CHANGE = "player_anim_track_change";
    public static final String PREF_ANIM_COVER_PULSE = "player_anim_cover_pulse";
    public static final String PREF_ANIM_BUTTONS = "player_anim_buttons";
    public static final String PREF_ANIM_AMBIENT = "player_anim_ambient";
    public static final String PREF_ANIM_SEEKBAR = "player_anim_seekbar";
    public static final String PREF_ANIM_SEEKBAR_WAVE = "player_anim_seekbar_wave";
    public static final String PREF_ANIM_SPEED = "player_anim_speed";

    public static final int SPEED_SLOW = 0;
    public static final int SPEED_NORMAL = 1;
    public static final int SPEED_FAST = 2;

    private MglaPlayerConfig() {
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isLargeCoverEnabled() {
        return getPrefs().getBoolean(PREF_LARGE_COVER, false);
    }

    public static void setLargeCoverEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_LARGE_COVER, enabled).apply();
    }

    public static boolean isDynamicColorsEnabled() {
        return getPrefs().getBoolean(PREF_DYNAMIC_COLORS, true);
    }

    public static void setDynamicColorsEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_DYNAMIC_COLORS, enabled).apply();
    }

    public static boolean isOpenAnimationEnabled() {
        return getPrefs().getBoolean(PREF_ANIM_OPEN, true);
    }

    public static void setOpenAnimationEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_ANIM_OPEN, enabled).apply();
    }

    public static boolean isTrackChangeAnimationEnabled() {
        return getPrefs().getBoolean(PREF_ANIM_TRACK_CHANGE, true);
    }

    public static void setTrackChangeAnimationEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_ANIM_TRACK_CHANGE, enabled).apply();
    }

    public static boolean isCoverPulseEnabled() {
        return getPrefs().getBoolean(PREF_ANIM_COVER_PULSE, true);
    }

    public static void setCoverPulseEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_ANIM_COVER_PULSE, enabled).apply();
    }

    public static boolean isButtonAnimationsEnabled() {
        return getPrefs().getBoolean(PREF_ANIM_BUTTONS, true);
    }

    public static void setButtonAnimationsEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_ANIM_BUTTONS, enabled).apply();
    }

    public static boolean isAmbientMotionEnabled() {
        return getPrefs().getBoolean(PREF_ANIM_AMBIENT, true);
    }

    public static void setAmbientMotionEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_ANIM_AMBIENT, enabled).apply();
    }

    public static boolean isSeekbarAnimationsEnabled() {
        return getPrefs().getBoolean(PREF_ANIM_SEEKBAR, true);
    }

    public static void setSeekbarAnimationsEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_ANIM_SEEKBAR, enabled).apply();
    }

    /** MD3 wavy played segment on the player timeline. */
    public static boolean isSeekbarWaveEnabled() {
        return getPrefs().getBoolean(PREF_ANIM_SEEKBAR_WAVE, true);
    }

    public static void setSeekbarWaveEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_ANIM_SEEKBAR_WAVE, enabled).apply();
    }

    public static int getAnimationSpeed() {
        return getPrefs().getInt(PREF_ANIM_SPEED, SPEED_NORMAL);
    }

    public static void setAnimationSpeed(int speed) {
        if (speed < SPEED_SLOW || speed > SPEED_FAST) {
            speed = SPEED_NORMAL;
        }
        getPrefs().edit().putInt(PREF_ANIM_SPEED, speed).apply();
    }

    public static String getAnimationSpeedTitle(int speed) {
        switch (speed) {
            case SPEED_SLOW:
                return "Медленно";
            case SPEED_FAST:
                return "Быстро";
            default:
                return "Обычная";
        }
    }

    /** Multiplier applied to animation durations (higher = slower). */
    public static float getAnimationDurationScale() {
        switch (getAnimationSpeed()) {
            case SPEED_SLOW:
                return 1.45f;
            case SPEED_FAST:
                return 0.65f;
            default:
                return 1f;
        }
    }

    public static long scaleDuration(long baseMs) {
        return Math.max(40L, (long) (baseMs * getAnimationDurationScale()));
    }
}
