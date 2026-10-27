package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.view.KeyEvent;

import org.telegram.ui.LaunchActivity;

/**
 * Foreground media service that exposes Yandex Music playback to the system media controls
 * (notification shade player, lock screen, Bluetooth, etc.) via MediaSession.
 */
public class MglaYandexMusicService extends Service {

    public static final String NOTIFY_PREVIOUS = "org.telegram.messenger.mgla.ym.previous";
    public static final String NOTIFY_CLOSE = "org.telegram.messenger.mgla.ym.close";
    public static final String NOTIFY_PAUSE = "org.telegram.messenger.mgla.ym.pause";
    public static final String NOTIFY_PLAY = "org.telegram.messenger.mgla.ym.play";
    public static final String NOTIFY_NEXT = "org.telegram.messenger.mgla.ym.next";

    private static final int ID_NOTIFICATION = 91824;
    public static final String ACTION_UPDATE = "org.telegram.messenger.mgla.ym.UPDATE";
    public static final String ACTION_STOP = "org.telegram.messenger.mgla.ym.STOP";

    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder playbackState;
    private Bitmap albumArtPlaceholder;
    private Bitmap currentAlbumArt;
    private String loadingCoverUrl;
    private boolean foregroundStarted;

    private final MglaYandexMusicPlayer.PlayerListener playerListener = new MglaYandexMusicPlayer.PlayerListener() {
        private long lastReportedPositionMs = -1;
        private long lastReportedTimeMs = 0;

        @Override
        public void onTrackChanged(MglaYandexMusicModel.Track track) {
            lastReportedPositionMs = -1;
            createNotification(true);
        }

        @Override
        public void onPlaybackStateChanged(boolean isPlaying, boolean isBuffering) {
            lastReportedPositionMs = -1;
            createNotification(false);
        }

        @Override
        public void onProgress(long positionMs, long durationMs) {
            long now = SystemClock.elapsedRealtime();
            // Android MediaSession interpolates position automatically based on elapsedRealtime.
            // Spamming setPlaybackState() every 17ms floods SystemUI with Binder IPC calls and causes
            // the system notification shade to lag terribly.
            // We only report position if a seek occurred (drift > 2000ms) or on first tick.
            if (lastReportedPositionMs < 0 || Math.abs(positionMs - (lastReportedPositionMs + (now - lastReportedTimeMs))) > 2000) {
                lastReportedPositionMs = positionMs;
                lastReportedTimeMs = now;
                updatePlaybackStateOnly();
            }
        }
    };

    public static void startOrUpdate(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MglaYandexMusicService.class);
        intent.setAction(ACTION_UPDATE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        try {
            context.stopService(new Intent(context, MglaYandexMusicService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        albumArtPlaceholder = Bitmap.createBitmap(AndroidUtilities.dp(102), AndroidUtilities.dp(102), Bitmap.Config.ARGB_8888);
        try {
            Drawable placeholder = getResources().getDrawable(R.drawable.nocover_big);
            placeholder.setBounds(0, 0, albumArtPlaceholder.getWidth(), albumArtPlaceholder.getHeight());
            placeholder.draw(new Canvas(albumArtPlaceholder));
        } catch (Throwable ignored) {
        }

        ComponentName mediaButtonReceiver = new ComponentName(getApplicationContext(), MglaYandexMusicReceiver.class);
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(mediaButtonReceiver);
        PendingIntent mediaButtonPendingIntent = PendingIntent.getBroadcast(
                this, 0, mediaButtonIntent, fixFlags(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));

        mediaSession = new MediaSessionCompat(this, "mglaYandexMusic", mediaButtonReceiver, mediaButtonPendingIntent);
        playbackState = new PlaybackStateCompat.Builder();
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setMediaButtonReceiver(mediaButtonPendingIntent);
        try {
            mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
        } catch (Throwable ignored) {
        }
        Intent sessionIntent = new Intent(this, LaunchActivity.class);
        sessionIntent.setAction("com.tmessages.openplayer");
        sessionIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        mediaSession.setSessionActivity(PendingIntent.getActivity(
                this, 0, sessionIntent,
                fixFlags(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE)
        ));
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                MglaYandexMusicPlayer.getInstance().play();
            }

            @Override
            public void onPause() {
                MglaYandexMusicPlayer.getInstance().pause();
            }

            @Override
            public void onSkipToNext() {
                MglaYandexMusicPlayer.getInstance().next();
            }

            @Override
            public void onSkipToPrevious() {
                MglaYandexMusicPlayer.getInstance().prev();
            }

            @Override
            public void onSeekTo(long pos) {
                MglaYandexMusicPlayer.getInstance().seekTo(pos);
                updatePlaybackStateOnly();
            }

            @Override
            public void onStop() {
                MglaYandexMusicPlayer.getInstance().stop();
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                if (mediaButtonEvent != null && Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonEvent.getAction())) {
                    KeyEvent keyEvent = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                        MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
                        switch (keyEvent.getKeyCode()) {
                            case KeyEvent.KEYCODE_HEADSETHOOK:
                            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                                player.togglePlayPause();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PLAY:
                                player.play();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                                player.pause();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_STOP:
                                player.stop();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_NEXT:
                                player.next();
                                return true;
                            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                                player.prev();
                                return true;
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        });
        mediaSession.setActive(true);

        MglaYandexMusicPlayer.getInstance().addListener(playerListener);

        try {
            IntentFilter noisy = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(noisyReceiver, noisy, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(noisyReceiver, noisy);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private final android.content.BroadcastReceiver noisyReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                MglaYandexMusicPlayer.getInstance().pause();
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            MglaYandexMusicPlayer.getInstance().stop();
            promoteEmptyForegroundThenStop();
            return START_NOT_STICKY;
        }
        if (MglaYandexMusicPlayer.getInstance().getCurrentTrack() == null) {
            promoteEmptyForegroundThenStop();
            return START_NOT_STICKY;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.registerMediaButtonEventReceiver(
                        new ComponentName(getApplicationContext(), MglaYandexMusicReceiver.class.getName()));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (mediaSession != null) {
            mediaSession.setActive(true);
        }
        createNotification(true);
        return START_STICKY;
    }

    /** Avoid Android O+ crash when startForegroundService is followed by stop without startForeground. */
    private void promoteEmptyForegroundThenStop() {
        try {
            Notification.Builder bldr = new Notification.Builder(this);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationsController.checkOtherNotificationsChannel();
                bldr.setChannelId(NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
            }
            bldr.setSmallIcon(R.drawable.player)
                    .setContentTitle("Яндекс Музыка")
                    .setContentText("")
                    .setPriority(Notification.PRIORITY_MIN);
            Notification n = bldr.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(ID_NOTIFICATION, n);
            }
            foregroundStarted = true;
        } catch (Throwable ignored) {
        }
        stopForegroundSafe(true);
        stopSelf();
    }

    @SuppressLint("NewApi")
    private void createNotification(boolean reloadCover) {
        if (mediaSession == null) {
            return;
        }
        MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
        MglaYandexMusicModel.Track track = player.getCurrentTrack();
        if (track == null) {
            stopForegroundSafe(true);
            stopSelf();
            return;
        }

        boolean isPlaying = player.isPlaying();
        boolean isBuffering = player.isBuffering();
        long position = player.getCurrentPosition();
        long duration = Math.max(player.getDuration(), track.durationMs > 0 ? track.durationMs : 0);

        String title = !TextUtils.isEmpty(track.title) ? track.title : "Яндекс Музыка";
        String artist = !TextUtils.isEmpty(track.artist) ? track.artist : "";
        String album = !TextUtils.isEmpty(track.album) ? track.album : player.getCurrentSourceTitle();
        if (TextUtils.isEmpty(album)) {
            album = "Яндекс Музыка";
        }

        Intent openIntent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        openIntent.setAction("com.tmessages.openplayer");
        openIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                fixFlags(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE)
        );

        PendingIntent pendingPrev = PendingIntent.getBroadcast(this, 1,
                new Intent(NOTIFY_PREVIOUS).setComponent(new ComponentName(this, MglaYandexMusicReceiver.class)),
                fixFlags(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        PendingIntent pendingPlayPause = PendingIntent.getBroadcast(this, 2,
                new Intent(isPlaying ? NOTIFY_PAUSE : NOTIFY_PLAY).setComponent(new ComponentName(this, MglaYandexMusicReceiver.class)),
                fixFlags(PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE));
        PendingIntent pendingNext = PendingIntent.getBroadcast(this, 3,
                new Intent(NOTIFY_NEXT).setComponent(new ComponentName(this, MglaYandexMusicReceiver.class)),
                fixFlags(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        PendingIntent pendingStop = PendingIntent.getService(this, 4,
                new Intent(this, MglaYandexMusicService.class).setAction(ACTION_STOP),
                fixFlags(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));

        if (reloadCover) {
            loadCoverAsync(track);
        }

        Bitmap art = currentAlbumArt != null && !currentAlbumArt.isRecycled() ? currentAlbumArt : albumArtPlaceholder;

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;

        int state;
        if (isBuffering && !isPlaying) {
            state = PlaybackStateCompat.STATE_BUFFERING;
        } else if (isPlaying) {
            state = PlaybackStateCompat.STATE_PLAYING;
        } else {
            state = PlaybackStateCompat.STATE_PAUSED;
        }

        playbackState = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, isPlaying ? 1f : 0f);

        if (mediaSession == null) {
            return;
        }
        mediaSession.setPlaybackState(playbackState.build());
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .build());

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle()
                .setMediaSession((android.media.session.MediaSession.Token) mediaSession.getSessionToken().getToken())
                .setShowActionsInCompactView(0, 1, 2);

        Notification.Builder bldr = new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationsController.checkOtherNotificationsChannel();
            bldr.setChannelId(NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
        }
        bldr.setSmallIcon(R.drawable.player)
                .setOngoing(isPlaying || isBuffering)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText(album)
                .setContentIntent(contentIntent)
                .setDeleteIntent(pendingStop)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(mediaStyle)
                .setLargeIcon(art);

        bldr.addAction(new Notification.Action.Builder(R.drawable.ic_action_previous, "Previous", pendingPrev).build());
        bldr.addAction(new Notification.Action.Builder(
                isPlaying ? R.drawable.ic_action_pause : R.drawable.ic_action_play,
                isPlaying ? "Pause" : "Play",
                pendingPlayPause
        ).build());
        bldr.addAction(new Notification.Action.Builder(R.drawable.ic_action_next, "Next", pendingNext).build());

        Notification notification = bldr.build();

        try {
            if (!foregroundStarted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(ID_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(ID_NOTIFICATION, notification);
                }
                foregroundStarted = true;
            } else {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.notify(ID_NOTIFICATION, notification);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            try {
                startForeground(ID_NOTIFICATION, notification);
                foregroundStarted = true;
            } catch (Throwable ignored) {
            }
        }
    }

    private void updatePlaybackStateOnly() {
        if (mediaSession == null) return;
        MglaYandexMusicPlayer player = MglaYandexMusicPlayer.getInstance();
        if (player.getCurrentTrack() == null) return;

        boolean isPlaying = player.isPlaying();
        boolean isBuffering = player.isBuffering();
        long position = player.getCurrentPosition();

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;

        int state;
        if (isBuffering && !isPlaying) {
            state = PlaybackStateCompat.STATE_BUFFERING;
        } else if (isPlaying) {
            state = PlaybackStateCompat.STATE_PLAYING;
        } else {
            state = PlaybackStateCompat.STATE_PAUSED;
        }

        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, isPlaying ? 1f : 0f)
                .build());
    }

    private void loadCoverAsync(MglaYandexMusicModel.Track track) {
        String url = track.getCoverUrl(300);
        if (TextUtils.isEmpty(url)) {
            currentAlbumArt = null;
            loadingCoverUrl = null;
            return;
        }
        if (TextUtils.equals(loadingCoverUrl, url) && currentAlbumArt != null) {
            return;
        }
        loadingCoverUrl = url;
        final String requestUrl = url;

        Bitmap cached = MglaMusicImageHelper.getFromCache(url + "_notif");
        if (cached != null && !cached.isRecycled()) {
            currentAlbumArt = cached;
            return;
        }

        MglaMusicImageHelper.musicImageQueue.postRunnable(() -> {
            Bitmap bitmap = MglaMusicImageHelper.loadBitmapSync(requestUrl, 300);
            if (bitmap != null) {
                MglaMusicImageHelper.putInCache(requestUrl + "_notif", bitmap);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (mediaSession == null || !TextUtils.equals(loadingCoverUrl, requestUrl)) {
                    return;
                }
                currentAlbumArt = bitmap;
                createNotification(false);
            });
        });
    }

    private static int fixFlags(int flags) {
        if (Build.VERSION.SDK_INT >= 31) {
            return flags;
        }
        return flags & ~PendingIntent.FLAG_MUTABLE & ~PendingIntent.FLAG_IMMUTABLE;
    }

    private void stopForegroundSafe(boolean removeNotification) {
        try {
            stopForeground(removeNotification);
        } catch (Throwable ignored) {
        }
        foregroundStarted = false;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Throwable ignored) {
        }
        MglaYandexMusicPlayer.getInstance().removeListener(playerListener);
        loadingCoverUrl = null;
        currentAlbumArt = null;
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        stopForegroundSafe(true);
        super.onDestroy();
    }
}
