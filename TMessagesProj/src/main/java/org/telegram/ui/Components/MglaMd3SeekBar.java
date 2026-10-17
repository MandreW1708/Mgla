package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import org.telegram.ui.MglaPlayerConfig;

/**
 * Material Design 3 expressive slider: thickens on press, smooth progress lerp, optional play pulse.
 */
public class MglaMd3SeekBar extends View {

    public interface Listener {
        void onProgressChanged(float progress, boolean fromUser);

        void onStartTracking();

        void onStopTracking(float progress);
    }

    private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF activeRect = new RectF();
    private final RectF thumbRect = new RectF();

    private Listener listener;
    private float progress;       // 0..1 drawn
    private float targetProgress; // 0..1 logical
    private boolean tracking;
    private float pressProgress;  // 0 resting → 1 pressed
    private float playPulse;      // subtle breathing when playing + animations on

    private ValueAnimator pressAnimator;
    private ValueAnimator progressAnimator;
    private ValueAnimator pulseAnimator;

    private int activeColor = 0xFFFFFFFF;
    private int inactiveColor = 0x47FFFFFF;
    private boolean playing;

    public MglaMd3SeekBar(Context context) {
        super(context);
        inactivePaint.setStyle(Paint.Style.FILL);
        activePaint.setStyle(Paint.Style.FILL);
        thumbPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void setColors(int active, int inactive) {
        activeColor = active;
        inactiveColor = inactive;
        invalidate();
    }

    public float getProgress() {
        return targetProgress;
    }

    /** Sets progress in 0..1. Animates when not tracking and MD3 timeline anim is on. */
    public void setProgress(float value, boolean animated) {
        value = Math.max(0f, Math.min(1f, value));
        targetProgress = value;
        if (tracking) {
            progress = value;
            invalidate();
            return;
        }
        final boolean doAnim = animated && MglaPlayerConfig.isSeekbarAnimationsEnabled();
        if (!doAnim) {
            if (progressAnimator != null) {
                progressAnimator.cancel();
                progressAnimator = null;
            }
            progress = value;
            invalidate();
            return;
        }
        if (Math.abs(progress - value) < 0.0008f) {
            progress = value;
            invalidate();
            return;
        }
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        final float from = progress;
        progressAnimator = ValueAnimator.ofFloat(from, value);
        // Short lerp so consecutive media ticks feel continuous, not jumpy.
        progressAnimator.setDuration(MglaPlayerConfig.scaleDuration(180));
        progressAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        progressAnimator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        progressAnimator.start();
    }

    public void setPlaying(boolean playing) {
        if (this.playing == playing) {
            return;
        }
        this.playing = playing;
        updatePulse();
    }

    private void updatePulse() {
        final boolean want = playing && !tracking && MglaPlayerConfig.isSeekbarAnimationsEnabled();
        if (!want) {
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
                pulseAnimator = null;
            }
            playPulse = 0f;
            invalidate();
            return;
        }
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(MglaPlayerConfig.scaleDuration(1400));
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(a -> {
            playPulse = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnimator.start();
    }

    private void setPressedVisual(boolean pressed) {
        if (!MglaPlayerConfig.isSeekbarAnimationsEnabled()) {
            pressProgress = pressed ? 1f : 0f;
            invalidate();
            return;
        }
        if (pressAnimator != null) {
            pressAnimator.cancel();
        }
        pressAnimator = ValueAnimator.ofFloat(pressProgress, pressed ? 1f : 0f);
        pressAnimator.setDuration(MglaPlayerConfig.scaleDuration(pressed ? 160 : 220));
        pressAnimator.setInterpolator(pressed ? CubicBezierInterpolator.EASE_OUT : CubicBezierInterpolator.DEFAULT);
        pressAnimator.addUpdateListener(a -> {
            pressProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        pressAnimator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(40));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // MD3 expressive: resting track 4dp → pressed 14dp; slight pulse while playing.
        final float trackH = dpf2(4f) + dpf2(10f) * pressProgress + dpf2(1.2f) * playPulse * (1f - pressProgress);
        final float cy = h / 2f;
        final float inset = dpf2(2);
        trackRect.set(inset, cy - trackH / 2f, w - inset, cy + trackH / 2f);
        final float radius = trackH / 2f;

        inactivePaint.setColor(inactiveColor);
        canvas.drawRoundRect(trackRect, radius, radius, inactivePaint);

        final float activeRight = trackRect.left + trackRect.width() * progress;
        if (activeRight > trackRect.left + 0.5f) {
            activeRect.set(trackRect.left, trackRect.top, activeRight, trackRect.bottom);
            activePaint.setColor(activeColor);
            canvas.drawRoundRect(activeRect, radius, radius, activePaint);
        }

        // Thumb: resting disc → pressed vertical pill (MD3 expressive handle).
        final float thumbW = dpf2(4f) + dpf2(4f) * pressProgress;
        final float thumbH = dpf2(12f) + dpf2(10f) * pressProgress + dpf2(2f) * playPulse * (1f - pressProgress);
        final float tx = Math.max(trackRect.left + thumbW / 2f,
                Math.min(trackRect.right - thumbW / 2f, activeRight));
        thumbRect.set(tx - thumbW / 2f, cy - thumbH / 2f, tx + thumbW / 2f, cy + thumbH / 2f);
        thumbPaint.setColor(activeColor);
        canvas.drawRoundRect(thumbRect, thumbW / 2f, thumbW / 2f, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float w = getWidth();
        if (w <= 0) {
            return false;
        }
        final float inset = dpf2(2);
        final float usable = Math.max(1f, w - inset * 2f);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                tracking = true;
                setPressedVisual(true);
                updatePulse();
                if (progressAnimator != null) {
                    progressAnimator.cancel();
                    progressAnimator = null;
                }
                progress = targetProgress = clampProgress((event.getX() - inset) / usable);
                invalidate();
                if (listener != null) {
                    listener.onStartTracking();
                    listener.onProgressChanged(targetProgress, true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!tracking) {
                    return false;
                }
                progress = targetProgress = clampProgress((event.getX() - inset) / usable);
                invalidate();
                if (listener != null) {
                    listener.onProgressChanged(targetProgress, true);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!tracking) {
                    return false;
                }
                tracking = false;
                setPressedVisual(false);
                updatePulse();
                if (listener != null) {
                    listener.onStopTracking(targetProgress);
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private static float clampProgress(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pressAnimator != null) {
            pressAnimator.cancel();
            pressAnimator = null;
        }
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }
}
