package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.cast.framework.CastContext;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileRefController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.MglaMusicImageHelper;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.messenger.MglaYandexMusicApi;
import org.telegram.messenger.MglaYandexMusicModel;
import org.telegram.messenger.MglaYandexMusicPlayback;
import org.telegram.messenger.MglaYandexMusicPlayer;
import org.telegram.messenger.MglaYandexMusicStore;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.chromecast.ChromecastController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.AudioPlayerCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CastMediaRouteButton;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.AnimationProperties;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaGlobalMusicBar;
import org.telegram.ui.Components.MglaMd3SeekBar;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Stories.recorder.DominantColors;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Fullscreen Material Design 3 player sheet with dynamic ambient background.
 * Used for Yandex Music, Telegram music, voice and round video.
 */
public class MglaMusicPlayerSheet extends BottomSheet implements MglaYandexMusicPlayer.PlayerListener, NotificationCenter.NotificationCenterDelegate {

    private static final int ACCENT_FALLBACK = 0xFFE8DEF8; // MD3 primary-container-ish
    private static final int ON_ACCENT = 0xFF1D1B20;
    private static final int PILL_BG = 0x28FFFFFF;
    private static final int SURFACE_CONTAINER = 0x22FFFFFF;

    private final MglaYandexMusicPlayer player;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final AvatarDrawable coverAvatarDrawable = new AvatarDrawable();

    private AmbientBackgroundView ambientBg;
    private FrameLayout coverWrap;
    private ImageView coverImage;
    private BackupImageView avatarCoverView;
    private ImageView artistAvatar;
    private TextView titleView;
    private TextView artistView;
    private TextView sourceTitle;
    private ImageView likeButton;
    private ImageView dislikeButton;
    private MglaMd3SeekBar seekBar;
    private TextView timeCurrentView;
    private TextView timeTotalView;
    private FrameLayout playPauseFab;
    private GradientDrawable playPauseFabBg;
    private ImageView playPauseIcon;
    private ImageView shufflePillIcon;
    private ImageView repeatPillIcon;
    private TextView lyricsPillLabel;
    private TextView waveIslandText;
    private FrameLayout root;
    private LinearLayout contentLayout;
    private View shufflePill;
    private View lyricsPill;
    private View repeatPill;
    private View pillsRow;
    private View lyricsScrim;
    private NestedScrollView lyricsScroll;
    private LinearLayout lyricsLinesCol;
    private boolean lyricsVisible;
    private String lyricsRequestTrackId;
    private String lyricsBoundTrackId;
    private final HashSet<String> lyricsInflight = new HashSet<>();
    private MglaYandexMusicApi.LyricsData activeLyrics;
    private int activeLyricsLine = -1;
    private boolean lyricsUserScrolling;
    private long lyricsUserScrollUntil;
    private ValueAnimator lyricsHighlightAnimator;
    private ValueAnimator lyricsScrollAnimator;
    private final HashMap<String, MglaYandexMusicApi.LyricsData> lyricsCache = new HashMap<>();
    private final HashSet<String> lyricsMissing = new HashSet<>();
    private final Runnable lyricsResumeAutoScroll = () -> lyricsUserScrolling = false;
    private View telegramControlsSpacer;
    private View waveIsland;
    private ImageView prevBtn;
    private ImageView nextBtn;
    private ImageView shareBtn;
    private ImageView moreBtn;
    private ImageView queueBtn;
    private FrameLayout telegramRepeatBtn;
    private ImageView telegramRepeatIcon;
    private TextView addToProfileBtn;
    private CastMediaRouteButton castRouteButton;
    private boolean castAvailable;

    private int coverSize;
    private float coverRadius;
    private boolean isUserSeeking;
    private final Runnable clearUserSeekingRunnable = () -> isUserSeeking = false;
    private String lastCoverUrl;
    private boolean observersRegistered;
    private Runnable pendingAfterDismiss;
    private ValueAnimator coverPulseAnimator;
    private ValueAnimator fabColorAnimator;
    private int currentAccent = ACCENT_FALLBACK;
    private boolean openAnimationPlayed;
    private CharSequence lastTitleShown;
    private CharSequence lastArtistShown;

    /** Morph expand/collapse from the mini-player island. */
    private final RectF morphFrom = new RectF();
    private final RectF morphTo = new RectF();
    private final RectF morphCurrent = new RectF();
    private final RectF morphCloseStart = new RectF();
    private final RectF savedMorphFrom = new RectF();
    private final Path morphClipPath = new Path();
    private float morphFromRadius;
    private float savedMorphFromRadius;
    private float morphProgress = 1f;
    private boolean morphActive;
    private boolean usedMorphOpen;
    private boolean hasSavedMorphFrom;
    private boolean morphCloseStarted;
    private boolean morphCloseFinishing;
    /** Close morph lerps morphCloseStart → morphFrom (island) instead of full screen → island. */
    private boolean morphCloseFromSwipe;
    private boolean sheetCleanupDone;

    public MglaMusicPlayerSheet(Context context) {
        super(context, false);
        this.player = MglaYandexMusicPlayer.getInstance();
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        setCanDismissWithSwipe(true);
        setOpenNoDelay(true);
        openDuration = 520;
        // Edge-to-edge sheet without FLAG_FULLSCREEN so the status bar stays visible.
        fullWidth = true;
        occupyNavigationBar = true;
        useBackgroundTopPadding = false;
        // Keep system nav bar transparent (same as MainTabs glass UI) — do not paint a solid strip.
        drawNavigationBar = false;
        navBarColorKey = -1;
        navBarColor = Color.TRANSPARENT;
        // Square full-screen background (no rounded top strip).
        shadowDrawable = new ColorDrawable(Color.BLACK);
        backgroundPaddingLeft = 0;
        backgroundPaddingTop = 0;
        if (containerView != null) {
            containerView.setBackgroundDrawable(shadowDrawable);
            containerView.setPadding(0, 0, 0, 0);
        }
        initView(context);
        player.addListener(this);
        registerObservers();
        refreshUi();
    }

    private boolean isYandexMode() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null) {
            return MglaYandexMusicPlayback.isYandexMessage(playing);
        }
        return player.getCurrentTrack() != null;
    }

    private void initView(Context context) {
        int fullH = AndroidUtilities.displaySize.y + AndroidUtilities.statusBarHeight;
        int padH = dp(20);
        int bottomPad = dp(16) + AndroidUtilities.navigationBarHeight;

        // Clip in draw() — containerView has hasOverlappingRendering=false so clipToOutline is a no-op.
        root = new FrameLayout(context) {
            @Override
            public void draw(Canvas canvas) {
                if (morphActive) {
                    morphClipPath.reset();
                    float r = Math.max(0f, morphFromRadius * (1f - morphProgress));
                    morphClipPath.addRoundRect(morphCurrent, r, r, Path.Direction.CW);
                    canvas.save();
                    canvas.clipPath(morphClipPath);
                    super.draw(canvas);
                    canvas.restore();
                } else {
                    super.draw(canvas);
                }
            }
        };
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                Math.max(fullH, dp(640))
        ));

        ambientBg = new AmbientBackgroundView(context);
        root.addView(ambientBg, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(padH, AndroidUtilities.statusBarHeight + dp(10), padH, bottomPad);

        // Header
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView collapseBtn = iconButton(context, R.drawable.ic_arrow_drop_down, v -> dismiss());
        header.addView(collapseBtn, LayoutHelper.createLinear(44, 44));

        sourceTitle = new TextView(context);
        sourceTitle.setText("Моя волна");
        sourceTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sourceTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        sourceTitle.setTextColor(0xE6FFFFFF);
        sourceTitle.setGravity(Gravity.CENTER);
        sourceTitle.setSingleLine(true);
        sourceTitle.setLetterSpacing(0.02f);
        sourceTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(sourceTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        queueBtn = iconButton(context, R.drawable.msg_list, v -> openQueue());
        header.addView(queueBtn, LayoutHelper.createLinear(44, 44));
        contentLayout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // Compact rounded 1:1 square; optional slightly larger via «Большая обложка»
        boolean largeCover = MglaPlayerConfig.isLargeCoverEnabled();
        coverSize = largeCover
                ? Math.min(dp(200), (int) (AndroidUtilities.displaySize.x * 0.52f))
                : Math.min(dp(148), (int) (AndroidUtilities.displaySize.x * 0.38f));
        coverRadius = dp(largeCover ? 22 : 18);
        coverWrap = new FrameLayout(context) {
            private final Path clipPath = new Path();
            private final RectF clipRect = new RectF();

            @Override
            protected void dispatchDraw(Canvas canvas) {
                clipRect.set(0, 0, getWidth(), getHeight());
                clipPath.reset();
                clipPath.addRoundRect(clipRect, coverRadius, coverRadius, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clipPath);
                super.dispatchDraw(canvas);
                canvas.restore();
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            coverWrap.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), coverRadius);
                }
            });
            coverWrap.setClipToOutline(true);
            coverWrap.setElevation(dp(4));
        }
        coverImage = new ImageView(context);
        coverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverImage.setAdjustViewBounds(false);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(coverRadius);
        coverBg.setColor(0xFF2A2A2A);
        coverImage.setBackground(coverBg);
        coverWrap.setBackground(coverBg);
        coverWrap.addView(coverImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        avatarCoverView = new BackupImageView(context);
        avatarCoverView.setVisibility(View.GONE);
        avatarCoverView.setRoundRadius((int) coverRadius);
        coverWrap.addView(avatarCoverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Lyrics overlay: no solid panel — ambient player background shows through the cover window.
        lyricsScrim = new View(context);
        lyricsScrim.setBackgroundColor(Color.TRANSPARENT);
        lyricsScrim.setVisibility(View.GONE);
        coverWrap.addView(lyricsScrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        lyricsScroll = new NestedScrollView(context) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN || ev.getAction() == MotionEvent.ACTION_MOVE) {
                    ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN || ev.getAction() == MotionEvent.ACTION_MOVE) {
                    ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    markLyricsUserScrolling();
                }
                return super.onTouchEvent(ev);
            }
        };
        lyricsScroll.setFillViewport(true);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        lyricsScroll.setBackgroundColor(Color.TRANSPARENT);
        lyricsScroll.setVisibility(View.GONE);
        lyricsScroll.setOnScrollChangeListener((NestedScrollView v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) -> {
            if (Math.abs(scrollY - oldScrollY) > 2) {
                markLyricsUserScrolling();
            }
        });
        lyricsLinesCol = new LinearLayout(context);
        lyricsLinesCol.setOrientation(LinearLayout.VERTICAL);
        lyricsLinesCol.setGravity(Gravity.CENTER_HORIZONTAL);
        lyricsLinesCol.setBackgroundColor(Color.TRANSPARENT);
        // Side inset keeps wrapped lines inside the rounded cover clip.
        lyricsLinesCol.setPadding(dp(12), dp(18), dp(12), dp(22));
        // Exact width (coverSize) — NestedScrollView otherwise measures child UNSPECIFIED
        // and long centered lines draw past the left/right edges of the window.
        lyricsScroll.addView(lyricsLinesCol, new FrameLayout.LayoutParams(coverSize, ViewGroup.LayoutParams.WRAP_CONTENT));
        coverWrap.addView(lyricsScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        coverWrap.setClipChildren(true);

        contentLayout.addView(coverWrap, LayoutHelper.createLinear(coverSize, coverSize, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        // Track info row
        LinearLayout infoRow = new LinearLayout(context);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);

        artistAvatar = new ImageView(context);
        artistAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setShape(GradientDrawable.OVAL);
        avBg.setColor(SURFACE_CONTAINER);
        artistAvatar.setBackground(avBg);
        artistAvatar.setClipToOutline(true);
        artistAvatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        infoRow.addView(artistAvatar, LayoutHelper.createLinear(40, 40, 0, 0, 12, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        artistView.setTextColor(0xCCFFFFFF);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        infoRow.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        shareBtn = iconButton(context, R.drawable.msg_share, v -> openForward());
        infoRow.addView(shareBtn, LayoutHelper.createLinear(40, 40));
        moreBtn = iconButton(context, R.drawable.ic_ab_other, v -> showTelegramMusicOptions());
        infoRow.addView(moreBtn, LayoutHelper.createLinear(40, 40));
        contentLayout.addView(infoRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // MD3 animated timeline
        seekBar = new MglaMd3SeekBar(context);
        seekBar.setColors(Color.WHITE, 0x47FFFFFF);
        seekBar.setListener(new MglaMd3SeekBar.Listener() {
            @Override
            public void onProgressChanged(float progress, boolean fromUser) {
                if (fromUser) {
                    long pos = (long) (getActiveDurationMs() * progress);
                    timeCurrentView.setText(formatTime(pos));
                    lyricsUserScrolling = false;
                    updateSyncedLyrics(pos);
                }
            }

            @Override
            public void onStartTracking() {
                AndroidUtilities.cancelRunOnUIThread(clearUserSeekingRunnable);
                isUserSeeking = true;
            }

            @Override
            public void onStopTracking(float progress) {
                long dur = getActiveDurationMs();
                // Keep the thumb on the tapped/dragged position; ignore stale player ticks until seek settles.
                if (seekBar != null) {
                    seekBar.setProgress(progress, false);
                }
                if (dur > 0) {
                    long pos = (long) (dur * progress);
                    timeCurrentView.setText(formatTime(pos));
                    seekActiveTo(pos);
                    lyricsUserScrolling = false;
                    updateSyncedLyrics(pos);
                }
                AndroidUtilities.cancelRunOnUIThread(clearUserSeekingRunnable);
                AndroidUtilities.runOnUIThread(clearUserSeekingRunnable, 400);
            }
        });
        contentLayout.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 0, 0, 0));

        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeCurrentView = timeLabel(context, "0:00");
        timeTotalView = timeLabel(context, "0:00");
        timeRow.addView(timeCurrentView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        timeRow.addView(new View(context), LayoutHelper.createLinear(0, 0, 1f));
        timeRow.addView(timeTotalView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        contentLayout.addView(timeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 0, 2, 18));

        // Extra space above controls only in Telegram mode (pills row is hidden there).
        telegramControlsSpacer = new View(context);
        contentLayout.addView(telegramControlsSpacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0));

        // Main controls
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        dislikeButton = iconButton(context, R.drawable.mgla_media_dislike, v -> {
            if (!isYandexMode()) {
                return;
            }
            bouncePress(dislikeButton);
            player.dislikeCurrentTrack();
            updateLikeButton();
        });
        controls.addView(dislikeButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER));

        prevBtn = iconButton(context, R.drawable.ic_action_previous, v -> {
            bouncePress(prevBtn);
            skipPrevious();
        });
        controls.addView(prevBtn, LayoutHelper.createLinear(52, 52, Gravity.CENTER, 4, 0, 10, 0));

        controls.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));

        playPauseFab = new FrameLayout(context);
        playPauseFabBg = new GradientDrawable();
        playPauseFabBg.setShape(GradientDrawable.OVAL);
        playPauseFabBg.setColor(currentAccent);
        playPauseFab.setBackground(playPauseFabBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            playPauseFab.setElevation(dp(6));
            playPauseFab.setStateListAnimator(null);
        }
        playPauseIcon = new ImageView(context);
        playPauseIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        playPauseIcon.setColorFilter(new PorterDuffColorFilter(ON_ACCENT, PorterDuff.Mode.SRC_IN));
        playPauseFab.addView(playPauseIcon, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        playPauseFab.setOnClickListener(v -> {
            if (MglaPlayerConfig.isButtonAnimationsEnabled()) {
                playPauseFab.animate().cancel();
                playPauseFab.animate()
                        .scaleX(0.88f).scaleY(0.88f)
                        .setDuration(MglaPlayerConfig.scaleDuration(90))
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                        .withEndAction(() -> {
                            togglePlayPause();
                            playPauseFab.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .setDuration(MglaPlayerConfig.scaleDuration(220))
                                    .setInterpolator(new OvershootInterpolator(1.6f))
                                    .start();
                        }).start();
            } else {
                togglePlayPause();
            }
        });
        controls.addView(playPauseFab, LayoutHelper.createLinear(84, 84, Gravity.CENTER));

        controls.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));

        nextBtn = iconButton(context, R.drawable.ic_action_next, v -> {
            bouncePress(nextBtn);
            skipNext();
        });
        controls.addView(nextBtn, LayoutHelper.createLinear(52, 52, Gravity.CENTER, 10, 0, 4, 0));

        likeButton = iconButton(context, R.drawable.media_like, v -> {
            if (!isYandexMode()) {
                return;
            }
            player.toggleLikeCurrentTrack();
            updateLikeButton();
            if (MglaPlayerConfig.isButtonAnimationsEnabled()) {
                likeButton.animate().cancel();
                likeButton.animate().scaleX(1.25f).scaleY(1.25f)
                        .setDuration(MglaPlayerConfig.scaleDuration(120))
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                        .withEndAction(() -> likeButton.animate().scaleX(1f).scaleY(1f)
                                .setDuration(MglaPlayerConfig.scaleDuration(180))
                                .setInterpolator(new OvershootInterpolator(2f))
                                .start())
                        .start();
            }
        });
        controls.addView(likeButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER));
        contentLayout.addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        castRouteButton = new CastMediaRouteButton(context) {
            @Override
            public void stateUpdated(boolean connected) {
            }
        };
        castAvailable = true;
        try {
            castRouteButton.setRouteSelector(CastContext.getSharedInstance(context).getMergedSelector());
        } catch (Throwable e) {
            FileLog.e(e);
            castAvailable = false;
        }
        castRouteButton.setVisibility(View.INVISIBLE);
        root.addView(castRouteButton, LayoutHelper.createFrame(1, 1, Gravity.LEFT | Gravity.TOP));

        // Pill row (Yandex only — hidden for Telegram media)
        LinearLayout pills = new LinearLayout(context);
        pillsRow = pills;
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER);

        FrameLayout shufflePillFrame = makePill(context);
        shufflePill = shufflePillFrame;
        shufflePillIcon = new ImageView(context);
        shufflePillIcon.setImageResource(R.drawable.player_new_shuffle);
        shufflePillIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        shufflePillFrame.addView(shufflePillIcon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        shufflePillFrame.setOnClickListener(v -> {
            if (!isYandexMode()) {
                return;
            }
            player.toggleShuffle();
            updateControlsState();
        });
        pills.addView(shufflePillFrame, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 8, 0));

        FrameLayout lyricsPillFrame = makePill(context);
        lyricsPill = lyricsPillFrame;
        lyricsPillLabel = new TextView(context);
        lyricsPillLabel.setText("Текст");
        lyricsPillLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        lyricsPillLabel.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        lyricsPillLabel.setTextColor(Color.WHITE);
        lyricsPillLabel.setGravity(Gravity.CENTER);
        lyricsPillFrame.addView(lyricsPillLabel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        lyricsPillFrame.setOnClickListener(v -> onLyricsPillClicked());
        pills.addView(lyricsPillFrame, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 8, 0));

        FrameLayout repeatPillFrame = makePill(context);
        repeatPill = repeatPillFrame;
        repeatPillIcon = new ImageView(context);
        repeatPillIcon.setImageResource(R.drawable.player_new_repeatall);
        repeatPillIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        repeatPillFrame.addView(repeatPillIcon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        repeatPillFrame.setOnClickListener(v -> {
            if (!isYandexMode()) {
                return;
            }
            player.toggleRepeat();
            updateControlsState();
        });
        pills.addView(repeatPillFrame, LayoutHelper.createLinear(0, 48, 1f));
        contentLayout.addView(pills, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        contentLayout.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // Telegram music: full-width repeat at bottom; Add to profile sits under it when shown.
        telegramRepeatBtn = makePill(context);
        telegramRepeatBtn.setVisibility(View.GONE);
        telegramRepeatIcon = new ImageView(context);
        telegramRepeatIcon.setImageResource(R.drawable.player_new_repeatall);
        telegramRepeatIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        telegramRepeatBtn.addView(telegramRepeatIcon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        telegramRepeatBtn.setOnClickListener(v -> showTelegramRepeatMenu());
        contentLayout.addView(telegramRepeatBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 0, 0, 8));

        addToProfileBtn = new TextView(context);
        addToProfileBtn.setVisibility(View.GONE);
        addToProfileBtn.setText(LocaleController.getString(R.string.AudioAddToProfile));
        addToProfileBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        addToProfileBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        addToProfileBtn.setTextColor(ON_ACCENT);
        addToProfileBtn.setGravity(Gravity.CENTER);
        GradientDrawable addBg = new GradientDrawable();
        addBg.setCornerRadius(dp(26));
        addBg.setColor(currentAccent);
        addToProfileBtn.setBackground(addBg);
        addToProfileBtn.setPadding(dp(16), 0, dp(16), 0);
        addToProfileBtn.setOnClickListener(v -> savePlayingToProfile(true));
        contentLayout.addView(addToProfileBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 0, 0, 0));

        // Bottom wave island (Yandex)
        waveIsland = new FrameLayout(context);
        GradientDrawable waveBg = new GradientDrawable();
        waveBg.setCornerRadius(dp(28));
        waveBg.setColor(SURFACE_CONTAINER);
        waveIsland.setBackground(waveBg);
        waveIsland.setPadding(dp(18), dp(14), dp(18), dp(14));

        waveIslandText = new TextView(context);
        waveIslandText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        waveIslandText.setTextColor(Color.WHITE);
        waveIslandText.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        waveIslandText.setGravity(Gravity.CENTER);
        waveIslandText.setSingleLine(true);
        waveIslandText.setEllipsize(TextUtils.TruncateAt.END);
        ((FrameLayout) waveIsland).addView(waveIslandText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        waveIsland.setOnClickListener(v -> {
            if (!isYandexMode()) {
                return;
            }
            player.playWaveByCurrentTrack();
        });
        contentLayout.addView(waveIsland, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        scroll.addView(contentLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        setCustomView(root);
    }

    private FrameLayout makePill(Context context) {
        FrameLayout pill = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(PILL_BG);
        pill.setBackground(bg);
        return pill;
    }

    private TextView timeLabel(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        tv.setTextColor(0xB3FFFFFF);
        tv.setLetterSpacing(0.02f);
        return tv;
    }

    private ImageView iconButton(Context context, int res, View.OnClickListener listener) {
        ImageView iv = new ImageView(context);
        iv.setImageResource(res);
        iv.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        iv.setPadding(dp(10), dp(10), dp(10), dp(10));
        iv.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, 1, dp(22)));
        iv.setOnClickListener(listener);
        return iv;
    }

    private void applySeekBarColors(int accent) {
        if (seekBar != null) {
            seekBar.setColors(accent, 0x47FFFFFF);
        }
    }

    private void bouncePress(View view) {
        if (view == null || !MglaPlayerConfig.isButtonAnimationsEnabled()) {
            return;
        }
        view.animate().cancel();
        view.animate().scaleX(0.86f).scaleY(0.86f)
                .setDuration(MglaPlayerConfig.scaleDuration(80))
                .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(MglaPlayerConfig.scaleDuration(200))
                        .setInterpolator(new OvershootInterpolator(1.8f))
                        .start())
                .start();
    }

    private void setTrackTexts(CharSequence title, CharSequence artist) {
        final boolean animate = MglaPlayerConfig.isTrackChangeAnimationEnabled()
                && lastTitleShown != null
                && (!TextUtils.equals(lastTitleShown, title) || !TextUtils.equals(lastArtistShown, artist));
        if (!animate) {
            titleView.setText(title);
            artistView.setText(artist);
            lastTitleShown = title;
            lastArtistShown = artist;
            return;
        }
        long dur = MglaPlayerConfig.scaleDuration(180);
        titleView.animate().cancel();
        artistView.animate().cancel();
        titleView.animate().alpha(0f).translationY(dp(6)).setDuration(dur / 2)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                .withEndAction(() -> {
                    titleView.setText(title);
                    artistView.setText(artist);
                    lastTitleShown = title;
                    lastArtistShown = artist;
                    titleView.setTranslationY(-dp(6));
                    artistView.setTranslationY(-dp(4));
                    titleView.animate().alpha(1f).translationY(0).setDuration(dur)
                            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                    artistView.animate().alpha(1f).translationY(0).setDuration(dur)
                            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                }).start();
        artistView.animate().alpha(0f).translationY(dp(4)).setDuration(dur / 2)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT).start();

        if (coverWrap != null && !lyricsVisible && MglaPlayerConfig.isTrackChangeAnimationEnabled()) {
            coverWrap.animate().cancel();
            coverWrap.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.85f)
                    .setDuration(MglaPlayerConfig.scaleDuration(160))
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                    .withEndAction(() -> coverWrap.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(MglaPlayerConfig.scaleDuration(280))
                            .setInterpolator(new OvershootInterpolator(1.2f))
                            .start())
                    .start();
        }
    }

    private void applyAccentColor(int color) {
        int accent = softenAccent(color);
        if (accent == currentAccent) {
            return;
        }
        final int from = currentAccent;
        currentAccent = accent;
        if (fabColorAnimator != null) {
            fabColorAnimator.cancel();
        }
        if (!MglaPlayerConfig.isTrackChangeAnimationEnabled() || playPauseFabBg == null) {
            if (playPauseFabBg != null) {
                playPauseFabBg.setColor(accent);
            }
            applySeekBarColors(Color.WHITE);
            refreshAddToProfileAccent();
            return;
        }
        fabColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, accent);
        fabColorAnimator.setDuration(MglaPlayerConfig.scaleDuration(420));
        fabColorAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        fabColorAnimator.addUpdateListener(a -> {
            int c = (int) a.getAnimatedValue();
            if (playPauseFabBg != null) {
                playPauseFabBg.setColor(c);
            }
        });
        fabColorAnimator.start();
        applySeekBarColors(Color.WHITE);
        refreshAddToProfileAccent();
    }

    private void refreshAddToProfileAccent() {
        if (addToProfileBtn == null || addToProfileBtn.getVisibility() != View.VISIBLE) {
            return;
        }
        GradientDrawable addBg = new GradientDrawable();
        addBg.setCornerRadius(dp(26));
        addBg.setColor(currentAccent);
        addToProfileBtn.setBackground(addBg);
        addToProfileBtn.setTextColor(ON_ACCENT);
    }

    private static int softenAccent(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color | 0xFF000000, hsv);
        hsv[1] = Math.min(0.45f, Math.max(0.18f, hsv[1] * 0.55f));
        hsv[2] = Math.max(0.78f, Math.min(0.96f, hsv[2] * 1.15f));
        return Color.HSVToColor(hsv);
    }

    private void updateCoverPulse(boolean playing) {
        if (coverWrap == null) {
            return;
        }
        // Lyrics replace the cover window — never pulse/scale while text is shown.
        if (lyricsVisible || !playing || !MglaPlayerConfig.isCoverPulseEnabled()) {
            stopCoverPulse(true);
            return;
        }
        if (coverPulseAnimator != null && coverPulseAnimator.isRunning()) {
            return;
        }
        coverPulseAnimator = ValueAnimator.ofFloat(1f, 1.025f);
        coverPulseAnimator.setDuration(MglaPlayerConfig.scaleDuration(1600));
        coverPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        coverPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        coverPulseAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        coverPulseAnimator.addUpdateListener(a -> {
            if (lyricsVisible) {
                stopCoverPulse(false);
                return;
            }
            float s = (float) a.getAnimatedValue();
            coverWrap.setScaleX(s);
            coverWrap.setScaleY(s);
        });
        coverPulseAnimator.start();
    }

    private void stopCoverPulse(boolean animate) {
        if (coverPulseAnimator != null) {
            coverPulseAnimator.cancel();
            coverPulseAnimator = null;
        }
        if (coverWrap == null) {
            return;
        }
        coverWrap.animate().cancel();
        if (animate && (coverWrap.getScaleX() != 1f || coverWrap.getScaleY() != 1f)) {
            coverWrap.animate().scaleX(1f).scaleY(1f)
                    .setDuration(MglaPlayerConfig.scaleDuration(180))
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .start();
        } else {
            coverWrap.setScaleX(1f);
            coverWrap.setScaleY(1f);
        }
    }

    private void playOpenAnimation() {
        if (openAnimationPlayed || !MglaPlayerConfig.isOpenAnimationEnabled() || contentLayout == null) {
            return;
        }
        // Morph open already handles the reveal — only start cover pulse.
        if (usedMorphOpen) {
            openAnimationPlayed = true;
            startCoverPulseAfterOpen(0);
            return;
        }
        openAnimationPlayed = true;
        final View[] staged = new View[]{coverWrap, titleView, artistView, seekBar, playPauseFab, pillsRow, waveIsland};
        long delay = 0;
        for (View v : staged) {
            if (v == null || v.getVisibility() != View.VISIBLE) {
                continue;
            }
            v.setAlpha(0f);
            v.setTranslationY(dp(18));
            v.setScaleX(0.96f);
            v.setScaleY(0.96f);
            final long d = delay;
            v.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                    .setStartDelay(d)
                    .setDuration(MglaPlayerConfig.scaleDuration(420))
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .start();
            delay += MglaPlayerConfig.scaleDuration(45);
        }
        startCoverPulseAfterOpen(delay + MglaPlayerConfig.scaleDuration(420));
    }

    private void startCoverPulseAfterOpen(long delayMs) {
        root.postDelayed(() -> {
            boolean playing;
            if (isYandexMode()) {
                playing = player.isPlaying();
            } else {
                MessageObject m = MediaController.getInstance().getPlayingMessageObject();
                playing = m != null && !MediaController.getInstance().isMessagePaused();
            }
            updateCoverPulse(playing);
        }, delayMs);
    }

    private boolean resolveMorphBounds(int w, int h) {
        morphTo.set(0, 0, w, h);
        if (MglaGlobalMusicBar.fillExpandSourceRect(root, morphFrom)) {
            fitMorphFromIntoSheet(w, h);
            if (morphFrom.width() >= dp(48) && morphFrom.height() >= dp(24)) {
                morphFromRadius = Math.min(MglaGlobalMusicBar.getExpandSourceRadius(), morphFrom.height() / 2f);
                savedMorphFrom.set(morphFrom);
                savedMorphFromRadius = morphFromRadius;
                hasSavedMorphFrom = true;
                return true;
            }
        }
        // Live capture often fails on close (bar under dialog / slightly outside clip).
        // Reuse the rect from a successful open morph.
        if (hasSavedMorphFrom && savedMorphFrom.width() >= dp(48) && savedMorphFrom.height() >= dp(24)) {
            morphFrom.set(savedMorphFrom);
            fitMorphFromIntoSheet(w, h);
            morphFromRadius = Math.min(savedMorphFromRadius, morphFrom.height() / 2f);
            return morphFrom.width() >= dp(48) && morphFrom.height() >= dp(24);
        }
        return false;
    }

    /** Slide the source rect inside the sheet without collapsing it to zero. */
    private void fitMorphFromIntoSheet(int w, int h) {
        final float bw = morphFrom.width();
        final float bh = morphFrom.height();
        if (bw <= 0 || bh <= 0) {
            return;
        }
        if (morphFrom.left < 0) {
            morphFrom.left = 0;
            morphFrom.right = Math.min(w, bw);
        }
        if (morphFrom.top < 0) {
            morphFrom.top = 0;
            morphFrom.bottom = Math.min(h, bh);
        }
        if (morphFrom.right > w) {
            morphFrom.right = w;
            morphFrom.left = Math.max(0, w - bw);
        }
        if (morphFrom.bottom > h) {
            morphFrom.bottom = h;
            morphFrom.top = Math.max(0, h - bh);
        }
    }

    private boolean captureMorphSourceRect() {
        if (root == null || containerView == null) {
            return false;
        }
        int w = root.getMeasuredWidth();
        int h = root.getMeasuredHeight();
        if (w <= 0 || h <= 0) {
            w = containerView.getMeasuredWidth();
            h = containerView.getMeasuredHeight();
        }
        if (w <= 0 || h <= 0) {
            return false;
        }
        return resolveMorphBounds(w, h);
    }

    /** Island rect from the open morph — safe while containerView is still swipe-translated. */
    private boolean captureMorphSourceRectFromSaved() {
        if (root == null || containerView == null || !hasSavedMorphFrom) {
            return false;
        }
        int w = root.getMeasuredWidth();
        int h = root.getMeasuredHeight();
        if (w <= 0 || h <= 0) {
            w = containerView.getMeasuredWidth();
            h = containerView.getMeasuredHeight();
        }
        if (w <= 0 || h <= 0 || savedMorphFrom.width() < dp(48) || savedMorphFrom.height() < dp(24)) {
            return false;
        }
        morphTo.set(0, 0, w, h);
        morphFrom.set(savedMorphFrom);
        fitMorphFromIntoSheet(w, h);
        morphFromRadius = Math.min(savedMorphFromRadius, morphFrom.height() / 2f);
        return morphFrom.width() >= dp(48) && morphFrom.height() >= dp(24);
    }

    private void beginMorphChrome(boolean closing) {
        // containerView's solid black bg would show unclipped.
        // Open: black root fill. Close: transparent so ambient/cover morph into the island (no grey flash).
        if (containerView != null) {
            containerView.setBackgroundColor(Color.TRANSPARENT);
        }
        if (root != null) {
            root.setBackgroundColor(closing ? Color.TRANSPARENT : Color.BLACK);
        }
        morphActive = true;
    }

    private void applyMorphProgress(float t) {
        morphProgress = Utilities.clamp(t, 1f, 0f);
        final boolean closing = currentSheetAnimationType == 2;
        if (closing && morphCloseFromSwipe) {
            // Swipe close: lerp release-frame rect → island (never through full-screen).
            morphCurrent.left = AndroidUtilities.lerp(morphFrom.left, morphCloseStart.left, morphProgress);
            morphCurrent.top = AndroidUtilities.lerp(morphFrom.top, morphCloseStart.top, morphProgress);
            morphCurrent.right = AndroidUtilities.lerp(morphFrom.right, morphCloseStart.right, morphProgress);
            morphCurrent.bottom = AndroidUtilities.lerp(morphFrom.bottom, morphCloseStart.bottom, morphProgress);
        } else {
            morphCurrent.left = AndroidUtilities.lerp(morphFrom.left, morphTo.left, morphProgress);
            morphCurrent.top = AndroidUtilities.lerp(morphFrom.top, morphTo.top, morphProgress);
            morphCurrent.right = AndroidUtilities.lerp(morphFrom.right, morphTo.right, morphProgress);
            morphCurrent.bottom = AndroidUtilities.lerp(morphFrom.bottom, morphTo.bottom, morphProgress);
        }

        if (contentLayout != null) {
            float contentT;
            if (closing) {
                // Keep cover/chrome readable while shrinking into the island.
                contentT = Utilities.clamp(morphProgress / 0.28f, 1f, 0f);
            } else {
                contentT = Utilities.clamp((morphProgress - 0.1f) / 0.5f, 1f, 0f);
            }
            contentT = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(contentT);
            contentLayout.setAlpha(contentT);
            float scale = closing
                    ? (0.88f + 0.12f * contentT)
                    : (0.94f + 0.06f * contentT);
            contentLayout.setPivotX(Math.max(1f, contentLayout.getWidth() / 2f));
            contentLayout.setPivotY(Math.max(1f, contentLayout.getHeight() * 0.28f));
            contentLayout.setScaleX(scale);
            contentLayout.setScaleY(scale);
        }
        if (ambientBg != null) {
            // Close: keep palette fill for the whole shrink so it doesn't become a grey pill.
            ambientBg.setAlpha(closing ? 1f : (0.4f + 0.6f * morphProgress));
        }
        if (root != null) {
            root.invalidate();
        }
        if (container != null) {
            container.invalidate();
        }
    }

    private void clearMorphClip() {
        morphActive = false;
        morphProgress = 1f;
        if (containerView != null) {
            containerView.setBackgroundDrawable(shadowDrawable);
        }
        if (root != null) {
            root.setBackgroundColor(Color.TRANSPARENT);
            root.invalidate();
        }
        if (contentLayout != null) {
            contentLayout.setAlpha(1f);
            contentLayout.setScaleX(1f);
            contentLayout.setScaleY(1f);
        }
        if (ambientBg != null) {
            ambientBg.setAlpha(1f);
        }
    }

    @Override
    protected boolean onCustomOpenAnimation() {
        if (!MglaPlayerConfig.isOpenAnimationEnabled() || containerView == null || root == null) {
            return false;
        }

        final float savedTy = containerView.getTranslationY();
        final float savedTx = containerView.getTranslationX();
        containerView.setTranslationY(0);
        containerView.setTranslationX(0);
        containerView.setAlpha(1f);
        onContainerViewTranslation();

        if (!captureMorphSourceRect()) {
            containerView.setTranslationY(savedTy);
            containerView.setTranslationX(savedTx);
            onContainerViewTranslation();
            return false;
        }

        usedMorphOpen = true;
        beginMorphChrome(false);
        applyMorphProgress(0f);
        MglaGlobalMusicBar.setHiddenForPlayerExpand(true);

        if (useHardwareLayer && container != null) {
            container.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        currentSheetAnimationType = 1;

        ValueAnimator morph = ValueAnimator.ofFloat(0f, 1f);
        morph.addUpdateListener(a -> applyMorphProgress((float) a.getAnimatedValue()));

        if (navigationBarAnimation != null) {
            navigationBarAnimation.cancel();
        }
        navigationBarAnimation = ValueAnimator.ofFloat(navigationBarAlpha, 1f);
        navigationBarAnimation.addUpdateListener(a -> {
            navigationBarAlpha = (float) a.getAnimatedValue();
            if (container != null) {
                container.invalidate();
            }
        });

        currentSheetAnimation = new AnimatorSet();
        currentSheetAnimation.playTogether(
                morph,
                ObjectAnimator.ofInt(backDrawable, AnimationProperties.COLOR_DRAWABLE_ALPHA, dimBehind ? dimBehindAlpha : 0),
                navigationBarAnimation
        );
        currentSheetAnimation.setDuration(MglaPlayerConfig.scaleDuration(520));
        currentSheetAnimation.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        currentSheetAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (currentSheetAnimation != null && currentSheetAnimation.equals(animation)) {
                    currentSheetAnimation = null;
                    currentSheetAnimationType = 0;
                }
                clearMorphClip();
                onOpenAnimationEnd();
                if (delegate != null) {
                    delegate.onOpenAnimationEnd();
                }
                if (useHardwareLayer && container != null) {
                    container.setLayerType(View.LAYER_TYPE_NONE, null);
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (currentSheetAnimation != null && currentSheetAnimation.equals(animation)) {
                    currentSheetAnimation = null;
                    currentSheetAnimationType = 0;
                }
            }
        });
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512);
        currentSheetAnimation.start();
        return true;
    }

    /**
     * Morph close is owned by {@link #dismiss()} so swipe-to-dismiss (which sets
     * allowCustomAnimation=false) still gets the reverse expand animation.
     */
    @Override
    protected boolean onCustomCloseAnimation() {
        return false;
    }

    private boolean shouldMorphClose() {
        return (usedMorphOpen || hasSavedMorphFrom)
                && (usedMorphOpen || MglaPlayerConfig.isOpenAnimationEnabled())
                && containerView != null
                && root != null;
    }

    private boolean startMorphCloseAnimation() {
        if (!shouldMorphClose()) {
            return false;
        }

        final float swipeTy = Math.max(0f, containerView.getTranslationY());
        containerView.setAlpha(1f);
        containerView.setVisibility(View.VISIBLE);

        morphCloseFromSwipe = swipeTy > dp(8);
        if (morphCloseFromSwipe) {
            // Live capture is wrong while translated — use open-time island rect.
            if (!captureMorphSourceRectFromSaved()) {
                morphCloseFromSwipe = false;
                return false;
            }
        } else if (!captureMorphSourceRect()) {
            return false;
        }
        MglaGlobalMusicBar.setHiddenForPlayerExpand(true);

        // Build the close-start clip to match the finger-release frame, apply it, THEN clear
        // translation — never draw a full-screen frame between those steps.
        if (morphCloseFromSwipe) {
            float h = morphTo.height() > 0 ? morphTo.height() : Math.max(root.getMeasuredHeight(), 1);
            float w = morphTo.width() > 0 ? morphTo.width() : Math.max(root.getMeasuredWidth(), 1);
            float topInset = Math.min(swipeTy, h * 0.55f);
            // Slight side/bottom tuck so it feels like the sheet is already dismissing.
            float sideInset = topInset * 0.04f;
            float bottomInset = topInset * 0.08f;
            morphCloseStart.set(sideInset, topInset, w - sideInset, h - bottomInset);
        } else {
            morphCloseStart.set(morphTo);
        }

        currentSheetAnimationType = 2;
        beginMorphChrome(true);
        applyMorphProgress(1f);

        containerView.setTranslationY(0);
        containerView.setTranslationX(0);
        onContainerViewTranslation();
        if (root != null) {
            root.invalidate();
        }

        // Shorter duration when already partially dismissed by swipe.
        float swipeFraction = morphCloseFromSwipe
                ? Utilities.clamp(swipeTy / Math.max(morphTo.height(), 1f), 0.55f, 0f)
                : 0f;
        long duration = MglaPlayerConfig.scaleDuration((long) (420 * (1f - swipeFraction * 0.45f)));

        ValueAnimator morph = ValueAnimator.ofFloat(1f, 0f);
        morph.addUpdateListener(a -> applyMorphProgress((float) a.getAnimatedValue()));

        if (navigationBarAnimation != null) {
            navigationBarAnimation.cancel();
        }
        navigationBarAnimation = ValueAnimator.ofFloat(navigationBarAlpha, 0f);
        navigationBarAnimation.addUpdateListener(a -> {
            navigationBarAlpha = (float) a.getAnimatedValue();
            if (container != null) {
                container.invalidate();
            }
        });

        if (currentSheetAnimation != null) {
            currentSheetAnimation.cancel();
        }
        currentSheetAnimation = new AnimatorSet();
        currentSheetAnimation.playTogether(
                morph,
                ObjectAnimator.ofInt(backDrawable, AnimationProperties.COLOR_DRAWABLE_ALPHA, 0),
                navigationBarAnimation
        );
        currentSheetAnimation.setDuration(duration);
        currentSheetAnimation.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        currentSheetAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (currentSheetAnimation != null && currentSheetAnimation.equals(animation)) {
                    currentSheetAnimation = null;
                    currentSheetAnimationType = 0;
                }
                finishMorphClose();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (currentSheetAnimation != null && currentSheetAnimation.equals(animation)) {
                    currentSheetAnimation = null;
                    currentSheetAnimationType = 0;
                }
            }
        });
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512);
        currentSheetAnimation.start();
        return true;
    }

    /** Hide the sheet while still clipped small — never expand it back before window teardown. */
    private void finishMorphClose() {
        if (morphCloseFinishing) {
            return;
        }
        morphCloseFinishing = true;

        // Tear down the morph layer first, then reveal the real island in the same frame.
        if (containerView != null) {
            containerView.setVisibility(View.INVISIBLE);
            containerView.setAlpha(0f);
        }
        if (root != null) {
            root.setVisibility(View.INVISIBLE);
            root.setBackgroundColor(Color.TRANSPARENT);
        }
        if (container != null) {
            container.setAlpha(0f);
        }
        try {
            backDrawable.setAlpha(0);
        } catch (Throwable ignored) {
        }
        morphActive = false;
        morphCloseFromSwipe = false;
        if (containerView != null) {
            containerView.setTranslationY(0);
            containerView.setTranslationX(0);
        }
        MglaGlobalMusicBar.setHiddenForPlayerExpand(false);
        skipDismissAnimation();
        dismiss();
    }

    private void refreshUi() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
            updateTrackUi(MglaYandexMusicPlayback.getTrack(playing));
        } else if (player.getCurrentTrack() != null && (player.isPlaying() || player.isBuffering())) {
            updateTrackUi(player.getCurrentTrack());
        } else if (playing != null && (playing.isMusic() || playing.isVoice() || playing.isRoundVideo())) {
            updateTelegramUi(playing);
        } else {
            updateTrackUi(player.getCurrentTrack());
        }
        updateControlsState();
        updateProgressFromActive();
        applyModeChrome();
    }

    private void applyModeChrome() {
        boolean yandex = isYandexMode();
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        boolean telegramMusic = !yandex && playing != null && playing.isMusic()
                && !playing.isVoice() && !playing.isRoundVideo()
                && !MglaYandexMusicPlayback.isYandexMessage(playing);
        boolean voiceOrRound = !yandex && playing != null
                && (playing.isVoice() || playing.isRoundVideo());
        int yandexVis = yandex ? View.VISIBLE : View.GONE;
        if (likeButton != null) {
            likeButton.setVisibility(yandexVis);
        }
        if (dislikeButton != null) {
            dislikeButton.setVisibility(yandexVis);
        }
        if (pillsRow != null) {
            pillsRow.setVisibility(yandexVis);
        }
        if (shufflePill != null) {
            shufflePill.setVisibility(yandexVis);
        }
        if (lyricsPill != null) {
            lyricsPill.setVisibility(yandexVis);
        }
        if (repeatPill != null) {
            repeatPill.setVisibility(yandexVis);
        }
        if (!yandex) {
            hideLyricsOverlay(false);
        }
        if (waveIsland != null) {
            waveIsland.setVisibility(yandexVis);
        }
        if (telegramControlsSpacer != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) telegramControlsSpacer.getLayoutParams();
            // Push play/prev/next lower for voice; music has its own chrome below.
            lp.height = (yandex || telegramMusic) ? 0 : dp(56);
            telegramControlsSpacer.setLayoutParams(lp);
        }
        if (shareBtn != null) {
            shareBtn.setVisibility(yandex ? View.GONE : View.VISIBLE);
        }
        if (moreBtn != null) {
            moreBtn.setVisibility(telegramMusic ? View.VISIBLE : View.GONE);
        }
        if (queueBtn != null) {
            boolean queueEnabled = telegramMusic || voiceOrRound;
            queueBtn.setEnabled(queueEnabled);
            queueBtn.setAlpha(queueEnabled ? 1f : 0.35f);
        }
        if (telegramRepeatBtn != null) {
            telegramRepeatBtn.setVisibility(telegramMusic ? View.VISIBLE : View.GONE);
            if (telegramMusic) {
                updateTelegramRepeatIcon();
            }
        }
        if (addToProfileBtn != null) {
            // Wait until SavedMusicIds finish loading so we don't flash "Add" for tracks already in profile.
            ensureSavedMusicIdsLoading();
            boolean showAdd = telegramMusic && !isMySavedMusicList() && !isNoforwards(playing)
                    && isSavedMusicIdsReady() && !isSavedToProfile(playing);
            addToProfileBtn.setVisibility(showAdd ? View.VISIBLE : View.GONE);
            if (showAdd) {
                refreshAddToProfileAccent();
            }
        }
        if (telegramRepeatBtn != null && telegramRepeatBtn.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) telegramRepeatBtn.getLayoutParams();
            // Keep repeat above Add-to-profile when that button is shown; otherwise pin to bottom.
            boolean showAdd = addToProfileBtn != null && addToProfileBtn.getVisibility() == View.VISIBLE;
            lp.bottomMargin = dp(showAdd ? 8 : 0);
            telegramRepeatBtn.setLayoutParams(lp);
        }
        if (artistAvatar != null && (telegramMusic || voiceOrRound)) {
            artistAvatar.setVisibility(View.GONE);
        }
    }

    private void showMusicCover() {
        if (coverImage != null) {
            coverImage.setVisibility(View.VISIBLE);
            coverImage.setAlpha(1f);
        }
        if (avatarCoverView != null) {
            avatarCoverView.setVisibility(View.GONE);
            avatarCoverView.setAlpha(1f);
            avatarCoverView.setImageDrawable(null);
        }
    }

    private void showAvatarCover() {
        if (coverImage != null) {
            coverImage.setVisibility(View.GONE);
        }
        if (avatarCoverView != null) {
            avatarCoverView.setVisibility(View.VISIBLE);
            // Same rounded square as album art — not a circle.
            avatarCoverView.setRoundRadius((int) coverRadius);
        }
    }

    private void updateTrackUi(MglaYandexMusicModel.Track track) {
        String newId = track != null ? track.id : null;
        boolean trackChanged = !TextUtils.equals(lyricsBoundTrackId, newId);
        lyricsBoundTrackId = newId;
        // Don't tear down lyrics on every refreshUi — only when the track actually changes.
        if (trackChanged) {
            hideLyricsOverlay(false);
        }
        if (!lyricsVisible) {
            showMusicCover();
        }
        if (artistAvatar != null) {
            artistAvatar.setVisibility(View.VISIBLE);
        }
        if (moreBtn != null) {
            moreBtn.setVisibility(View.VISIBLE);
        }
        if (track == null) {
            setTrackTexts("Нет трека", "");
            coverImage.setImageResource(R.drawable.bot_music);
            artistAvatar.setImageResource(R.drawable.bot_music);
            if (waveIslandText != null) {
                waveIslandText.setText("Запустить волну по треку");
            }
            if (sourceTitle != null) {
                sourceTitle.setText("Яндекс Музыка");
            }
            updateLyricsPillState(null);
            return;
        }
        setTrackTexts(track.title, track.artist);
        timeTotalView.setText(track.getFormattedDuration());

        String source = player.getCurrentSourceTitle();
        if (TextUtils.isEmpty(source)) {
            source = player.isMyWaveActive() ? "Моя волна" : "Яндекс Музыка";
        }
        // Header above cover: show wave context when a wave is playing.
        if (sourceTitle != null) {
            sourceTitle.setText(source);
        }

        lastCoverUrl = null;
        // Player cover: high resolution (1000x1000) when online, fallback to downloaded cover when offline.
        MglaMusicImageHelper.bindPlayerCover(coverImage, track, R.drawable.bot_music, new MglaMusicImageHelper.ImageCallback() {
            @Override
            public void onLoaded(Bitmap bitmap) {
                extractAmbientColorsFromBitmap(bitmap);
            }

            @Override
            public void onFailed() {
                applyAccentColor(ACCENT_FALLBACK);
            }
        });
        MglaMusicImageHelper.bindCircleCover(artistAvatar, track.getCoverUrl(100), R.drawable.bot_music);

        if (waveIslandText != null) {
            waveIslandText.setText("Запустить волну по треку");
        }

        updateLikeButton();
        updateLyricsPillState(track);
        prefetchLyrics(track);
    }

    private void updateTelegramUi(MessageObject playing) {
        final boolean voiceOrRound = playing.isVoice() || playing.isRoundVideo();
        if (voiceOrRound) {
            setTrackTexts(playing.getMusicAuthor(), LocaleController.getString(
                    playing.isRoundVideo() ? R.string.AttachRound : R.string.AttachAudio));
            sourceTitle.setText(LocaleController.getString(R.string.AttachAudio));
            showAvatarCover();
            bindTelegramAvatar(playing);
            if (artistAvatar != null) {
                artistAvatar.setVisibility(View.GONE);
            }
            if (moreBtn != null) {
                moreBtn.setVisibility(View.GONE);
            }
            lastCoverUrl = null;
            if (ambientBg != null) {
                ambientBg.setColors(0xFF2A3340, 0xFF1A2230, 0xFF121820, 0xFF0C1018);
            }
            applyAccentColor(ACCENT_FALLBACK);
        } else {
            if (artistAvatar != null) {
                artistAvatar.setVisibility(View.GONE);
            }
            if (moreBtn != null) {
                moreBtn.setVisibility(View.VISIBLE);
            }
            setTrackTexts(playing.getMusicTitle(), playing.getMusicAuthor());
            sourceTitle.setText(LocaleController.getString(R.string.AttachMusic));
            bindTelegramMusicCover(playing);
        }
        long dur = resolveTelegramDurationMs(playing);
        timeTotalView.setText(formatTime(dur));
        if (waveIslandText != null) {
            waveIslandText.setText(titleView.getText());
        }
    }

    private void bindTelegramAvatar(MessageObject messageObject) {
        TLRPC.User user = null;
        TLRPC.Chat chat = null;
        final int account = messageObject.currentAccount;
        final MessagesController mc = MessagesController.getInstance(account);

        if (messageObject.isOutOwner()
                || (messageObject.messageOwner.fwd_from != null
                && messageObject.messageOwner.fwd_from.from_id instanceof TLRPC.TL_peerUser
                && messageObject.messageOwner.fwd_from.from_id.user_id == UserConfig.getInstance(account).getClientUserId())) {
            user = UserConfig.getInstance(account).getCurrentUser();
        } else if (messageObject.messageOwner.fwd_from != null && messageObject.messageOwner.fwd_from.from_id instanceof TLRPC.TL_peerChannel) {
            chat = mc.getChat(messageObject.messageOwner.fwd_from.from_id.channel_id);
        } else if (messageObject.messageOwner.fwd_from != null && messageObject.messageOwner.fwd_from.from_id instanceof TLRPC.TL_peerChat) {
            chat = mc.getChat(messageObject.messageOwner.fwd_from.from_id.chat_id);
        } else if (messageObject.messageOwner.fwd_from != null && messageObject.messageOwner.fwd_from.from_id instanceof TLRPC.TL_peerUser) {
            user = mc.getUser(messageObject.messageOwner.fwd_from.from_id.user_id);
        } else if (messageObject.messageOwner.from_id instanceof TLRPC.TL_peerChat) {
            chat = mc.getChat(messageObject.messageOwner.from_id.chat_id);
        } else if (messageObject.messageOwner.from_id instanceof TLRPC.TL_peerChannel) {
            chat = mc.getChat(messageObject.messageOwner.from_id.channel_id);
        } else if (messageObject.messageOwner.from_id == null && messageObject.messageOwner.peer_id != null && messageObject.messageOwner.peer_id.channel_id != 0) {
            chat = mc.getChat(messageObject.messageOwner.peer_id.channel_id);
        } else {
            long senderId = messageObject.getSenderId();
            if (senderId > 0) {
                user = mc.getUser(senderId);
            } else if (senderId < 0) {
                chat = mc.getChat(-senderId);
            }
        }

        if (user != null) {
            avatarDrawable.setInfo(user);
            coverAvatarDrawable.setInfo(user);
            coverAvatarDrawable.setRoundRadius((int) coverRadius);
            bindHighResCoverAvatar(user, account);
            artistAvatar.setImageDrawable(avatarDrawable);
        } else if (chat != null) {
            avatarDrawable.setInfo(chat);
            coverAvatarDrawable.setInfo(chat);
            coverAvatarDrawable.setRoundRadius((int) coverRadius);
            bindHighResCoverAvatar(chat, account);
            artistAvatar.setImageDrawable(avatarDrawable);
        } else {
            avatarDrawable.setInfo(0, messageObject.getMusicAuthor(), null);
            coverAvatarDrawable.setInfo(0, messageObject.getMusicAuthor(), null);
            coverAvatarDrawable.setRoundRadius((int) coverRadius);
            if (avatarCoverView != null) {
                avatarCoverView.setImageDrawable(coverAvatarDrawable);
                avatarCoverView.setRoundRadius((int) coverRadius);
            }
            artistAvatar.setImageDrawable(avatarDrawable);
        }
    }

    /** Load profile photo_big at cover pixel size (not the tiny 50_50 chat avatar). */
    private void bindHighResCoverAvatar(TLRPC.User user, int account) {
        bindHighResCoverAvatarInternal(
                ImageLocation.getForUserOrChat(account, user, ImageLocation.TYPE_BIG),
                ImageLocation.getForUserOrChat(account, user, ImageLocation.TYPE_SMALL),
                user);
    }

    private void bindHighResCoverAvatar(TLRPC.Chat chat, int account) {
        bindHighResCoverAvatarInternal(
                ImageLocation.getForUserOrChat(account, chat, ImageLocation.TYPE_BIG),
                ImageLocation.getForUserOrChat(account, chat, ImageLocation.TYPE_SMALL),
                chat);
    }

    private void bindHighResCoverAvatarInternal(ImageLocation big, ImageLocation small, Object parent) {
        if (avatarCoverView == null) {
            return;
        }
        final int side = Math.max(320, coverSize * 2);
        final String filter = side + "_" + side;
        if (big != null) {
            avatarCoverView.setImage(big, filter, small, "50_50", coverAvatarDrawable, null, null, 0, parent);
        } else {
            avatarCoverView.setImageDrawable(coverAvatarDrawable);
        }
        avatarCoverView.setRoundRadius((int) coverRadius);
    }

    private void openQueue() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || MglaYandexMusicPlayback.isYandexMessage(playing)) {
            return;
        }
        if (playing.isVoice() || playing.isRoundVideo()) {
            showVoiceQueueSheet();
            return;
        }
        if (playing.isMusic()) {
            showMusicQueueSheet();
        }
    }

    private boolean isMySavedMusicList() {
        MessagesController.SavedMusicList list = MediaController.getInstance().currentSavedMusicList;
        final int account = UserConfig.selectedAccount;
        return list != null && list.dialogId == UserConfig.getInstance(account).getClientUserId();
    }

    private boolean isNoforwards(MessageObject messageObject) {
        return messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.noforwards;
    }

    private boolean isSavedToProfile(MessageObject messageObject) {
        if (messageObject == null || messageObject.getDocument() == null) {
            return false;
        }
        final int account = messageObject.currentAccount;
        final long documentId = messageObject.getDocument().id;
        if (MessagesController.getInstance(account).getSavedMusicIds().ids.contains(documentId)) {
            return true;
        }
        // Fast local hint before SavedMusicIds network load finishes (featured profile track).
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final TLRPC.UserFull userInfo = MessagesController.getInstance(account).getUserFull(selfId);
        return userInfo != null && userInfo.saved_music != null && userInfo.saved_music.id == documentId;
    }

    private boolean isSavedMusicIdsReady() {
        MessagesController.SavedMusicIds musicIds = MessagesController.getInstance(UserConfig.selectedAccount).getSavedMusicIds();
        return musicIds != null && musicIds.loaded;
    }

    private void ensureSavedMusicIdsLoading() {
        MessagesController.SavedMusicIds musicIds = MessagesController.getInstance(UserConfig.selectedAccount).getSavedMusicIds();
        if (musicIds != null) {
            musicIds.load();
        }
    }

    private void bindTelegramMusicCover(MessageObject messageObject) {
        // Reuse BackupImageView path (same rounded-square cover as voice avatars).
        if (coverImage != null) {
            coverImage.setVisibility(View.GONE);
        }
        if (avatarCoverView == null) {
            return;
        }
        avatarCoverView.setVisibility(View.VISIBLE);
        avatarCoverView.setRoundRadius((int) coverRadius);
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        if (audioInfo != null && audioInfo.getCover() != null) {
            avatarCoverView.setImageBitmap(audioInfo.getCover());
            lastCoverUrl = null;
            extractAmbientColorsFromBitmap(audioInfo.getCover());
            return;
        }
        final String artworkUrl = messageObject.getArtworkUrl(false);
        final ImageLocation thumb = getArtworkThumbImageLocation(messageObject);
        if (!TextUtils.isEmpty(artworkUrl)) {
            avatarCoverView.setImage(ImageLocation.getForPath(artworkUrl), null, thumb, null, null, null, null, 0, messageObject);
            extractAmbientColors(artworkUrl);
            lastCoverUrl = artworkUrl;
        } else if (thumb != null) {
            avatarCoverView.setImage(thumb, null, null, null, null, null, null, 0, messageObject);
            lastCoverUrl = null;
            if (ambientBg != null) {
                ambientBg.setColors(0xFF2A3340, 0xFF1A2230, 0xFF121820, 0xFF0C1018);
            }
            applyAccentColor(ACCENT_FALLBACK);
        } else {
            avatarCoverView.setImageResource(R.drawable.bot_music);
            lastCoverUrl = null;
            applyAccentColor(ACCENT_FALLBACK);
        }
    }

    private void extractAmbientColorsFromBitmap(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) {
            return;
        }
        if (!MglaPlayerConfig.isDynamicColorsEnabled()) {
            if (ambientBg != null) {
                ambientBg.setColors(0xFF2A1F3D, 0xFF1A2438, 0xFF142028, 0xFF0E0E12);
            }
            applyAccentColor(ACCENT_FALLBACK);
            return;
        }
        final Bitmap copy = bmp.copy(bmp.getConfig() != null ? bmp.getConfig() : Bitmap.Config.ARGB_8888, false);
        Utilities.globalQueue.postRunnable(() -> {
            if (copy == null) {
                return;
            }
            int[] colors = DominantColors.getColorsSync(false, copy, true);
            if (!copy.isRecycled()) {
                copy.recycle();
            }
            final int c1 = colors != null && colors.length > 0 ? colors[0] : 0xFF3A2A4A;
            final int c2 = colors != null && colors.length > 1 ? colors[1] : 0xFF1A2A3A;
            AndroidUtilities.runOnUIThread(() -> {
                if (ambientBg != null) {
                    ambientBg.setColors(c1, c2, darken(c1, 0.45f), darken(c2, 0.55f));
                }
                applyAccentColor(c1);
            });
        });
    }

    private ImageLocation getArtworkThumbImageLocation(MessageObject messageObject) {
        final TLRPC.Document document = messageObject.getDocument();
        TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        if (thumb != null) {
            return ImageLocation.getForDocument(thumb, document);
        }
        final String smallArtworkUrl = messageObject.getArtworkUrl(true);
        if (!TextUtils.isEmpty(smallArtworkUrl)) {
            return ImageLocation.getForPath(smallArtworkUrl);
        }
        return null;
    }

    private void showVoiceQueueSheet() {
        ArrayList<MessageObject> playlist = MediaController.getInstance().getVoiceMessagesPlaylist();
        final ArrayList<MessageObject> items = new ArrayList<>();
        if (playlist != null && !playlist.isEmpty()) {
            items.addAll(playlist);
        } else {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing == null) {
                return;
            }
            items.add(playing);
        }
        showPlaylistQueueSheet(LocaleController.getString(R.string.AttachAudio), items);
    }

    private void showMusicQueueSheet() {
        ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaylist();
        final ArrayList<MessageObject> items = new ArrayList<>();
        if (playlist != null && !playlist.isEmpty()) {
            items.addAll(playlist);
        } else {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing == null) {
                return;
            }
            items.add(playing);
        }
        CharSequence title;
        if (isMySavedMusicList()) {
            title = LocaleController.getString(R.string.ProfilePlaylistTitleMine);
        } else {
            MessagesController.SavedMusicList savedList = MediaController.getInstance().currentSavedMusicList;
            if (savedList != null) {
                title = LocaleController.formatString(R.string.ProfilePlaylistTitle, DialogObject.getShortName(savedList.dialogId));
            } else {
                MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                title = playing != null ? DialogObject.getName(playing.getDialogId()) : LocaleController.getString(R.string.AttachMusic);
            }
            if (TextUtils.isEmpty(title)) {
                title = LocaleController.getString(R.string.AttachMusic);
            }
        }
        showPlaylistQueueSheet(title, items);
    }

    private void showPlaylistQueueSheet(CharSequence titleText, ArrayList<MessageObject> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        final Context context = getContext();
        final BottomSheet sheet = new BottomSheet(context, false);
        sheet.setApplyTopPadding(false);
        sheet.setApplyBottomPadding(false);

        final int cellDp = 56;
        final int maxListPx = (int) (AndroidUtilities.displaySize.y * 0.55f);
        final int maxVisible = Math.max(1, maxListPx / dp(cellDp));
        final int visibleCount = Math.min(items.size(), maxVisible);
        final int listHeightDp = visibleCount * cellDp;
        final boolean canScroll = items.size() > maxVisible;
        final int cellBg = Theme.getColor(Theme.key_dialogBackground);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(4) + AndroidUtilities.navigationBarHeight);

        TextView title = new TextView(context);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setPadding(dp(20), dp(8), dp(20), dp(6));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context) {
            @Override
            public boolean canScrollVertically() {
                return canScroll && super.canScrollVertically();
            }
        });
        listView.setHasFixedSize(true);
        listView.setItemAnimator(null);
        listView.setOverScrollMode(canScroll ? View.OVER_SCROLL_IF_CONTENT_SCROLLS : View.OVER_SCROLL_NEVER);
        listView.setNestedScrollingEnabled(canScroll);
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return true;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                AudioPlayerCell cell = new AudioPlayerCell(context, AudioPlayerCell.VIEW_TYPE_DEFAULT, null);
                cell.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(cellDp)));
                return new RecyclerListView.Holder(cell);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                AudioPlayerCell cell = (AudioPlayerCell) holder.itemView;
                cell.setBackgroundColor(cellBg);
                cell.setMessageObject(items.get(position), false, null, position + 1 < items.size(), null);
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            if (view instanceof AudioPlayerCell) {
                ((AudioPlayerCell) view).didPressedButton();
            } else {
                MediaController.getInstance().playMessage(items.get(position));
            }
            sheet.dismiss();
            AndroidUtilities.runOnUIThread(this::refreshUi, 50);
        });
        if (canScroll) {
            listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (lm == null || items.isEmpty()) {
                        return;
                    }
                    int first = lm.findFirstVisibleItemPosition();
                    int visible = Math.abs(lm.findLastVisibleItemPosition() - first) + 1;
                    if (SharedConfig.playOrderReversed) {
                        if (first < 10) {
                            MediaController.getInstance().loadMoreMusic();
                        }
                    } else if (first + visible > items.size() - 10) {
                        MediaController.getInstance().loadMoreMusic();
                    }
                }
            });
        }

        content.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, listHeightDp));
        sheet.setCustomView(content);
        sheet.show();
    }

    private void updateTelegramRepeatIcon() {
        if (telegramRepeatIcon == null) {
            return;
        }
        int mode = SharedConfig.repeatMode;
        int icon = R.drawable.player_new_repeatall;
        int color = Color.WHITE;
        if (mode == 2) {
            icon = R.drawable.player_new_repeatone;
            color = currentAccent;
        } else if (SharedConfig.shuffleMusic) {
            icon = mode == 1 ? R.drawable.player_new_repeat_shuffle : R.drawable.player_new_shuffle;
            color = currentAccent;
        } else if (SharedConfig.playOrderReversed) {
            icon = mode == 1 ? R.drawable.player_new_repeat_reverse : R.drawable.player_new_order;
            color = currentAccent;
        } else if (mode == 1) {
            icon = R.drawable.player_new_repeatall;
            color = currentAccent;
        }
        telegramRepeatIcon.setImageResource(icon);
        telegramRepeatIcon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    private void showTelegramRepeatMenu() {
        if (telegramRepeatBtn == null || container == null) {
            return;
        }
        ItemOptions o = ItemOptions.makeOptions(container, null, telegramRepeatBtn, true);
        o.addChecked(SharedConfig.repeatMode == 2, R.drawable.player_new_repeatone, LocaleController.getString(R.string.RepeatSong), () -> {
            SharedConfig.setRepeatMode(SharedConfig.repeatMode == 2 ? 0 : 2);
            updateTelegramRepeatIcon();
            o.dismiss();
        });
        o.addChecked(SharedConfig.repeatMode == 1, R.drawable.player_new_repeatall, LocaleController.getString(R.string.RepeatList), () -> {
            SharedConfig.setRepeatMode(SharedConfig.repeatMode == 1 ? 0 : 1);
            updateTelegramRepeatIcon();
            o.dismiss();
        });
        o.addGap();
        o.addChecked(SharedConfig.shuffleMusic, R.drawable.player_new_shuffle, LocaleController.getString(R.string.ShuffleList), () -> {
            if (SharedConfig.shuffleMusic) {
                MediaController.getInstance().setPlaybackOrderType(0);
            } else {
                MediaController.getInstance().setPlaybackOrderType(2);
            }
            updateTelegramRepeatIcon();
            o.dismiss();
        });
        o.addGap();
        o.addChecked(SharedConfig.playOrderReversed, R.drawable.player_new_order, LocaleController.getString(R.string.ReverseOrder), () -> {
            if (SharedConfig.playOrderReversed) {
                MediaController.getInstance().setPlaybackOrderType(0);
            } else {
                MediaController.getInstance().setPlaybackOrderType(1);
            }
            updateTelegramRepeatIcon();
            o.dismiss();
        });
        o.setGravity(Gravity.CENTER_HORIZONTAL);
        o.show();
    }

    private void showTelegramMusicOptions() {
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null || !messageObject.isMusic() || container == null || moreBtn == null) {
            return;
        }
        final boolean noforwards = isNoforwards(messageObject);
        final ItemOptions o = ItemOptions.makeOptions(container, null, moreBtn, true);
        if (!isMySavedMusicList()) {
            ItemOptions saveOptions = buildSaveOptions(o, messageObject);
            o.addIf(!noforwards, R.drawable.msg_stories_save, LocaleController.getString(R.string.AudioSaveTo), () -> o.openSwipeback(saveOptions));
            if (!noforwards && o.getLast() != null) {
                o.getLast().setRightIcon(R.drawable.msg_arrowright);
            }
            o.addGap();
        }
        o.addIf(!noforwards, R.drawable.msg_forward, LocaleController.getString(R.string.Forward), () -> {
            o.dismiss();
            openForward();
        });
        o.addIf(!noforwards, R.drawable.msg_shareout, LocaleController.getString(R.string.ShareFile), () -> {
            o.dismiss();
            shareTelegramMusic(messageObject);
        });
        o.addIf(messageObject.getId() > 0, R.drawable.msg_message, LocaleController.getString(R.string.ShowInChat), () -> {
            o.dismiss();
            openPlayingInChat(messageObject);
        });
        if (castAvailable && castRouteButton != null) {
            o.add(R.drawable.menu_video_chromecast, LocaleController.getString(R.string.VideoPlayerChromecast), () -> {
                o.dismiss();
                try {
                    ChromecastController.getInstance().setCurrentMediaAndCastIfNeeded(MediaController.getInstance().getCurrentChromecastMedia());
                    castRouteButton.performClick();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        }
        o.addIf(isMySavedMusicList(), R.drawable.msg_delete, LocaleController.getString(R.string.ProfilePlaylistRemoveFromProfile), true, () -> {
            o.dismiss();
            savePlayingToProfile(false);
        });
        o.show();
    }

    private ItemOptions buildSaveOptions(ItemOptions o, MessageObject messageObject) {
        final int currentAccount = messageObject.currentAccount;
        final MessagesController.SavedMusicIds musicIds = MessagesController.getInstance(currentAccount).getSavedMusicIds();
        final TLRPC.Document document = messageObject.getDocument();
        final long documentId = document != null ? document.id : 0;

        final ItemOptions o2 = o.makeSwipeback();
        o2.add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back), o::closeSwipeback);
        o2.addGap();
        o2.addIf(!musicIds.ids.contains(documentId), R.drawable.left_status_profile, LocaleController.getString(R.string.AudioSaveToMyProfile), () -> {
            saveToProfile(messageObject, true, () -> {
                applyModeChrome();
                BulletinFactory.of(container, null)
                        .createSimpleBulletin(R.raw.saved_messages, LocaleController.getString(R.string.AudioSaveToMyProfileSaved))
                        .show();
                o.dismiss();
            }, false);
        });
        o2.add(R.drawable.msg_saved, LocaleController.getString(R.string.AudioSaveToSavedMessages), () -> {
            forwardToDialog(messageObject, UserConfig.getInstance(currentAccount).getClientUserId());
            BulletinFactory.of(container, null)
                    .createSimpleBulletin(R.raw.saved_messages, LocaleController.getString(R.string.AudioSaveToSavedMessagesSaved))
                    .show();
            o.dismiss();
        });
        o2.add(R.drawable.menu_download_round, LocaleController.getString(R.string.AudioSaveToMusicFolder), () -> {
            saveToMusicFolder(messageObject);
            o.dismiss();
        });
        o2.addGap();
        o2.addText(LocaleController.getString(R.string.AudioSaveToInfo), 12, dp(200));
        return o2;
    }

    private void savePlayingToProfile(boolean save) {
        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null) {
            return;
        }
        saveToProfile(messageObject, save, () -> {
            applyModeChrome();
            BulletinFactory.of(container, null)
                    .createSimpleBulletin(
                            save ? R.raw.saved_messages : R.raw.ic_delete,
                            LocaleController.getString(save ? R.string.AudioSaveToMyProfileSaved : R.string.AudioSaveToMyProfileUnsaved))
                    .show();
        }, false);
    }

    private void saveToProfile(MessageObject messageObject, boolean save, Runnable done, boolean triedFileRef) {
        final TLRPC.Document document = messageObject.getDocument();
        if (document == null) {
            return;
        }
        final int currentAccount = messageObject.currentAccount;
        final long documentId = document.id;
        final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = !save;
        req.id = new TLRPC.TL_inputDocument();
        req.id.id = documentId;
        req.id.access_hash = document.access_hash;
        req.id.file_reference = document.file_reference != null ? document.file_reference : new byte[0];
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> {
            if (err != null && FileRefController.isFileRefError(err.text)) {
                if (triedFileRef || messageObject.getId() < 0) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (container != null) {
                            BulletinFactory.of(container, null).showForError(err);
                        }
                    });
                    return;
                }
                refreshFileRefAndRetrySave(messageObject, save, done);
                return;
            } else if (err != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (container != null) {
                        BulletinFactory.of(container, null).showForError(err);
                    }
                });
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(currentAccount).getSavedMusicIds().update(documentId, save);
                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                final TLRPC.UserFull userInfo = MessagesController.getInstance(currentAccount).getUserFull(selfId);
                if (userInfo != null) {
                    if (save) {
                        userInfo.flags2 |= TLObject.FLAG_21;
                        userInfo.saved_music = document;
                    } else if (userInfo.saved_music != null && userInfo.saved_music.id == documentId) {
                        userInfo.flags2 &= ~TLObject.FLAG_21;
                        userInfo.saved_music = null;
                    }
                    MessagesStorage.getInstance(currentAccount).updateUserInfo(userInfo, true);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.profileMusicUpdated, selfId);
                }
                if (done != null) {
                    done.run();
                }
            });
        });
    }

    private void refreshFileRefAndRetrySave(MessageObject messageObject, boolean save, Runnable done) {
        final int currentAccount = messageObject.currentAccount;
        final int msgId = messageObject.getId();
        if (messageObject.getDialogId() >= 0) {
            TLRPC.TL_messages_getMessages fileRefReq = new TLRPC.TL_messages_getMessages();
            fileRefReq.id.add(msgId);
            ConnectionsManager.getInstance(currentAccount).sendRequest(fileRefReq, (res1, err1) -> {
                if (res1 instanceof TLRPC.messages_Messages) {
                    TLRPC.Message message = findMessageById((TLRPC.messages_Messages) res1, msgId);
                    if (message != null) {
                        saveToProfile(new MessageObject(currentAccount, message, false, true), save, done, true);
                        return;
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (container != null && err1 != null) {
                        BulletinFactory.of(container, null).showForError(err1);
                    }
                });
            });
        } else {
            TLRPC.TL_channels_getMessages fileRefReq = new TLRPC.TL_channels_getMessages();
            fileRefReq.channel = MessagesController.getInstance(currentAccount).getInputChannel(-messageObject.getDialogId());
            fileRefReq.id.add(msgId);
            ConnectionsManager.getInstance(currentAccount).sendRequest(fileRefReq, (res1, err1) -> {
                if (res1 instanceof TLRPC.messages_Messages) {
                    TLRPC.Message message = findMessageById((TLRPC.messages_Messages) res1, msgId);
                    if (message != null) {
                        saveToProfile(new MessageObject(currentAccount, message, false, true), save, done, true);
                        return;
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (container != null && err1 != null) {
                        BulletinFactory.of(container, null).showForError(err1);
                    }
                });
            });
        }
    }

    private static TLRPC.Message findMessageById(TLRPC.messages_Messages r, int msgId) {
        for (int i = 0; i < r.messages.size(); i++) {
            if (r.messages.get(i).id == msgId) {
                return r.messages.get(i);
            }
        }
        return null;
    }

    private void forwardToDialog(MessageObject messageObject, long dialogId) {
        final int currentAccount = messageObject.currentAccount;
        ArrayList<MessageObject> list = new ArrayList<>();
        list.add(messageObject);
        SendMessagesHelper.getInstance(currentAccount).sendMessage(list, dialogId, false, false, true, 0, 0);
    }

    private void saveToMusicFolder(MessageObject messageObject) {
        LaunchActivity parentActivity = LaunchActivity.instance;
        if (parentActivity == null) {
            return;
        }
        final int currentAccount = messageObject.currentAccount;
        if (Build.VERSION.SDK_INT >= 23 && (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE)
                && parentActivity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            parentActivity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            return;
        }
        String fileName = FileLoader.getDocumentFileName(messageObject.getDocument());
        if (TextUtils.isEmpty(fileName)) {
            fileName = messageObject.getFileName();
        }
        String path = messageObject.messageOwner.attachPath;
        if (path != null && path.length() > 0) {
            File temp = new File(path);
            if (!temp.exists()) {
                path = null;
            }
        }
        if (path == null || path.length() == 0) {
            path = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner).toString();
        }
        MediaController.saveFile(path, parentActivity, 3, fileName,
                messageObject.getDocument() != null ? messageObject.getDocument().mime_type : "",
                uri -> BulletinFactory.of(container, null).createDownloadBulletin(BulletinFactory.FileType.AUDIO).show());
    }

    private void shareTelegramMusic(MessageObject messageObject) {
        LaunchActivity parentActivity = LaunchActivity.instance;
        if (parentActivity == null) {
            return;
        }
        try {
            final int currentAccount = messageObject.currentAccount;
            File f = null;
            if (!TextUtils.isEmpty(messageObject.messageOwner.attachPath)) {
                f = new File(messageObject.messageOwner.attachPath);
                if (!f.exists()) {
                    f = null;
                }
            }
            if (f == null) {
                f = FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner);
            }
            if (f != null && f.exists()) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(messageObject.getMimeType());
                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", f));
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignore) {
                        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                    }
                } else {
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f));
                }
                parentActivity.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(parentActivity);
                builder.setTitle(LocaleController.getString(R.string.AppName));
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                builder.setMessage(LocaleController.getString(R.string.PleaseDownload));
                builder.show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void openPlayingInChat(MessageObject messageObject) {
        LaunchActivity parentActivity = LaunchActivity.instance;
        if (parentActivity == null) {
            return;
        }
        final int currentAccount = messageObject.currentAccount;
        if (UserConfig.selectedAccount != currentAccount) {
            parentActivity.switchToAccount(currentAccount, true);
        }
        Bundle args = new Bundle();
        long did = messageObject.getDialogId();
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
        } else if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did);
        } else {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-did);
            if (chat != null && chat.migrated_to != null) {
                args.putLong("migrated_to", did);
                did = -chat.migrated_to.channel_id;
            }
            args.putLong("chat_id", -did);
        }
        args.putInt("message_id", messageObject.getId());
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.closeChats);
        pendingAfterDismiss = () -> parentActivity.presentFragment(new ChatActivity(args), false, false);
        super.setOnDismissListener((DialogInterface dialog) -> {
            Runnable r = pendingAfterDismiss;
            pendingAfterDismiss = null;
            if (r != null) {
                AndroidUtilities.runOnUIThread(r, 40);
            }
        });
        dismiss();
    }

    /** Collapse the sheet, then open the standard forward chat picker. */
    private void openForward() {
        if (isYandexMode()) {
            return;
        }
        final MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();
        if (messageObject == null || MglaYandexMusicPlayback.isYandexMessage(messageObject)) {
            return;
        }
        if (messageObject.messageOwner != null && messageObject.messageOwner.noforwards) {
            return;
        }
        final int currentAccount = messageObject.currentAccount;
        if (UserConfig.selectedAccount != currentAccount) {
            LaunchActivity launchActivity = LaunchActivity.instance;
            if (launchActivity != null) {
                launchActivity.switchToAccount(currentAccount, true);
            }
        }

        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", true);
        final DialogsActivity fragment = new DialogsActivity(args);

        final ArrayList<MessageObject> fmessages;
        final TLRPC.TL_document document;
        if (messageObject.getId() < 0) {
            fmessages = null;
            if (!(messageObject.getDocument() instanceof TLRPC.TL_document)) {
                return;
            }
            document = (TLRPC.TL_document) messageObject.getDocument();
        } else {
            fmessages = new ArrayList<>();
            fmessages.add(messageObject);
            document = null;
        }

        fragment.setDelegate((fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.size() > 1 || dids.get(0).dialogId == UserConfig.getInstance(currentAccount).getClientUserId() || message != null || fmessages == null) {
                for (int a = 0; a < dids.size(); a++) {
                    long did = dids.get(a).dialogId;
                    if (message != null) {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did, null, null, null, true, null, null, null, true, 0, 0, null, false));
                    }
                    if (fmessages != null) {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(fmessages, did, false, false, true, 0, 0);
                    } else {
                        SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(document, null, messageObject.messageOwner.attachPath, did, null, null, null, null, null, null, notify, scheduleDate, 0, 0, null, null, false, false));
                    }
                }
                fragment1.finishFragment();
                final BaseFragment lastFragment = LaunchActivity.getLastFragment();
                if (lastFragment != null) {
                    BulletinFactory.of(lastFragment)
                            .createSimpleBulletin(
                                    R.raw.forward,
                                    dids.size() == 1 && dids.get(0).dialogId == UserConfig.getInstance(currentAccount).getClientUserId()
                                            ? LocaleController.getString(R.string.FwdMessageToSavedMessages)
                                            : dids.size() == 1 && dids.get(0).dialogId > 0
                                            ? LocaleController.formatString(R.string.FwdMessageToUser, DialogObject.getShortName(dids.get(0).dialogId))
                                            : dids.size() == 1 && dids.get(0).dialogId < 0
                                            ? LocaleController.formatString(R.string.FwdMessageToGroup, DialogObject.getShortName(dids.get(0).dialogId))
                                            : LocaleController.formatPluralStringComma("FwdMessageToManyChats", dids.size())
                            )
                            .show();
                }
            } else {
                MessagesStorage.TopicKey topicKey = dids.get(0);
                long did = topicKey.dialogId;
                Bundle args1 = new Bundle();
                args1.putBoolean("scrollToTopOnResume", true);
                if (DialogObject.isEncryptedDialog(did)) {
                    args1.putInt("enc_id", DialogObject.getEncryptedChatId(did));
                } else if (DialogObject.isUserDialog(did)) {
                    args1.putLong("user_id", did);
                } else {
                    args1.putLong("chat_id", -did);
                }
                ChatActivity chatActivity = new ChatActivity(args1);
                if (topicKey.topicId != 0) {
                    ForumUtilities.applyTopic(chatActivity, topicKey);
                }
                BaseFragment host = LaunchActivity.getLastFragment();
                LaunchActivity launchActivity = LaunchActivity.instance;
                boolean presented = false;
                if (launchActivity != null) {
                    presented = launchActivity.presentFragment(chatActivity, true, false);
                } else if (host != null) {
                    presented = host.presentFragment(chatActivity, true);
                }
                if (presented) {
                    chatActivity.showFieldPanelForForward(true, fmessages);
                    if (topicKey.topicId != 0) {
                        fragment1.removeSelfFromStack();
                    }
                } else {
                    fragment1.finishFragment();
                }
            }
            return true;
        });

        final LaunchActivity launchActivity = LaunchActivity.instance;
        pendingAfterDismiss = () -> {
            if (launchActivity != null && !launchActivity.isFinishing()) {
                launchActivity.presentFragment(fragment);
            } else {
                BaseFragment last = LaunchActivity.getLastFragment();
                if (last != null) {
                    last.presentFragment(fragment);
                }
            }
        };
        super.setOnDismissListener((DialogInterface dialog) -> {
            Runnable r = pendingAfterDismiss;
            pendingAfterDismiss = null;
            if (r != null) {
                AndroidUtilities.runOnUIThread(r, 40);
            }
        });
        dismiss();
    }

    private static long resolveTelegramDurationMs(MessageObject playing) {
        if (playing == null) {
            return 0L;
        }
        if (playing.audioPlayerDuration > 0) {
            return playing.audioPlayerDuration * 1000L;
        }
        double sec = playing.getDuration();
        return sec > 0 ? (long) (sec * 1000L) : 0L;
    }

    private long getActiveDurationMs() {
        if (isYandexMode()) {
            return Math.max(0L, player.getDuration());
        }
        return resolveTelegramDurationMs(MediaController.getInstance().getPlayingMessageObject());
    }

    private long getActivePositionMs() {
        if (isYandexMode()) {
            return Math.max(0L, player.getCurrentPosition());
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return 0L;
        }
        long dur = resolveTelegramDurationMs(playing);
        if (dur > 0 && playing.audioProgress > 0f) {
            return (long) (playing.audioProgress * dur);
        }
        return Math.max(0, playing.audioProgressMs);
    }

    private void updateProgressFromActive() {
        long pos = getActivePositionMs();
        long dur = getActiveDurationMs();
        if (dur > 0 && seekBar != null) {
            // Snap immediately — animating from 0 on open makes the thumb crawl to the playhead.
            seekBar.setProgress(pos / (float) dur, false);
            timeCurrentView.setText(formatTime(pos));
            timeTotalView.setText(formatTime(dur));
        }
        updateSyncedLyrics(pos);
    }

    private void togglePlayPause() {
        if (isYandexMode()) {
            player.togglePlayPause();
            return;
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        if (MediaController.getInstance().isMessagePaused()) {
            MediaController.getInstance().playMessage(playing);
        } else {
            MediaController.getInstance().pauseMessage(playing);
        }
    }

    private void skipPrevious() {
        if (isYandexMode()) {
            player.prev();
        } else {
            MediaController.getInstance().playPreviousMessage();
        }
    }

    private void skipNext() {
        if (isYandexMode()) {
            player.next();
        } else {
            MediaController.getInstance().playNextMessage();
        }
    }

    private void seekActiveTo(long positionMs) {
        if (isYandexMode()) {
            player.seekTo(positionMs);
            return;
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        long dur = resolveTelegramDurationMs(playing);
        if (playing != null && dur > 0) {
            MediaController.getInstance().seekToProgress(playing, positionMs / (float) dur);
        }
    }

    private void registerObservers() {
        if (observersRegistered) {
            return;
        }
        observersRegistered = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingDidStart);
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingDidReset);
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.musicIdsLoaded);
        }
        ensureSavedMusicIdsLoading();
    }

    private void unregisterObservers() {
        if (!observersRegistered) {
            return;
        }
        observersRegistered = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.messagePlayingDidStart);
            NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.messagePlayingDidReset);
            NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.musicIdsLoaded);
        }
    }

    private void extractAmbientColors(String coverUrl) {
        if (TextUtils.isEmpty(coverUrl) || coverUrl.equals(lastCoverUrl)) {
            return;
        }
        lastCoverUrl = coverUrl;
        if (!MglaPlayerConfig.isDynamicColorsEnabled()) {
            if (ambientBg != null) {
                ambientBg.setColors(0xFF2A1F3D, 0xFF1A2438, 0xFF142028, 0xFF0E0E12);
            }
            applyAccentColor(ACCENT_FALLBACK);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bmp = MglaMusicImageHelper.loadBitmapSync(coverUrl, 64);
            if (bmp == null) {
                return;
            }
            int[] colors = DominantColors.getColorsSync(false, bmp, true);
            if (!bmp.isRecycled()) {
                bmp.recycle();
            }
            final int c1 = colors != null && colors.length > 0 ? colors[0] : 0xFF3A2A4A;
            final int c2 = colors != null && colors.length > 1 ? colors[1] : 0xFF1A2A3A;
            AndroidUtilities.runOnUIThread(() -> {
                if (ambientBg != null) {
                    ambientBg.setColors(c1, c2, darken(c1, 0.45f), darken(c2, 0.55f));
                }
                applyAccentColor(c1);
            });
        });
    }

    private static int darken(int color, float amount) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0.08f, hsv[2] * (1f - amount));
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        return Color.HSVToColor(hsv);
    }

    private void updateLikeButton() {
        if (likeButton == null) {
            return;
        }
        MglaYandexMusicModel.Track track = player.getCurrentTrack();
        boolean liked = track != null && track.isLiked;
        likeButton.setImageResource(liked ? R.drawable.media_like_active : R.drawable.media_like);
        likeButton.setColorFilter(new PorterDuffColorFilter(liked ? 0xFFFF334B : Color.WHITE, PorterDuff.Mode.SRC_IN));
    }

    private void updateYandexRepeatIcon() {
        if (repeatPillIcon == null) {
            return;
        }
        int mode = MglaYandexMusicStore.getInstance().getRepeatMode();
        // 0 = off, 1 = whole queue/playlist, 2 = one track
        if (mode == 2) {
            repeatPillIcon.setImageResource(R.drawable.player_new_repeatone);
            repeatPillIcon.setColorFilter(new PorterDuffColorFilter(currentAccent, PorterDuff.Mode.SRC_IN));
            repeatPillIcon.setAlpha(1f);
        } else if (mode == 1) {
            repeatPillIcon.setImageResource(R.drawable.player_new_repeatall);
            repeatPillIcon.setColorFilter(new PorterDuffColorFilter(currentAccent, PorterDuff.Mode.SRC_IN));
            repeatPillIcon.setAlpha(1f);
        } else {
            repeatPillIcon.setImageResource(R.drawable.player_new_repeat_off);
            repeatPillIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            repeatPillIcon.setAlpha(0.55f);
        }
    }

    private void updateLyricsPillState(MglaYandexMusicModel.Track track) {
        if (lyricsPill == null || lyricsPillLabel == null) {
            return;
        }
        boolean has;
        if (track == null || TextUtils.isEmpty(track.id)) {
            has = false;
        } else if (lyricsMissing.contains(track.id)) {
            has = false;
        } else if (lyricsCache.containsKey(track.id)) {
            MglaYandexMusicApi.LyricsData d = lyricsCache.get(track.id);
            has = d != null && !d.isEmpty();
        } else {
            has = true;
        }
        lyricsPill.setEnabled(has);
        lyricsPill.setClickable(has);
        lyricsPill.setAlpha(has ? 1f : 0.35f);
        lyricsPillLabel.setAlpha(1f);
        lyricsPillLabel.setTextColor(lyricsVisible && has ? currentAccent : Color.WHITE);
    }

    private void prefetchLyrics(MglaYandexMusicModel.Track track) {
        if (track == null || TextUtils.isEmpty(track.id)) {
            return;
        }
        final String id = track.id;
        if (lyricsCache.containsKey(id) || lyricsMissing.contains(id) || lyricsInflight.contains(id)) {
            updateLyricsPillState(track);
            return;
        }
        lyricsRequestTrackId = id;
        lyricsInflight.add(id);
        MglaYandexMusicApi.fetchLyrics(id, new MglaYandexMusicApi.Callback<MglaYandexMusicApi.LyricsData>() {
            @Override
            public void onSuccess(MglaYandexMusicApi.LyricsData result) {
                lyricsInflight.remove(id);
                if (result == null || result.isEmpty()) {
                    lyricsMissing.add(id);
                    lyricsCache.remove(id);
                } else {
                    lyricsMissing.remove(id);
                    lyricsCache.put(id, result);
                }
                MglaYandexMusicModel.Track current = player.getCurrentTrack();
                if (current != null && id.equals(current.id)) {
                    updateLyricsPillState(current);
                    if (lyricsVisible) {
                        if (result != null && !result.isEmpty()) {
                            showLyricsOverlay(result);
                        } else {
                            showLyricsUnavailable();
                        }
                    }
                }
            }

            @Override
            public void onError(Exception error) {
                lyricsInflight.remove(id);
                lyricsMissing.add(id);
                FileLog.e("lyrics fetch failed id=" + id, error);
                MglaYandexMusicModel.Track current = player.getCurrentTrack();
                if (current != null && id.equals(current.id)) {
                    updateLyricsPillState(current);
                    if (lyricsVisible) {
                        showLyricsUnavailable();
                    }
                }
            }
        });
    }

    private void onLyricsPillClicked() {
        if (!isYandexMode() || lyricsPill == null) {
            return;
        }
        // Always allow closing lyrics, even if the pill later becomes disabled (no lyrics).
        if (lyricsVisible) {
            hideLyricsOverlay(true);
            return;
        }
        if (!lyricsPill.isEnabled()) {
            return;
        }
        MglaYandexMusicModel.Track track = player.getCurrentTrack();
        if (track == null || TextUtils.isEmpty(track.id)) {
            return;
        }
        MglaYandexMusicApi.LyricsData cached = lyricsCache.get(track.id);
        if (cached != null && !cached.isEmpty()) {
            showLyricsOverlay(cached);
            return;
        }
        if (lyricsMissing.contains(track.id)) {
            showLyricsUnavailable();
            updateLyricsPillState(track);
            return;
        }
        showLyricsLoading();
        prefetchLyrics(track);
    }

    private void showLyricsLoading() {
        MglaYandexMusicApi.LyricsData loading = new MglaYandexMusicApi.LyricsData(false);
        loading.lines.add(new MglaYandexMusicApi.LyricsLine(-1, "Загрузка текста…"));
        showLyricsOverlay(loading);
    }

    private void showLyricsUnavailable() {
        MglaYandexMusicApi.LyricsData empty = new MglaYandexMusicApi.LyricsData(false);
        empty.lines.add(new MglaYandexMusicApi.LyricsLine(-1, "Текст недоступен"));
        showLyricsOverlay(empty);
    }

    private void showLyricsOverlay(MglaYandexMusicApi.LyricsData data) {
        lyricsVisible = true;
        activeLyrics = data;
        activeLyricsLine = -1;
        lyricsUserScrolling = false;
        AndroidUtilities.cancelRunOnUIThread(lyricsResumeAutoScroll);

        if (lyricsScrim != null) {
            lyricsScrim.setVisibility(View.VISIBLE);
        }
        if (lyricsScroll != null) {
            lyricsScroll.setVisibility(View.VISIBLE);
            lyricsScroll.scrollTo(0, 0);
        }
        bindLyricsLines(data);

        // Cover must not show through — lyrics replace the art in this window.
        if (coverImage != null) {
            coverImage.setVisibility(View.INVISIBLE);
            coverImage.setBackground(null);
        }
        if (avatarCoverView != null) {
            avatarCoverView.setVisibility(View.INVISIBLE);
        }
        // Drop the grey plate + elevation shadow bands so only ambient gradient shows.
        if (coverWrap != null) {
            coverWrap.setBackground(null);
            coverWrap.setElevation(0f);
        }
        stopCoverPulse(false);
        updateLyricsPillState(player.getCurrentTrack());
        updateSyncedLyrics(getActivePositionMs());
    }

    private void bindLyricsLines(MglaYandexMusicApi.LyricsData data) {
        if (lyricsLinesCol == null) {
            return;
        }
        // Lock column width to the cover window so TextViews must wrap.
        int windowW = coverWrap != null && coverWrap.getWidth() > 0 ? coverWrap.getWidth() : coverSize;
        ViewGroup.LayoutParams colLp = lyricsLinesCol.getLayoutParams();
        if (colLp != null) {
            colLp.width = windowW;
            lyricsLinesCol.setLayoutParams(colLp);
        }
        lyricsLinesCol.removeAllViews();
        final int lineWidth = Math.max(dp(48), windowW - lyricsLinesCol.getPaddingLeft() - lyricsLinesCol.getPaddingRight());
        if (data == null || data.isEmpty()) {
            TextView empty = makeLyricsLineView("Текст недоступен", false, lineWidth);
            empty.setAlpha(0.7f);
            lyricsLinesCol.addView(empty, new LinearLayout.LayoutParams(lineWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (int i = 0; i < data.lines.size(); i++) {
            MglaYandexMusicApi.LyricsLine line = data.lines.get(i);
            TextView tv = makeLyricsLineView(line.text, data.synced, lineWidth);
            if (data.synced && line.timeMs >= 0) {
                tv.setOnClickListener(v -> {
                    if (!isYandexMode()) {
                        return;
                    }
                    player.seekTo(line.timeMs);
                    activeLyricsLine = -1;
                    lyricsUserScrolling = false;
                    updateSyncedLyrics(line.timeMs);
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(lineWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(data.synced ? 12 : 8);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lyricsLinesCol.addView(tv, lp);
        }
    }

    private TextView makeLyricsLineView(String text, boolean syncedStyle, int maxWidthPx) {
        TextView tv = new TextView(lyricsLinesCol.getContext()) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int exact = MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY);
                super.onMeasure(exact, heightMeasureSpec);
            }
        };
        tv.setText(text);
        // Fixed size for all lines — highlight is color/alpha only (no jump/cut on long lines).
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, syncedStyle ? 15 : 14);
        tv.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        tv.setTextColor(Color.argb(140, 255, 255, 255));
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setLineSpacing(dp(3), 1.05f);
        tv.setShadowLayer(dp(5), 0, dp(1), 0xAA000000);
        tv.setSingleLine(false);
        tv.setMaxLines(12);
        tv.setEllipsize(null);
        tv.setHorizontallyScrolling(false);
        tv.setPadding(dp(2), 0, dp(2), 0);
        if (Build.VERSION.SDK_INT >= 23) {
            tv.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
            tv.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        }
        return tv;
    }

    private void markLyricsUserScrolling() {
        lyricsUserScrolling = true;
        lyricsUserScrollUntil = System.currentTimeMillis() + 2500;
        AndroidUtilities.cancelRunOnUIThread(lyricsResumeAutoScroll);
        AndroidUtilities.runOnUIThread(lyricsResumeAutoScroll, 2500);
    }

    private void updateSyncedLyrics(long positionMs) {
        if (!lyricsVisible || activeLyrics == null || lyricsLinesCol == null) {
            return;
        }
        if (!activeLyrics.synced) {
            return;
        }
        int idx = findLyricsLineIndex(activeLyrics, positionMs);
        if (idx == activeLyricsLine) {
            return;
        }
        int prev = activeLyricsLine;
        activeLyricsLine = idx;
        animateLyricsHighlight(prev, idx);
        if (!lyricsUserScrolling && System.currentTimeMillis() >= lyricsUserScrollUntil) {
            scrollLyricsToLine(idx);
        }
    }

    private static int findLyricsLineIndex(MglaYandexMusicApi.LyricsData data, long positionMs) {
        if (data == null || data.lines.isEmpty()) {
            return -1;
        }
        int best = -1;
        for (int i = 0; i < data.lines.size(); i++) {
            long t = data.lines.get(i).timeMs;
            if (t < 0) {
                continue;
            }
            if (t <= positionMs) {
                best = i;
            } else {
                break;
            }
        }
        return best;
    }

    private void animateLyricsHighlight(int prevIndex, int nextIndex) {
        if (lyricsHighlightAnimator != null) {
            lyricsHighlightAnimator.cancel();
            lyricsHighlightAnimator = null;
        }
        final TextView prev = lyricsLineAt(prevIndex);
        final TextView next = lyricsLineAt(nextIndex);
        final int inactiveColor = Color.argb(140, 255, 255, 255);
        final int activeColor = Color.WHITE;
        if (prev == null && next == null) {
            return;
        }
        final float prevStartA = prev != null ? prev.getAlpha() : 0f;
        final float nextStartA = next != null ? next.getAlpha() : 0f;
        lyricsHighlightAnimator = ValueAnimator.ofFloat(0f, 1f);
        lyricsHighlightAnimator.setDuration(MglaPlayerConfig.scaleDuration(320));
        lyricsHighlightAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        lyricsHighlightAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            if (prev != null) {
                prev.setAlpha(prevStartA + (0.45f - prevStartA) * t);
                prev.setTextColor(blendArgb(activeColor, inactiveColor, t));
            }
            if (next != null) {
                next.setAlpha(nextStartA + (1f - nextStartA) * t);
                next.setTextColor(blendArgb(inactiveColor, activeColor, t));
            }
        });
        lyricsHighlightAnimator.start();
    }

    private TextView lyricsLineAt(int index) {
        if (lyricsLinesCol == null || index < 0 || index >= lyricsLinesCol.getChildCount()) {
            return null;
        }
        View child = lyricsLinesCol.getChildAt(index);
        return child instanceof TextView ? (TextView) child : null;
    }

    private static int blendArgb(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    private void scrollLyricsToLine(int index) {
        if (lyricsScroll == null || lyricsLinesCol == null || index < 0 || index >= lyricsLinesCol.getChildCount()) {
            return;
        }
        View child = lyricsLinesCol.getChildAt(index);
        if (child == null) {
            return;
        }
        int target = child.getTop() + child.getHeight() / 2 - lyricsScroll.getHeight() / 2
                + lyricsLinesCol.getPaddingTop();
        target = Math.max(0, Math.min(target, Math.max(0, lyricsLinesCol.getHeight() - lyricsScroll.getHeight())));
        if (lyricsScrollAnimator != null) {
            lyricsScrollAnimator.cancel();
        }
        final int from = lyricsScroll.getScrollY();
        if (Math.abs(from - target) < dp(2)) {
            return;
        }
        lyricsScrollAnimator = ValueAnimator.ofInt(from, target);
        lyricsScrollAnimator.setDuration(MglaPlayerConfig.scaleDuration(420));
        lyricsScrollAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        lyricsScrollAnimator.addUpdateListener(a -> {
            if (lyricsScroll != null) {
                lyricsScroll.scrollTo(0, (int) a.getAnimatedValue());
            }
        });
        lyricsScrollAnimator.start();
    }

    private void hideLyricsOverlay(boolean unused) {
        lyricsVisible = false;
        activeLyrics = null;
        activeLyricsLine = -1;
        lyricsUserScrolling = false;
        AndroidUtilities.cancelRunOnUIThread(lyricsResumeAutoScroll);
        if (lyricsHighlightAnimator != null) {
            lyricsHighlightAnimator.cancel();
            lyricsHighlightAnimator = null;
        }
        if (lyricsScrollAnimator != null) {
            lyricsScrollAnimator.cancel();
            lyricsScrollAnimator = null;
        }
        if (lyricsScrim != null) {
            lyricsScrim.setVisibility(View.GONE);
        }
        if (lyricsScroll != null) {
            lyricsScroll.setVisibility(View.GONE);
        }
        if (lyricsLinesCol != null) {
            lyricsLinesCol.removeAllViews();
        }
        if (coverImage != null) {
            coverImage.setVisibility(View.VISIBLE);
            coverImage.setAlpha(1f);
            GradientDrawable coverBg = new GradientDrawable();
            coverBg.setCornerRadius(coverRadius);
            coverBg.setColor(0xFF2A2A2A);
            coverImage.setBackground(coverBg);
        }
        if (coverWrap != null) {
            GradientDrawable wrapBg = new GradientDrawable();
            wrapBg.setCornerRadius(coverRadius);
            wrapBg.setColor(0xFF2A2A2A);
            coverWrap.setBackground(wrapBg);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                coverWrap.setElevation(dp(4));
            }
        }
        if (avatarCoverView != null) {
            avatarCoverView.setAlpha(1f);
            // Visibility restored by showMusicCover / showAvatarCover on next bind.
        }
        updateLyricsPillState(player.getCurrentTrack());
        boolean playing = isYandexMode() ? player.isPlaying()
                : (MediaController.getInstance().getPlayingMessageObject() != null
                && !MediaController.getInstance().isMessagePaused());
        updateCoverPulse(playing);
    }

    private void updateControlsState() {
        boolean isPlaying;
        if (isYandexMode()) {
            isPlaying = player.isPlaying();
        } else {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            isPlaying = playing != null
                    && !MediaController.getInstance().isMessagePaused()
                    && !MediaController.getInstance().isDownloadingCurrentMessage();
        }
        playPauseIcon.setImageResource(isPlaying ? R.drawable.ic_action_pause : R.drawable.ic_action_play);
        if (seekBar != null) {
            seekBar.setPlaying(isPlaying);
        }
        if (shufflePillIcon != null) {
            boolean shuffle = MglaYandexMusicStore.getInstance().isShuffle();
            shufflePillIcon.setColorFilter(new PorterDuffColorFilter(shuffle ? currentAccent : Color.WHITE, PorterDuff.Mode.SRC_IN));
            if (shufflePill != null) {
                shufflePill.setAlpha(1f);
            }
        }
        if (isYandexMode()) {
            updateYandexRepeatIcon();
        } else {
            updateTelegramRepeatIcon();
        }
        if (openAnimationPlayed || !MglaPlayerConfig.isOpenAnimationEnabled()) {
            updateCoverPulse(isPlaying);
        }
    }

    private String formatTime(long ms) {
        if (ms <= 0) return "0:00";
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    @Override
    public void show() {
        super.show();
        refreshUi();
        if (ambientBg != null) {
            if (MglaPlayerConfig.isAmbientMotionEnabled()) {
                ambientBg.start();
            } else {
                ambientBg.stop();
                ambientBg.invalidate();
            }
        }
    }

    @Override
    public void onOpenAnimationEnd() {
        playOpenAnimation();
    }

    @Override
    public void dismissInternal() {
        MglaGlobalMusicBar.setHiddenForPlayerExpand(false);
        // Safe now that the window is going away — no fullscreen flash.
        clearMorphClip();
        super.dismissInternal();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.musicIdsLoaded) {
            applyModeChrome();
            return;
        }
        if (isUserSeeking || (seekBar != null && seekBar.isTracking())) {
            return;
        }
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            updateProgressFromActive();
            return;
        }
        refreshUi();
    }

    @Override
    public void onTrackChanged(MglaYandexMusicModel.Track track) {
        AndroidUtilities.runOnUIThread(this::refreshUi);
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying, boolean isBuffering) {
        AndroidUtilities.runOnUIThread(this::updateControlsState);
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
        if (!isYandexMode()) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (isUserSeeking || (seekBar != null && seekBar.isTracking())) {
                updateSyncedLyrics(positionMs);
                return;
            }
            if (durationMs > 0 && seekBar != null) {
                seekBar.setProgress(positionMs / (float) durationMs, false);
                timeCurrentView.setText(formatTime(positionMs));
                timeTotalView.setText(formatTime(durationMs));
            }
            updateSyncedLyrics(positionMs);
        });
    }

    @Override
    public void onQueueChanged() {
        AndroidUtilities.runOnUIThread(this::refreshUi);
    }

    private void cleanupSheetResources() {
        if (sheetCleanupDone) {
            return;
        }
        sheetCleanupDone = true;
        AndroidUtilities.cancelRunOnUIThread(clearUserSeekingRunnable);
        AndroidUtilities.cancelRunOnUIThread(lyricsResumeAutoScroll);
        isUserSeeking = false;
        if (ambientBg != null) {
            ambientBg.stop();
        }
        if (coverPulseAnimator != null) {
            coverPulseAnimator.cancel();
            coverPulseAnimator = null;
        }
        if (lyricsHighlightAnimator != null) {
            lyricsHighlightAnimator.cancel();
            lyricsHighlightAnimator = null;
        }
        if (lyricsScrollAnimator != null) {
            lyricsScrollAnimator.cancel();
            lyricsScrollAnimator = null;
        }
        if (fabColorAnimator != null) {
            fabColorAnimator.cancel();
            fabColorAnimator = null;
        }
        unregisterObservers();
        player.removeListener(this);
    }

    private void restoreSystemBarsAfterDismiss() {
        try {
            if (LaunchActivity.instance != null) {
                LaunchActivity.instance.checkSystemBarColors(true, true, true);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onContainerTranslationYChanged(float translationY) {
        super.onContainerTranslationYChanged(translationY);
        // While the user is swiping the sheet down, keep the mini-player hidden so it
        // doesn't flash in the gap above the sheet before morph-close starts.
        if (!morphCloseFinishing && usedMorphOpen) {
            MglaGlobalMusicBar.setHiddenForPlayerExpand(true);
        }
    }

    @Override
    public void dismiss() {
        // Finishing after reverse-morph: tear down the window without another animation.
        if (morphCloseFinishing) {
            cleanupSheetResources();
            super.dismiss();
            restoreSystemBarsAfterDismiss();
            return;
        }

        // Already reverse-morphing (ignore swipe/double-tap).
        if (morphCloseStarted) {
            return;
        }

        // Own the close morph here so swipe (allowCustomAnimation=false) still animates correctly.
        // Keep ambient/resources alive until the morph finishes — cleanup mid-swipe caused a blink.
        if (startMorphCloseAnimation()) {
            morphCloseStarted = true;
            return;
        }

        cleanupSheetResources();
        MglaGlobalMusicBar.setHiddenForPlayerExpand(false);
        super.dismiss();
        restoreSystemBarsAfterDismiss();
    }

    /**
     * Soft animated multi-blob gradient that fills the sheet using cover palette colors.
     */
    private static class AmbientBackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors = new int[]{0xFF2A1F3D, 0xFF1A2438, 0xFF142028, 0xFF0E0E12};
        private final int[] fromColors = new int[4];
        private final int[] toColors = new int[4];
        private float phase;
        private ValueAnimator animator;
        private ValueAnimator colorAnimator;

        AmbientBackgroundView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setColors(int c1, int c2, int c3, int c4) {
            final int[] next = new int[]{c1 | 0xFF000000, c2 | 0xFF000000, c3 | 0xFF000000, c4 | 0xFF000000};
            if (!MglaPlayerConfig.isTrackChangeAnimationEnabled()) {
                System.arraycopy(next, 0, colors, 0, 4);
                invalidate();
                return;
            }
            System.arraycopy(colors, 0, fromColors, 0, 4);
            System.arraycopy(next, 0, toColors, 0, 4);
            if (colorAnimator != null) {
                colorAnimator.cancel();
            }
            colorAnimator = ValueAnimator.ofFloat(0f, 1f);
            colorAnimator.setDuration(MglaPlayerConfig.scaleDuration(520));
            colorAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            final ArgbEvaluator evaluator = new ArgbEvaluator();
            colorAnimator.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                for (int i = 0; i < 4; i++) {
                    colors[i] = (int) evaluator.evaluate(t, fromColors[i], toColors[i]);
                }
                invalidate();
            });
            colorAnimator.start();
        }

        void start() {
            if (!MglaPlayerConfig.isAmbientMotionEnabled()) {
                stop();
                return;
            }
            if (animator != null && animator.isRunning()) return;
            animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            animator.setDuration(MglaPlayerConfig.scaleDuration(14000));
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void stop() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (colorAnimator != null) {
                colorAnimator.cancel();
                colorAnimator = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            // Base vertical wash
            paint.setShader(new LinearGradient(0, 0, w * 0.3f, h,
                    new int[]{colors[0], colors[3], colors[1]},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            float cx1 = w * (0.25f + 0.2f * (float) Math.sin(phase));
            float cy1 = h * (0.28f + 0.18f * (float) Math.cos(phase * 0.85f));
            float cx2 = w * (0.75f + 0.18f * (float) Math.cos(phase * 1.1f));
            float cy2 = h * (0.62f + 0.2f * (float) Math.sin(phase * 0.7f));
            float cx3 = w * (0.5f + 0.25f * (float) Math.sin(phase * 0.55f + 1f));
            float cy3 = h * (0.45f + 0.22f * (float) Math.cos(phase * 0.95f + 0.5f));

            drawBlob(canvas, cx1, cy1, Math.max(w, h) * 0.55f, colors[0]);
            drawBlob(canvas, cx2, cy2, Math.max(w, h) * 0.5f, colors[1]);
            drawBlob(canvas, cx3, cy3, Math.max(w, h) * 0.42f, colors[2]);

            // Darken bottom for readability
            paint.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                    new int[]{0x00000000, 0x99000000}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, h * 0.55f, w, h, paint);
            paint.setShader(null);
        }

        private void drawBlob(Canvas canvas, float cx, float cy, float radius, int color) {
            int opaque = color | 0xFF000000;
            int mid = (color & 0x00FFFFFF) | 0x66000000;
            paint.setShader(new RadialGradient(cx, cy, radius,
                    new int[]{opaque, mid, 0x00000000},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
        }
    }
}
