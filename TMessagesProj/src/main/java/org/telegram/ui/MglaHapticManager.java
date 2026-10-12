package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;

import java.util.Locale;

public class MglaHapticManager {

    public static final int STRENGTH_LIGHT = 0;
    public static final int STRENGTH_MEDIUM = 1;
    public static final int STRENGTH_STRONG = 2;

    public static final String[] STRENGTH_NAMES = {
        "Слабая", "Средняя", "Сильная"
    };

    // User-selectable effects
    public static final int PATTERN_SOFT = 0;
    public static final int PATTERN_CLICK = 1;
    public static final int PATTERN_HEAVY = 2;
    public static final int PATTERN_DOUBLE = 3;
    public static final int PATTERN_TRIPLE = 4;
    public static final int PATTERN_PULSE = 5;
    public static final int PATTERN_SHARP = 6;
    public static final int PATTERN_SUCCESS = 7;
    public static final int PATTERN_WARNING = 8;
    public static final int PATTERN_TICK = 9;

    public static final String[] PATTERN_NAMES = {
        "Мягкий", "Клик", "Сильный", "Двойной", "Тройной",
        "Пульс", "Резкий", "Успех", "Предупреждение", "Тик"
    };

    // Legacy aliases kept for older prefs / call sites
    public static final int PATTERN_SHORT = PATTERN_CLICK;
    public static final int PATTERN_LONG = PATTERN_HEAVY;

    /**
     * Amplitude-capable timings (ms). Even indices = pause, odd = vibrate.
     * First entry is initial delay.
     */
    private static final long[][] TIMINGS = {
        {0, 12},                                      // SOFT
        {0, 18},                                      // CLICK
        {0, 32},                                      // HEAVY
        {0, 16, 36, 16},                              // DOUBLE
        {0, 14, 28, 14, 28, 14},                      // TRIPLE
        {0, 18, 40, 70},                              // PULSE
        {0, 8},                                       // SHARP
        {0, 14, 55, 22},                              // SUCCESS
        {0, 18, 40, 18, 40, 28},                      // WARNING
        {0, 6}                                        // TICK
    };

    private static final int[][] AMPLITUDES = {
        {0, 90},                                      // SOFT
        {0, 160},                                     // CLICK
        {0, 255},                                     // HEAVY
        {0, 170, 0, 170},                             // DOUBLE
        {0, 150, 0, 150, 0, 150},                     // TRIPLE
        {0, 140, 0, 220},                             // PULSE
        {0, 220},                                     // SHARP
        {0, 150, 0, 255},                             // SUCCESS
        {0, 200, 0, 160, 0, 255},                     // WARNING
        {0, 70}                                       // TICK
    };

    /**
     * Longer fallbacks for devices without amplitude control (many Xiaomi /
     * MediaTek motors ignore 6–15 ms pulses and need ≥25–40 ms to feel).
     */
    private static final long[][] FALLBACK_TIMINGS = {
        {0, 35},                                      // SOFT
        {0, 45},                                      // CLICK
        {0, 75},                                      // HEAVY
        {0, 40, 45, 40},                              // DOUBLE
        {0, 35, 40, 35, 40, 35},                      // TRIPLE
        {0, 40, 50, 90},                              // PULSE
        {0, 30},                                      // SHARP
        {0, 40, 55, 55},                              // SUCCESS
        {0, 45, 50, 45, 50, 55},                      // WARNING
        {0, 25}                                       // TICK
    };

    public static final int ACTION_CLICK = 0;
    public static final int ACTION_SEND = 1;
    public static final int ACTION_LONG_PRESS = 2;
    public static final int ACTION_MENU = 3;
    public static final int ACTION_BACK = 4;
    public static final int ACTION_DEFAULT = 5;

    public static final String[] ACTION_NAMES = {
        "Клик", "Отправка", "Долгое нажатие", "Меню", "Назад", "Прочее"
    };

    public static final String[] ACTION_KEYS = {
        "haptic_click", "haptic_send", "haptic_long_press", "haptic_menu", "haptic_back", "haptic_default"
    };

    /** Actions shown in the per-action customization list (excludes DEFAULT / «Прочее»). */
    public static final int[] CUSTOMIZABLE_ACTIONS = {
        ACTION_CLICK, ACTION_SEND, ACTION_LONG_PRESS, ACTION_MENU, ACTION_BACK
    };

    private static final String PREFS = "mgla_config";
    private static final String KEY_ENABLED = "haptic_enabled";
    private static final String KEY_STRENGTH = "haptic_strength";
    private static final String KEY_PATTERN = "haptic_pattern";

    private static Vibrator cachedVibrator;
    private static Boolean amplitudeControl;
    private static Boolean xiaomiDevice;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        if (!org.telegram.messenger.MglaFeatureFlags.isAllowed(KEY_ENABLED)) {
            return false;
        }
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        if (enabled && !org.telegram.messenger.MglaFeatureFlags.isAllowed(KEY_ENABLED)) {
            enabled = false;
        }
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getStrength() {
        return normalizeStrength(prefs().getInt(KEY_STRENGTH, STRENGTH_MEDIUM));
    }

    public static void setStrength(int strength) {
        prefs().edit().putInt(KEY_STRENGTH, normalizeStrength(strength)).apply();
    }

    public static int getPattern() {
        SharedPreferences p = prefs();
        int raw = p.getInt(KEY_PATTERN, PATTERN_CLICK);
        if (!p.getBoolean("haptic_pattern_v2", false)) {
            int migrated = migrateLegacyPattern(raw);
            p.edit().putInt(KEY_PATTERN, migrated).putBoolean("haptic_pattern_v2", true).apply();
            return migrated;
        }
        return normalizePattern(raw);
    }

    public static void setPattern(int pattern) {
        prefs().edit()
                .putInt(KEY_PATTERN, normalizePattern(pattern))
                .putBoolean("haptic_pattern_v2", true)
                .apply();
    }

    public static boolean hasCustomPatternForAction(int action) {
        if (action < 0 || action >= ACTION_KEYS.length) {
            return false;
        }
        return prefs().contains(ACTION_KEYS[action]) && prefs().getInt(ACTION_KEYS[action], -1) >= 0;
    }

    public static int getPatternForAction(int action) {
        SharedPreferences p = prefs();
        if (action < 0 || action >= ACTION_KEYS.length) {
            action = ACTION_DEFAULT;
        }
        int pattern = p.getInt(ACTION_KEYS[action], -1);
        if (pattern >= 0) {
            if (!p.getBoolean("haptic_pattern_v2", false)) {
                pattern = migrateLegacyPattern(pattern);
            }
            if (pattern < PATTERN_NAMES.length) {
                return pattern;
            }
        }
        return getPattern();
    }

    /** @param pattern use {@code -1} to clear override and fall back to the default effect */
    public static void setPatternForAction(int action, int pattern) {
        if (action < 0 || action >= ACTION_KEYS.length) {
            return;
        }
        SharedPreferences.Editor editor = prefs().edit();
        if (pattern < 0) {
            editor.remove(ACTION_KEYS[action]);
        } else {
            editor.putInt(ACTION_KEYS[action], normalizePattern(pattern));
            editor.putBoolean("haptic_pattern_v2", true);
        }
        editor.apply();
    }

    public static String getPatternDisplayNameForAction(int action) {
        if (!hasCustomPatternForAction(action)) {
            return "Как по умолчанию";
        }
        return PATTERN_NAMES[getPatternForAction(action)];
    }

    public static String getSummary() {
        if (!isEnabled()) {
            return "Выкл.";
        }
        return PATTERN_NAMES[getPattern()] + " · " + STRENGTH_NAMES[getStrength()];
    }

    /** Old 0..5 map → new effect indices. */
    private static int migrateLegacyPattern(int old) {
        switch (old) {
            case 0:
                return PATTERN_CLICK;
            case 1:
                return PATTERN_HEAVY;
            case 2:
                return PATTERN_DOUBLE;
            case 3:
                return PATTERN_TRIPLE;
            case 4:
                return PATTERN_PULSE;
            case 5:
                return PATTERN_TICK;
            default:
                return PATTERN_CLICK;
        }
    }

    public static void vibrate() {
        vibrate(ACTION_DEFAULT);
    }

    @SuppressWarnings("MissingPermission")
    public static void vibrate(int action) {
        vibratePattern(getPatternForAction(action), false);
    }

    @SuppressWarnings("MissingPermission")
    public static void previewPattern(int pattern) {
        vibratePattern(pattern, true);
    }

    @SuppressWarnings("MissingPermission")
    private static void vibratePattern(int pattern, boolean force) {
        if (!force && !isEnabled()) {
            return;
        }
        try {
            Vibrator vibrator = getVibrator();
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }

            pattern = normalizePattern(pattern);
            int strength = getStrength();

            try {
                vibrator.cancel();
            } catch (Throwable ignore) {
            }

            if (Build.VERSION.SDK_INT >= 26) {
                VibrationEffect effect = createEffect(vibrator, pattern, strength);
                if (effect == null) {
                    return;
                }
                vibrateWithEffect(vibrator, effect);
            } else {
                // Pre-Oreo: duration-only patterns; baseline fallbacks work on most devices.
                vibrator.vibrate(scaleTimings(FALLBACK_TIMINGS[pattern], strength, false), -1);
            }
        } catch (Throwable e) {
            // OEM vibrator services can throw; never crash the UI.
        }
    }

    @SuppressWarnings("MissingPermission")
    private static void vibrateWithEffect(Vibrator vibrator, VibrationEffect effect) {
        if (Build.VERSION.SDK_INT >= 33) {
            VibrationAttributes attrs = new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build();
            vibrator.vibrate(effect, attrs);
        } else {
            vibrator.vibrate(effect);
        }
    }

    private static VibrationEffect createEffect(Vibrator vibrator, int pattern, int strength) {
        if (Build.VERSION.SDK_INT < 26) {
            return null;
        }
        final boolean xiaomi = isXiaomiDevice();
        final boolean useAmplitude = hasAmplitudeControl(vibrator);

        if (useAmplitude) {
            // Baseline path for Pixel / Samsung / etc. Xiaomi gets longer pulses + louder amp.
            long[] timings = scaleTimings(TIMINGS[pattern], strength, xiaomi);
            int[] amplitudes = scaleAmplitudes(AMPLITUDES[pattern], strength, xiaomi);
            return VibrationEffect.createWaveform(timings, amplitudes, -1);
        }

        // No amplitude control: longer baseline pulses so the effect is still felt.
        // Xiaomi motors are especially weak without amplitude — extra stretch.
        long[] timings = scaleTimings(FALLBACK_TIMINGS[pattern], strength, xiaomi);
        return VibrationEffect.createWaveform(timings, -1);
    }

    /**
     * @param xiaomiBoost extra duration stretch + higher min pulse for Xiaomi/Redmi/POCO only.
     *                    Other devices keep the shared baseline timings.
     */
    private static long[] scaleTimings(long[] source, int strength, boolean xiaomiBoost) {
        float strengthScale = getStrengthDurationScale(strength);
        float deviceScale = xiaomiBoost ? 1.55f : 1f;
        long minPulse = xiaomiBoost ? 28 : 10;
        long[] out = new long[source.length];
        for (int i = 0; i < source.length; i++) {
            if (i % 2 == 0) {
                // pauses: slight stretch on Xiaomi so multi-tap patterns stay distinct
                out[i] = source[i] == 0 ? 0 : Math.max(1, Math.round(source[i] * (xiaomiBoost ? 1.2f : 1f)));
            } else {
                out[i] = Math.max(minPulse, Math.round(source[i] * strengthScale * deviceScale));
            }
        }
        return out;
    }

    private static int[] scaleAmplitudes(int[] source, int strength, boolean xiaomiBoost) {
        float scale = getStrengthAmplitudeScale(strength) * (xiaomiBoost ? 1.25f : 1f);
        int[] out = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            if (source[i] <= 0) {
                out[i] = 0;
            } else {
                int value = Math.round(source[i] * scale);
                // Xiaomi often reports amplitude control but feels muted below ~120.
                if (xiaomiBoost) {
                    value = Math.max(120, value);
                }
                out[i] = Math.max(1, Math.min(255, value));
            }
        }
        return out;
    }

    private static float getStrengthDurationScale(int strength) {
        switch (strength) {
            case STRENGTH_LIGHT:
                return 0.85f;
            case STRENGTH_STRONG:
                return 1.3f;
            case STRENGTH_MEDIUM:
            default:
                return 1.0f;
        }
    }

    private static float getStrengthAmplitudeScale(int strength) {
        switch (strength) {
            case STRENGTH_LIGHT:
                return 0.75f;
            case STRENGTH_STRONG:
                return 1.15f;
            case STRENGTH_MEDIUM:
            default:
                return 1.0f;
        }
    }

    /** Xiaomi / Redmi / POCO / Black Shark — majority of users, weak default haptics. */
    private static boolean isXiaomiDevice() {
        if (xiaomiDevice != null) {
            return xiaomiDevice;
        }
        String m = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase(Locale.US) : "";
        String b = Build.BRAND != null ? Build.BRAND.toLowerCase(Locale.US) : "";
        xiaomiDevice = m.contains("xiaomi") || b.contains("xiaomi")
                || m.contains("redmi") || b.contains("redmi")
                || m.contains("poco") || b.contains("poco")
                || m.contains("blackshark") || b.contains("blackshark");
        return xiaomiDevice;
    }

    private static boolean hasAmplitudeControl(Vibrator vibrator) {
        if (amplitudeControl != null) {
            return amplitudeControl;
        }
        try {
            amplitudeControl = Build.VERSION.SDK_INT >= 26 && vibrator != null && vibrator.hasAmplitudeControl();
        } catch (Throwable e) {
            amplitudeControl = false;
        }
        return amplitudeControl;
    }

    @SuppressWarnings("deprecation")
    private static Vibrator getVibrator() {
        if (cachedVibrator != null) {
            return cachedVibrator;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) {
                    cachedVibrator = manager.getDefaultVibrator();
                }
            }
            if (cachedVibrator == null) {
                cachedVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Throwable e) {
            try {
                cachedVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            } catch (Throwable ignore) {
                return null;
            }
        }
        return cachedVibrator;
    }

    private static int normalizePattern(int pattern) {
        if (pattern < 0 || pattern >= PATTERN_NAMES.length) {
            return PATTERN_CLICK;
        }
        return pattern;
    }

    private static int normalizeStrength(int strength) {
        return strength >= 0 && strength < STRENGTH_NAMES.length ? strength : STRENGTH_MEDIUM;
    }

    public static void performHaptic(View view) {
        if (!isEnabled()) {
            return;
        }
        // Always drive the motor ourselves so strength/effect settings apply everywhere.
        vibrate(ACTION_CLICK);
        // On non-Xiaomi, also tap the system haptic layer for devices with good linear resonators.
        if (view != null && !isXiaomiDevice()) {
            try {
                view.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                );
            } catch (Throwable ignore) {
            }
        }
    }
}
