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
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaMusicImageHelper;
import org.telegram.messenger.MglaYandexMusicModel;
import org.telegram.messenger.MglaYandexMusicPlayer;
import org.telegram.messenger.MglaYandexMusicStore;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.DominantColors;

import java.util.Locale;

/**
 * Fullscreen Yandex Music–style player sheet with dynamic ambient background.
 */
public class MglaMusicPlayerSheet extends BottomSheet implements MglaYandexMusicPlayer.PlayerListener {

    private static final int YELLOW = 0xFFFFCC00;
    private static final int PILL_BG = 0x33FFFFFF;

    private final MglaYandexMusicPlayer player;

    private AmbientBackgroundView ambientBg;
    private ImageView coverImage;
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

    private boolean isUserSeeking;
    private String lastCoverUrl;

    public MglaMusicPlayerSheet(Context context) {
        super(context, false);
        this.player = MglaYandexMusicPlayer.getInstance();
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        setCanDismissWithSwipe(true);
        // Keep system nav bar transparent (same as MainTabs glass UI) — do not paint a solid strip.
        drawNavigationBar = false;
        navBarColorKey = -1;
        navBarColor = Color.TRANSPARENT;
        initView(context);
        player.addListener(this);
    }

    private void initView(Context context) {
        int screenH = AndroidUtilities.displaySize.y;
        int padH = dp(20);
        int bottomPad = dp(16) + AndroidUtilities.navigationBarHeight;

        root = new FrameLayout(context);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                Math.max(screenH - dp(24), dp(640))
        ));

        ambientBg = new AmbientBackgroundView(context);
        root.addView(ambientBg, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padH, dp(10) + AndroidUtilities.statusBarHeight / 4, padH, bottomPad);

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

        ImageView castBtn = iconButton(context, R.drawable.msg_voice_speaker, v -> {});
        header.addView(castBtn, LayoutHelper.createLinear(40, 40, 0, 0, 4, 0));
        ImageView queueBtn = iconButton(context, R.drawable.msg_list, v -> {});
        header.addView(queueBtn, LayoutHelper.createLinear(40, 40));
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        // Compact rounded-square cover so seek/controls stay on screen
        final float coverRadius = dp(22);
        int coverSize = Math.min(dp(148), (int) (AndroidUtilities.displaySize.x * 0.38f));
        FrameLayout coverWrap = new FrameLayout(context) {
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
        coverImage = new ImageView(context);
        coverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverImage.setAdjustViewBounds(false);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(coverRadius);
        coverBg.setColor(0xFF2A2A2A);
        coverImage.setBackground(coverBg);
        coverWrap.setBackground(coverBg);
        coverWrap.addView(coverImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
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

        ImageView shareBtn = iconButton(context, R.drawable.msg_share, v -> {});
        infoRow.addView(shareBtn, LayoutHelper.createLinear(36, 36));
        ImageView moreBtn = iconButton(context, R.drawable.ic_ab_other, v -> {});
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
                    long duration = player.getDuration();
                    timeCurrentView.setText(formatTime((duration * progress) / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserSeeking = false;
                long duration = player.getDuration();
                player.seekTo((duration * seekBar.getProgress()) / 1000);
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

        // Main controls
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        dislikeButton = iconButton(context, R.drawable.msg_voice_muted, v -> player.next());
        controls.addView(dislikeButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER));

        ImageView prevBtn = iconButton(context, R.drawable.ic_action_previous, v -> player.prev());
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
                        player.togglePlayPause();
                        playPauseFab.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    }).start();
        });
        controls.addView(playPauseFab, LayoutHelper.createLinear(72, 72, Gravity.CENTER));

        controls.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));

        ImageView nextBtn = iconButton(context, R.drawable.ic_action_next, v -> player.next());
        controls.addView(nextBtn, LayoutHelper.createLinear(48, 48, Gravity.CENTER, 10, 0, 8, 0));

        likeButton = iconButton(context, R.drawable.media_like, v -> {
            player.toggleLikeCurrentTrack();
            updateLikeButton();
            likeButton.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100)
                    .withEndAction(() -> likeButton.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                    .start();
        });
        controls.addView(likeButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER));
        content.addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Pill row
        LinearLayout pills = new LinearLayout(context);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER);

        FrameLayout shufflePill = makePill(context);
        shufflePillIcon = new ImageView(context);
        shufflePillIcon.setImageResource(R.drawable.player_new_shuffle);
        shufflePillIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        shufflePill.addView(shufflePillIcon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        shufflePill.setOnClickListener(v -> {
            player.toggleShuffle();
            updateControlsState();
        });
        pills.addView(shufflePill, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 8, 0));

        FrameLayout lyricsPill = makePill(context);
        TextView lyricsText = new TextView(context);
        lyricsText.setText("Текст");
        lyricsText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        lyricsText.setTextColor(Color.WHITE);
        lyricsText.setGravity(Gravity.CENTER);
        lyricsPill.addView(lyricsText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        pills.addView(lyricsPill, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 8, 0));

        FrameLayout checkPill = makePill(context);
        ImageView checkIcon = new ImageView(context);
        checkIcon.setImageResource(R.drawable.msg_mini_checks);
        checkIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        checkPill.addView(checkIcon, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        pills.addView(checkPill, LayoutHelper.createLinear(0, 44, 1f));
        content.addView(pills, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        content.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // Bottom wave island
        FrameLayout waveIsland = new FrameLayout(context);
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
        waveIsland.addView(waveIslandText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        waveIsland.setOnClickListener(v -> {
            if (!player.isMyWaveActive()) {
                player.playMyWave();
            }
        });
        content.addView(waveIsland, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        scroll.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        setCustomView(root);

        updateTrackUi(player.getCurrentTrack());
        updateControlsState();
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur > 0) {
            seekBar.setProgress((int) ((pos * 1000) / dur));
            timeCurrentView.setText(formatTime(pos));
            timeTotalView.setText(formatTime(dur));
        }
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

    private void updateTrackUi(MglaYandexMusicModel.Track track) {
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
        MglaYandexMusicModel.Track track = player.getCurrentTrack();
        boolean liked = track != null && track.isLiked;
        likeButton.setImageResource(liked ? R.drawable.media_like_active : R.drawable.media_like);
        likeButton.setColorFilter(new PorterDuffColorFilter(liked ? 0xFFFF334B : Color.WHITE, PorterDuff.Mode.SRC_IN));
    }

    private void updateControlsState() {
        boolean isPlaying = player.isPlaying();
        playPauseIcon.setImageResource(isPlaying ? R.drawable.ic_action_pause : R.drawable.ic_action_play);
        boolean shuffle = MglaYandexMusicStore.getInstance().isShuffle();
        shufflePillIcon.setColorFilter(new PorterDuffColorFilter(shuffle ? YELLOW : Color.WHITE, PorterDuff.Mode.SRC_IN));
    }

    private String formatTime(long ms) {
        if (ms <= 0) return "0:00";
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    @Override
    public void show() {
        super.show();
        if (ambientBg != null) {
            ambientBg.start();
        }
    }

    @Override
    public void onTrackChanged(MglaYandexMusicModel.Track track) {
        AndroidUtilities.runOnUIThread(() -> {
            updateTrackUi(track);
            updateControlsState();
        });
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying, boolean isBuffering) {
        AndroidUtilities.runOnUIThread(this::updateControlsState);
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
        if (isUserSeeking) return;
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
        AndroidUtilities.runOnUIThread(() -> updateTrackUi(player.getCurrentTrack()));
    }

    @Override
    public void dismiss() {
        if (ambientBg != null) {
            ambientBg.stop();
        }
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
