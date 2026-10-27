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
    private String currentRotorSessionId;
    private String currentRotorBatchId;
    private final List<String> currentRotorSeeds = new ArrayList<>();
    private boolean isRotorLoading;
    private boolean observersRegistered;
    /** After a one-shot track ends, start wave seeded by this track id. */
    private String waveSeedAfterQueue;
    private long lastProgressNotifyMs;
    private final List<String> recentWaveTrackIds = new ArrayList<>();

    private MglaYandexMusicPlayer() {
        ensureObservers();
    }

    private void ensureObservers() {
        if (observersRegistered) {
            return;
        }
        observersRegistered = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingDidStart);
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingDidReset);
            NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        }
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
            MglaMusicWidgetProvider.updateAll();
        });
    }

    private void notifyPlaybackState(boolean playing, boolean buffering) {
        AndroidUtilities.runOnUIThread(() -> {
            for (PlayerListener l : listeners) {
                l.onPlaybackStateChanged(playing, buffering);
            }
            MglaMusicWidgetProvider.updateAll();
        });
    }

    private void notifyProgress(long positionMs, long durationMs) {
        if (Thread.currentThread() == ApplicationLoader.applicationHandler.getLooper().getThread()) {
            for (PlayerListener l : listeners) {
                l.onProgress(positionMs, durationMs);
            }
        } else {
            AndroidUtilities.runOnUIThread(() -> {
                for (PlayerListener l : listeners) {
                    l.onProgress(positionMs, durationMs);
                }
            });
        }
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
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
            return !MediaController.getInstance().isMessagePaused();
        }
        return isPlaying;
    }

    public boolean isBuffering() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
            return MediaController.getInstance().isDownloadingCurrentMessage();
        }
        return isBuffering;
    }

    public boolean isMyWaveActive() {
        return isMyWaveActive;
    }

    public boolean isCollectionWaveActive() {
        return isMyWaveActive && "Моя волна по коллекции".equals(currentSourceTitle);
    }

    /** Personal «Моя волна» (home tab), not collection / activity / track wave. */
    public boolean isPersonalWaveActive() {
        return isMyWaveActive && "Моя волна".equals(currentSourceTitle);
    }

    public String getCurrentSourceTitle() {
        return currentSourceTitle != null ? currentSourceTitle : "";
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
        playTrack(track, newQueue, sourceTitle, false);
    }

    /** Play one track from search; when it ends, continue with Моя волна seeded by that track. */
    public void playTrackThenWave(MglaYandexMusicModel.Track track) {
        if (track == null) return;
        List<MglaYandexMusicModel.Track> q = new ArrayList<>();
        q.add(track);
        playTrack(track, q, "Поиск", true);
    }

    public void playTrack(MglaYandexMusicModel.Track track, List<MglaYandexMusicModel.Track> newQueue, String sourceTitle, boolean thenWave) {
        if (track == null) return;
        ensureObservers();
        isMyWaveActive = false;
        currentRotorSessionId = null;
        currentRotorBatchId = null;
        currentRotorSeeds.clear();
        waveSeedAfterQueue = thenWave && !TextUtils.isEmpty(track.id) ? track.id : null;
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
        List<String> seeds = MglaYandexMusicStore.getInstance().buildMyWaveSeeds();
        playRotorSession(seeds, "Моя волна");
    }

    /** Always force-starts a brand new «Моя волна» stream with fresh recommendations. */
    public void startFreshMyWave() {
        currentRotorSessionId = null;
        currentRotorBatchId = null;
        synchronized (queue) {
            queue.clear();
            currentIndex = -1;
            currentTrack = null;
        }
        notifyQueueChanged();
        playMyWave();
    }

    /** Start or pause/resume personal «Моя волна» with current saved settings. */
    public void toggleMyWave() {
        if (isPersonalWaveActive()) {
            if (isPlaying) {
                pause();
            } else if (currentTrack != null) {
                play();
            } else {
                startFreshMyWave();
            }
        } else {
            startFreshMyWave();
        }
    }

    /**
     * If personal wave is already playing, restart it so new diversity/mood/language
     * settings take effect immediately with fresh recommendations.
     */
    public void restartPersonalWaveIfActive() {
        if (isPersonalWaveActive()) {
            startFreshMyWave();
        }
    }

    /**
     * Wave based on «Мне нравится»: recommendations mixed from collection preferences,
     * not just sequential playback of liked tracks.
     */
    public void playCollectionWave() {
        ensureObservers();
        isMyWaveActive = true;
        waveSeedAfterQueue = null;
        isRotorLoading = false;
        currentSourceTitle = "Моя волна по коллекции";
        currentRotorStation = "collection";
        currentRotorSessionId = null;
        currentRotorBatchId = null;
        currentRotorSeeds.clear();
        isBuffering = true;
        notifyPlaybackState(isPlaying, true);

        List<MglaYandexMusicModel.Track> liked = MglaYandexMusicStore.getInstance().getCachedFavorites();
        if (liked != null && !liked.isEmpty()) {
            startCollectionWaveWithLiked(liked);
            return;
        }
        MglaYandexMusicApi.fetchFavorites(new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Track>>() {
            @Override
            public void onSuccess(List<MglaYandexMusicModel.Track> tracks) {
                if (tracks == null || tracks.isEmpty()) {
                    // Empty collection — fall back to personal wave.
                    playMyWave();
                    return;
                }
                startCollectionWaveWithLiked(tracks);
            }

            @Override
            public void onError(Exception error) {
                playMyWave();
            }
        });
    }

    private void startCollectionWaveWithLiked(List<MglaYandexMusicModel.Track> liked) {
        MglaYandexMusicModel.UserInfo user = MglaYandexMusicStore.getInstance().getUserInfo();
        List<String> seeds = new ArrayList<>();
        // Prefer wave seeded by «Мне нравится» playlist; else by a liked track.
        if (user != null && !TextUtils.isEmpty(user.uid)) {
            seeds.add("playlist:" + user.uid + ":3");
        } else {
            MglaYandexMusicModel.Track seedTrack = liked.get((int) (Math.random() * liked.size()));
            if (seedTrack != null && !TextUtils.isEmpty(seedTrack.id)) {
                seeds.add("track:" + seedTrack.id);
            }
        }
        if (seeds.isEmpty()) {
            seeds.add("user:onyourwave");
        }
        playRotorSession(seeds, "Моя волна по коллекции");
    }

    public void playRotorStation(String station, String title) {
        List<String> seeds = new ArrayList<>();
        seeds.add(!TextUtils.isEmpty(station) ? station : "user:onyourwave");
        playRotorSession(seeds, title);
    }

    public void playRotorSession(List<String> seeds, String title) {
        ensureObservers();
        isMyWaveActive = true;
        waveSeedAfterQueue = null;
        isRotorLoading = false; // allow replacing an in-flight fetch when switching sources
        currentSourceTitle = !TextUtils.isEmpty(title) ? title : "Моя волна";
        currentRotorStation = seeds != null && !seeds.isEmpty() ? seeds.get(0) : "user:onyourwave";
        currentRotorSessionId = null;
        currentRotorBatchId = null;
        currentRotorSeeds.clear();
        if (seeds != null) {
            currentRotorSeeds.addAll(seeds);
        }
        if (currentRotorSeeds.isEmpty()) {
            currentRotorSeeds.add("user:onyourwave");
        }

        isBuffering = true;
        notifyPlaybackState(isPlaying, true);

        fetchRotorTracks(false);
    }

    /** Toggle collection wave: start, or pause/resume when already active. */
    public void toggleCollectionWave() {
        if (isCollectionWaveActive()) {
            if (isPlaying) {
                pause();
            } else if (currentTrack != null) {
                play();
            } else {
                playCollectionWave();
            }
        } else {
            playCollectionWave();
        }
    }

    private void fetchRotorTracks(boolean append) {
        if (isRotorLoading) return;
        isRotorLoading = true;

        if (append && !TextUtils.isEmpty(currentRotorSessionId)) {
            List<String> queueIds = new ArrayList<>();
            synchronized (queue) {
                for (MglaYandexMusicModel.Track t : queue) {
                    if (t != null && !TextUtils.isEmpty(t.id)) {
                        queueIds.add(t.id);
                    }
                }
            }
            MglaYandexMusicApi.continueRotorSession(currentRotorSessionId, queueIds, new MglaYandexMusicApi.Callback<MglaYandexMusicApi.RotorBatch>() {
                @Override
                public void onSuccess(MglaYandexMusicApi.RotorBatch batch) {
                    applyRotorBatch(batch, true);
                }

                @Override
                public void onError(Exception error) {
                    // Session expired — open a new one and append.
                    startFreshRotorSession(true);
                }
            });
            return;
        }

        startFreshRotorSession(append);
    }

    private void startFreshRotorSession(boolean append) {
        final List<String> seeds = new ArrayList<>(currentRotorSeeds);
        MglaYandexMusicApi.startRotorSession(seeds, new MglaYandexMusicApi.Callback<MglaYandexMusicApi.RotorBatch>() {
            @Override
            public void onSuccess(MglaYandexMusicApi.RotorBatch batch) {
                applyRotorBatch(batch, append);
            }

            @Override
            public void onError(Exception error) {
                // Playlist seed may fail — retry with a liked track, then personal wave.
                if (!seeds.isEmpty() && seeds.get(0).startsWith("playlist:")) {
                    List<MglaYandexMusicModel.Track> liked = MglaYandexMusicStore.getInstance().getCachedFavorites();
                    if (liked != null && !liked.isEmpty()) {
                        MglaYandexMusicModel.Track seedTrack = liked.get((int) (Math.random() * liked.size()));
                        if (seedTrack != null && !TextUtils.isEmpty(seedTrack.id)) {
                            currentRotorSeeds.clear();
                            currentRotorSeeds.add("track:" + seedTrack.id);
                            startFreshRotorSession(append);
                            return;
                        }
                    }
                }
                MglaYandexMusicApi.fetchRotorTracks("user:onyourwave", new MglaYandexMusicApi.Callback<List<MglaYandexMusicModel.Track>>() {
                    @Override
                    public void onSuccess(List<MglaYandexMusicModel.Track> tracks) {
                        isRotorLoading = false;
                        applyRotorTracks(tracks, append);
                    }

                    @Override
                    public void onError(Exception e2) {
                        isRotorLoading = false;
                        if (!append && queue.isEmpty()) {
                            List<MglaYandexMusicModel.Track> cached = MglaYandexMusicStore.getInstance().getCachedRotor();
                            if (cached != null && !cached.isEmpty()) {
                                applyRotorTracks(cached, false);
                                return;
                            }
                        }
                        isBuffering = false;
                        notifyPlaybackState(false, false);
                    }
                });
            }
        });
    }

    private void applyRotorBatch(MglaYandexMusicApi.RotorBatch batch, boolean append) {
        isRotorLoading = false;
        if (batch == null) return;
        if (!TextUtils.isEmpty(batch.sessionId)) {
            currentRotorSessionId = batch.sessionId;
        }
        if (!TextUtils.isEmpty(batch.batchId)) {
            currentRotorBatchId = batch.batchId;
        }
        if (!append && !TextUtils.isEmpty(batch.sessionId)) {
            MglaYandexMusicApi.sendRotorFeedback(batch.sessionId, batch.batchId, "radioStarted", null, 0, null);
        }
        applyRotorTracks(batch.tracks, append);
    }

    private void applyRotorTracks(List<MglaYandexMusicModel.Track> tracks, boolean append) {
        if (tracks == null || tracks.isEmpty()) return;
        synchronized (queue) {
            if (append) {
                queue.addAll(tracks);
            } else {
                List<MglaYandexMusicModel.Track> fresh = new ArrayList<>(tracks);
                if (fresh.size() > 1 && !recentWaveTrackIds.isEmpty()) {
                    int bestIdx = 0;
                    for (int i = 0; i < fresh.size(); i++) {
                        MglaYandexMusicModel.Track t = fresh.get(i);
                        if (t != null && !recentWaveTrackIds.contains(t.id)) {
                            bestIdx = i;
                            break;
                        }
                    }
                    if (bestIdx > 0) {
                        MglaYandexMusicModel.Track first = fresh.remove(bestIdx);
                        fresh.add(0, first);
                    }
                }
                queue.clear();
                queue.addAll(fresh);
                currentIndex = 0;
                loadAndPlayCurrent();
            }
        }
        notifyQueueChanged();
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
        if (isMyWaveActive && !TextUtils.isEmpty(track.id)) {
            recentWaveTrackIds.remove(track.id);
            recentWaveTrackIds.add(track.id);
            if (recentWaveTrackIds.size() > 50) {
                recentWaveTrackIds.remove(0);
            }
        }
        notifyTrackChanged(track);

        isBuffering = true;
        isPlaying = false;
        notifyPlaybackState(false, true);

        if (isMyWaveActive && !TextUtils.isEmpty(currentRotorSessionId) && !TextUtils.isEmpty(track.id)) {
            MglaYandexMusicApi.sendRotorFeedback(currentRotorSessionId, currentRotorBatchId, "trackStarted", track.id, 0, null);
        }

        if (isMyWaveActive && currentIndex >= queue.size() - 2) {
            fetchRotorTracks(true);
        }

        int account = UserConfig.selectedAccount;
        File file = MglaYandexMusicPlayback.fileForPlayback(track.id);
        MessageObject current = MglaYandexMusicPlayback.createMessageObject(track, file, account);
        if (current == null) {
            return;
        }

        // Own MediaSession for headset / BT AVRCP — stop Telegram MusicPlayerService so sessions don't fight.
        try {
            ApplicationLoader.applicationContext.stopService(
                    new android.content.Intent(ApplicationLoader.applicationContext, MusicPlayerService.class));
        } catch (Throwable ignored) {
        }
        try {
            MglaYandexMusicService.startOrUpdate(ApplicationLoader.applicationContext);
        } catch (Throwable ignored) {
        }

        // Pass the full Yandex queue so playlist next-prev stay in sync.
        ArrayList<MessageObject> playlist;
        synchronized (queue) {
            playlist = MglaYandexMusicPlayback.buildPlaylist(queue, account);
        }
        if (playlist.isEmpty()) {
            playlist = new ArrayList<>();
            playlist.add(current);
        }
        MessageObject playObject = current;
        for (int i = 0; i < playlist.size(); i++) {
            MessageObject candidate = playlist.get(i);
            if (candidate != null && candidate.getId() == current.getId()) {
                playObject = candidate;
                break;
            }
        }
        MediaController.getInstance().setPlaylist(playlist, playObject, 0, false, null);
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
                isPlaying = true;
                isBuffering = false;
                notifyPlaybackState(true, false);
            } else {
                MediaController.getInstance().pauseMessage(playing);
                isPlaying = false;
                isBuffering = false;
                notifyPlaybackState(false, false);
            }
            return;
        }
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    public void play() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(playing);
            }
            isPlaying = true;
            isBuffering = false;
            notifyPlaybackState(true, false);
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
        isBuffering = false;
        notifyPlaybackState(false, false);
    }

    public void stop() {
        MediaController.getInstance().cleanupPlayer(true, true);
        isPlaying = false;
        isBuffering = false;
        isMyWaveActive = false;
        currentSourceTitle = "";
        currentRotorSessionId = null;
        currentRotorBatchId = null;
        currentTrack = null;
        synchronized (queue) {
            queue.clear();
            currentIndex = -1;
        }
        notifyPlaybackState(false, false);
        notifyTrackChanged(null);
        notifyQueueChanged();
        try {
            MglaYandexMusicService.stop(ApplicationLoader.applicationContext);
        } catch (Throwable ignored) {
        }
    }

    public void next() {
        synchronized (queue) {
            if (queue.isEmpty()) return;
            if (isMyWaveActive && !TextUtils.isEmpty(currentRotorSessionId) && currentTrack != null && !TextUtils.isEmpty(currentTrack.id)) {
                long pos = getCurrentPosition();
                long dur = getDuration();
                float playedSec = pos / 1000f;
                String type = (dur > 0 && (pos >= dur * 0.8f || pos >= dur - 5000)) ? "trackFinished" : "skip";
                MglaYandexMusicApi.sendRotorFeedback(currentRotorSessionId, currentRotorBatchId, type, currentTrack.id, playedSec, null);
            }
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
            } else if (!TextUtils.isEmpty(waveSeedAfterQueue)) {
                String seed = waveSeedAfterQueue;
                waveSeedAfterQueue = null;
                List<String> seeds = new ArrayList<>();
                seeds.add("track:" + seed);
                playRotorSession(seeds, "Моя волна");
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
            if (isMyWaveActive && !TextUtils.isEmpty(currentRotorSessionId) && currentTrack != null && !TextUtils.isEmpty(currentTrack.id)) {
                float playedSec = getCurrentPosition() / 1000f;
                MglaYandexMusicApi.sendRotorFeedback(currentRotorSessionId, currentRotorBatchId, "skip", currentTrack.id, playedSec, null);
            }
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
        if (isMyWaveActive && !TextUtils.isEmpty(currentRotorSessionId)) {
            MglaYandexMusicApi.sendRotorFeedback(
                    currentRotorSessionId,
                    currentRotorBatchId,
                    newLiked ? "like" : "unlike",
                    currentTrack.id,
                    getCurrentPosition() / 1000f,
                    null);
        }
    }

    /**
     * Dislike current track: remove from «Мне нравится», tell the wave engine (if active),
     * persist library dislike, and skip to the next track.
     */
    public void dislikeCurrentTrack() {
        if (currentTrack == null) {
            return;
        }
        final MglaYandexMusicModel.Track track = currentTrack;
        final float playedSec = getCurrentPosition() / 1000f;

        if (track.isLiked) {
            track.isLiked = false;
            MglaYandexMusicStore.getInstance().setLiked(track.id, false);
            MglaYandexMusicApi.toggleLike(track.id, false, null);
            notifyTrackChanged(track);
        }

        if (isMyWaveActive && !TextUtils.isEmpty(currentRotorSessionId)) {
            MglaYandexMusicApi.sendRotorFeedback(
                    currentRotorSessionId,
                    currentRotorBatchId,
                    "dislike",
                    track.id,
                    playedSec,
                    null);
        }
        // Always register a library dislike so future recommendations account for it.
        MglaYandexMusicApi.addTrackDislike(track.id, null);
        next();
    }

    /** Start «Моя волна» seeded by the currently playing track. */
    public void playWaveByCurrentTrack() {
        if (currentTrack == null || TextUtils.isEmpty(currentTrack.id)) {
            return;
        }
        List<String> seeds = new ArrayList<>();
        seeds.add("track:" + currentTrack.id);
        playRotorSession(seeds, "Моя волна по треку");
    }

    /** Drop Yandex session state without touching MediaController (Telegram media took over). */
    public void clearSessionWithoutStoppingPlayer() {
        if (currentTrack == null && !isPlaying && !isBuffering) {
            try {
                MglaYandexMusicService.stop(ApplicationLoader.applicationContext);
            } catch (Throwable ignored) {
            }
            return;
        }
        isPlaying = false;
        isBuffering = false;
        isMyWaveActive = false;
        currentSourceTitle = "";
        currentRotorSessionId = null;
        currentRotorBatchId = null;
        currentTrack = null;
        notifyPlaybackState(false, false);
        notifyTrackChanged(null);
        try {
            MglaYandexMusicService.stop(ApplicationLoader.applicationContext);
        } catch (Throwable ignored) {
        }
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
                try {
                    MglaYandexMusicService.startOrUpdate(ApplicationLoader.applicationContext);
                } catch (Throwable ignored) {
                }
            } else if (playing != null) {
                // Telegram voice / round / music replaced Yandex — release session state only.
                clearSessionWithoutStoppingPlayer();
                MglaMusicWidgetProvider.updateAll();
            }
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            // Natural end is handled via MediaController.playNextMessageWithoutOrder → next()
            isPlaying = false;
            isBuffering = false;
            notifyPlaybackState(false, false);
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && MglaYandexMusicPlayback.isYandexMessage(playing)) {
                boolean curPlaying = !MediaController.getInstance().isMessagePaused();
                boolean curBuffering = MediaController.getInstance().isDownloadingCurrentMessage();
                if (curPlaying != isPlaying || curBuffering != isBuffering) {
                    isPlaying = curPlaying;
                    isBuffering = curBuffering;
                    notifyPlaybackState(isPlaying, isBuffering);
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastProgressNotifyMs >= 45) {
                    lastProgressNotifyMs = now;
                    notifyProgress(getCurrentPosition(), getDuration());
                }
            }
        } else if (id == NotificationCenter.playerDidStartPlaying) {
            // Non-Yandex VideoPlayer started — pause Yandex via MediaController already handled by MediaController
        }
    }
}
