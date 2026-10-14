package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Обёртка карточки «Продолжить…»: мягкая волна свечения бежит по периметру.
 */
public class MglaMemoryPerimeterGlow extends FrameLayout {

    private final Path borderPath = new Path();
    private final Path glowSegment = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private final RectF box = new RectF();
    private final Paint softPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator animator;
    private float phase;
    private float pathLength;
    private boolean running;

    public MglaMemoryPerimeterGlow(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipToPadding(false);
        setClipChildren(false);
        // Небольшой запас, чтобы свечение не обрезалось краями карточки.
        int pad = dp(3);
        setPadding(pad, pad, pad, pad);

        softPaint.setStyle(Paint.Style.STROKE);
        softPaint.setStrokeCap(Paint.Cap.ROUND);
        softPaint.setStrokeJoin(Paint.Join.ROUND);
        softPaint.setStrokeWidth(dp(7));

        corePaint.setStyle(Paint.Style.STROKE);
        corePaint.setStrokeCap(Paint.Cap.ROUND);
        corePaint.setStrokeJoin(Paint.Join.ROUND);
        corePaint.setStrokeWidth(dp(2.2f));

        basePaint.setStyle(Paint.Style.STROKE);
        basePaint.setStrokeWidth(dp(1.1f));
    }

    public void setContent(View content) {
        removeAllViews();
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildPath(w, h);
    }

    private void rebuildPath(int w, int h) {
        borderPath.reset();
        if (w <= 0 || h <= 0) {
            pathLength = 0;
            return;
        }
        float inset = dp(3.5f);
        box.set(inset, inset, w - inset, h - inset);
        float radius = dp(MglaUi.BLOCK_CORNER_RADIUS_DP);
        borderPath.addRoundRect(box, radius, radius, Path.Direction.CW);
        pathMeasure.setPath(borderPath, false);
        pathLength = pathMeasure.getLength();
    }

    private int accent() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (pathLength <= 0 || getVisibility() != VISIBLE) {
            return;
        }
        int color = accent();
        basePaint.setColor(withAlpha(color, 38));
        canvas.drawPath(borderPath, basePaint);

        // Длина «волны» — около четверти периметра.
        float wave = Math.max(dp(64), pathLength * 0.28f);
        float head = (phase % 1f) * pathLength;

        drawWave(canvas, softPaint, color, head, wave, 55);
        drawWave(canvas, corePaint, color, head, wave * 0.55f, 220);
    }

    private void drawWave(Canvas canvas, Paint paint, int color, float head, float wave, int peakAlpha) {
        glowSegment.reset();
        float start = head - wave;
        if (start < 0) {
            // Волна переезжает через стык пути.
            pathMeasure.getSegment(pathLength + start, pathLength, glowSegment, true);
            pathMeasure.getSegment(0, head, glowSegment, true);
        } else {
            pathMeasure.getSegment(start, head, glowSegment, true);
        }
        // Градиент яркости вдоль сегмента: хвост тусклее, голова ярче.
        // Рисуем сегмент дважды с разной альфой через слой — достаточно мягкого пика у головы.
        paint.setColor(withAlpha(color, peakAlpha / 3));
        canvas.drawPath(glowSegment, paint);

        glowSegment.reset();
        float tip = Math.max(dp(18), wave * 0.22f);
        float tipStart = head - tip;
        if (tipStart < 0) {
            pathMeasure.getSegment(pathLength + tipStart, pathLength, glowSegment, true);
            pathMeasure.getSegment(0, head, glowSegment, true);
        } else {
            pathMeasure.getSegment(tipStart, head, glowSegment, true);
        }
        paint.setColor(withAlpha(color, peakAlpha));
        canvas.drawPath(glowSegment, paint);
    }

    private void startGlow() {
        if (running) {
            return;
        }
        running = true;
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2600);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
        }
        if (!animator.isStarted()) {
            animator.start();
        }
    }

    private void stopGlow() {
        running = false;
        if (animator != null) {
            animator.cancel();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncAnimation();
    }

    @Override
    public void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopGlow();
        super.onDetachedFromWindow();
    }

    private void syncAnimation() {
        if (isAttachedToWindow() && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE) {
            startGlow();
        } else {
            stopGlow();
        }
    }
}
