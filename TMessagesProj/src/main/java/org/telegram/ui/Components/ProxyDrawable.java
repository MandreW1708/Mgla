package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

/**
 * Shield icon for the chats header / menu.
 * When Mgla auto-bypass is on, draws DE1 / DE2 / FI flag badge inside the shield.
 */
public class ProxyDrawable extends Drawable {

    /** No Mgla region badge — plain shield. */
    public static final int REGION_NONE = 0;
    /** Германия1 (mglabot). */
    public static final int REGION_DE1 = 1;
    /** Германия2 (mglahub). */
    public static final int REGION_DE2 = 2;
    /** Ultra Finland. */
    public static final int REGION_FI = 3;

    private final Drawable emptyDrawable;
    private final Drawable fullDrawable;

    private final Paint outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF circleRect = new RectF();
    private final RectF flagRect = new RectF();
    private final RectF stripeRect = new RectF();
    private final Path shieldClip = new Path();

    private int radOffset = 0;
    private long lastUpdateTime;

    private float connectedAnimationProgress;

    private boolean connected;
    private boolean isEnabled;
    /** Auto-bypass suspended because system VPN is on. */
    private boolean paused;
    private int region = REGION_NONE;
    private int shieldColor = Color.WHITE;

    public ProxyDrawable(Context context) {
        super();
        emptyDrawable = context.getResources().getDrawable(R.drawable.outline_shield_plain_24).mutate();
        fullDrawable = context.getResources().getDrawable(R.drawable.outline_shield_check).mutate();

        outerPaint.setStyle(Paint.Style.STROKE);
        outerPaint.setStrokeWidth(AndroidUtilities.dp(1.66f));
        outerPaint.setStrokeCap(Paint.Cap.ROUND);
        outerPaint.setColor(Color.WHITE);

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);

        lastUpdateTime = SystemClock.elapsedRealtime();
    }

    public void setConnected(boolean enabled, boolean value, boolean animated) {
        isEnabled = enabled;
        connected = value;
        lastUpdateTime = SystemClock.elapsedRealtime();
        if (!animated) {
            connectedAnimationProgress = connected ? 1.0f : 0.0f;
        }
        invalidateSelf();
    }

    /** {@link #REGION_NONE}, {@link #REGION_DE1}, {@link #REGION_DE2} or {@link #REGION_FI}. */
    public void setRegion(int regionMode) {
        int next = regionMode;
        if (next != REGION_DE1 && next != REGION_DE2 && next != REGION_FI) {
            next = REGION_NONE;
        }
        if (region == next) {
            return;
        }
        region = next;
        invalidateSelf();
    }

    public int getRegion() {
        return region;
    }

    public void setPaused(boolean value) {
        if (paused == value) {
            return;
        }
        paused = value;
        invalidateSelf();
    }

    public boolean isPaused() {
        return paused;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        long newTime = SystemClock.elapsedRealtime();
        long dt = newTime - lastUpdateTime;
        lastUpdateTime = newTime;

        final boolean withFlag = region != REGION_NONE;

        if (!withFlag) {
            drawLegacy(canvas, dt);
            return;
        }

        // Shield outline (themed); dim slightly while paused.
        emptyDrawable.setAlpha(paused ? 180 : 255);
        setBounds(emptyDrawable);
        emptyDrawable.draw(canvas);

        // Flag badge clipped roughly to the inner shield body.
        Rect b = getBounds();
        float cx = b.exactCenterX();
        float cy = b.exactCenterY() - AndroidUtilities.dpf2(0.5f);
        float fw = AndroidUtilities.dpf2(12.5f);
        float fh = AndroidUtilities.dpf2(8.5f);
        flagRect.set(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f);

        int save = canvas.save();
        buildShieldInnerClip(shieldClip, b);
        canvas.clipPath(shieldClip);
        int flagAlphaSave = flagPaint.getAlpha();
        if (paused) {
            flagPaint.setAlpha(160);
            badgeBgPaint.setAlpha(160);
            textPaint.setAlpha(160);
        }
        drawFlag(canvas, flagRect, region);
        flagPaint.setAlpha(flagAlphaSave);
        badgeBgPaint.setAlpha(255);
        textPaint.setAlpha(255);
        canvas.restoreToCount(save);

        if (paused) {
            drawPauseGlyph(canvas, cx, cy);
        } else if (isEnabled && (!connected || connectedAnimationProgress != 1.0f)) {
            outerPaint.setColor(shieldColor);
            outerPaint.setAlpha((int) (255 * (1.0f - connectedAnimationProgress)));
            radOffset += (int) (360 * dt / 1000.0f);
            float r = AndroidUtilities.dpf2(5.5f);
            circleRect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(circleRect, -90 + radOffset, 90, false, outerPaint);
            invalidateSelf();
        }

        if (!paused) {
            animateConnectedProgress(dt);
        }
    }

    private void drawLegacy(@NonNull Canvas canvas, long dt) {
        if (!isEnabled && !paused) {
            setBounds(emptyDrawable);
            emptyDrawable.draw(canvas);
            return;
        }

        setBounds(emptyDrawable);
        emptyDrawable.setAlpha(paused ? 180 : 255);
        emptyDrawable.draw(canvas);

        Rect b = getBounds();
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();

        if (paused) {
            drawPauseGlyph(canvas, cx, cy);
            return;
        }

        if (!connected || connectedAnimationProgress != 1.0f) {
            outerPaint.setColor(shieldColor);
            outerPaint.setAlpha((int) (255 * (1.0f - connectedAnimationProgress)));

            radOffset += (int) (360 * dt / 1000.0f);

            int r = AndroidUtilities.dp(4);
            circleRect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(circleRect, -90 + radOffset, 90, false, outerPaint);
            invalidateSelf();
        }

        if (isEnabled && (connected || connectedAnimationProgress != 0.0f)) {
            fullDrawable.setAlpha((int) (255 * connectedAnimationProgress));
            setBounds(fullDrawable);
            fullDrawable.draw(canvas);
        }

        animateConnectedProgress(dt);
    }

    /** Classic pause glyph: two vertical bars in a dark disc. */
    private void drawPauseGlyph(Canvas canvas, float cx, float cy) {
        float discR = AndroidUtilities.dpf2(5.8f);
        badgeBgPaint.setColor(0xE6000000);
        badgeBgPaint.setAlpha(230);
        canvas.drawCircle(cx, cy, discR, badgeBgPaint);

        flagPaint.setColor(0xFFFFFFFF);
        flagPaint.setAlpha(255);
        float barW = AndroidUtilities.dpf2(1.7f);
        float barH = AndroidUtilities.dpf2(7.2f);
        float gap = AndroidUtilities.dpf2(1.5f);
        float top = cy - barH / 2f;
        float bottom = cy + barH / 2f;
        float left1 = cx - gap / 2f - barW;
        float left2 = cx + gap / 2f;
        float rad = AndroidUtilities.dpf2(0.6f);
        stripeRect.set(left1, top, left1 + barW, bottom);
        canvas.drawRoundRect(stripeRect, rad, rad, flagPaint);
        stripeRect.set(left2, top, left2 + barW, bottom);
        canvas.drawRoundRect(stripeRect, rad, rad, flagPaint);
    }

    private void animateConnectedProgress(long dt) {
        if (connected && connectedAnimationProgress != 1.0f) {
            connectedAnimationProgress += dt / 300.0f;
            if (connectedAnimationProgress > 1.0f) {
                connectedAnimationProgress = 1.0f;
            }
            invalidateSelf();
        } else if (!connected && connectedAnimationProgress != 0.0f) {
            connectedAnimationProgress -= dt / 300.0f;
            if (connectedAnimationProgress < 0.0f) {
                connectedAnimationProgress = 0.0f;
            }
            invalidateSelf();
        }
    }

    private void drawFlag(Canvas canvas, RectF rect, int mode) {
        float l = rect.left;
        float t = rect.top;
        float r = rect.right;
        float b = rect.bottom;
        float w = rect.width();
        float h = rect.height();
        float radius = AndroidUtilities.dpf2(1.2f);

        if (mode == REGION_FI) {
            // Finnish flag: white field, blue Nordic cross.
            badgeBgPaint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(rect, radius, radius, badgeBgPaint);
            flagPaint.setColor(0xFF002F6C);
            float bar = h * 0.22f;
            float vLeft = l + w * 0.30f;
            canvas.drawRect(l, t + (h - bar) / 2f, r, t + (h + bar) / 2f, flagPaint);
            canvas.drawRect(vLeft, t, vLeft + bar * 0.95f, b, flagPaint);
            return;
        }

        // German flag: black / red / gold horizontal stripes.
        float third = h / 3f;
        badgeBgPaint.setColor(0xFF000000);
        stripeRect.set(l, t, r, b);
        canvas.drawRoundRect(stripeRect, radius, radius, badgeBgPaint);
        flagPaint.setColor(0xFFDD0000);
        canvas.drawRect(l, t + third, r, t + third * 2f, flagPaint);
        flagPaint.setColor(0xFFFFCE00);
        canvas.drawRect(l, t + third * 2f, r, b, flagPaint);
        // Re-round bottom corners in gold.
        badgeBgPaint.setColor(0xFFFFCE00);
        stripeRect.set(l, t + third * 2f - 1f, r, b);
        canvas.drawRoundRect(stripeRect, radius, radius, badgeBgPaint);
        flagPaint.setColor(0xFFFFCE00);
        canvas.drawRect(l, t + third * 2f, r, b - radius, flagPaint);

        // Digit 1 or 2 in a dark disc over the flag.
        String digit = mode == REGION_DE2 ? "2" : "1";
        float discR = Math.min(w, h) * 0.42f;
        float dcx = (l + r) / 2f;
        float dcy = (t + b) / 2f;
        badgeBgPaint.setColor(0xCC000000);
        canvas.drawCircle(dcx, dcy, discR, badgeBgPaint);
        textPaint.setTextSize(discR * 1.35f);
        textPaint.setColor(0xFFFFFFFF);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = dcy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(digit, dcx, textY, textPaint);
    }

    /** Resolve badge from current Mgla bypass selection (or NONE). */
    public static int resolveMglaRegion() {
        try {
            if (!org.telegram.utils.wsbypass.MglaWsConfig.isEnabled()) {
                return REGION_NONE;
            }
            if (org.telegram.utils.wsbypass.MglaWsConfig.isUltraBypassEnabled()) {
                return REGION_FI;
            }
            if (org.telegram.utils.wsbypass.MglaWsConfig.REGION_HUB
                .equals(org.telegram.utils.wsbypass.MglaWsConfig.getBypassRegion())) {
                return REGION_DE2;
            }
            return REGION_DE1;
        } catch (Throwable ignored) {
            return REGION_NONE;
        }
    }

    /** Soft inner clip so the flag sits inside the shield silhouette. */
    private void buildShieldInnerClip(Path path, Rect bounds) {
        path.reset();
        float cx = bounds.exactCenterX();
        float top = bounds.top + AndroidUtilities.dpf2(4f);
        float bottom = bounds.bottom - AndroidUtilities.dpf2(3.5f);
        float halfW = AndroidUtilities.dpf2(7.2f);
        path.moveTo(cx, top);
        path.cubicTo(cx + halfW * 0.35f, top + AndroidUtilities.dpf2(1.2f),
            cx + halfW, top + AndroidUtilities.dpf2(2.5f),
            cx + halfW, top + AndroidUtilities.dpf2(5f));
        path.cubicTo(cx + halfW, top + AndroidUtilities.dpf2(12f),
            cx + halfW * 0.85f, bottom - AndroidUtilities.dpf2(6f),
            cx, bottom);
        path.cubicTo(cx - halfW * 0.85f, bottom - AndroidUtilities.dpf2(6f),
            cx - halfW, top + AndroidUtilities.dpf2(12f),
            cx - halfW, top + AndroidUtilities.dpf2(5f));
        path.cubicTo(cx - halfW, top + AndroidUtilities.dpf2(2.5f),
            cx - halfW * 0.35f, top + AndroidUtilities.dpf2(1.2f),
            cx, top);
        path.close();
    }

    private void setBounds(Drawable drawable) {
        Rect bounds = getBounds();
        drawable.setBounds(
            bounds.centerX() - drawable.getIntrinsicWidth() / 2,
            bounds.centerY() - drawable.getIntrinsicHeight() / 2,
            bounds.centerX() + drawable.getIntrinsicWidth() / 2,
            bounds.centerY() + drawable.getIntrinsicHeight() / 2
        );
    }

    @Override
    public void setAlpha(int alpha) {
        emptyDrawable.setAlpha(alpha);
        fullDrawable.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        emptyDrawable.setColorFilter(cf);
        fullDrawable.setColorFilter(cf);
        if (cf instanceof PorterDuffColorFilter) {
            // Theme tints the shield vectors; keep spinner matching that tint.
            // Flag paints stay unfiltered so DE/FI colours survive SRC_IN on ImageView.
            try {
                // Best-effort: many builds expose the color via reflection; fall back to white.
                java.lang.reflect.Field f = PorterDuffColorFilter.class.getDeclaredField("mColor");
                f.setAccessible(true);
                shieldColor = f.getInt(cf);
            } catch (Throwable ignored) {
                shieldColor = Color.WHITE;
            }
            outerPaint.setColor(shieldColor);
        } else if (cf == null) {
            shieldColor = Color.WHITE;
            outerPaint.setColor(shieldColor);
        }
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(24);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(24);
    }
}
