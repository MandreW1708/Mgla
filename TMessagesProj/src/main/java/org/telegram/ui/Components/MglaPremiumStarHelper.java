package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.MglaPremiumController;
import org.telegram.ui.Components.Premium.StarParticlesView;

/**
 * Цветные star-particles для Mgla Premium (синий / красный / золотой).
 */
public final class MglaPremiumStarHelper {

    private MglaPremiumStarHelper() {
    }

    public static Paint[] createPaints() {
        Paint[] paints = new Paint[20];
        for (int i = 0; i < paints.length; i++) {
            paints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        return paints;
    }

    public static void tintPaints(Paint[] paints, int plan) {
        if (paints == null || paints.length == 0) {
            return;
        }
        if (plan < MglaPremiumController.PLAN_PREMIUM) {
            plan = MglaPremiumController.PLAN_ULTRA;
        }
        int light = MglaPremiumController.colorLight(plan);
        int mid = MglaPremiumController.colorMid(plan);
        int deep = MglaPremiumController.colorDeep(plan);
        for (int i = 0; i < paints.length; i++) {
            float t = i / (float) Math.max(1, paints.length - 1);
            int c = t < 0.5f
                    ? ColorUtils.blendARGB(deep, mid, t * 2f)
                    : ColorUtils.blendARGB(mid, light, (t - 0.5f) * 2f);
            paints[i].setColorFilter(new PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN));
        }
    }

    public static StarParticlesView create(Context context, int count, int plan, Paint[] paints) {
        tintPaints(paints, plan);
        return new StarParticlesView(context, count) {
            @Override
            protected void configure() {
                drawable.type = 100;
                drawable.roundEffect = true;
                drawable.useRotate = true;
                drawable.useBlur = false;
                drawable.useGradient = false;
                drawable.forceMaxAlpha = true;
                drawable.checkBounds = true;
                drawable.isCircle = true;
                drawable.useScale = true;
                drawable.startFromCenter = true;
                drawable.size1 = 10;
                drawable.size2 = 12;
                drawable.size3 = 14;
                drawable.k1 = drawable.k2 = drawable.k3 = 0.98f;
                drawable.getPaint = i -> paints[i % paints.length];
                drawable.init();
            }

            @Override
            protected int getStarsRectWidth() {
                return getMeasuredWidth();
            }
        };
    }

    public static void applyPlan(StarParticlesView view, Paint[] paints, int plan) {
        if (view == null || paints == null) {
            return;
        }
        tintPaints(paints, plan);
        view.invalidate();
    }
}
