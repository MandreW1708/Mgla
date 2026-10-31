package org.telegram.messenger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.widget.RemoteViews;

import org.telegram.ui.LaunchActivity;

/**
 * Modern Material You / Dark Glass music widget for the home screen.
 * Connects directly to the "Музыка" section (MglaYandexMusicPlayer) and MediaController.
 */
public class MglaMusicWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_PLAY_PAUSE = "org.telegram.messenger.MGLA_MUSIC_WIDGET_PLAY_PAUSE";
    public static final String ACTION_NEXT = "org.telegram.messenger.MGLA_MUSIC_WIDGET_NEXT";
    public static final String ACTION_PREV = "org.telegram.messenger.MGLA_MUSIC_WIDGET_PREV";
    public static final String ACTION_LIKE = "org.telegram.messenger.MGLA_MUSIC_WIDGET_LIKE";
    public static final String ACTION_WAVE = "org.telegram.messenger.MGLA_MUSIC_WIDGET_WAVE";
    public static final String ACTION_OPEN_MGLA_MUSIC = "org.telegram.messenger.OPEN_MGLA_MUSIC";

    private static final String PREFS_NAME = "mgla_music_widget_prefs";
    private static final String KEY_TITLE = "last_title";
    private static final String KEY_ARTIST = "last_artist";
    private static final String KEY_COVER_URL = "last_cover_url";
    private static final String KEY_SOURCE = "last_source";
    private static final String KEY_LIKED = "last_liked";

    /** Cover in layout is 64dp; keep binder payload tiny (well under the ~1 MB RemoteViews cap). */
    private static final int WIDGET_COVER_SIDE = 128;
    private static final int WIDGET_COVER_MAX_BYTES = 512 * 1024;

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null || intent.getAction() == null) {
            return;
        }

        final String action = intent.getAction();
        if (ACTION_PLAY_PAUSE.equals(action)) {
            final PendingResult pendingResult = goAsync();
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
                    if (player.getCurrentTrack() != null || !player.getQueue().isEmpty()) {
                        player.togglePlayPause();
                    } else {
                        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                        if (playing != null) {
                            if (MediaController.getInstance().isMessagePaused()) {
                                MediaController.getInstance().playMessage(playing);
                            } else {
                                MediaController.getInstance().pauseMessage(playing);
                            }
                        } else {
                            player.playMyWave();
                        }
                    }
                    updateAll();
                } catch (Throwable t) {
                    FileLog.e(t);
                } finally {
                    if (pendingResult != null) {
                        try {
                            pendingResult.finish();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            });
        } else if (ACTION_NEXT.equals(action)) {
            final PendingResult pendingResult = goAsync();
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
                    if (player.getCurrentTrack() != null) {
                        player.next();
                    } else {
                        MediaController.getInstance().playNextMessage();
                    }
                    updateAll();
                } catch (Throwable t) {
                    FileLog.e(t);
                } finally {
                    if (pendingResult != null) {
                        try {
                            pendingResult.finish();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            });
        } else if (ACTION_PREV.equals(action)) {
            final PendingResult pendingResult = goAsync();
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
                    if (player.getCurrentTrack() != null) {
                        player.prev();
                    } else {
                        MediaController.getInstance().playPreviousMessage();
                    }
                    updateAll();
                } catch (Throwable t) {
                    FileLog.e(t);
                } finally {
                    if (pendingResult != null) {
                        try {
                            pendingResult.finish();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            });
        } else if (ACTION_LIKE.equals(action)) {
            final PendingResult pendingResult = goAsync();
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
                    player.toggleLikeCurrentTrack();
                    updateAll();
                } catch (Throwable t) {
                    FileLog.e(t);
                } finally {
                    if (pendingResult != null) {
                        try {
                            pendingResult.finish();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            });
        } else if (ACTION_WAVE.equals(action)) {
            final PendingResult pendingResult = goAsync();
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
                    player.playMyWave();
                    updateAll();
                } catch (Throwable t) {
                    FileLog.e(t);
                } finally {
                    if (pendingResult != null) {
                        try {
                            pendingResult.finish();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            });
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null) {
            return;
        }
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
    }

    public static void updateAll() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, MglaMusicWidgetProvider.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            for (int id : ids) {
                updateWidget(context, manager, id);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        if (context == null || manager == null) {
            return;
        }

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.mgla_music_widget);

        MglaYandexMusicPlayer ymPlayer = MglaYandexMusicPlayer.getInstance();
        MglaYandexMusicModel.Track currentTrack = ymPlayer != null ? ymPlayer.getCurrentTrack() : null;
        MessageObject playingMsg = MediaController.getInstance().getPlayingMessageObject();

        String title;
        String artist;
        String coverUrl;
        String sourceBadge;
        String statusText;
        boolean isPlaying;
        boolean isLiked;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (currentTrack != null) {
            title = !TextUtils.isEmpty(currentTrack.title) ? currentTrack.title : "Без названия";
            artist = !TextUtils.isEmpty(currentTrack.artist) ? currentTrack.artist : "Яндекс Музыка";
            coverUrl = currentTrack.getCoverUrl(WIDGET_COVER_SIDE);
            isLiked = currentTrack.isLiked;
            isPlaying = ymPlayer.isPlaying();
            sourceBadge = ymPlayer.isMyWaveActive() ? "Моя волна" : ymPlayer.getCurrentSourceTitle();
            if (TextUtils.isEmpty(sourceBadge)) {
                sourceBadge = "Яндекс Музыка";
            }
            statusText = isPlaying ? "Играет" : "Пауза";

            try {
                prefs.edit()
                        .putString(KEY_TITLE, title)
                        .putString(KEY_ARTIST, artist)
                        .putString(KEY_COVER_URL, coverUrl)
                        .putString(KEY_SOURCE, sourceBadge)
                        .putBoolean(KEY_LIKED, isLiked)
                        .apply();
            } catch (Throwable ignore) {
            }
        } else if (playingMsg != null && playingMsg.isMusic()) {
            title = playingMsg.getMusicTitle();
            if (TextUtils.isEmpty(title)) {
                title = "Без названия";
            }
            artist = playingMsg.getMusicAuthor();
            if (TextUtils.isEmpty(artist)) {
                artist = "Музыка";
            }
            coverUrl = playingMsg.getArtworkUrl(false);
            isLiked = false;
            isPlaying = !MediaController.getInstance().isMessagePaused();
            sourceBadge = "Telegram";
            statusText = isPlaying ? "Играет" : "Пауза";
        } else {
            title = prefs.getString(KEY_TITLE, "Моя волна");
            artist = prefs.getString(KEY_ARTIST, "Персональный поток");
            coverUrl = prefs.getString(KEY_COVER_URL, null);
            sourceBadge = prefs.getString(KEY_SOURCE, "Моя волна");
            isLiked = prefs.getBoolean(KEY_LIKED, false);
            isPlaying = false;
            statusText = "Нажмите ▶ для воспроизведения";
        }

        views.setTextViewText(R.id.music_widget_title, title);
        views.setTextViewText(R.id.music_widget_artist, artist);
        views.setTextViewText(R.id.music_widget_source_badge, sourceBadge);
        views.setTextViewText(R.id.music_widget_status, statusText);

        views.setImageViewResource(
                R.id.music_widget_play_pause,
                isPlaying ? R.drawable.ic_action_pause : R.drawable.ic_action_play
        );

        views.setImageViewResource(
                R.id.music_widget_like,
                isLiked ? R.drawable.media_like_active : R.drawable.media_like
        );

        // Tap on card, header, cover, or track info opens Music tab in LaunchActivity
        Intent openMusicIntent = new Intent(context, LaunchActivity.class);
        openMusicIntent.setAction(ACTION_OPEN_MGLA_MUSIC);
        openMusicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(
                context,
                appWidgetId,
                openMusicIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, piOpen);
        views.setOnClickPendingIntent(R.id.widget_header, piOpen);
        views.setOnClickPendingIntent(R.id.music_widget_cover, piOpen);
        views.setOnClickPendingIntent(R.id.music_widget_info, piOpen);

        // Control PendingIntents
        Intent playPauseIntent = new Intent(context, MglaMusicWidgetProvider.class);
        playPauseIntent.setAction(ACTION_PLAY_PAUSE);
        PendingIntent piPlay = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + 1,
                playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.music_widget_play_pause, piPlay);

        Intent nextIntent = new Intent(context, MglaMusicWidgetProvider.class);
        nextIntent.setAction(ACTION_NEXT);
        PendingIntent piNext = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + 2,
                nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.music_widget_next, piNext);

        Intent prevIntent = new Intent(context, MglaMusicWidgetProvider.class);
        prevIntent.setAction(ACTION_PREV);
        PendingIntent piPrev = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + 3,
                prevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.music_widget_prev, piPrev);

        Intent likeIntent = new Intent(context, MglaMusicWidgetProvider.class);
        likeIntent.setAction(ACTION_LIKE);
        PendingIntent piLike = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + 4,
                likeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.music_widget_like, piLike);

        Intent waveIntent = new Intent(context, MglaMusicWidgetProvider.class);
        waveIntent.setAction(ACTION_WAVE);
        PendingIntent piWave = PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + 5,
                waveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.music_widget_wave_btn, piWave);

        // Album cover loading — always force a tiny bitmap; oversized covers crash the launcher
        // via TransactionTooLargeException / RemoteViews binder limit (~1 MB).
        if (!TextUtils.isEmpty(coverUrl)) {
            final String cacheKey = coverUrl + "_widget_s" + WIDGET_COVER_SIDE;
            Bitmap cached = MglaMusicImageHelper.getFromCache(cacheKey);
            if (isSafeWidgetBitmap(cached)) {
                views.setImageViewBitmap(R.id.music_widget_cover, cached);
            } else {
                views.setImageViewResource(R.id.music_widget_cover, R.drawable.bot_music);
                final String finalCoverUrl = coverUrl;
                MglaMusicImageHelper.musicImageQueue.postRunnable(() -> {
                    try {
                        Bitmap loaded = MglaMusicImageHelper.loadBitmapSync(finalCoverUrl, WIDGET_COVER_SIDE);
                        Bitmap safe = MglaMusicImageHelper.forRemoteViews(
                                loaded, WIDGET_COVER_SIDE, AndroidUtilities.dp(14));
                        if (safe != null && isSafeWidgetBitmap(safe)) {
                            MglaMusicImageHelper.putInCache(cacheKey, safe);
                            views.setImageViewBitmap(R.id.music_widget_cover, safe);
                            manager.updateAppWidget(appWidgetId, views);
                        }
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                });
            }
        } else {
            views.setImageViewResource(R.id.music_widget_cover, R.drawable.bot_music);
        }

        manager.updateAppWidget(appWidgetId, views);
    }

    private static boolean isSafeWidgetBitmap(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled() && bitmap.getByteCount() <= WIDGET_COVER_MAX_BYTES;
    }
}
