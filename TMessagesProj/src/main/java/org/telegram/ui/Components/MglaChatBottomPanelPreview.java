package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.MglaGlassConfig;

/**
 * Live mini preview of chat bottom input panel styles (Standard / Telegram).
 */
public class MglaChatBottomPanelPreview extends View {

    private final Paint wallpaperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private int style = MglaGlassConfig.BOTTOM_PANEL_STYLE_STANDARD;
    private float animProgress = 1f;
    private int fromStyle = style;
    private ValueAnimator animator;

    public MglaChatBottomPanelPreview(Context context) {
        super(context);
        setWillNotDraw(false);

        hintPaint.setTextSize(dpf2(13));
        glassStrokePaint.setStyle(Paint.Style.STROKE);
        glassStrokePaint.setStrokeWidth(dpf2(0.66f));
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setStrokeWidth(dpf2(1.8f));

        applyFromConfig();
    }

    public void applyFromConfig() {
        setStyle(MglaGlassConfig.getChatBottomPanelStyle(), false);
    }

    public void setStyleAnimated(int newStyle) {
        setStyle(newStyle, true);
    }

    public void setStyle(int newStyle, boolean animate) {
        if (animate && style != newStyle) {
            fromStyle = style;
            style = newStyle;
            if (animator != null) {
                animator.cancel();
            }
            animProgress = 0f;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(240);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.addUpdateListener(a -> {
                animProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            style = newStyle;
            fromStyle = newStyle;
            animProgress = 1f;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(96) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int pl = getPaddingLeft();
        final int pt = getPaddingTop();
        final int w = getWidth() - pl - getPaddingRight();
        final int h = dp(96);
        if (w <= 0 || h <= 0) {
            return;
        }

        canvas.save();
        canvas.translate(pl, pt);

        final boolean dark = Theme.isCurrentThemeDark();
        final int wallpaperTop = dark ? 0xFF2B2140 : 0xFFD9C8F0;
        final int wallpaperBottom = dark ? 0xFF1A1428 : 0xFFB8D4F0;
        wallpaperPaint.setShader(new LinearGradient(0, 0, w * 0.4f, h, wallpaperTop, wallpaperBottom, Shader.TileMode.CLAMP));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, dp(14), dp(14), wallpaperPaint);

        vignettePaint.setShader(new LinearGradient(0, h, 0, h * 0.35f,
                ColorUtils.setAlphaComponent(0xFF000000, dark ? 55 : 22),
                ColorUtils.setAlphaComponent(0xFF000000, 0),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, dp(14), dp(14), vignettePaint);

        final int glassFill = ColorUtils.setAlphaComponent(dark ? 0xFF241833 : 0xFFFFFFFF, dark ? 140 : 165);
        final int glassStroke = ColorUtils.setAlphaComponent(dark ? 0xFFFFFFFF : 0xFF000000, dark ? 32 : 20);
        final int iconColor = dark ? 0xFFF2EEF8 : 0xFF1B1B1F;
        final int hintColor = dark ? 0xFFB9AFC8 : 0xFF6B6575;
        final int accent = Theme.getColor(Theme.key_chat_messagePanelSend);

        glassPaint.setColor(glassFill);
        glassStrokePaint.setColor(glassStroke);
        iconPaint.setColor(iconColor);
        fillPaint.setColor(iconColor);
        hintPaint.setColor(hintColor);
        accentPaint.setColor(accent);

        final float pad = dp(10);
        final float pillH = dp(38);
        final float rowTop = h - pad - pillH;

        if (animProgress < 1f && fromStyle != style) {
            canvas.saveLayerAlpha(0, 0, w, h, Math.round(255 * (1f - animProgress)), Canvas.ALL_SAVE_FLAG);
            canvas.translate(0, (1f - animProgress) * dp(6));
            drawStyle(canvas, fromStyle, w, pad, rowTop, pillH);
            canvas.restore();

            canvas.saveLayerAlpha(0, 0, w, h, Math.round(255 * animProgress), Canvas.ALL_SAVE_FLAG);
            canvas.translate(0, (animProgress - 1f) * dp(6));
            drawStyle(canvas, style, w, pad, rowTop, pillH);
            canvas.restore();
        } else {
            drawStyle(canvas, style, w, pad, rowTop, pillH);
        }
        canvas.restore();
    }

    private void drawStyle(Canvas canvas, int drawStyle, int w, float pad, float rowTop, float pillH) {
        final boolean telegram = drawStyle == MglaGlassConfig.BOTTOM_PANEL_STYLE_TELEGRAM;
        final float gap = dp(4);
        final float circle = pillH;

        if (telegram) {
            // One wide field pill: emoji | hint.............. | attach | send
            rect.set(pad, rowTop, w - pad, rowTop + pillH);
            drawGlass(canvas, rect, pillH / 2f);
            drawEmoji(canvas, pad + dp(18), rowTop + pillH / 2f);
            hintPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Сообщение", pad + dp(38), rowTop + pillH / 2f + dp(4.5f), hintPaint);
            drawAttach(canvas, w - pad - dp(52), rowTop + pillH / 2f);
            drawSend(canvas, w - pad - dp(20), rowTop + pillH / 2f, true);
        } else {
            // Mgla Standard: attach circle | field pill | send circle
            rect.set(pad, rowTop, pad + circle, rowTop + pillH);
            drawGlass(canvas, rect, circle / 2f);
            drawAttach(canvas, rect.centerX(), rect.centerY());

            rect.set(pad + circle + gap, rowTop, w - pad - circle - gap, rowTop + pillH);
            drawGlass(canvas, rect, pillH / 2f);
            drawEmoji(canvas, rect.left + dp(18), rowTop + pillH / 2f);
            hintPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Сообщение", rect.left + dp(38), rowTop + pillH / 2f + dp(4.5f), hintPaint);

            rect.set(w - pad - circle, rowTop, w - pad, rowTop + pillH);
            drawGlass(canvas, rect, circle / 2f);
            // Accent disc behind send for the outside-button look
            canvas.drawCircle(rect.centerX(), rect.centerY(), dp(14), accentPaint);
            drawSend(canvas, rect.centerX(), rect.centerY(), false);
        }
    }

    private void drawGlass(Canvas canvas, RectF r, float radius) {
        path.reset();
        path.addRoundRect(r, radius, radius, Path.Direction.CW);
        canvas.drawPath(path, glassPaint);
        canvas.drawPath(path, glassStrokePaint);
    }

    private void drawEmoji(Canvas canvas, float cx, float cy) {
        canvas.drawCircle(cx, cy, dp(8), iconPaint);
        fillPaint.setColor(iconPaint.getColor());
        canvas.drawCircle(cx - dp(3), cy - dp(1.5f), dp(1.2f), fillPaint);
        canvas.drawCircle(cx + dp(3), cy - dp(1.5f), dp(1.2f), fillPaint);
        path.reset();
        path.moveTo(cx - dp(3.5f), cy + dp(2.5f));
        path.quadTo(cx, cy + dp(5.5f), cx + dp(3.5f), cy + dp(2.5f));
        canvas.drawPath(path, iconPaint);
    }

    private void drawAttach(Canvas canvas, float cx, float cy) {
        path.reset();
        path.moveTo(cx + dp(4), cy - dp(5));
        path.lineTo(cx - dp(2), cy + dp(4));
        path.quadTo(cx - dp(5), cy + dp(1), cx - dp(1), cy - dp(3));
        path.lineTo(cx + dp(3), cy + dp(2));
        canvas.drawPath(path, iconPaint);
    }

    private void drawSend(Canvas canvas, float cx, float cy, boolean filledAccent) {
        if (filledAccent) {
            canvas.drawCircle(cx, cy, dp(14), accentPaint);
        }
        final int prev = iconPaint.getColor();
        iconPaint.setColor(0xFFFFFFFF);
        path.reset();
        path.moveTo(cx - dp(5), cy + dp(4));
        path.lineTo(cx - dp(5), cy - dp(2));
        path.lineTo(cx + dp(6), cy);
        path.close();
        fillPaint.setColor(0xFFFFFFFF);
        canvas.drawPath(path, fillPaint);
        iconPaint.setColor(prev);
    }
}
