package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
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
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.MglaMusicImageHelper;
import org.telegram.messenger.MglaYandexMusicModel;
import org.telegram.messenger.MglaYandexMusicPlayback;
import org.telegram.messenger.MglaYandexMusicPlayer;
import org.telegram.messenger.MglaYandexMusicStore;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.AudioPlayerCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Stories.recorder.DominantColors;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Fullscreen Yandex Music–style player sheet with dynamic ambient background.
 * Also used for Telegram music / voice / round video from the in-chat top panel.
 */
public class MglaMusicPlayerSheet extends BottomSheet implements MglaYandexMusicPlayer.PlayerListener, NotificationCenter.NotificationCenterDelegate {

    private static final int YELLOW = 0xFFFFCC00;
    private static final int PILL_BG = 0x33FFFFFF;

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
    private SeekBar seekBar;
    private TextView timeCurrentView;
    private TextView timeTotalView;
    private FrameLayout playPauseFab;
    private ImageView playPauseIcon;
    private ImageView shufflePillIcon;
    private TextView waveIslandText;
    private FrameLayout root;
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

    private int coverSize;
    private float coverRadius;
    private boolean isUserSeeking;
    private String lastCoverUrl;
    private boolean observersRegistered;
    private Runnable pendingAfterDismiss;

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

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padH, AndroidUtilities.statusBarHeight + dp(6), padH, bottomPad);

        // Header
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView collapseBtn = iconButton(context, R.drawable.ic_arrow_drop_down, v -> dismiss());
        header.addView(collapseBtn, LayoutHelper.createLinear(40, 40));

        sourceTitle = new TextView(context);
        sourceTitle.setText("Моя волна");
        sourceTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        sourceTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        sourceTitle.setTextColor(Color.WHITE);
        sourceTitle.setGravity(Gravity.CENTER);
        sourceTitle.setSingleLine(true);
        sourceTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(sourceTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        queueBtn = iconButton(context, R.drawable.msg_list, v -> openQueue());
        header.addView(queueBtn, LayoutHelper.createLinear(40, 40));
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        // Compact cover: rounded square for music, circle avatar for Telegram voice/round
        coverRadius = dp(22);
        coverSize = Math.min(dp(148), (int) (AndroidUtilities.displaySize.x * 0.38f));
        coverWrap = new FrameLayout(context) {
            private final Path clipPath = new Path();
            private final RectF clipRect = new RectF();

            @Override
            public void draw(Canvas canvas) {
                clipRect.set(0, 0, getWidth(), getHeight());
                clipPath.reset();
                clipPath.addRoundRect(clipRect, coverRadius, coverRadius, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clipPath);
                super.draw(canvas);
                canvas.restore();
            }
        };
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
        content.addView(coverWrap, LayoutHelper.createLinear(coverSize, coverSize, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        // Track info row
        LinearLayout infoRow = new LinearLayout(context);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);

        artistAvatar = new ImageView(context);
        artistAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setShape(GradientDrawable.OVAL);
        avBg.setColor(0x33FFFFFF);
        artistAvatar.setBackground(avBg);
        artistAvatar.setClipToOutline(true);
        artistAvatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        infoRow.addView(artistAvatar, LayoutHelper.createLinear(36, 36, 0, 0, 10, 0));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        artistView.setTextColor(0xB3FFFFFF);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        infoRow.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        shareBtn = iconButton(context, R.drawable.msg_share, v -> openForward());
        infoRow.addView(shareBtn, LayoutHelper.createLinear(36, 36));
        moreBtn = iconButton(context, R.drawable.ic_ab_other, v -> {});
        infoRow.addView(moreBtn, LayoutHelper.createLinear(36, 36));
        content.addView(infoRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        // Seekbar
        seekBar = new SeekBar(context);
        seekBar.setMax(1000);
        try {
            seekBar.getProgressDrawable().setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            if (seekBar.getThumb() != null) {
                seekBar.getThumb().setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            }
        } catch (Throwable ignored) {
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    timeCurrentView.setText(formatTime((getActiveDurationMs() * progress) / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserSeeking = false;
                seekActiveTo((getActiveDurationMs() * seekBar.getProgress()) / 1000);
            }
        });
        content.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 28, 0, 0, 0, 0));

        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeCurrentView = timeLabel(context, "0:00");
        timeTotalView = timeLabel(context, "0:00");
        timeRow.addView(timeCurrentView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        timeRow.addView(new View(context), LayoutHelper.createLinear(0, 0, 1f));
        timeRow.addView(timeTotalView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        content.addView(timeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 0, 2, 12));

        // Extra space above controls only in Telegram mode (pills row is hidden there).
        telegramControlsSpacer = new View(context);
        content.addView(telegramControlsSpacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0));

        // Main controls
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        dislikeButton = iconButton(context, R.drawable.msg_voice_muted, v -> skipNext());
        controls.addView(dislikeButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER));

        prevBtn = iconButton(context, R.drawable.ic_action_previous, v -> skipPrevious());
        controls.addView(prevBtn, LayoutHelper.createLinear(48, 48, Gravity.CENTER, 8, 0, 10, 0));

        // Spacer to keep yellow FAB centered between side controls
        controls.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));

        playPauseFab = new FrameLayout(context);
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(YELLOW);
        playPauseFab.setBackground(fabBg);
        playPauseIcon = new ImageView(context);
        playPauseIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        playPauseIcon.setColorFilter(new PorterDuffColorFilter(0xFF1A1A1A, PorterDuff.Mode.SRC_IN));
        playPauseFab.addView(playPauseIcon, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
        playPauseFab.setOnClickListener(v -> {
            playPauseFab.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70)
                    .withEndAction(() -> {
                        togglePlayPause();
                        playPauseFab.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    }).start();
        });
        controls.addView(playPauseFab, LayoutHelper.createLinear(72, 72, Gravity.CENTER));

        controls.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));

        nextBtn = iconButton(context, R.drawable.ic_action_next, v -> skipNext());
        controls.addView(nextBtn, LayoutHelper.createLinear(48, 48, Gravity.CENTER, 10, 0, 8, 0));

        likeButton = iconButton(context, R.drawable.media_like, v -> {
            if (!isYandexMode()) {
                return;
            }
            player.toggleLikeCurrentTrack();
            updateLikeButton();
            likeButton.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100)
                    .withEndAction(() -> likeButton.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                    .start();
        });
        controls.addView(likeButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER));
        content.addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

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
        pills.addView(shufflePillFrame, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 8, 0));

        FrameLayout lyricsPillFrame = makePill(context);
        lyricsPill = lyricsPillFrame;
        TextView lyricsText = new TextView(context);
        lyricsText.setText("Текст");
        lyricsText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        lyricsText.setTextColor(Color.WHITE);
        lyricsText.setGravity(Gravity.CENTER);
        lyricsPillFrame.addView(lyricsText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        pills.addView(lyricsPillFrame, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 8, 0));

        FrameLayout checkPillFrame = makePill(context);
        checkPill = checkPillFrame;
        ImageView checkIcon = new ImageView(context);
        checkIcon.setImageResource(R.drawable.msg_mini_checks);
        checkIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        checkPillFrame.addView(checkIcon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        pills.addView(checkPillFrame, LayoutHelper.createLinear(0, 44, 1f));
        content.addView(pills, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        content.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // Bottom wave island
        waveIsland = new FrameLayout(context);
        GradientDrawable waveBg = new GradientDrawable();
        waveBg.setCornerRadius(dp(28));
        waveBg.setColors(new int[]{0x55FFFFFF, 0x22FFFFFF});
        waveBg.setOrientation(GradientDrawable.Orientation.TL_BR);
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
        content.addView(waveIsland, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        scroll.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        setCustomView(root);
    }

    private FrameLayout makePill(Context context) {
        FrameLayout pill = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(PILL_BG);
        pill.setBackground(bg);
        return pill;
    }

    private TextView timeLabel(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setTextColor(0x99FFFFFF);
        return tv;
    }

    private ImageView iconButton(Context context, int res, View.OnClickListener listener) {
        ImageView iv = new ImageView(context);
        iv.setImageResource(res);
        iv.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setOnClickListener(listener);
        return iv;
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
            // Push play/prev/next lower when Yandex pills are hidden.
            lp.height = yandex ? 0 : dp(56);
            telegramControlsSpacer.setLayoutParams(lp);
        }
        if (shareBtn != null) {
            shareBtn.setVisibility(yandex ? View.GONE : View.VISIBLE);
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
            titleView.setText("Нет трека");
            artistView.setText("");
            coverImage.setImageResource(R.drawable.bot_music);
            artistAvatar.setImageResource(R.drawable.bot_music);
            waveIslandText.setText("Моя волна");
            return;
        }
        titleView.setText(track.title);
        artistView.setText(track.artist);
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
            titleView.setText(playing.getMusicAuthor());
            artistView.setText(LocaleController.getString(
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
        } else {
            showMusicCover();
            if (artistAvatar != null) {
                artistAvatar.setVisibility(View.VISIBLE);
            }
            if (moreBtn != null) {
                moreBtn.setVisibility(View.VISIBLE);
            }
            titleView.setText(playing.getMusicTitle());
            artistView.setText(playing.getMusicAuthor());
            sourceTitle.setText(LocaleController.getString(R.string.AttachMusic));
            String art = playing.getArtworkUrl(true);
            if (!TextUtils.isEmpty(art)) {
                MglaMusicImageHelper.bindCover(coverImage, art, 0, R.drawable.bot_music);
                MglaMusicImageHelper.bindCircleCover(artistAvatar, art, R.drawable.bot_music);
                extractAmbientColors(art);
            } else {
                coverImage.setImageResource(R.drawable.bot_music);
                artistAvatar.setImageResource(R.drawable.bot_music);
            }
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
        }
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

        final Context context = getContext();
        final BottomSheet sheet = new BottomSheet(context, false);
        sheet.setApplyTopPadding(false);
        sheet.setApplyBottomPadding(false);

        final int cellDp = 56;
        final int maxListPx = (int) (AndroidUtilities.displaySize.y * 0.55f);
        final int maxVisible = Math.max(1, maxListPx / dp(cellDp));
        final int visibleCount = Math.min(items.size(), maxVisible);
        // LayoutHelper heights are in dp — do not pass pre-dp pixel values.
        final int listHeightDp = visibleCount * cellDp;
        final boolean canScroll = items.size() > maxVisible;
        final int cellBg = Theme.getColor(Theme.key_dialogBackground);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(4) + AndroidUtilities.navigationBarHeight);

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.AttachAudio));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"), Typeface.BOLD);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setPadding(dp(20), dp(8), dp(20), dp(6));
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

        content.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, listHeightDp));
        sheet.setCustomView(content);
        sheet.show();
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
        if (dur > 0) {
            seekBar.setProgress((int) ((pos * 1000) / dur));
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
    }

    private void extractAmbientColors(String coverUrl) {
        if (TextUtils.isEmpty(coverUrl) || coverUrl.equals(lastCoverUrl)) {
            return;
        }
        lastCoverUrl = coverUrl;
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
        if (shufflePillIcon != null) {
            boolean shuffle = MglaYandexMusicStore.getInstance().isShuffle();
            shufflePillIcon.setColorFilter(new PorterDuffColorFilter(shuffle ? YELLOW : Color.WHITE, PorterDuff.Mode.SRC_IN));
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
            ambientBg.start();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
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
            if (durationMs > 0) {
                seekBar.setProgress((int) ((positionMs * 1000) / durationMs));
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
        private float phase;
        private ValueAnimator animator;

        AmbientBackgroundView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setColors(int c1, int c2, int c3, int c4) {
            colors[0] = c1 | 0xFF000000;
            colors[1] = c2 | 0xFF000000;
            colors[2] = c3 | 0xFF000000;
            colors[3] = c4 | 0xFF000000;
            invalidate();
        }

        void start() {
            if (animator != null && animator.isRunning()) return;
            animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            animator.setDuration(14000);
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
