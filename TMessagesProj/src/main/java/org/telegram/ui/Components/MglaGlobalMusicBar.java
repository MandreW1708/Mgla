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
import android.text.TextUtils;
import android.util.TypedValue;
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
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.MglaMusicPlayerSheet;

/**
 * Global glass music bar above the bottom island — Telegram + Yandex Music.
 * Soft underglow behind the glass + progress flush with the island rim.
 */
public class MglaGlobalMusicBar extends FrameLayout implements NotificationCenter.NotificationCenterDelegate, MglaYandexMusicPlayer.PlayerListener {

    /** Same outer height as tabs island (glass padding included). */
    public static final int BAR_HEIGHT = DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS;
    /** Extra space so underglow stays inside this view (no contentView clipChildren=false). */
    public static final int GLOW_OUTSET_DP = 18;

    private final Theme.ResourcesProvider resourcesProvider;
    private BlurredBackgroundDrawable glassBackground;
    private FrameLayout glassHost;

    private ImageView coverView;
    private TextView titleView;
    private TextView artistView;
    private ImageView playPauseBtn;

    private final Paint underglowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path perimeterPath = new Path();
    private final Path progressPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private final RectF glassRect = new RectF();
    private final RectF glowRect = new RectF();
    private final Rect glassPad = new Rect();

    private float progress;
    private float cornerRadius;
    private float glowPulse = 1f;
    private ValueAnimator pulseAnimator;
    private boolean observersRegistered;
    private VisibilityListener visibilityListener;

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
        if (was != visibility && visibilityListener != null) {
            visibilityListener.onMusicBarVisibilityChanged(visibility == VISIBLE);
        }
    }

    public MglaGlobalMusicBar(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
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

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(6), 0);

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
        }), LayoutHelper.createLinear(32, 32, 0, 0, 2, 0));

        // Inner host gets liquid glass as View background (same path as tabs island).
        glassHost = new FrameLayout(context);
        glassHost.setClipChildren(false);
        glassHost.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(glassHost, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL,
                GLOW_OUTSET_DP, GLOW_OUTSET_DP, GLOW_OUTSET_DP, GLOW_OUTSET_DP));

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
        if (factory == null || glassHost == null) {
            return;
        }
        // Same factory/provider as tabs island — includes liquid glass when LiteMode allows it.
        glassBackground = factory.create(glassHost, BlurredBackgroundProviderImpl.mainTabs(provider));
        glassBackground.setRadius(cornerRadius);
        glassBackground.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f));
        glassHost.setBackground(glassBackground);
    }

    public void updateGlassColors() {
        if (glassBackground != null) {
            glassBackground.updateColors();
        }
        if (glassHost != null) {
            glassHost.invalidate();
        }
        invalidate();
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

        setProgress(playing.audioProgress);
        if (!paused && !buffering) {
            startPulse();
        } else {
            stopPulse();
        }
        invalidate();
    }

    private void showYandex(MglaYandexMusicModel.Track track) {
        setVisibility(VISIBLE);
        titleView.setText(track.title);
        artistView.setText(track.artist);
        boolean playing = MglaYandexMusicPlayer.getInstance().isPlaying();
        playPauseBtn.setImageResource(playing ? R.drawable.ic_action_pause : R.drawable.ic_action_play);
        MglaMusicImageHelper.bindCircleCover(coverView, track.getCoverUrl(100), R.drawable.nocover_small);
        if (playing) {
            startPulse();
        } else {
            stopPulse();
        }
        invalidate();
    }

    private void setProgress(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        invalidate();
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
            invalidate();
        });
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        glowPulse = 1f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(BAR_HEIGHT + GLOW_OUTSET_DP * 2), MeasureSpec.EXACTLY));
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
        final int glow = dp(GLOW_OUTSET_DP);
        final float inset = glassInset();
        glassRect.set(
                glow + inset,
                glow + inset,
                getWidth() - glow - inset,
                getHeight() - glow - inset);
    }

    @Override
    public void draw(Canvas canvas) {
        syncGlassRect();
        drawSoftUnderglow(canvas);
        // Liquid glass is drawn as glassHost's background (like tabsView).
        super.draw(canvas);
        drawProgressRim(canvas);
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

        // Soft outer bloom — muted theme accent
        final int layers = 8;
        float maxSpread = dp(18) * glowPulse;
        for (int i = layers; i >= 1; i--) {
            float t = i / (float) layers;
            float spread = maxSpread * t;
            float alpha = (0.12f * (1f - t) * (1f - t)) * (0.55f + 0.45f * Math.min(1f, progress + 0.35f));
            underglowPaint.setColor(Color.argb(
                    Math.max(0, Math.min(255, (int) (alpha * 255))),
                    ar, ag, ab
            ));
            glowRect.set(glassRect);
            glowRect.inset(-spread, -spread);
            float rr = r + spread;
            canvas.drawRoundRect(glowRect, rr, rr, underglowPaint);
        }

        // Closer accent underglow — still muted vs timeline
        for (int i = 4; i >= 1; i--) {
            float t = i / 4f;
            float spread = dp(10) * t * glowPulse;
            float alpha = 0.10f * (1f - t) * (0.4f + 0.6f * progress);
            underglowPaint.setColor(Color.argb(
                    Math.max(0, Math.min(255, (int) (alpha * 255))),
                    ar, ag, ab
            ));
            glowRect.set(glassRect);
            glowRect.inset(-spread, -spread);
            canvas.drawRoundRect(glowRect, r + spread, r + spread, underglowPaint);
        }
    }

    private void drawProgressRim(Canvas canvas) {
        syncGlassRect();
        float r = Math.min(cornerRadius, Math.min(glassRect.width(), glassRect.height()) / 2f);
        if (r <= 0) return;

        perimeterPath.reset();
        perimeterPath.moveTo(glassRect.centerX(), glassRect.top);
        perimeterPath.arcTo(new RectF(glassRect.right - 2 * r, glassRect.top, glassRect.right, glassRect.top + 2 * r), -90, 90, false);
        perimeterPath.lineTo(glassRect.right, glassRect.bottom - r);
        perimeterPath.arcTo(new RectF(glassRect.right - 2 * r, glassRect.bottom - 2 * r, glassRect.right, glassRect.bottom), 0, 90, false);
        perimeterPath.lineTo(glassRect.left + r, glassRect.bottom);
        perimeterPath.arcTo(new RectF(glassRect.left, glassRect.bottom - 2 * r, glassRect.left + 2 * r, glassRect.bottom), 90, 90, false);
        perimeterPath.lineTo(glassRect.left, glassRect.top + r);
        perimeterPath.arcTo(new RectF(glassRect.left, glassRect.top, glassRect.left + 2 * r, glassRect.top + 2 * r), 180, 90, false);
        perimeterPath.lineTo(glassRect.centerX(), glassRect.top);

        if (progress <= 0.001f) {
            return;
        }
        pathMeasure.setPath(perimeterPath, false);
        float length = pathMeasure.getLength();
        if (length <= 0f) return;
        progressPath.reset();
        pathMeasure.getSegment(0, length * progress, progressPath, true);
        // Timeline: theme accent, brightened
        progressPaint.setColor(ColorUtils.blendARGB(themeAccent(), Color.WHITE, 0.28f));
        progressPaint.setAlpha(255);
        canvas.drawPath(progressPath, progressPaint);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        refresh();
    }

    @Override
    public void onTrackChanged(MglaYandexMusicModel.Track track) {
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
        setProgress(positionMs / (float) durationMs);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulse();
    }
}
