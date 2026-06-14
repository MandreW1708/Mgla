package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;

public class MglaHapticManager {

    // Уровни силы (длительность в мс)
    public static final int STRENGTH_LIGHT = 0;
    public static final int STRENGTH_MEDIUM = 1;
    public static final int STRENGTH_STRONG = 2;

    public static final String[] STRENGTH_NAMES = {
        "Слабая", "Средняя", "Сильная"
    };

    public static final int[] STRENGTH_DURATIONS = {
        30, 50, 80
    };

    // Паттерны эффектов (ритм вибрации)
    public static final int PATTERN_SHORT = 0;      // один короткий импульс
    public static final int PATTERN_LONG = 1;       // один длинный импульс
    public static final int PATTERN_DOUBLE = 2;     // два коротких
    public static final int PATTERN_TRIPLE = 3;     // три коротких
    public static final int PATTERN_PULSE = 4;      // пульс (короткий-пауза-длинный)
    public static final int PATTERN_TICK = 5;       // очень короткий тик

    public static final String[] PATTERN_NAMES = {
        "Короткий", "Длинный", "Двойной", "Тройной", "Пульс", "Тик"
    };

    // Паттерны VibrationEffect: {задержка, вибрация, пауза, вибрация, ...}
    public static final long[][] PATTERNS = {
        {0, 30},                          // PATTERN_SHORT
        {0, 100},                         // PATTERN_LONG
        {0, 24, 34, 24},                  // PATTERN_DOUBLE
        {0, 20, 30, 20, 30, 20},          // PATTERN_TRIPLE
        {0, 22, 42, 80},                  // PATTERN_PULSE
        {0, 10}                           // PATTERN_TICK
    };

    // Типы действий
    public static final int ACTION_CLICK = 0;
    public static final int ACTION_SEND = 1;
    public static final int ACTION_LONG_PRESS = 2;
    public static final int ACTION_MENU = 3;
    public static final int ACTION_BACK = 4;
    public static final int ACTION_DEFAULT = 5;

    public static final String[] ACTION_NAMES = {
        "Клик", "Отправка", "Долгое нажатие", "Меню", "Назад", "По умолчанию"
    };

    public static final String[] ACTION_KEYS = {
        "haptic_click", "haptic_send", "haptic_long_press", "haptic_menu", "haptic_back", "haptic_default"
    };

    public static boolean isEnabled() {
        return ApplicationLoader.applicationContext
            .getSharedPreferences("mgla_config", Context.MODE_PRIVATE)
            .getBoolean("haptic_enabled", false);
    }

    public static int getStrength() {
        int strength = ApplicationLoader.applicationContext
            .getSharedPreferences("mgla_config", Context.MODE_PRIVATE)
            .getInt("haptic_strength", STRENGTH_MEDIUM);
        return normalizeStrength(strength);
    }

    public static int getPatternForAction(int action) {
        SharedPreferences prefs = ApplicationLoader.applicationContext
            .getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
        if (action < 0 || action >= ACTION_KEYS.length) {
            action = ACTION_DEFAULT;
        }
        int pattern = prefs.getInt(ACTION_KEYS[action], -1);
        if (pattern >= 0 && pattern < PATTERN_NAMES.length) {
            return pattern;
        }
        pattern = prefs.getInt("haptic_pattern", PATTERN_SHORT);
        return pattern >= 0 && pattern < PATTERN_NAMES.length ? pattern : PATTERN_SHORT;
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
        if (!force && !isEnabled()) return;
        try {
            Vibrator vibrator = (Vibrator) ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null) return;

            pattern = normalizePattern(pattern);
            int strength = getStrength();

            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(createEffect(pattern, strength));
            } else {
                vibrator.vibrate(createLegacyPattern(pattern, strength), -1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static int normalizePattern(int pattern) {
        return pattern >= 0 && pattern < PATTERNS.length ? pattern : PATTERN_SHORT;
    }

    private static int normalizeStrength(int strength) {
        return strength >= 0 && strength < STRENGTH_NAMES.length ? strength : STRENGTH_MEDIUM;
    }

    private static VibrationEffect createEffect(int pattern, int strength) {
        if (Build.VERSION.SDK_INT >= 26) {
            return VibrationEffect.createWaveform(createWaveform(pattern, strength), createAmplitudes(pattern, strength), -1);
        }
        return null;
    }

    private static long[] createWaveform(int pattern, int strength) {
        long[] source = PATTERNS[pattern];
        long[] waveform = new long[source.length];
        float strengthScale = getStrengthScale(strength);
        for (int i = 0; i < source.length; i++) {
            if (i == 0 || i % 2 == 0) {
                waveform[i] = source[i];
            } else {
                waveform[i] = Math.max(8, Math.round(source[i] * strengthScale));
            }
        }
        return waveform;
    }

    private static int[] createAmplitudes(int pattern, int strength) {
        long[] source = PATTERNS[pattern];
        int[] amplitudes = new int[source.length];
        int amplitude = getAmplitude(strength);
        for (int i = 0; i < amplitudes.length; i++) {
            amplitudes[i] = i == 0 || i % 2 == 0 ? 0 : amplitude;
        }
        return amplitudes;
    }

    private static long[] createLegacyPattern(int pattern, int strength) {
        return createWaveform(pattern, strength);
    }

    private static float getStrengthScale(int strength) {
        switch (strength) {
            case STRENGTH_LIGHT:
                return 0.75f;
            case STRENGTH_STRONG:
                return 1.25f;
            case STRENGTH_MEDIUM:
            default:
                return 1.0f;
        }
    }

    private static int getAmplitude(int strength) {
        switch (strength) {
            case STRENGTH_LIGHT:
                return 90;
            case STRENGTH_STRONG:
                return 255;
            case STRENGTH_MEDIUM:
            default:
                return 180;
        }
    }

    public static void performHaptic(View view) {
        if (!isEnabled()) return;
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }
}
