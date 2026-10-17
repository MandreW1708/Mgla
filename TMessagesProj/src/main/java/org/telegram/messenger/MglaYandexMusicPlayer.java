package org.telegram.messenger;

import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Yandex Music queue / controls facade.
 * Actual audio goes through Telegram {@link MediaController} + {@link MusicPlayerService}.
 */
public final class MglaYandexMusicPlayer implements NotificationCenter.NotificationCenterDelegate {

    public interface PlayerListener {
        default void onTrackChanged(MglaYandexMusicModel.Track track) {}
        default void onPlaybackStateChanged(boolean isPlaying, boolean isBuffering) {}
        default void onProgress(long positionMs, long durationMs) {}
        default void onQueueChanged() {}
    }

    private static volatile MglaYandexMusicPlayer instance;

    public static MglaYandexMusicPlayer getInstance() {
        if (instance == null) {
            synchronized (MglaYandexMusicPlayer.class) {
                if (instance == null) {
                    instance = new MglaYandexMusicPlayer();
                }
            }
        }
        return instance;
    }

    private final List<PlayerListener> listeners = new CopyOnWriteArrayList<>();
    private final List<MglaYandexMusicModel.Track> queue = new ArrayList<>();
    private int currentIndex = -1;
    private MglaYandexMusicModel.Track currentTrack;

    private boolean isPlaying;
    private boolean isBuffering;
    private boolean isMyWaveActive;
    private String currentSourceTitle = "";
    private String currentRotorStation = "user:onyourwave";
    private boolean isRotorLoading;
    private boolean observersRegistered;

    private MglaYandexMusicPlayer() {
        ensureObservers();
    }

    private void ensureObservers() {
        if (observersRegistered) {
            return;
        }
        observersRegistered = true;
        int account = UserConfig.selectedAccount;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.playerDidStartPlaying);
    }

    public void addListener(PlayerListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(PlayerListener listener) {
        listeners.remove(listener);
    }

    private void notifyTrackChanged(MglaYandexMusicModel.Track track) {
        AndroidUtilities.runOnUIThread(() -> {
            for (PlayerListener l : listeners) {
                l.onTrackChanged(track);
            }
        });
    }

    private void notifyPlaybackState(boolean playing, boolean buffering) {
        AndroidUtilities.runOnUIThread(() -> {
            for (PlayerListener l : listeners) {
                l.onPlaybackStateChanged(playing, buffering);
            }
        });
    }

    private void notifyProgress(long positionMs, long durationMs) {
        AndroidUtilities.runOnUIThread(() -> {
            for (PlayerListener l : listeners) {
                l.onProgress(positionMs, durationMs);
            }
        });
    }

    private void notifyQueueChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (PlayerListener l : listeners) {
                l.onQueueChanged();
            }
        });
    }

    public List<MglaYandexMusicModel.Track> getQueue() {
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public MglaYandexMusicModel.Track getCurrentTrack() {
        return currentTrack;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isBuffering() {
        return isBuffering;
    }

    public boolean isMyWaveActive() {
        return isMyWaveActive;
    }

    public String getCurrentSourceTitle() {
        return currentSourceTitle;
    }

    public long getCurrentPosition() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
            long durationMs = getDuration();
            if (durationMs > 0 && playing.audioProgress > 0f) {
                return Math.max(0L, Math.min(durationMs, (long) (playing.audioProgress * durationMs)));
            }
            if (playing.audioProgressMs > 0) {
                return Math.max(0, playing.audioProgressMs);
            }
            return Math.max(0, (long) (playing.audioProgressSec * 1000L));
        }
        return 0;
    }

    public long getDuration() {
        if (currentTrack != null && currentTrack.durationMs > 0) {
            return currentTrack.durationMs;
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
            return Math.max(0, (long) (playing.getDuration() * 1000L));
        }
        return 0;
    }

    public void playTrack(MglaYandexMusicModel.Track track, List<MglaYandexMusicModel.Track> newQueue, String sourceTitle) {
        if (track == null) return;
        ensureObservers();
        isMyWaveActive = false;
        currentSourceTitle = sourceTitle != null ? sourceTitle : "";

        synchronized (queue) {
            if (newQueue != null && !newQueue.isEmpty()) {
                queue.clear();
                queue.addAll(newQueue);
            } else if (queue.isEmpty() || indexOfTrack(track) < 0) {
                queue.clear();
                queue.add(track);
            }
            currentIndex = indexOfTrack(track);
            if (currentIndex < 0) {
                queue.add(0, track);
                currentIndex = 0;
            }
        }
        notifyQueueChanged();
        loadAndPlayCurrent();
    }

    private int indexOfTrack(MglaYandexMusicModel.Track track) {
        if (track == null) return -1;
        for (int i = 0; i < queue.size(); i++) {
            MglaYandexMusicModel.Track t = queue.get(i);
            if (t != null && TextUtils.equals(t.id, track.id)) {
                return i;
            }
        }
        return -1;
    }

    public void playMyWave() {
        playRotorStation("user:onyourwave", "Моя волна");
    }

    public void playCollectionWave() {
        playRotorStation("user:collection", "Моя волна по коллекции");
    }

    public void playRotorStation(String station, String title) {
        ensureObservers();
        isMyWaveActive = true;
        currentSourceTitle = !TextUtils.isEmpty(title) ? title : "Моя волна";
        currentRotorStation = !TextUtils.isEmpty(station) ? station : "user:onyourwave";

        if ("user:onyourwave".equals(currentRotorStation)) {
            List<MglaYandexMusicModel.Track> cached = MglaYandexMusicStore.getInstance().getCachedRotor();
            if (cached != null && !cached.isEmpty()) {
                synchronized (queue) {
                    queue.clear();
                    queue.addAll(cached);
                    currentIndex = 0;
                }
                notifyQueueChanged();
                loadAndPlayCurrent();
            } else {
                isBuffering = true;
                notifyPlaybackState(isPlaying, true);
            }
        } else {
            isBuffering = true;
            notifyPlaybackState(isPlaying, true);
        }
        fetchRotorTracks(false);
    }

    private void fetchRotorTracks(boolean append) {
        if (isRotorLoading) return;
        isRotorLoading = true;
        final String station = currentRotorStation != null ? currentRotorStation : "user:onyourwave";
        MglaYandexMusicApi.fetchRotorTracks(station, new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Track>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Track> tracks) {
                isRotorLoading = false;
                if (tracks == null || tracks.isEmpty()) return;
                synchronized (queue) {
                    if (append) {
                        queue.addAll(tracks);
                    } else if (currentTrack == null || !isPlaying) {
                        queue.clear();
                        queue.addAll(tracks);
                        currentIndex = 0;
                        loadAndPlayCurrent();
                    } else {
                        queue.addAll(tracks);
                    }
                }
                notifyQueueChanged();
            }

            @Override
            public void onError(Exception error) {
                isRotorLoading = false;
            }
        });
    }

    private void loadAndPlayCurrent() {
        MglaYandexMusicModel.Track track;
        synchronized (queue) {
            if (currentIndex < 0 || currentIndex >= queue.size()) {
                return;
            }
            track = queue.get(currentIndex);
        }
        currentTrack = track;
        track.isLiked = MglaYandexMusicStore.getInstance().isLiked(track.id);
        notifyTrackChanged(track);

        isBuffering = true;
        isPlaying = false;
        notifyPlaybackState(false, true);

        if (isMyWaveActive && currentIndex >= queue.size() - 2) {
            fetchRotorTracks(true);
        }

        int account = UserConfig.selectedAccount;
        File file = MglaYandexMusicPlayback.cacheFileForTrack(track.id);
        MessageObject current = MglaYandexMusicPlayback.createMessageObject(track, file, account);
        if (current == null) {
            return;
        }

        // Stop custom Yandex notification service — Telegram MusicPlayerService takes over
        try {
            MglaYandexMusicService.stop(ApplicationLoader.applicationContext);
        } catch (Throwable ignored) {
        }

        ArrayList<MessageObject> playlist = new ArrayList<>();
        playlist.add(current);
        MediaController.getInstance().setPlaylist(playlist, current, 0, false, null);
    }

    public void togglePlayPause() {
        if (currentTrack == null) {
            if (!queue.isEmpty()) {
                currentIndex = Math.max(0, currentIndex);
                loadAndPlayCurrent();
            } else {
                playMyWave();
            }
            return;
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(playing);
            } else {
                MediaController.getInstance().pauseMessage(playing);
            }
            return;
        }
        if (isPlaying) {
            pause();
        } else {
            play();
        }
    }

    public void play() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
            MediaController.getInstance().playMessage(playing);
            return;
        }
        if (!queue.isEmpty()) {
            loadAndPlayCurrent();
        }
    }

    public void pause() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null) {
            MediaController.getInstance().pauseMessage(playing);
        }
        isPlaying = false;
        notifyPlaybackState(false, false);
    }

    public void stop() {
        MediaController.getInstance().cleanupPlayer(true, true);
        isPlaying = false;
        isBuffering = false;
        currentTrack = null;
        notifyPlaybackState(false, false);
        notifyTrackChanged(null);
    }

    public void next() {
        synchronized (queue) {
            if (queue.isEmpty()) return;
            int repeatMode = MglaYandexMusicStore.getInstance().getRepeatMode();
            if (repeatMode == 2) {
                seekTo(0);
                play();
                return;
            }
            if (currentIndex + 1 < queue.size()) {
                currentIndex++;
                loadAndPlayCurrent();
            } else if (isMyWaveActive) {
                fetchRotorTracks(true);
            } else if (repeatMode == 1) {
                currentIndex = 0;
                loadAndPlayCurrent();
            } else {
                pause();
            }
        }
    }

    public void prev() {
        if (getCurrentPosition() > 4000) {
            seekTo(0);
            return;
        }
        synchronized (queue) {
            if (queue.isEmpty()) return;
            if (currentIndex - 1 >= 0) {
                currentIndex--;
                loadAndPlayCurrent();
            } else {
                seekTo(0);
            }
        }
    }

    public void seekTo(long positionMs) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
            float progress = 0f;
            long dur = getDuration();
            if (dur > 0) {
                progress = positionMs / (float) dur;
            }
            MediaController.getInstance().seekToProgress(playing, progress);
        }
    }

    public void toggleShuffle() {
        boolean nextShuffle = !MglaYandexMusicStore.getInstance().isShuffle();
        MglaYandexMusicStore.getInstance().setShuffle(nextShuffle);
        synchronized (queue) {
            if (queue.size() > 1 && currentTrack != null && nextShuffle) {
                List<MglaYandexMusicModel.Track> remaining = new ArrayList<>(queue);
                remaining.remove(currentTrack);
                Collections.shuffle(remaining);
                queue.clear();
                queue.add(currentTrack);
                queue.addAll(remaining);
                currentIndex = 0;
            }
        }
        notifyQueueChanged();
    }

    public void toggleRepeat() {
        int current = MglaYandexMusicStore.getInstance().getRepeatMode();
        MglaYandexMusicStore.getInstance().setRepeatMode((current + 1) % 3);
        notifyQueueChanged();
    }

    public void toggleLikeCurrentTrack() {
        if (currentTrack == null) return;
        boolean newLiked = !currentTrack.isLiked;
        currentTrack.isLiked = newLiked;
        MglaYandexMusicStore.getInstance().setLiked(currentTrack.id, newLiked);
        notifyTrackChanged(currentTrack);
        MglaYandexMusicApi.toggleLike(currentTrack.id, newLiked, null);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged) {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
                MglaYandexMusicModel.Track track = MglaYandexMusicPlayback.getTrack(playing);
                if (track != null) {
                    currentTrack = track;
                    synchronized (queue) {
                        int idx = indexOfTrack(track);
                        if (idx >= 0) {
                            currentIndex = idx;
                        }
                    }
                    notifyTrackChanged(track);
                }
                isBuffering = MediaController.getInstance().isDownloadingCurrentMessage();
                isPlaying = !MediaController.getInstance().isMessagePaused() && !isBuffering;
                notifyPlaybackState(isPlaying, isBuffering);
            }
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            // Natural end is handled via MediaController.playNextMessageWithoutOrder → next()
            isPlaying = false;
            isBuffering = false;
            notifyPlaybackState(false, false);
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
                notifyProgress(getCurrentPosition(), getDuration());
            }
        } else if (id == NotificationCenter.playerDidStartPlaying) {
            // Non-Yandex VideoPlayer started — pause Yandex via MediaController already handled by MediaController
        }
    }
}
