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

    public static final int EFFECT_LIGHT = 0;
    public static final int EFFECT_MEDIUM = 1;
    public static final int EFFECT_STRONG = 2;
    public static final int EFFECT_TICK = 3;
    public static final int EFFECT_DOUBLE = 4;
    public static final int EFFECT_PULSE = 5;

    public static final String[] EFFECT_NAMES = {
        "Лёгкая", "Средняя", "Сильная", "Тик", "Двойная", "Пульс"
    };

    public static boolean isEnabled() {
        return ApplicationLoader.applicationContext
            .getSharedPreferences("mgla_config", Context.MODE_PRIVATE)
            .getBoolean("haptic_enabled", false);
    }

    public static int getEffect() {
        return ApplicationLoader.applicationContext
            .getSharedPreferences("mgla_config", Context.MODE_PRIVATE)
            .getInt("haptic_effect", EFFECT_LIGHT);
    }

    public static void vibrate() {
        if (!isEnabled()) return;
        try {
            Vibrator vibrator = (Vibrator) ApplicationLoader.applicationContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;

            int effect = getEffect();
            if (Build.VERSION.SDK_INT >= 29) {
                int predefined;
                switch (effect) {
                    case EFFECT_LIGHT:  predefined = VibrationEffect.EFFECT_TICK; break;
                    case EFFECT_MEDIUM: predefined = VibrationEffect.EFFECT_CLICK; break;
                    case EFFECT_STRONG: predefined = VibrationEffect.EFFECT_HEAVY_CLICK; break;
                    case EFFECT_TICK:   predefined = VibrationEffect.EFFECT_TICK; break;
                    case EFFECT_DOUBLE: predefined = VibrationEffect.EFFECT_DOUBLE_CLICK; break;
                    case EFFECT_PULSE:  predefined = VibrationEffect.EFFECT_HEAVY_CLICK; break;
                    default:            predefined = VibrationEffect.EFFECT_CLICK; break;
                }
                vibrator.vibrate(VibrationEffect.createPredefined(predefined));
                return;
            }
            // Fallback for older APIs
            switch (effect) {
                case EFFECT_STRONG: vibrator.vibrate(40); break;
                case EFFECT_TICK: vibrator.vibrate(10); break;
                case EFFECT_DOUBLE: vibrator.vibrate(new long[]{0, 10, 30, 10}, -1); break;
                case EFFECT_PULSE: vibrator.vibrate(new long[]{0, 30, 100, 30}, -1); break;
                case EFFECT_LIGHT: vibrator.vibrate(15); break;
                default: vibrator.vibrate(25); break;
            }
        } catch (Exception e) {
            // ignore
        }
    }

    public static void performHaptic(View view) {
        if (!isEnabled()) return;
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }
}
