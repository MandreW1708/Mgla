package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MglaMusicImageHelper;
import org.telegram.messenger.MglaYandexMusicModel;
import org.telegram.messenger.MglaYandexMusicPlayback;
import org.telegram.messenger.MglaYandexMusicPlayer;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.MglaMusicPlayerSheet;

/**
 * Global glass music bar above the bottom island — Telegram + Yandex Music.
 * Glass lives on its own island view (same path as tabs); underglow/progress are siblings
 * so they never interfere with the liquid-glass RenderNode background.
 */
public class MglaGlobalMusicBar extends FrameLayout implements NotificationCenter.NotificationCenterDelegate, MglaYandexMusicPlayer.PlayerListener {

    /** Same outer height as tabs island (glass padding included). */
    public static final int BAR_HEIGHT = DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS;
    /** Extra space so underglow stays inside this view (no contentView clipChildren=false). */
    public static final int GLOW_OUTSET_DP = 18;
    /** Gap between music island and tabs glass (must match MainTabs layout). */
    public static final int TABS_GAP_DP = 2;

    private static boolean globallyVisible;

    /** Whether the global mini-player island is currently shown (for blur exclusion holes). */
    public static boolean isGloballyVisible() {
        return globallyVisible;
    }

    /** How much to extend the bottom blur exclusion upward while the mini-player is visible. */
    public static int blurExclusionExtraDp() {
        return globallyVisible ? BAR_HEIGHT + TABS_GAP_DP : 0;
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private BlurredBackgroundDrawable glassBackground;
    private FrameLayout glassIsland;
    private View underglowView;
    private View progressView;

    private ImageView coverView;
    private TextView titleView;
    private TextView artistView;
    private ImageView playPauseBtn;

    private final Paint underglowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path perimeterPath = new Path();
    private final Path progressPath = new Path();
    private final Path glassClipPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private final RectF glassRect = new RectF();
    private final RectF glowRect = new RectF();
    private final RectF arcRect = new RectF();
    private final Rect glassPad = new Rect();

    private float displayProgress;
    private long progressAnchorPosMs;
    private long progressAnchorDurMs;
    private long progressAnchorUptime;
    private boolean progressTicking;
    private float perimeterLength;
    private boolean perimeterDirty = true;
    private int underglowProgressBucket = -1;

    private float cornerRadius;
    private float glowPulse = 1f;
    private ValueAnimator pulseAnimator;
    private boolean observersRegistered;
    private VisibilityListener visibilityListener;

    private final Choreographer.FrameCallback progressFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!progressTicking) {
                return;
            }
            if (updateDisplayProgress()) {
                progressView.invalidate();
                // Underglow only needs coarse progress — avoid dual invalidates every frame.
                int bucket = (int) (displayProgress * 40f);
                if (bucket != underglowProgressBucket) {
                    underglowProgressBucket = bucket;
                    underglowView.invalidate();
                }
            }
            if (progressTicking) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public interface VisibilityListener {
        void onMusicBarVisibilityChanged(boolean visible);
    }

    public void setVisibilityListener(VisibilityListener listener) {
        visibilityListener = listener;
    }

    @Override
    public void setVisibility(int visibility) {
        final int was = getVisibility();
        super.setVisibility(visibility);
        globallyVisible = visibility == VISIBLE;
        if (was != visibility && visibilityListener != null) {
            visibilityListener.onMusicBarVisibilityChanged(visibility == VISIBLE);
        }
        if (visibility == VISIBLE) {
            invalidateGlass();
            if (isActivelyPlaying()) {
                startProgressTicker();
            }
        } else {
            stopProgressTicker();
        }
    }

    public MglaGlobalMusicBar(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setClipChildren(false);
        setClipToPadding(false);
        setClipToOutline(false);
        setVisibility(GONE);

        cornerRadius = dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f);

        underglowPaint.setStyle(Paint.Style.FILL);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(dp(2f));
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeJoin(Paint.Join.ROUND);

        underglowView = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                syncGlassRect();
                drawSoftUnderglow(canvas);
            }
        };
        underglowView.setWillNotDraw(false);
        addView(underglowView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(6), 0);

        coverView = new ImageView(context);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setShape(GradientDrawable.OVAL);
        coverBg.setColor(0x33FFFFFF);
        coverView.setBackground(coverBg);
        coverView.setClipToOutline(true);
        coverView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        row.addView(coverView, LayoutHelper.createLinear(40, 40, 4, 0, 10, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        artistView.setTextColor(0xB3FFFFFF);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        row.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        row.addView(createControl(context, R.drawable.ic_action_previous, v -> {
            if (isYandexActive()) {
                MglaYandexMusicPlayer.getInstance().prev();
            } else {
                MediaController.getInstance().playPreviousMessage();
            }
        }), LayoutHelper.createLinear(34, 34));

        playPauseBtn = createControl(context, R.drawable.ic_action_play, v -> {
            if (isYandexActive()) {
                MglaYandexMusicPlayer.getInstance().togglePlayPause();
            } else {
                MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                if (playing != null) {
                    if (MediaController.getInstance().isMessagePaused()) {
                        MediaController.getInstance().playMessage(playing);
                    } else {
                        MediaController.getInstance().pauseMessage(playing);
                    }
                }
            }
        });
        row.addView(playPauseBtn, LayoutHelper.createLinear(38, 38));

        row.addView(createControl(context, R.drawable.ic_action_next, v -> {
            if (isYandexActive()) {
                MglaYandexMusicPlayer.getInstance().next();
            } else {
                MediaController.getInstance().playNextMessage();
            }
        }), LayoutHelper.createLinear(34, 34));

        row.addView(createControl(context, R.drawable.msg_close, v -> {
            if (isYandexActive()) {
                MglaYandexMusicPlayer.getInstance().stop();
            } else {
                MediaController.getInstance().cleanupPlayer(true, true);
            }
            setVisibility(GONE);
            stopPulse();
            stopProgressTicker();
        }), LayoutHelper.createLinear(32, 32, 0, 0, 2, 0));

        // Liquid glass background on this view only — identical role to tabsView / searchButton.
        glassIsland = new FrameLayout(context);
        glassIsland.setClipChildren(false);
        glassIsland.setClipToPadding(false);
        glassIsland.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(glassIsland, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL,
                GLOW_OUTSET_DP, GLOW_OUTSET_DP, GLOW_OUTSET_DP, GLOW_OUTSET_DP));

        progressView = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                syncGlassRect();
                drawProgressRim(canvas);
            }
        };
        progressView.setWillNotDraw(false);
        progressView.setClickable(false);
        progressView.setFocusable(false);
        addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        setOnClickListener(v -> {
            if (isYandexActive()) {
                new MglaMusicPlayerSheet(getContext()).show();
            } else {
                MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                if (playing != null && playing.isMusic()) {
                    new AudioPlayerAlert(getContext(), resourcesProvider).show();
                }
            }
        });
    }

    private ImageView createControl(Context context, int res, OnClickListener listener) {
        ImageView iv = new ImageView(context);
        iv.setImageResource(res);
        iv.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        iv.setPadding(dp(6), dp(6), dp(6), dp(6));
        iv.setOnClickListener(listener);
        return iv;
    }

    public void attachGlass(BlurredBackgroundDrawableViewFactory factory, Theme.ResourcesProvider provider) {
        if (factory == null || glassIsland == null) {
            return;
        }
        glassBackground = factory.create(glassIsland, BlurredBackgroundProviderImpl.mainTabs(provider));
        glassBackground.setRadius(cornerRadius);
        glassBackground.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f));
        glassIsland.setBackground(glassBackground);
        invalidateGlass();
    }

    public void invalidateGlass() {
        if (glassBackground instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) glassBackground).invalidateDisplayList();
        }
        if (glassIsland != null) {
            glassIsland.invalidate();
        }
        if (underglowView != null) {
            underglowView.invalidate();
        }
        if (progressView != null) {
            progressView.invalidate();
        }
    }

    public void updateGlassColors() {
        if (glassBackground != null) {
            glassBackground.updateColors();
        }
        invalidateGlass();
    }

    public void onResume() {
        registerObservers();
        MglaYandexMusicPlayer.getInstance().addListener(this);
        refresh();
    }

    public void onPause() {
        unregisterObservers();
        MglaYandexMusicPlayer.getInstance().removeListener(this);
        stopPulse();
        stopProgressTicker();
    }

    private void registerObservers() {
        if (observersRegistered) return;
        observersRegistered = true;
        int account = UserConfig.selectedAccount;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
    }

    private void unregisterObservers() {
        if (!observersRegistered) return;
        observersRegistered = false;
        int account = UserConfig.selectedAccount;
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
    }

    private boolean isYandexActive() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        return playing != null && MglaYandexMusicPlayback.isYandexMessage(playing);
    }

    private boolean isActivelyPlaying() {
        if (isYandexActive()) {
            return MglaYandexMusicPlayer.getInstance().isPlaying()
                    && !MglaYandexMusicPlayer.getInstance().isBuffering();
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        return playing != null
                && !MediaController.getInstance().isMessagePaused()
                && !MediaController.getInstance().isDownloadingCurrentMessage();
    }

    public void refresh() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || playing.getId() == 0 || (!playing.isMusic() && !MglaYandexMusicPlayback.isYandexMessage(playing))) {
            MglaYandexMusicModel.Track yandex = MglaYandexMusicPlayer.getInstance().getCurrentTrack();
            if (yandex != null && (MglaYandexMusicPlayer.getInstance().isPlaying() || MglaYandexMusicPlayer.getInstance().isBuffering())) {
                showYandex(yandex);
                return;
            }
            setVisibility(GONE);
            stopPulse();
            stopProgressTicker();
            return;
        }

        setVisibility(VISIBLE);
        titleView.setText(playing.getMusicTitle());
        artistView.setText(playing.getMusicAuthor());

        boolean paused = MediaController.getInstance().isMessagePaused();
        boolean buffering = MediaController.getInstance().isDownloadingCurrentMessage();
        playPauseBtn.setImageResource(paused || buffering ? R.drawable.ic_action_play : R.drawable.ic_action_pause);

        if (MglaYandexMusicPlayback.isYandexMessage(playing)) {
            MglaYandexMusicModel.Track track = MglaYandexMusicPlayback.getTrack(playing);
            if (track != null) {
                MglaMusicImageHelper.bindCircleCover(coverView, track.getCoverUrl(100), R.drawable.nocover_small);
            } else {
                coverView.setImageResource(R.drawable.nocover_small);
            }
        } else {
            String art = playing.getArtworkUrl(true);
            if (!TextUtils.isEmpty(art)) {
                MglaMusicImageHelper.bindCircleCover(coverView, art, R.drawable.nocover_small);
            } else {
                coverView.setImageResource(R.drawable.nocover_small);
            }
        }

        syncProgressFromPlayer(playing.audioProgress, resolveDurationMs(playing), !paused && !buffering, false);

        if (!paused && !buffering) {
            startPulse();
        } else {
            stopPulse();
        }
    }

    private void showYandex(MglaYandexMusicModel.Track track) {
        setVisibility(VISIBLE);
        titleView.setText(track.title);
        artistView.setText(track.artist);
        boolean playing = MglaYandexMusicPlayer.getInstance().isPlaying();
        playPauseBtn.setImageResource(playing ? R.drawable.ic_action_pause : R.drawable.ic_action_play);
        MglaMusicImageHelper.bindCircleCover(coverView, track.getCoverUrl(100), R.drawable.nocover_small);
        long durationMs = Math.max(0L, MglaYandexMusicPlayer.getInstance().getDuration());
        float progress = durationMs > 0
                ? Math.max(0f, Math.min(1f, MglaYandexMusicPlayer.getInstance().getCurrentPosition() / (float) durationMs))
                : 0f;
        syncProgressFromPlayer(progress, durationMs, playing, false);
        if (playing) {
            startPulse();
        } else {
            stopPulse();
        }
    }

    private static long resolveDurationMs(MessageObject playing) {
        if (playing == null) {
            return 0L;
        }
        if (playing.audioPlayerDuration > 0) {
            return playing.audioPlayerDuration * 1000L;
        }
        double sec = playing.getDuration();
        if (sec > 0) {
            return (long) (sec * 1000L);
        }
        return 0L;
    }

    /**
     * Soft-sync timeline clock to player. While playing, ignore small backward corrections
     * so sparse/stale callbacks cannot yank the rim backwards (flicker).
     */
    private void syncProgressFromPlayer(float progress01, long durationMs, boolean running, boolean force) {
        durationMs = Math.max(0L, durationMs);
        progress01 = Math.max(0f, Math.min(1f, progress01));
        long positionMs = durationMs > 0 ? (long) (progress01 * durationMs) : 0L;
        long now = SystemClock.uptimeMillis();

        if (durationMs > 0) {
            progressAnchorDurMs = durationMs;
        }

        long extrapolated = progressAnchorPosMs;
        if (progressTicking && progressAnchorDurMs > 0) {
            extrapolated += Math.max(0L, now - progressAnchorUptime);
        }

        long delta = positionMs - extrapolated;
        boolean seekLike = force || Math.abs(delta) > 450;

        if (!running || getVisibility() != VISIBLE) {
            progressAnchorPosMs = positionMs;
            progressAnchorUptime = now;
            snapDisplayProgress(positionMs);
            stopProgressTicker();
            progressView.invalidate();
            underglowView.invalidate();
            return;
        }

        if (seekLike || !progressTicking) {
            progressAnchorPosMs = positionMs;
            progressAnchorUptime = now;
            snapDisplayProgress(positionMs);
        } else if (delta > 30) {
            // Player ahead of our clock — catch up without visible snap-back.
            progressAnchorPosMs = positionMs;
            progressAnchorUptime = now;
        }
        // delta <= 0 and small: ignore (prevents 1s / stale-ms jerks)

        startProgressTicker();
        progressView.invalidate();
    }

    private void snapDisplayProgress(long positionMs) {
        if (progressAnchorDurMs <= 0L) {
            displayProgress = 0f;
        } else {
            displayProgress = Math.max(0f, Math.min(1f, positionMs / (float) progressAnchorDurMs));
        }
    }

    private boolean updateDisplayProgress() {
        float next;
        if (progressAnchorDurMs <= 0L) {
            next = 0f;
        } else {
            long pos = progressAnchorPosMs;
            if (isActivelyPlaying()) {
                pos += Math.max(0L, SystemClock.uptimeMillis() - progressAnchorUptime);
            }
            next = Math.max(0f, Math.min(1f, pos / (float) progressAnchorDurMs));
        }
        if (Math.abs(next - displayProgress) < 0.00002f) {
            return false;
        }
        // Never move backwards while actively playing (seeks use snapDisplayProgress).
        if (isActivelyPlaying() && next + 0.0005f < displayProgress) {
            return false;
        }
        displayProgress = next;
        return true;
    }

    private void startProgressTicker() {
        if (progressTicking) {
            return;
        }
        progressTicking = true;
        Choreographer.getInstance().postFrameCallback(progressFrameCallback);
    }

    private void stopProgressTicker() {
        if (!progressTicking) {
            return;
        }
        progressTicking = false;
        Choreographer.getInstance().removeFrameCallback(progressFrameCallback);
    }

    private void startPulse() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(0.85f, 1.15f);
        pulseAnimator.setDuration(2200);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(a -> {
            glowPulse = (float) a.getAnimatedValue();
            underglowView.invalidate();
        });
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        glowPulse = 1f;
        if (underglowView != null) {
            underglowView.invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(BAR_HEIGHT + GLOW_OUTSET_DP * 2), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            invalidateGlass();
        }
    }

    private float glassInset() {
        if (glassBackground != null) {
            glassBackground.getPadding(glassPad);
            if (glassPad.left > 0) {
                return glassPad.left;
            }
        }
        return dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f);
    }

    private void syncGlassRect() {
        if (glassIsland == null || glassIsland.getWidth() <= 0) {
            if (!glassRect.isEmpty()) {
                glassRect.setEmpty();
                perimeterDirty = true;
            }
            return;
        }
        final float inset = glassInset();
        float l = glassIsland.getLeft() + inset;
        float t = glassIsland.getTop() + inset;
        float r = glassIsland.getRight() - inset;
        float b = glassIsland.getBottom() - inset;
        if (Math.abs(glassRect.left - l) > 0.5f || Math.abs(glassRect.top - t) > 0.5f
                || Math.abs(glassRect.right - r) > 0.5f || Math.abs(glassRect.bottom - b) > 0.5f) {
            glassRect.set(l, t, r, b);
            perimeterDirty = true;
        }
    }

    private int themeAccent() {
        return Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
    }

    private void drawSoftUnderglow(Canvas canvas) {
        if (glassRect.width() <= 0 || glassRect.height() <= 0) {
            return;
        }
        float r = Math.min(cornerRadius, Math.min(glassRect.width(), glassRect.height()) / 2f);
        final int accent = themeAccent();
        final int ar = Color.red(accent);
        final int ag = Color.green(accent);
        final int ab = Color.blue(accent);

        canvas.save();
        glassClipPath.reset();
        glassClipPath.addRoundRect(glassRect, r, r, Path.Direction.CW);
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipOutPath(glassClipPath);
        }

        final int layers = 8;
        float maxSpread = dp(18) * glowPulse;
        for (int i = layers; i >= 1; i--) {
            float t = i / (float) layers;
            float spread = maxSpread * t;
            float alpha = (0.12f * (1f - t) * (1f - t)) * (0.55f + 0.45f * Math.min(1f, displayProgress + 0.35f));
            underglowPaint.setColor(Color.argb(
                    Math.max(0, Math.min(255, (int) (alpha * 255))),
                    ar, ag, ab
            ));
            glowRect.set(glassRect);
            glowRect.inset(-spread, -spread);
            canvas.drawRoundRect(glowRect, r + spread, r + spread, underglowPaint);
        }

        for (int i = 4; i >= 1; i--) {
            float t = i / 4f;
            float spread = dp(10) * t * glowPulse;
            float alpha = 0.10f * (1f - t) * (0.4f + 0.6f * displayProgress);
            underglowPaint.setColor(Color.argb(
                    Math.max(0, Math.min(255, (int) (alpha * 255))),
                    ar, ag, ab
            ));
            glowRect.set(glassRect);
            glowRect.inset(-spread, -spread);
            canvas.drawRoundRect(glowRect, r + spread, r + spread, underglowPaint);
        }
        canvas.restore();
    }

    private void ensurePerimeterPath() {
        if (!perimeterDirty) {
            return;
        }
        perimeterDirty = false;
        perimeterPath.reset();
        perimeterLength = 0f;
        if (glassRect.width() <= 0 || glassRect.height() <= 0) {
            return;
        }
        float r = Math.min(cornerRadius, Math.min(glassRect.width(), glassRect.height()) / 2f);
        if (r <= 0) {
            return;
        }
        perimeterPath.moveTo(glassRect.centerX(), glassRect.top);
        arcRect.set(glassRect.right - 2 * r, glassRect.top, glassRect.right, glassRect.top + 2 * r);
        perimeterPath.arcTo(arcRect, -90, 90, false);
        perimeterPath.lineTo(glassRect.right, glassRect.bottom - r);
        arcRect.set(glassRect.right - 2 * r, glassRect.bottom - 2 * r, glassRect.right, glassRect.bottom);
        perimeterPath.arcTo(arcRect, 0, 90, false);
        perimeterPath.lineTo(glassRect.left + r, glassRect.bottom);
        arcRect.set(glassRect.left, glassRect.bottom - 2 * r, glassRect.left + 2 * r, glassRect.bottom);
        perimeterPath.arcTo(arcRect, 90, 90, false);
        perimeterPath.lineTo(glassRect.left, glassRect.top + r);
        arcRect.set(glassRect.left, glassRect.top, glassRect.left + 2 * r, glassRect.top + 2 * r);
        perimeterPath.arcTo(arcRect, 180, 90, false);
        perimeterPath.lineTo(glassRect.centerX(), glassRect.top);
        pathMeasure.setPath(perimeterPath, false);
        perimeterLength = pathMeasure.getLength();
    }

    private void drawProgressRim(Canvas canvas) {
        if (glassRect.width() <= 0 || glassRect.height() <= 0 || displayProgress <= 0.001f) {
            return;
        }
        ensurePerimeterPath();
        if (perimeterLength <= 0f) {
            return;
        }
        progressPath.reset();
        pathMeasure.getSegment(0, perimeterLength * displayProgress, progressPath, true);
        progressPaint.setColor(ColorUtils.blendARGB(themeAccent(), Color.WHITE, 0.28f));
        progressPaint.setAlpha(255);
        canvas.drawPath(progressPath, progressPaint);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            if (getVisibility() != VISIBLE) {
                return;
            }
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing == null) {
                return;
            }
            float progress = playing.audioProgress;
            if (args != null && args.length > 1 && args[1] instanceof Float) {
                progress = (Float) args[1];
            }
            syncProgressFromPlayer(progress, resolveDurationMs(playing), isActivelyPlaying(), false);
            return;
        }
        if (id == NotificationCenter.messagePlayingDidStart) {
            // New track / seek-to-start — hard reset timeline.
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null) {
                syncProgressFromPlayer(playing.audioProgress, resolveDurationMs(playing), isActivelyPlaying(), true);
            }
            refresh();
            return;
        }
        refresh();
    }

    @Override
    public void onTrackChanged(MglaYandexMusicModel.Track track) {
        displayProgress = 0f;
        progressAnchorPosMs = 0L;
        progressAnchorUptime = SystemClock.uptimeMillis();
        refresh();
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying, boolean isBuffering) {
        refresh();
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
        if (getVisibility() != VISIBLE || durationMs <= 0) {
            return;
        }
        syncProgressFromPlayer(positionMs / (float) durationMs, durationMs, isActivelyPlaying(), false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulse();
        stopProgressTicker();
    }
}
