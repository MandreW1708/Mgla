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
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.MglaGlassConfig;

/**
 * Live mini preview of the chat header styles (Standard / Clean / Telegram).
 * For Clean, glass backgrounds follow the «Убрать фон» toggles.
 */
public class MglaChatHeaderPreview extends View {

    private final Paint wallpaperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private int style = MglaGlassConfig.HEADER_STYLE_STANDARD;
    private boolean hideBackGlass = true;
    private boolean hideTitleGlass = true;
    private boolean hidePinnedGlass = true;
    private boolean hideTranslationGlass = true;

    private float animProgress = 1f;
    private int fromStyle = style;
    private ValueAnimator animator;

    public MglaChatHeaderPreview(Context context) {
        super(context);
        setWillNotDraw(false);

        titlePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTextSize(dpf2(13));
        subtitlePaint.setTextSize(dpf2(11));
        captionPaint.setTextSize(dpf2(10));
        captionPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        glassStrokePaint.setStyle(Paint.Style.STROKE);
        glassStrokePaint.setStrokeWidth(dpf2(0.66f));
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setStrokeWidth(dpf2(1.8f));

        applyFromConfig();
    }

    public void applyFromConfig() {
        setPreviewState(
                MglaGlassConfig.getChatHeaderStyle(),
                MglaGlassConfig.isCleanHeaderHideBackBtnEnabled(),
                MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled(),
                false
        );
    }

    public void setStyleAnimated(int newStyle) {
        setPreviewState(
                newStyle,
                MglaGlassConfig.isCleanHeaderHideBackBtnEnabled(),
                MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled(),
                true
        );
    }

    public void setPreviewState(int newStyle, boolean hideBack, boolean hideTitle, boolean hidePinned, boolean hideTranslation, boolean animate) {
        hideBackGlass = hideBack;
        hideTitleGlass = hideTitle;
        hidePinnedGlass = hidePinned;
        hideTranslationGlass = hideTranslation;

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

    private static final int PREVIEW_HEIGHT_DP = 148;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(PREVIEW_HEIGHT_DP) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int pl = getPaddingLeft();
        final int pt = getPaddingTop();
        final int w = getWidth() - pl - getPaddingRight();
        final int h = dp(PREVIEW_HEIGHT_DP);
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

        vignettePaint.setShader(new LinearGradient(0, 0, 0, h,
                ColorUtils.setAlphaComponent(0xFF000000, dark ? 48 : 20),
                ColorUtils.setAlphaComponent(0xFF000000, 0),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, dp(14), dp(14), vignettePaint);

        final int glassFill = ColorUtils.setAlphaComponent(dark ? 0xFF241833 : 0xFFFFFFFF, dark ? 140 : 165);
        final int glassStroke = ColorUtils.setAlphaComponent(dark ? 0xFFFFFFFF : 0xFF000000, dark ? 32 : 20);
        final int iconColor = dark ? 0xFFF2EEF8 : 0xFF1B1B1F;
        final int titleColor = dark ? 0xFFF7F4FB : 0xFF111114;
        final int subtitleColor = dark ? 0xFFB9AFC8 : 0xFF6B6575;

        glassPaint.setColor(glassFill);
        glassStrokePaint.setColor(glassStroke);
        iconPaint.setColor(iconColor);
        titlePaint.setColor(titleColor);
        subtitlePaint.setColor(subtitleColor);
        captionPaint.setColor(ColorUtils.setAlphaComponent(titleColor, 170));
        avatarPaint.setColor(0xFFFF8A3D);
        fillPaint.setColor(iconColor);

        final float pad = dp(10);
        final float rowTop = dp(14);
        final float pillH = dp(34);
        final float gap = dp(4);

        if (animProgress < 1f && fromStyle != style) {
            canvas.saveLayerAlpha(0, 0, w, h, Math.round(255 * (1f - animProgress)), Canvas.ALL_SAVE_FLAG);
            canvas.translate(0, (1f - animProgress) * dp(6));
            drawStyle(canvas, fromStyle, w, pad, rowTop, pillH, gap);
            canvas.restore();

            canvas.saveLayerAlpha(0, 0, w, h, Math.round(255 * animProgress), Canvas.ALL_SAVE_FLAG);
            canvas.translate(0, (animProgress - 1f) * dp(6));
            drawStyle(canvas, style, w, pad, rowTop, pillH, gap);
            canvas.restore();
        } else {
            drawStyle(canvas, style, w, pad, rowTop, pillH, gap);
        }
        canvas.restore();
    }

    private void drawStyle(Canvas canvas, int drawStyle, int w, float pad, float rowTop, float pillH, float gap) {
        final boolean clean = drawStyle == MglaGlassConfig.HEADER_STYLE_CLEAN;
        final boolean telegram = drawStyle == MglaGlassConfig.HEADER_STYLE_TELEGRAM;
        final boolean showBackGlass = !clean || !hideBackGlass;
        final boolean showTitleGlass = !clean || !hideTitleGlass;
        final boolean showPinnedGlass = !clean || !hidePinnedGlass;
        final boolean showTranslationGlass = !clean || !hideTranslationGlass;

        final float backSize = pillH;
        final float menuW = telegram ? dp(64) : pillH;
        final float backLeft = pad;
        final float backRight = backLeft + backSize;
        final float menuRight = w - pad;
        final float menuLeft = menuRight - menuW;

        rect.set(backLeft, rowTop, backRight, rowTop + pillH);
        if (showBackGlass) {
            drawGlass(canvas, rect, pillH / 2f);
        }
        drawBackArrow(canvas, rect.centerX(), rect.centerY());

        rect.set(backRight + gap, rowTop, menuLeft - gap, rowTop + pillH);
        if (showTitleGlass) {
            drawGlass(canvas, rect, pillH / 2f);
        }
        if (telegram) {
            drawTelegramTitle(canvas, rect);
        } else {
            drawStandardTitle(canvas, rect);
        }

        rect.set(menuLeft, rowTop, menuRight, rowTop + pillH);
        if (telegram) {
            drawGlass(canvas, rect, pillH / 2f);
            drawCallAndMore(canvas, rect);
        } else {
            // Standard / Clean: peer avatar in the right slot (not ⋮).
            drawRightAvatar(canvas, rect);
        }

        final float secondaryTop = rowTop + pillH + dp(10);
        final float pinnedH = dp(36);
        final float translationH = dp(22);

        rect.set(pad, secondaryTop, w - pad, secondaryTop + pinnedH);
        if (showPinnedGlass) {
            drawGlass(canvas, rect, dp(12));
        }
        captionPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Закреплённое сообщение", rect.left + dp(10), rect.centerY() + dp(3.5f), captionPaint);

        rect.set(pad, secondaryTop + pinnedH + dp(5), w - pad, secondaryTop + pinnedH + dp(5) + translationH);
        if (showTranslationGlass) {
            drawGlass(canvas, rect, dp(11));
        }
        canvas.drawText("Панель перевода", rect.left + dp(10), rect.centerY() + dp(3.5f), captionPaint);
    }

    private void drawRightAvatar(Canvas canvas, RectF slot) {
        final float size = Math.min(slot.width(), slot.height()) - dp(2);
        final float cx = slot.centerX();
        final float cy = slot.centerY();
        canvas.drawCircle(cx, cy, size / 2f, avatarPaint);

        final int saved = titlePaint.getColor();
        final float savedSize = titlePaint.getTextSize();
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setTextSize(dpf2(12));
        canvas.drawText("M", cx, cy + dp(4), titlePaint);
        titlePaint.setTextSize(savedSize);
        titlePaint.setColor(saved);
    }

    private void drawGlass(Canvas canvas, RectF r, float radius) {
        path.reset();
        path.addRoundRect(r, radius, radius, Path.Direction.CW);
        canvas.drawPath(path, glassPaint);
        canvas.drawPath(path, glassStrokePaint);
    }

    private void drawBackArrow(Canvas canvas, float cx, float cy) {
        path.reset();
        path.moveTo(cx + dp(3), cy - dp(5));
        path.lineTo(cx - dp(4), cy);
        path.lineTo(cx + dp(3), cy + dp(5));
        canvas.drawPath(path, iconPaint);
    }

    private void drawMoreDots(Canvas canvas, float cx, float cy) {
        canvas.drawCircle(cx, cy - dp(5), dp(1.4f), fillPaint);
        canvas.drawCircle(cx, cy, dp(1.4f), fillPaint);
        canvas.drawCircle(cx, cy + dp(5), dp(1.4f), fillPaint);
    }

    private void drawCallAndMore(Canvas canvas, RectF menu) {
        final float callCx = menu.left + menu.width() * 0.32f;
        final float moreCx = menu.left + menu.width() * 0.72f;
        final float cy = menu.centerY();
        path.reset();
        path.moveTo(callCx - dp(5), cy + dp(3));
        path.quadTo(callCx - dp(7), cy - dp(1), callCx - dp(2), cy - dp(5));
        path.quadTo(callCx + dp(2), cy - dp(1), callCx + dp(5), cy + dp(2));
        canvas.drawPath(path, iconPaint);
        drawMoreDots(canvas, moreCx, cy);
    }

    private void drawStandardTitle(Canvas canvas, RectF r) {
        titlePaint.setTextAlign(Paint.Align.CENTER);
        subtitlePaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Имя чата", r.centerX(), r.top + dp(14), titlePaint);
        canvas.drawText("в сети", r.centerX(), r.top + dp(26), subtitlePaint);
    }

    private void drawTelegramTitle(Canvas canvas, RectF r) {
        final float avatar = dp(24);
        final float ax = r.left + dp(5);
        canvas.drawCircle(ax + avatar / 2f, r.centerY(), avatar / 2f, avatarPaint);

        final int savedTitleColor = titlePaint.getColor();
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setTextSize(dpf2(11));
        canvas.drawText("M", ax + avatar / 2f, r.centerY() + dp(4), titlePaint);
        titlePaint.setTextSize(dpf2(13));
        titlePaint.setColor(savedTitleColor);

        titlePaint.setTextAlign(Paint.Align.LEFT);
        subtitlePaint.setTextAlign(Paint.Align.LEFT);
        final float tx = ax + avatar + dp(6);
        canvas.drawText("MandreW", tx, r.top + dp(14), titlePaint);
        canvas.drawText("был(а) недавно", tx, r.top + dp(26), subtitlePaint);
    }
}
