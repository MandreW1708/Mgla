package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.animation.ArgbEvaluator;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
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
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaMd3SeekBar;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Stories.recorder.DominantColors;

import java.io.File;
import java.util.ArrayList;
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
    private TextView waveIslandText;
    private FrameLayout root;
    private LinearLayout contentLayout;
    private View shufflePill;
    private View lyricsPill;
    private View checkPill;
    private View pillsRow;
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
    private String lastCoverUrl;
    private boolean observersRegistered;
    private Runnable pendingAfterDismiss;
    private ValueAnimator coverPulseAnimator;
    private ValueAnimator fabColorAnimator;
    private int currentAccent = ACCENT_FALLBACK;
    private boolean openAnimationPlayed;
    private CharSequence lastTitleShown;
    private CharSequence lastArtistShown;

    public MglaMusicPlayerSheet(Context context) {
        super(context, false);
        this.player = MglaYandexMusicPlayer.getInstance();
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        setCanDismissWithSwipe(true);
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
        return player.getCurrentTrack() != null && (player.isPlaying() || player.isBuffering());
    }

    private void initView(Context context) {
        int fullH = AndroidUtilities.displaySize.y + AndroidUtilities.statusBarHeight;
        int padH = dp(20);
        int bottomPad = dp(16) + AndroidUtilities.navigationBarHeight;

        root = new FrameLayout(context);
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
                    timeCurrentView.setText(formatTime((long) (getActiveDurationMs() * progress)));
                }
            }

            @Override
            public void onStartTracking() {
                isUserSeeking = true;
            }

            @Override
            public void onStopTracking(float progress) {
                isUserSeeking = false;
                seekActiveTo((long) (getActiveDurationMs() * progress));
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

        dislikeButton = iconButton(context, R.drawable.msg_voice_muted, v -> skipNext());
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
        TextView lyricsText = new TextView(context);
        lyricsText.setText("Текст");
        lyricsText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        lyricsText.setTextColor(Color.WHITE);
        lyricsText.setGravity(Gravity.CENTER);
        lyricsPillFrame.addView(lyricsText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        pills.addView(lyricsPillFrame, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 8, 0));

        FrameLayout checkPillFrame = makePill(context);
        checkPill = checkPillFrame;
        ImageView checkIcon = new ImageView(context);
        checkIcon.setImageResource(R.drawable.msg_mini_checks);
        checkIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        checkPillFrame.addView(checkIcon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        pills.addView(checkPillFrame, LayoutHelper.createLinear(0, 48, 1f));
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
            if (isYandexMode() && !player.isMyWaveActive()) {
                player.playMyWave();
            }
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

        if (coverWrap != null && MglaPlayerConfig.isTrackChangeAnimationEnabled()) {
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
        if (!playing || !MglaPlayerConfig.isCoverPulseEnabled()) {
            if (coverPulseAnimator != null) {
                coverPulseAnimator.cancel();
                coverPulseAnimator = null;
            }
            coverWrap.animate().scaleX(1f).scaleY(1f)
                    .setDuration(MglaPlayerConfig.scaleDuration(220))
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .start();
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
            float s = (float) a.getAnimatedValue();
            coverWrap.setScaleX(s);
            coverWrap.setScaleY(s);
        });
        coverPulseAnimator.start();
    }

    private void playOpenAnimation() {
        if (openAnimationPlayed || !MglaPlayerConfig.isOpenAnimationEnabled() || contentLayout == null) {
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
        root.postDelayed(() -> {
            boolean playing;
            if (isYandexMode()) {
                playing = player.isPlaying();
            } else {
                MessageObject m = MediaController.getInstance().getPlayingMessageObject();
                playing = m != null && !MediaController.getInstance().isMessagePaused();
            }
            updateCoverPulse(playing);
        }, delay + MglaPlayerConfig.scaleDuration(420));
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
        if (checkPill != null) {
            checkPill.setVisibility(yandexVis);
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
        }
        if (avatarCoverView != null) {
            avatarCoverView.setVisibility(View.GONE);
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
        showMusicCover();
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
            waveIslandText.setText("Моя волна");
            return;
        }
        setTrackTexts(track.title, track.artist);
        timeTotalView.setText(track.getFormattedDuration());

        String source = player.getCurrentSourceTitle();
        if (TextUtils.isEmpty(source)) {
            source = player.isMyWaveActive() ? "Моя волна" : "Яндекс Музыка";
        }
        sourceTitle.setText(source);

        String coverUrl = track.getCoverUrl(400);
        // Load square bitmap; rounded corners come from coverWrap clipPath.
        MglaMusicImageHelper.bindCover(coverImage, coverUrl, 0, R.drawable.bot_music);
        MglaMusicImageHelper.bindCircleCover(artistAvatar, track.getCoverUrl(100), R.drawable.bot_music);
        extractAmbientColors(coverUrl);

        if (player.isMyWaveActive()) {
            waveIslandText.setText("▶  Моя волна по треку  ·  " + track.title);
        } else {
            waveIslandText.setText("▶  " + source + "  ·  " + track.title);
        }

        updateLikeButton();
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
            seekBar.setProgress(pos / (float) dur, true);
            timeCurrentView.setText(formatTime(pos));
            timeTotalView.setText(formatTime(dur));
        }
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
        int account = UserConfig.selectedAccount;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.musicIdsLoaded);
        ensureSavedMusicIdsLoading();
    }

    private void unregisterObservers() {
        if (!observersRegistered) {
            return;
        }
        observersRegistered = false;
        int account = UserConfig.selectedAccount;
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.musicIdsLoaded);
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
        }
        if (!isYandexMode()) {
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
        root.post(this::playOpenAnimation);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.musicIdsLoaded) {
            applyModeChrome();
            return;
        }
        if (isUserSeeking) {
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
        if (isUserSeeking || !isYandexMode()) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (durationMs > 0 && seekBar != null) {
                seekBar.setProgress(positionMs / (float) durationMs, true);
                timeCurrentView.setText(formatTime(positionMs));
                timeTotalView.setText(formatTime(durationMs));
            }
        });
    }

    @Override
    public void onQueueChanged() {
        AndroidUtilities.runOnUIThread(this::refreshUi);
    }

    @Override
    public void dismiss() {
        if (ambientBg != null) {
            ambientBg.stop();
        }
        if (coverPulseAnimator != null) {
            coverPulseAnimator.cancel();
            coverPulseAnimator = null;
        }
        if (fabColorAnimator != null) {
            fabColorAnimator.cancel();
            fabColorAnimator = null;
        }
        unregisterObservers();
        player.removeListener(this);
        super.dismiss();
        // Restore MainTabs transparent system bars after the sheet window closes.
        try {
            if (LaunchActivity.instance != null) {
                LaunchActivity.instance.checkSystemBarColors(true, true, true);
            }
        } catch (Throwable ignored) {
        }
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
