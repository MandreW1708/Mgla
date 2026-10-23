package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import org.telegram.ui.MglaPlayerConfig;

/**
 * Material Design 3 media timeline: played segment is an animated wave,
 * remaining segment is a straight track; thickens on press.
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
    private final Path wavePath = new Path();
    private final RectF trackRect = new RectF();
    private final RectF thumbRect = new RectF();

    private Listener listener;
    private float progress;       // 0..1 drawn
    private float targetProgress; // 0..1 logical
    private boolean tracking;
    private float pressProgress;  // 0 resting → 1 pressed
    private float wavePhase;      // radians, continuous while playing
    private float waveAmpProgress; // 0 flat → 1 full wave (paused / pressed flatten)

    private ValueAnimator pressAnimator;
    private ValueAnimator progressAnimator;
    private ValueAnimator waveAnimator;
    private ValueAnimator ampAnimator;

    private int activeColor = 0xFFFFFFFF;
    private int inactiveColor = 0x47FFFFFF;
    private boolean playing;

    public MglaMd3SeekBar(Context context) {
        super(context);
        inactivePaint.setStyle(Paint.Style.STROKE);
        inactivePaint.setStrokeCap(Paint.Cap.ROUND);
        inactivePaint.setStrokeJoin(Paint.Join.ROUND);
        activePaint.setStyle(Paint.Style.STROKE);
        activePaint.setStrokeCap(Paint.Cap.ROUND);
        activePaint.setStrokeJoin(Paint.Join.ROUND);
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

    public boolean isTracking() {
        return tracking;
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
        // Large jumps (open sheet, track change, seek) must snap — animating looks like a crawl.
        final float delta = Math.abs(progress - value);
        final boolean doAnim = animated && MglaPlayerConfig.isSeekbarAnimationsEnabled() && delta < 0.04f;
        if (!doAnim) {
            if (progressAnimator != null) {
                progressAnimator.cancel();
                progressAnimator = null;
            }
            progress = value;
            invalidate();
            return;
        }
        if (delta < 0.0008f) {
            progress = value;
            invalidate();
            return;
        }
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        final float from = progress;
        progressAnimator = ValueAnimator.ofFloat(from, value);
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
        updateWaveMotion();
    }

    private void updateWaveMotion() {
        final boolean wantWave = playing && !tracking && MglaPlayerConfig.isSeekbarWaveEnabled();
        animateAmp(wantWave ? 1f : 0f);

        if (!wantWave) {
            if (waveAnimator != null) {
                waveAnimator.cancel();
                waveAnimator = null;
            }
            return;
        }
        if (waveAnimator != null && waveAnimator.isRunning()) {
            return;
        }
        // Continuous phase shift — MD3 wavy progress indicator.
        waveAnimator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2.0));
        waveAnimator.setDuration(MglaPlayerConfig.scaleDuration(1200));
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.setInterpolator(new LinearInterpolator());
        waveAnimator.addUpdateListener(a -> {
            wavePhase = (float) a.getAnimatedValue();
            invalidate();
        });
        waveAnimator.start();
    }

    private void animateAmp(float target) {
        if (!MglaPlayerConfig.isSeekbarAnimationsEnabled()) {
            waveAmpProgress = target;
            invalidate();
            return;
        }
        if (Math.abs(waveAmpProgress - target) < 0.01f) {
            waveAmpProgress = target;
            return;
        }
        if (ampAnimator != null) {
            ampAnimator.cancel();
        }
        ampAnimator = ValueAnimator.ofFloat(waveAmpProgress, target);
        ampAnimator.setDuration(MglaPlayerConfig.scaleDuration(target > waveAmpProgress ? 280 : 220));
        ampAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        ampAnimator.addUpdateListener(a -> {
            waveAmpProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        ampAnimator.start();
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

        // Pick up live setting changes (Player → Animations → Волна таймлайна).
        final boolean wantWave = playing && !tracking && MglaPlayerConfig.isSeekbarWaveEnabled();
        final boolean waveRunning = waveAnimator != null && waveAnimator.isRunning();
        if (wantWave != waveRunning || (wantWave && waveAmpProgress < 0.99f && (ampAnimator == null || !ampAnimator.isRunning()))) {
            updateWaveMotion();
        }

        // Stroke thickness: resting ~3.5dp → pressed thicker.
        final float stroke = dpf2(3.5f) + dpf2(6f) * pressProgress;
        inactivePaint.setStrokeWidth(stroke);
        activePaint.setStrokeWidth(stroke);

        final float cy = h / 2f;
        final float inset = dpf2(6);
        final float left = inset;
        final float right = w - inset;
        final float trackW = Math.max(1f, right - left);
        trackRect.set(left, cy - stroke / 2f, right, cy + stroke / 2f);

        final float activeRight = left + trackW * progress;
        // Amplitude collapses while pressed so the thumb is easy to place.
        final float amp = dpf2(3.2f) * waveAmpProgress * (1f - pressProgress);

        // Remaining (inactive) — straight line from playhead to end.
        inactivePaint.setColor(inactiveColor);
        if (activeRight < right - 0.5f) {
            canvas.drawLine(Math.max(activeRight, left), cy, right, cy, inactivePaint);
        }

        // Played (active) — MD3 wave when playing, otherwise a straight line.
        activePaint.setColor(activeColor);
        if (activeRight > left + 0.5f) {
            if (amp > 0.4f) {
                buildWavePath(left, activeRight, cy, amp);
                canvas.drawPath(wavePath, activePaint);
            } else {
                canvas.drawLine(left, cy, activeRight, cy, activePaint);
            }
        }

        // Thumb: resting disc → pressed vertical pill.
        final float thumbW = dpf2(4f) + dpf2(4f) * pressProgress;
        final float thumbH = dpf2(14f) + dpf2(10f) * pressProgress;
        final float tx = Math.max(left + thumbW / 2f, Math.min(right - thumbW / 2f, activeRight));
        thumbRect.set(tx - thumbW / 2f, cy - thumbH / 2f, tx + thumbW / 2f, cy + thumbH / 2f);
        thumbPaint.setColor(activeColor);
        canvas.drawRoundRect(thumbRect, thumbW / 2f, thumbW / 2f, thumbPaint);
    }

    private void buildWavePath(float startX, float endX, float cy, float amplitude) {
        wavePath.reset();
        final float length = endX - startX;
        if (length <= 0f) {
            return;
        }
        // ~18dp per half-period — readable MD3-style undulation.
        final float wavelength = dpf2(18f);
        final float step = Math.max(1.5f, dpf2(1.5f));
        final float twoPi = (float) (Math.PI * 2.0);
        wavePath.moveTo(startX, cy);
        for (float x = startX + step; x < endX; x += step) {
            final float t = (x - startX) / wavelength;
            final float y = cy + amplitude * (float) Math.sin(t * twoPi + wavePhase);
            wavePath.lineTo(x, y);
        }
        // End exactly on the centerline at the playhead so it meets the straight remainder cleanly.
        wavePath.lineTo(endX, cy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float w = getWidth();
        if (w <= 0) {
            return false;
        }
        final float inset = dpf2(6);
        final float usable = Math.max(1f, w - inset * 2f);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                tracking = true;
                setPressedVisual(true);
                updateWaveMotion();
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
                updateWaveMotion();
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
        if (waveAnimator != null) {
            waveAnimator.cancel();
            waveAnimator = null;
        }
        if (ampAnimator != null) {
            ampAnimator.cancel();
            ampAnimator = null;
        }
    }
}
