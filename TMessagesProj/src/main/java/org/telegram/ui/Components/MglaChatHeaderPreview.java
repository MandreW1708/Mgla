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
 * Live mini preview of chat header styles (Standard / Clean / Telegram / Custom).
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
    private MglaChatHeaderLayout customLayout;
    private boolean forceCustomLayout;

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
        forceCustomLayout = MglaGlassConfig.isCustomHeaderEnabled();
        customLayout = forceCustomLayout ? MglaChatHeaderLayout.resolve() : null;
        setPreviewState(
                MglaGlassConfig.getChatHeaderStyle(),
                MglaGlassConfig.isCleanHeaderHideBackBtnEnabled(),
                MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled(),
                false
        );
    }

    /** Force Custom preview from live constructor config. */
    public void setCustomPreviewFromConfig() {
        forceCustomLayout = true;
        customLayout = MglaChatHeaderLayout.resolveCustom();
        style = MglaGlassConfig.HEADER_STYLE_CUSTOM;
        fromStyle = style;
        animProgress = 1f;
        requestLayout();
        invalidate();
    }

    public void setStyleAnimated(int newStyle) {
        forceCustomLayout = newStyle == MglaGlassConfig.HEADER_STYLE_CUSTOM;
        customLayout = forceCustomLayout ? MglaChatHeaderLayout.resolve() : null;
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

    private static final int PREVIEW_HEIGHT_DP = 158;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int extra = 0;
        if (forceCustomLayout && customLayout != null) {
            extra = Math.max(0, customLayout.headerExtraHeightDp);
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(PREVIEW_HEIGHT_DP + extra) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int pl = getPaddingLeft();
        final int pt = getPaddingTop();
        final int w = getWidth() - pl - getPaddingRight();
        int extra = (forceCustomLayout && customLayout != null) ? Math.max(0, customLayout.headerExtraHeightDp) : 0;
        final int h = dp(PREVIEW_HEIGHT_DP + extra);
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
        if (drawStyle == MglaGlassConfig.HEADER_STYLE_CUSTOM || (forceCustomLayout && drawStyle == style)) {
            MglaChatHeaderLayout layout = customLayout != null ? customLayout : MglaChatHeaderLayout.resolve();
            drawCustom(canvas, layout, w, rowTop);
            return;
        }

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
            drawRightAvatar(canvas, rect, Math.min(rect.width(), rect.height()) - dp(2));
        }

        drawSecondaryPanels(canvas, w, pad, rowTop + pillH + dp(10), showPinnedGlass, showTranslationGlass, true, true, 23);
    }

    private void drawCustom(Canvas canvas, MglaChatHeaderLayout layout, int w, float rowTop) {
        final float pad = dp(layout.padDp);
        final float gap = dp(layout.gapDp);
        final float pillH = dp(34 + Math.max(0, layout.headerExtraHeightDp > 10 ? 6 : 0));
        final float corner = Math.min(pillH / 2f, dp(layout.glassRadiusDp));

        float cursor = pad;
        float backRight = cursor;
        if (layout.showBack) {
            rect.set(cursor, rowTop, cursor + pillH, rowTop + pillH);
            if (layout.drawGlassBack) {
                drawGlass(canvas, rect, corner);
            }
            drawBackArrow(canvas, rect.centerX(), rect.centerY());
            backRight = cursor + pillH;
            cursor = backRight + gap;
        }

        float menuLeft = w - pad;
        if (layout.showMenu) {
            final float menuW = layout.telegramLike ? dp(64) : pillH;
            menuLeft = w - pad - menuW;
            rect.set(menuLeft, rowTop, w - pad, rowTop + pillH);
            if (layout.drawGlassMenu) {
                drawGlass(canvas, rect, corner);
            }
            if (layout.telegramLike) {
                drawCallAndMore(canvas, rect);
            } else if (!(layout.showAvatar && layout.avatarAtEnd)) {
                drawMoreDots(canvas, rect.centerX(), rect.centerY());
            } else {
                drawRightAvatar(canvas, rect, Math.min(layout.avatarSizeDp, pillH - dp(2)), layout);
            }
        } else if (layout.showAvatar && layout.avatarAtEnd) {
            final float av = Math.min(layout.avatarSizeDp, pillH);
            menuLeft = w - pad - av;
            rect.set(menuLeft, rowTop, w - pad, rowTop + pillH);
            drawRightAvatar(canvas, rect, av - dp(2), layout);
        }

        float titleLeft = cursor;
        float titleRight = menuLeft - (layout.showMenu || (layout.showAvatar && layout.avatarAtEnd) ? gap : 0);
        if (titleRight > titleLeft + dp(8) && (layout.showTitle || (layout.showAvatar && layout.avatarAtStart))) {
            rect.set(titleLeft, rowTop, titleRight, rowTop + pillH);
            if (layout.drawGlassTitle) {
                drawGlass(canvas, rect, corner);
            }
            if (layout.telegramLike || (layout.showAvatar && layout.avatarAtStart)) {
                drawTelegramTitle(canvas, rect, layout);
            } else if (layout.showTitle) {
                drawStandardTitle(canvas, rect, layout);
            }
        }

        float secondaryTop = rowTop + pillH + dp(10);
        drawSecondaryPanels(canvas, w, pad, secondaryTop,
                layout.drawGlassPinned, layout.drawGlassTranslation,
                layout.showPinned, layout.showTranslation,
                layout.glassRadiusDp);
    }

    private void drawSecondaryPanels(Canvas canvas, int w, float pad, float top,
                                     boolean glassPinned, boolean glassTranslation,
                                     boolean showPinned, boolean showTranslation,
                                     int radiusDp) {
        float y = top;
        final float pinnedH = dp(36);
        final float translationH = dp(22);
        final float r = Math.min(pinnedH / 2f, dp(radiusDp));
        if (showPinned) {
            rect.set(pad, y, w - pad, y + pinnedH);
            if (glassPinned) {
                drawGlass(canvas, rect, r);
            }
            captionPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Закреплённое сообщение", rect.left + dp(10), rect.centerY() + dp(3.5f), captionPaint);
            y += pinnedH + dp(5);
        }
        if (showTranslation) {
            rect.set(pad, y, w - pad, y + translationH);
            if (glassTranslation) {
                drawGlass(canvas, rect, Math.min(translationH / 2f, dp(radiusDp)));
            }
            captionPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Панель перевода", rect.left + dp(10), rect.centerY() + dp(3.5f), captionPaint);
        }
    }

    private void drawRightAvatar(Canvas canvas, RectF slot, float size) {
        drawRightAvatar(canvas, slot, size, null);
    }

    private void drawRightAvatar(Canvas canvas, RectF slot, float size, MglaChatHeaderLayout layout) {
        size = Math.max(dp(18), Math.min(size, Math.min(slot.width(), slot.height()) - dp(2)));
        final float cx = slot.centerX();
        final float cy = slot.centerY();
        final float corner = layout != null
                ? Math.min(size / 2f, dp(layout.glassRadiusDp))
                : size / 2f;
        rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        canvas.drawRoundRect(rect, corner, corner, avatarPaint);

        final int saved = titlePaint.getColor();
        final float savedSize = titlePaint.getTextSize();
        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setTextSize(dpf2(Math.max(10, size * 0.35f / AndroidUtilities.density)));
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
        drawStandardTitle(canvas, r, null);
    }

    private void drawStandardTitle(Canvas canvas, RectF r, MglaChatHeaderLayout layout) {
        final boolean center = layout == null || layout.titleCentered;
        titlePaint.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
        subtitlePaint.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
        if (layout != null) {
            titlePaint.setTextSize(dpf2(layout.titleTextSizeSp - 4));
            subtitlePaint.setTextSize(dpf2(layout.subtitleTextSizeSp - 2));
        } else {
            titlePaint.setTextSize(dpf2(13));
            subtitlePaint.setTextSize(dpf2(11));
        }
        final float tx = center ? r.centerX() : r.left + dp(12);
        if (layout == null || layout.showTitle) {
            canvas.drawText("Имя чата", tx, r.top + dp(14), titlePaint);
        }
        if (layout == null || layout.showSubtitle) {
            canvas.drawText("в сети", tx, r.top + dp(26), subtitlePaint);
        }
    }

    private void drawTelegramTitle(Canvas canvas, RectF r) {
        drawTelegramTitle(canvas, r, null);
    }

    private void drawTelegramTitle(Canvas canvas, RectF r, MglaChatHeaderLayout layout) {
        final float avatar = layout != null ? Math.min(dp(layout.avatarSizeDp - 8), r.height() - dp(8)) : dp(24);
        final float ax = r.left + dp(5);
        if (layout == null || layout.showAvatar) {
            final float corner = layout != null
                    ? Math.min(avatar / 2f, dp(layout.glassRadiusDp))
                    : avatar / 2f;
            rect.set(ax, r.centerY() - avatar / 2f, ax + avatar, r.centerY() + avatar / 2f);
            canvas.drawRoundRect(rect, corner, corner, avatarPaint);
            final int savedTitleColor = titlePaint.getColor();
            titlePaint.setColor(0xFFFFFFFF);
            titlePaint.setTextAlign(Paint.Align.CENTER);
            titlePaint.setTextSize(dpf2(11));
            canvas.drawText("M", ax + avatar / 2f, r.centerY() + dp(4), titlePaint);
            titlePaint.setTextSize(dpf2(13));
            titlePaint.setColor(savedTitleColor);
        }

        titlePaint.setTextAlign(Paint.Align.LEFT);
        subtitlePaint.setTextAlign(Paint.Align.LEFT);
        if (layout != null) {
            titlePaint.setTextSize(dpf2(layout.titleTextSizeSp - 4));
            subtitlePaint.setTextSize(dpf2(layout.subtitleTextSizeSp - 2));
        }
        final float tx = ax + ((layout == null || layout.showAvatar) ? avatar + dp(6) : dp(6));
        if (layout == null || layout.showTitle) {
            canvas.drawText("MandreW", tx, r.top + dp(14), titlePaint);
        }
        if (layout == null || layout.showSubtitle) {
            canvas.drawText("был(а) недавно", tx, r.top + dp(26), subtitlePaint);
        }
    }
}
