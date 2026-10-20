package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * Динамичное переливающееся свечение строго вокруг овала поля ввода.
 */
public class MglaWhisperDictationGlow {

    private final Paint bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private SweepGradient sweep;
    private int[] sweepColors;
    private float animatedAlpha;
    private boolean active;
    private long startMs;
    private long lastFrameMs;

    public MglaWhisperDictationGlow() {
        bloomPaint.setStyle(Paint.Style.STROKE);
        bloomPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        corePaint.setStyle(Paint.Style.STROKE);
        corePaint.setStrokeCap(Paint.Cap.ROUND);
        sparkPaint.setStyle(Paint.Style.STROKE);
        sparkPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setActive(boolean active) {
        if (active && !this.active) {
            startMs = SystemClock.elapsedRealtime();
        }
        this.active = active;
        if (!active) {
            lastFrameMs = 0;
        }
    }

    public boolean isActive() {
        return active || animatedAlpha > 0.01f;
    }

    public void draw(Canvas canvas, View host, View field, Theme.ResourcesProvider resourcesProvider) {
        if (host == null || field == null || field.getVisibility() != View.VISIBLE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastFrameMs == 0) {
            lastFrameMs = now;
            if (startMs == 0) {
                startMs = now;
            }
        }
        float dt = Math.min(32f, now - lastFrameMs) / 1000f;
        lastFrameMs = now;
        float target = active ? 1f : 0f;
        animatedAlpha += (target - animatedAlpha) * Math.min(1f, dt * 10f);
        if (animatedAlpha < 0.01f && !active) {
            return;
        }

        float left = field.getLeft() + field.getTranslationX();
        float top = field.getTop() + field.getTranslationY();
        View p = (View) field.getParent();
        while (p != null && p != host) {
            left += p.getLeft() + p.getTranslationX();
            top += p.getTop() + p.getTranslationY();
            if (!(p.getParent() instanceof View)) {
                break;
            }
            p = (View) p.getParent();
        }
        // Плотно по овалу поля — без раздувания на всю панель
        float inset = dpf2(0.5f);
        rect.set(left + inset, top + inset, left + field.getWidth() - inset, top + field.getHeight() - inset);
        float radius = Math.min(rect.height() / 2f, dpf2(20));
        float cx = rect.centerX();
        float cy = rect.centerY();

        int accent = Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider);
        int accent2 = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        int voice = Theme.getColor(Theme.key_chat_messagePanelVoiceBackground, resourcesProvider);
        ensureSweep(cx, cy, accent, accent2, voice);

        float t = (now - startMs) / 1000f;
        float angle = (t * 140f) % 360f;
        float pulse = 0.82f + 0.18f * (float) Math.sin(t * Math.PI * 2.2);
        float breath = 0.9f + 0.1f * (float) Math.sin(t * Math.PI * 1.3);

        matrix.reset();
        matrix.setRotate(angle, cx, cy);
        sweep.setLocalMatrix(matrix);

        bloomPaint.setShader(sweep);
        glowPaint.setShader(sweep);
        corePaint.setShader(sweep);
        sparkPaint.setShader(sweep);

        int base = (int) (animatedAlpha * 255 * pulse);
        bloomPaint.setStrokeWidth(dpf2(10f) * breath);
        bloomPaint.setAlpha((int) (base * 0.18f));
        glowPaint.setStrokeWidth(dpf2(5.5f) * breath);
        glowPaint.setAlpha((int) (base * 0.40f));
        corePaint.setStrokeWidth(dpf2(2.2f));
        corePaint.setAlpha((int) (base * 0.92f));
        sparkPaint.setStrokeWidth(dpf2(1.15f));
        sparkPaint.setAlpha(Math.min(255, (int) (base * 1.05f)));

        canvas.drawRoundRect(rect, radius, radius, bloomPaint);
        canvas.drawRoundRect(rect, radius, radius, glowPaint);
        canvas.drawRoundRect(rect, radius, radius, corePaint);
        canvas.drawRoundRect(rect, radius, radius, sparkPaint);

        host.invalidate();
    }

    private float sweepCx = Float.NaN;
    private float sweepCy = Float.NaN;

    private void ensureSweep(float cx, float cy, int c1, int c2, int c3) {
        int a1 = ColorUtils.setAlphaComponent(c1, 255);
        int a2 = ColorUtils.setAlphaComponent(c2, 255);
        int a3 = ColorUtils.setAlphaComponent(c3, 255);
        int white = 0xFFFFFFFF;
        int[] colors = new int[]{
            a1, white, a2, a3, white, a1, a2, a1
        };
        boolean centerChanged = sweep == null
            || Float.isNaN(sweepCx)
            || Math.abs(sweepCx - cx) > 0.5f
            || Math.abs(sweepCy - cy) > 0.5f;
        boolean colorsChanged = sweepColors == null || !colorsEqual(sweepColors, colors);
        if (centerChanged || colorsChanged) {
            sweepColors = colors;
            sweepCx = cx;
            sweepCy = cy;
            sweep = new SweepGradient(cx, cy, colors, new float[]{
                0f, 0.12f, 0.28f, 0.45f, 0.58f, 0.72f, 0.88f, 1f
            });
            bloomPaint.setShader(sweep);
            glowPaint.setShader(sweep);
            corePaint.setShader(sweep);
            sparkPaint.setShader(sweep);
        }
    }

    private static boolean colorsEqual(int[] a, int[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
