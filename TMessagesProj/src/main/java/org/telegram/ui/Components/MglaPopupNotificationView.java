package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.MglaGlassConfig;

/**
 * Futuristic in-app message popup: liquid glass card, avatar-tinted fill, pulsing border.
 */
public class MglaPopupNotificationView extends FrameLayout {

    private final BackupImageView avatarView;
    private final TextView titleView;
    private final TextView previewView;
    private final TextView actionView;

    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();

    private int accentColor;
    private int avatarColor = 0xFF5B8DEF;
    private int avatarColor2 = 0xFF3A5F9A;
    private int alphaPercent = 90;
    private boolean pulseEnabled = true;
    private boolean useGlassDrawable;

    private float accentPulse = 0.7f;
    private float pressScale = 1f;
    private ValueAnimator pulseAnimator;
    private ValueAnimator pressAnimator;
    private android.view.ViewPropertyAnimator runningShowHide;

    private BlurredBackgroundDrawable glassBackground;
    private BlurredBackgroundSourceColor glassColorSource;
    private BlurredBackgroundProvider glassColorProvider;
    @Nullable
    private View glassSourceRoot;

    private Runnable onOpen;

    public MglaPopupNotificationView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipToPadding(false);
        setClipChildren(false);
        setPadding(dp(14), dp(12), dp(12), dp(12));

        accentColor = Theme.getColor(Theme.key_featuredStickers_addButton);
        if (ColorUtils.calculateLuminance(accentColor) > 0.72f) {
            accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader);
        }

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(20));
        addView(avatarView, LayoutHelper.createFrame(40, 40, Gravity.START | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        addView(textColumn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.START, 58, 0, 72, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textColumn.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        previewView = new TextView(context);
        previewView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        previewView.setMaxLines(2);
        previewView.setEllipsize(TextUtils.TruncateAt.END);
        previewView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        previewView.setLineSpacing(dpf2(1.2f), 1f);
        textColumn.addView(previewView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        actionView = new TextView(context);
        actionView.setText("Открыть");
        actionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        actionView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        actionView.setTextColor(accentColor);
        actionView.setPadding(dp(10), dp(8), dp(10), dp(8));
        actionView.setBackground(Theme.createSelectorDrawable(ColorUtils.setAlphaComponent(accentColor, 0x28), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        addView(actionView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dpf2(1.6f));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dpf2(1.5f));
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(dpf2(2.4f));

        setOnClickListener(v -> {
            if (onOpen != null) {
                onOpen.run();
            }
        });
        actionView.setOnClickListener(v -> {
            if (onOpen != null) {
                onOpen.run();
            }
        });

        ensureLiquidGlass();
    }

    public BackupImageView getAvatarView() {
        return avatarView;
    }

    /**
     * Prefer an existing liquid-glass factory (Chat / Dialogs / MainTabs) so the card refracts real UI.
     * Falls back to the self-contained glass created in the constructor.
     *
     * @param sourceRoot fragment contentView used for blur sampling coordinates (may be null)
     */
    public void attachGlass(@Nullable BlurredBackgroundDrawableViewFactory factory, @Nullable View sourceRoot) {
        if (factory == null) {
            return;
        }
        glassSourceRoot = sourceRoot;
        // Popup lives on drawerLayoutContainer — outside fragment contentView ancestry,
        // so skip ViewPositionWatcher and sync offsets manually.
        BlurredBackgroundDrawable drawable = factory.create((View) null, buildGlassColorProvider());
        drawable.setRadius(dpf2(18));
        drawable.setThickness(dp(10));
        drawable.setIntensity(0.82f);
        glassBackground = drawable;
        useGlassDrawable = true;
        setBackground(glassBackground);
        glassPaint.setColor(0);
        syncGlassSourceOffset();
        invalidateGlass();
    }

    private void syncGlassSourceOffset() {
        if (glassBackground == null || getWidth() <= 0) {
            return;
        }
        final int[] popupLoc = new int[2];
        getLocationInWindow(popupLoc);
        if (glassSourceRoot != null && glassSourceRoot.isAttachedToWindow()) {
            final int[] rootLoc = new int[2];
            glassSourceRoot.getLocationInWindow(rootLoc);
            glassBackground.setSourceOffset(popupLoc[0] - rootLoc[0], popupLoc[1] - rootLoc[1]);
        } else {
            glassBackground.setSourceOffset(Math.max(0, popupLoc[0]), Math.max(0, popupLoc[1]));
        }
    }

    public void bind(CharSequence title, CharSequence preview, int alphaPercent, boolean pulseEnabled,
                     int avatarColor, int avatarColor2, Runnable open) {
        titleView.setText(title);
        previewView.setText(preview);
        onOpen = open;
        this.alphaPercent = Math.max(60, Math.min(100, alphaPercent));
        this.pulseEnabled = pulseEnabled;
        this.avatarColor = avatarColor != 0 ? avatarColor : this.avatarColor;
        this.avatarColor2 = avatarColor2 != 0 ? avatarColor2 : this.avatarColor2;

        accentColor = pickAccent(this.avatarColor, this.avatarColor2);
        applyColors();
        invalidate();
    }

    private static int pickAccent(int c1, int c2) {
        int accent = ColorUtils.blendARGB(c1, c2, 0.25f);
        float lum = (float) ColorUtils.calculateLuminance(accent);
        if (lum > 0.68f) {
            accent = ColorUtils.blendARGB(accent, 0xFF000000, 0.28f);
        } else if (lum < 0.18f) {
            accent = ColorUtils.blendARGB(accent, 0xFFFFFFFF, 0.35f);
        }
        return accent | 0xFF000000;
    }

    private int themeCardColor(boolean dark) {
        if (dark) {
            return 0xFF1C1C1E;
        }
        return Theme.getColor(Theme.key_windowBackgroundWhite);
    }

    private void applyColors() {
        final boolean dark = Theme.isCurrentThemeDark();
        final int cardBg = themeCardColor(dark);
        final float glassAlpha = Math.max(0.55f, Math.min(1f, alphaPercent / 100f)) * MglaGlassConfig.getGlassBackgroundAlpha(dark);
        final int glassFill = ColorUtils.setAlphaComponent(cardBg, Math.round(255 * glassAlpha));

        if (glassColorSource != null) {
            glassColorSource.setColor(ColorUtils.setAlphaComponent(cardBg, Math.round(255 * 0.55f)));
        }
        if (glassBackground != null) {
            glassBackground.updateColors();
            invalidateGlass();
        }

        if (!useGlassDrawable) {
            glassPaint.setColor(glassFill);
            glassPaint.setShader(null);
        } else {
            glassPaint.setColor(0);
        }

        strokePaint.setColor(ColorUtils.setAlphaComponent(accentColor, dark ? 0x55 : 0x40));
        ringPaint.setColor(ColorUtils.setAlphaComponent(0xFFFFFFFF, dark ? 0xBB : 0xDD));
        titleView.setTextColor(dark ? 0xFFF2F4F8 : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        previewView.setTextColor(dark ? 0xFFB0B8C6 : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        actionView.setTextColor(accentColor);
        actionView.setBackground(Theme.createSelectorDrawable(ColorUtils.setAlphaComponent(accentColor, 0x28), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        rebuildSheen(getHeight());
    }

    private BlurredBackgroundProvider buildGlassColorProvider() {
        glassColorProvider = new BlurredBackgroundProviderBuilder(null)
                .setBackgroundColor((r, isDark) -> {
                    final int cardBg = themeCardColor(isDark);
                    final float a = Math.max(0.55f, Math.min(1f, alphaPercent / 100f)) * MglaGlassConfig.getGlassBackgroundAlpha(isDark);
                    return ColorUtils.setAlphaComponent(cardBg, Math.round(255 * a));
                })
                .setStrokeColorTop((r, isDark) -> ColorUtils.setAlphaComponent(0xFFFFFFFF, isDark ? 0x2E : 0x3D))
                .setStrokeColorBottom((r, isDark) -> ColorUtils.setAlphaComponent(0xFFFFFFFF, isDark ? 0x14 : 0x1A))
                .setStrokeWidth(dpf2(0.6f), dpf2(0.45f))
                .setShadowColor((r, isDark) -> isDark ? 0x28000000 : 0x18000000)
                .setShadowLayer(dpf2(8), 0, dpf2(2.5f))
                .build();
        return glassColorProvider;
    }

    private void ensureLiquidGlass() {
        if (glassBackground != null) {
            return;
        }
        glassColorSource = new BlurredBackgroundSourceColor();
        glassColorSource.setColor(ColorUtils.setAlphaComponent(themeCardColor(Theme.isCurrentThemeDark()), 0x8C));

        BlurredBackgroundSource source = glassColorSource;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlurredBackgroundSourceRenderNode node = new BlurredBackgroundSourceRenderNode(glassColorSource);
            node.setUnderSource(glassColorSource);
            source = node;
        }

        BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(source);
        factory.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        glassBackground = factory.create(this, buildGlassColorProvider());
        glassBackground.setRadius(dpf2(18));
        glassBackground.setThickness(dp(10));
        glassBackground.setIntensity(0.82f);
        useGlassDrawable = glassBackground instanceof BlurredBackgroundDrawableRenderNode
                && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        if (useGlassDrawable || glassBackground != null) {
            setBackground(glassBackground);
            useGlassDrawable = true;
        }
    }

    private void invalidateGlass() {
        if (glassBackground instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) glassBackground).invalidateDisplayList();
        }
        invalidate();
    }

    public void showAnimated() {
        cancelRunning();
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setAlpha(0f);
        setTranslationY(-dp(28));
        setScaleX(0.94f);
        setScaleY(0.94f);
        runningShowHide = animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(380)
                .setInterpolator(new OvershootInterpolator(1.05f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setLayerType(LAYER_TYPE_NONE, null);
                        runningShowHide = null;
                        startPulse();
                    }
                });
        runningShowHide.start();
    }

    public void hideAnimated(Runnable after) {
        cancelRunning();
        setLayerType(LAYER_TYPE_HARDWARE, null);
        runningShowHide = animate()
                .alpha(0f)
                .translationY(-dp(16))
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setLayerType(LAYER_TYPE_NONE, null);
                        runningShowHide = null;
                        if (after != null) {
                            after.run();
                        }
                    }
                });
        runningShowHide.start();
    }

    private void startPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (!pulseEnabled) {
            accentPulse = 0.55f;
            invalidate();
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(0.08f, 1f);
        pulseAnimator.setDuration(620);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(a -> {
            accentPulse = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnimator.start();
    }

    private void cancelRunning() {
        if (runningShowHide != null) {
            runningShowHide.setListener(null);
            runningShowHide.cancel();
            runningShowHide = null;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            animatePress(0.97f);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            animatePress(1f);
        }
        return super.onTouchEvent(event);
    }

    private void animatePress(float target) {
        if (pressAnimator != null) {
            pressAnimator.cancel();
        }
        pressAnimator = ValueAnimator.ofFloat(pressScale, target);
        pressAnimator.setDuration(120);
        pressAnimator.addUpdateListener(a -> {
            pressScale = (float) a.getAnimatedValue();
            setScaleX(pressScale);
            setScaleY(pressScale);
        });
        pressAnimator.start();
    }

    private void rebuildSheen(int h) {
        if (h <= 0 || useGlassDrawable) {
            sheenPaint.setShader(null);
            return;
        }
        final int top = ColorUtils.setAlphaComponent(accentColor, 0x30);
        sheenPaint.setShader(new LinearGradient(
                0, 0, 0, h,
                new int[]{top, 0x00000000, 0x00000000},
                new float[]{0f, 0.42f, 1f},
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        final float inset = dpf2(0.5f);
        cardRect.set(inset, inset, w - inset, h - inset);
        rebuildSheen(h);
        syncGlassSourceOffset();
        invalidateGlass();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            syncGlassSourceOffset();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        final float r = dpf2(18);
        if (!useGlassDrawable) {
            canvas.drawRoundRect(cardRect, r, r, glassPaint);
            if (sheenPaint.getShader() != null) {
                canvas.drawRoundRect(cardRect, r, r, sheenPaint);
            }
            canvas.drawRoundRect(cardRect, r, r, strokePaint);
        }

        // Pulsing avatar-colored frame: soft halo + bright stroke.
        final float pulseBoost = accentPulse;
        accentPaint.setStrokeWidth(dpf2(7f));
        accentPaint.setColor(ColorUtils.setAlphaComponent(accentColor, Math.round(90 * pulseBoost)));
        canvas.drawRoundRect(cardRect, r, r, accentPaint);
        accentPaint.setStrokeWidth(dpf2(3.6f));
        accentPaint.setColor(ColorUtils.setAlphaComponent(accentColor, Math.round(160 * pulseBoost)));
        canvas.drawRoundRect(cardRect, r, r, accentPaint);
        accentPaint.setStrokeWidth(dpf2(2.2f));
        accentPaint.setColor(ColorUtils.setAlphaComponent(accentColor, Math.round(255 * pulseBoost)));
        canvas.drawRoundRect(cardRect, r, r, accentPaint);

        final float cx = getPaddingLeft() + dp(10) + dp(20);
        final float cy = getHeight() / 2f;
        ringPaint.setAlpha(0xCC);
        canvas.drawCircle(cx, cy, dp(21.5f), ringPaint);

        super.dispatchDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelRunning();
        if (pressAnimator != null) {
            pressAnimator.cancel();
            pressAnimator = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int maxW = MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(68), MeasureSpec.EXACTLY)
        );
    }
}
