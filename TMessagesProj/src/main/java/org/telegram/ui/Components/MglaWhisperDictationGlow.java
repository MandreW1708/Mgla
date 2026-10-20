package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import org.telegram.ui.ActionBar.Theme;

/**
 * Переливающееся свечение вокруг овала поля ввода во время диктовки Whisper.
 */
public class MglaWhisperDictationGlow {

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint softPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private LinearGradient sweep;
    private float animatedAlpha;
    private boolean active;
    private long lastFrameMs;

    public MglaWhisperDictationGlow() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(2.5f));
        softPaint.setStyle(Paint.Style.STROKE);
        softPaint.setStrokeWidth(dp(6f));
    }

    public void setActive(boolean active) {
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
        }
        float dt = Math.min(32f, now - lastFrameMs) / 1000f;
        lastFrameMs = now;
        float target = active ? 1f : 0f;
        animatedAlpha += (target - animatedAlpha) * Math.min(1f, dt * 8f);
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
        float right = left + field.getWidth();
        float bottom = top + field.getHeight();
        float pad = dp(1.5f);
        rect.set(left - pad, top - pad, right + pad, bottom + pad);
        float radius = Math.min(rect.height() / 2f, dp(22));

        int c1 = Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider);
        int c2 = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        int c3 = Theme.getColor(Theme.key_chat_messagePanelVoiceBackground, resourcesProvider);
        if (sweep == null) {
            sweep = new LinearGradient(0, 0, dp(120), 0,
                new int[]{c1, c2, c3, c1},
                new float[]{0f, 0.33f, 0.66f, 1f},
                Shader.TileMode.MIRROR);
            strokePaint.setShader(sweep);
            softPaint.setShader(sweep);
        }
        float shift = (now % 1600) / 1600f * dp(240);
        matrix.reset();
        matrix.setTranslate(left + shift, top);
        sweep.setLocalMatrix(matrix);

        int a = (int) (animatedAlpha * 220);
        strokePaint.setAlpha(a);
        softPaint.setAlpha((int) (animatedAlpha * 70));
        canvas.drawRoundRect(rect, radius, radius, softPaint);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);

        host.invalidate();
    }
}
