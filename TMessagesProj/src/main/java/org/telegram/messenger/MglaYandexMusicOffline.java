package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent offline downloads for Yandex Music.
 * Files go under Telegram's media root ({@link FileLoader#MEDIA_DIR_AUDIO}),
 * which already follows Data &amp; Storage → storage path (internal / SD).
 */
public final class MglaYandexMusicOffline {

    public interface Listener {
        void onOfflineChanged();
    }

    public interface ProgressCallback {
        void onProgress(String trackId, int done, int total);
        void onTrackDone(String trackId, boolean ok);
        void onFinished(int okCount, int failCount);
    }

    private static final String PREFS = "mgla_yandex_music_offline";
    private static final String KEY_TRACKS_JSON = "tracks_json";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static volatile MglaYandexMusicOffline instance;

    public static MglaYandexMusicOffline getInstance() {
        if (instance == null) {
            synchronized (MglaYandexMusicOffline.class) {
                if (instance == null) {
                    instance = new MglaYandexMusicOffline();
                }
            }
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final Map<String, MglaYandexMusicModel.Track> tracks = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, Boolean> downloading = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean batchRunning;

    private MglaYandexMusicOffline() {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadIndex();
        pruneMissingFiles();
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : listeners) {
                l.onOfflineChanged();
            }
        });
    }

    /** Directory on the storage selected in Telegram Data &amp; Storage settings. */
    public static File getOfflineDir() {
        File audio = FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO);
        if (audio != null) {
            File dir = new File(audio, "YandexMusic");
            if (ensureDir(dir)) {
                return dir;
            }
        }
        File docs = FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT);
        if (docs != null) {
            File dir = new File(docs, "YandexMusic");
            if (ensureDir(dir)) {
                return dir;
            }
        }
        File external = ApplicationLoader.applicationContext.getExternalFilesDir(null);
        if (external != null) {
            File dir = new File(external, "Telegram/YandexMusic");
            if (ensureDir(dir)) {
                return dir;
            }
        }
        File fallback = new File(ApplicationLoader.applicationContext.getFilesDir(), "YandexMusic");
        ensureDir(fallback);
        return fallback;
    }

    private static boolean ensureDir(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            if (!dir.isDirectory()) {
                return false;
            }
            AndroidUtilities.createEmptyFile(new File(dir, ".nomedia"));
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static String safeTrackFileName(String trackId) {
        String safeId = trackId != null ? trackId.replaceAll("[^a-zA-Z0-9._-]", "_") : "track";
        return safeId + ".mp3";
    }

    public File fileForTrack(String trackId) {
        return new File(getOfflineDir(), safeTrackFileName(trackId));
    }

    public boolean isDownloaded(String trackId) {
        if (TextUtils.isEmpty(trackId)) {
            return false;
        }
        File f = fileForTrack(trackId);
        return f.exists() && f.length() > 1024;
    }

    public boolean isDownloading(String trackId) {
        return trackId != null && downloading.containsKey(trackId);
    }

    public boolean isBatchRunning() {
        return batchRunning;
    }

    public int getDownloadedCount() {
        synchronized (tracks) {
            return tracks.size();
        }
    }

    /** Total bytes of offline audio files currently on disk. */
    public long getDownloadedBytes() {
        long total = 0;
        synchronized (tracks) {
            for (String id : tracks.keySet()) {
                File f = fileForTrack(id);
                if (f.exists()) {
                    total += f.length();
                }
            }
        }
        return total;
    }

    public long getDownloadedBytes(String trackId) {
        if (TextUtils.isEmpty(trackId)) {
            return 0;
        }
        File f = fileForTrack(trackId);
        return f.exists() ? f.length() : 0;
    }

    public long getDownloadedBytes(Collection<String> trackIds) {
        if (trackIds == null || trackIds.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (String id : trackIds) {
            total += getDownloadedBytes(id);
        }
        return total;
    }

    public List<MglaYandexMusicModel.Track> getDownloadedTracks() {
        synchronized (tracks) {
            ArrayList<MglaYandexMusicModel.Track> list = new ArrayList<>(tracks.values());
            // Drop entries whose files disappeared.
            ArrayList<MglaYandexMusicModel.Track> alive = new ArrayList<>();
            for (MglaYandexMusicModel.Track t : list) {
                if (t != null && isDownloaded(t.id)) {
                    alive.add(t);
                }
            }
            return alive;
        }
    }

    public MglaYandexMusicModel.Track getTrack(String trackId) {
        if (TextUtils.isEmpty(trackId)) {
            return null;
        }
        synchronized (tracks) {
            return tracks.get(trackId);
        }
    }

    private void loadIndex() {
        String raw = prefs.getString(KEY_TRACKS_JSON, null);
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            synchronized (tracks) {
                tracks.clear();
                for (int i = 0; i < arr.length(); i++) {
                    MglaYandexMusicModel.Track t = MglaYandexMusicModel.Track.fromJson(arr.optJSONObject(i));
                    if (t != null && !TextUtils.isEmpty(t.id)) {
                        tracks.put(t.id, t);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void saveIndex() {
        JSONArray arr = new JSONArray();
        synchronized (tracks) {
            for (MglaYandexMusicModel.Track t : tracks.values()) {
                if (t != null) {
                    arr.put(t.toJson());
                }
            }
        }
        prefs.edit().putString(KEY_TRACKS_JSON, arr.toString()).apply();
    }

    private void pruneMissingFiles() {
        boolean changed = false;
        synchronized (tracks) {
            ArrayList<String> remove = new ArrayList<>();
            for (Map.Entry<String, MglaYandexMusicModel.Track> e : tracks.entrySet()) {
                File f = fileForTrack(e.getKey());
                if (!f.exists() || f.length() <= 1024) {
                    remove.add(e.getKey());
                }
            }
            for (String id : remove) {
                tracks.remove(id);
                changed = true;
            }
        }
        if (changed) {
            saveIndex();
        }
    }

    public void rememberTrack(MglaYandexMusicModel.Track track) {
        rememberTrack(track, true);
    }

    public void rememberTrack(MglaYandexMusicModel.Track track, boolean notify) {
        if (track == null || TextUtils.isEmpty(track.id)) {
            return;
        }
        synchronized (tracks) {
            tracks.put(track.id, track);
        }
        saveIndex();
        if (notify && !batchRunning) {
            notifyChanged();
        }
        // Persist list-quality cover for offline UI.
        if (!TextUtils.isEmpty(track.getCoverUrl(300))) {
            MglaMusicImageHelper.prefetchCoverToDisk(track.getCoverUrl(300));
        }
    }

    public void remove(String trackId) {
        remove(trackId, true);
    }

    public void remove(String trackId, boolean notify) {
        if (TextUtils.isEmpty(trackId)) {
            return;
        }
        File f = fileForTrack(trackId);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        // Also clear streaming cache copy if present.
        File cache = MglaYandexMusicPlayback.streamingCacheFileForTrack(trackId);
        if (cache != null && cache.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
        }
        synchronized (tracks) {
            tracks.remove(trackId);
        }
        saveIndex();
        if (notify) {
            notifyChanged();
        }
    }

    /** Remove several offline tracks and fire a single change notification. */
    public void removeMany(Collection<String> trackIds) {
        if (trackIds == null || trackIds.isEmpty()) {
            return;
        }
        for (String id : trackIds) {
            remove(id, false);
        }
        notifyChanged();
    }

    public void removeAll() {
        List<MglaYandexMusicModel.Track> all = getDownloadedTracks();
        for (MglaYandexMusicModel.Track t : all) {
            if (t != null) {
                File f = fileForTrack(t.id);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        synchronized (tracks) {
            tracks.clear();
        }
        saveIndex();
        notifyChanged();
    }

    /** Download one track for offline use. No-op if already downloaded. */
    public void download(MglaYandexMusicModel.Track track, Runnable onDoneUi) {
        if (track == null || TextUtils.isEmpty(track.id)) {
            if (onDoneUi != null) {
                AndroidUtilities.runOnUIThread(onDoneUi);
            }
            return;
        }
        if (isDownloaded(track.id)) {
            rememberTrack(track);
            if (onDoneUi != null) {
                AndroidUtilities.runOnUIThread(onDoneUi);
            }
            return;
        }
        if (downloading.putIfAbsent(track.id, Boolean.TRUE) != null) {
            return;
        }
        final String trackId = track.id;
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            try {
                ok = downloadSync(track);
            } catch (Exception e) {
                FileLog.e("MglaYandexMusicOffline: download failed " + trackId, e);
            } finally {
                downloading.remove(trackId);
            }
            if (ok) {
                rememberTrack(track);
            }
            if (onDoneUi != null) {
                AndroidUtilities.runOnUIThread(onDoneUi);
            } else {
                notifyChanged();
            }
        });
    }

    /** Download many tracks sequentially (playlist / favorites). */
    public void downloadAll(List<MglaYandexMusicModel.Track> list, ProgressCallback callback) {
        if (list == null || list.isEmpty()) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onFinished(0, 0));
            }
            return;
        }
        if (batchRunning) {
            return;
        }
        batchRunning = true;
        final ArrayList<MglaYandexMusicModel.Track> copy = new ArrayList<>(list);
        Utilities.globalQueue.postRunnable(() -> {
            int ok = 0;
            int fail = 0;
            final int total = copy.size();
            for (int i = 0; i < copy.size(); i++) {
                MglaYandexMusicModel.Track track = copy.get(i);
                if (track == null || TextUtils.isEmpty(track.id)) {
                    fail++;
                    continue;
                }
                final String trackId = track.id;
                final int doneIndex = i + 1;
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) {
                        callback.onProgress(trackId, doneIndex, total);
                    }
                });
                boolean success;
                if (isDownloaded(trackId)) {
                    rememberTrack(track, false);
                    success = true;
                } else if (downloading.putIfAbsent(trackId, Boolean.TRUE) != null) {
                    success = false;
                } else {
                    try {
                        success = downloadSync(track);
                    } catch (Exception e) {
                        FileLog.e(e);
                        success = false;
                    } finally {
                        downloading.remove(trackId);
                    }
                    if (success) {
                        rememberTrack(track, false);
                    }
                }
                if (success) {
                    ok++;
                } else {
                    fail++;
                }
                final boolean okFlag = success;
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) {
                        callback.onTrackDone(trackId, okFlag);
                    }
                });
            }
            batchRunning = false;
            final int okFinal = ok;
            final int failFinal = fail;
            AndroidUtilities.runOnUIThread(() -> {
                notifyChanged();
                if (callback != null) {
                    callback.onFinished(okFinal, failFinal);
                }
            });
        });
    }

    private boolean downloadSync(MglaYandexMusicModel.Track track) throws Exception {
        File out = fileForTrack(track.id);
        if (out.exists() && out.length() > 1024) {
            return true;
        }

        // Reuse streaming cache if already warm.
        File streaming = MglaYandexMusicPlayback.streamingCacheFileForTrack(track.id);
        if (streaming != null && streaming.exists() && streaming.length() > 1024) {
            copyFile(streaming, out);
            return out.exists() && out.length() > 1024;
        }

        MglaYandexMusicApi.AudioStream stream = MglaYandexMusicApi.resolveAudioStreamSync(track.id);
        if (stream == null || TextUtils.isEmpty(stream.url)) {
            throw new Exception("No stream");
        }
        String url = stream.url;
        if (url.startsWith("file:")) {
            File local = new File(Uri.parse(url).getPath());
            if (local.exists() && local.length() > 1024) {
                if (!local.getAbsolutePath().equals(out.getAbsolutePath())) {
                    copyFile(local, out);
                }
                return out.exists() && out.length() > 1024;
            }
            throw new Exception("Local stream missing");
        }

        HttpURLConnection conn = MglaDirectHttp.open(url);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Referer", "https://music.yandex.ru/");

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("CDN HTTP " + code);
        }
        InputStream is = conn.getInputStream();
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = is.read(buf)) >= 0) {
            fos.write(buf, 0, n);
            total += n;
        }
        fos.close();
        is.close();
        conn.disconnect();
        if (total < 1024) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new Exception("Downloaded file too small");
        }
        if (out.exists()) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
        if (!tmp.renameTo(out)) {
            copyFile(tmp, out);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return out.exists() && out.length() > 1024;
    }

    private static void copyFile(File from, File to) throws Exception {
        if (from == null || to == null) {
            throw new Exception("copy null");
        }
        if (to.exists()) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
        }
        File parent = to.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        out.close();
        in.close();
    }
}
