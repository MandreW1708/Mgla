package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Direct HTTP API client for Yandex Music (reverse-engineered official endpoints).
 */
public final class MglaYandexMusicApi {

    private static final String BASE_URL = "https://api.music.yandex.net";
    private static final String BASE_URL_RU = "https://api.music.yandex.ru";
    private static final String SALT = "XGRlBW9FXlekgbPrRHuSiA";
    /** Web player secret for /get-file-info HMAC (from Yandex Music web frontend). */
    private static final String FILE_INFO_SECRET = "7tvSmFbyf5hJnIHhCimDDD";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String CLIENT_ANDROID = "YandexMusicAndroid/24023621";
    private static final String CLIENT_WEB = "YandexMusicWebNext/1.0.0";

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception error);
    }

    /** Playable stream: progressive mp3 or HLS. */
    public static final class AudioStream {
        public final String url;
        /** "hls" or "other" (progressive), matching VideoPlayer media types. */
        public final String type;

        public AudioStream(String url, String type) {
            this.url = url;
            this.type = !TextUtils.isEmpty(type) ? type : "other";
        }

        public boolean isHls() {
            return "hls".equalsIgnoreCase(type);
        }
    }

    private static final Map<String, StreamCacheEntry> directUrlCache = Collections.synchronizedMap(new HashMap<>());

    private static class StreamCacheEntry {
        final AudioStream stream;
        final long expiresAt;

        StreamCacheEntry(AudioStream stream, long ttlMs) {
            this.stream = stream;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }

        boolean isValid() {
            return System.currentTimeMillis() < expiresAt && stream != null && !TextUtils.isEmpty(stream.url);
        }
    }

    private MglaYandexMusicApi() {
    }

    private static String getAuthHeader() {
        String token = MglaYandexMusicStore.getInstance().getToken();
        return TextUtils.isEmpty(token) ? null : "OAuth " + token;
    }

    private static HttpURLConnection setupConnection(String urlStr, String method) throws Exception {
        HttpURLConnection conn = MglaDirectHttp.open(urlStr);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("X-Yandex-Music-Client", CLIENT_ANDROID);
        conn.setRequestProperty("Accept-Language", "ru");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Referer", "https://music.yandex.ru/");

        String auth = getAuthHeader();
        if (auth != null) {
            conn.setRequestProperty("Authorization", auth);
        }
        return conn;
    }

    public static void invalidateStreamCache(String trackId) {
        if (!TextUtils.isEmpty(trackId)) {
            directUrlCache.remove(trackId);
        }
    }

    private static boolean looksLikeHls(String url, String container) {
        if (!TextUtils.isEmpty(container) && container.toLowerCase().contains("hls")) {
            return true;
        }
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains(".m3u8") || lower.contains("hls");
    }

    private static boolean looksLikeDirectMp3(String url) {
        return !TextUtils.isEmpty(url) && url.contains("/get-mp3/") && url.contains("://");
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            throw new Exception("HTTP error " + code + " with empty body");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + sb.toString());
        }
        return sb.toString();
    }

    public static void fetchAccountStatus(Callback<MglaYandexMusicModel.UserInfo> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                HttpURLConnection conn = setupConnection(BASE_URL + "/account/status", "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                if (result == null) {
                    throw new Exception("Invalid account status response: missing result");
                }
                JSONObject account = result.optJSONObject("account");
                if (account == null) {
                    throw new Exception("Invalid account status response: missing account");
                }

                String uid = String.valueOf(account.opt("uid"));
                String login = account.optString("login", "");
                String fullName = account.optString("fullName", account.optString("displayName", login));

                JSONObject plus = result.optJSONObject("plus");
                boolean hasPlus = plus != null && plus.optBoolean("hasPlus", false);
                if (!hasPlus) {
                    JSONObject permissions = result.optJSONObject("permissions");
                    if (permissions != null && permissions.optLong("until", 0) > System.currentTimeMillis() / 1000) {
                        hasPlus = true;
                    }
                }

                String avatarUrl = null;
                JSONObject defaultEmail = account.optJSONObject("default_email");
                if (account.has("avatarUrl")) {
                    avatarUrl = account.optString("avatarUrl", null);
                }

                MglaYandexMusicModel.UserInfo userInfo = new MglaYandexMusicModel.UserInfo(uid, login, fullName, avatarUrl, hasPlus);
                MglaYandexMusicStore.getInstance().saveUserInfo(userInfo);

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(userInfo);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void fetchFavorites(Callback<List<MglaYandexMusicModel.Track>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                MglaYandexMusicModel.UserInfo user = MglaYandexMusicStore.getInstance().getUserInfo();
                String uid = user != null ? user.uid : null;
                if (TextUtils.isEmpty(uid)) {
                    HttpURLConnection connStatus = setupConnection(BASE_URL + "/account/status", "GET");
                    String respStatus = readResponse(connStatus);
                    JSONObject rootStatus = new JSONObject(respStatus);
                    JSONObject resStatus = rootStatus.optJSONObject("result");
                    if (resStatus != null && resStatus.has("account")) {
                        uid = String.valueOf(resStatus.getJSONObject("account").opt("uid"));
                    }
                }
                if (TextUtils.isEmpty(uid)) {
                    throw new Exception("Cannot fetch favorites: User ID is unknown");
                }

                HttpURLConnection conn = setupConnection(BASE_URL + "/users/" + uid + "/likes/tracks", "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                JSONObject library = result != null ? result.optJSONObject("library") : null;
                JSONArray tracksArr = library != null ? library.optJSONArray("tracks") : null;

                List<String> trackIds = new ArrayList<>();
                if (tracksArr != null) {
                    for (int i = 0; i < tracksArr.length(); i++) {
                        JSONObject item = tracksArr.optJSONObject(i);
                        if (item != null) {
                            String id = String.valueOf(item.opt("id"));
                            if (!TextUtils.isEmpty(id)) {
                                trackIds.add(id);
                            }
                        }
                    }
                }

                MglaYandexMusicStore.getInstance().setLikedBatch(trackIds);

                if (trackIds.isEmpty()) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (callback != null) callback.onSuccess(new ArrayList<>());
                    });
                    return;
                }

                int batchSize = Math.min(100, trackIds.size());
                List<String> subList = trackIds.subList(0, batchSize);
                StringBuilder postBody = new StringBuilder("track-ids=");
                for (int i = 0; i < subList.size(); i++) {
                    if (i > 0) postBody.append(',');
                    postBody.append(subList.get(i));
                }

                HttpURLConnection connTracks = setupConnection(BASE_URL + "/tracks", "POST");
                connTracks.setDoOutput(true);
                connTracks.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = connTracks.getOutputStream();
                os.write(postBody.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                String respTracks = readResponse(connTracks);
                JSONObject rootTracks = new JSONObject(respTracks);
                JSONArray resArr = rootTracks.optJSONArray("result");

                List<MglaYandexMusicModel.Track> list = new ArrayList<>();
                if (resArr != null) {
                    for (int i = 0; i < resArr.length(); i++) {
                        JSONObject obj = resArr.optJSONObject(i);
                        if (obj != null) {
                            MglaYandexMusicModel.Track t = MglaYandexMusicModel.Track.fromApiJson(obj);
                            if (t != null) {
                                t.isLiked = true;
                                list.add(t);
                            }
                        }
                    }
                }

                MglaYandexMusicStore.getInstance().setCachedFavorites(list);

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(list);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void fetchPlaylists(Callback<List<MglaYandexMusicModel.Playlist>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                MglaYandexMusicModel.UserInfo user = MglaYandexMusicStore.getInstance().getUserInfo();
                String uid = user != null ? user.uid : null;
                if (TextUtils.isEmpty(uid)) {
                    HttpURLConnection connStatus = setupConnection(BASE_URL + "/account/status", "GET");
                    String respStatus = readResponse(connStatus);
                    JSONObject rootStatus = new JSONObject(respStatus);
                    JSONObject resStatus = rootStatus.optJSONObject("result");
                    if (resStatus != null && resStatus.has("account")) {
                        uid = String.valueOf(resStatus.getJSONObject("account").opt("uid"));
                    }
                }
                if (TextUtils.isEmpty(uid)) {
                    throw new Exception("Cannot fetch playlists: User ID is unknown");
                }

                HttpURLConnection conn = setupConnection(BASE_URL + "/users/" + uid + "/playlists/list", "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONArray resArr = root.optJSONArray("result");

                List<MglaYandexMusicModel.Playlist> list = new ArrayList<>();
                if (resArr != null) {
                    for (int i = 0; i < resArr.length(); i++) {
                        JSONObject obj = resArr.optJSONObject(i);
                        if (obj != null) {
                            MglaYandexMusicModel.Playlist p = MglaYandexMusicModel.Playlist.fromApiJson(obj);
                            if (p != null) {
                                list.add(p);
                            }
                        }
                    }
                }

                MglaYandexMusicStore.getInstance().setCachedPlaylists(list);

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(list);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void fetchPlaylistTracks(String uid, long kind, Callback<List<MglaYandexMusicModel.Track>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String effectiveUid = uid;
                if (TextUtils.isEmpty(effectiveUid)) {
                    MglaYandexMusicModel.UserInfo user = MglaYandexMusicStore.getInstance().getUserInfo();
                    effectiveUid = user != null ? user.uid : "";
                }
                HttpURLConnection conn = setupConnection(BASE_URL + "/users/" + effectiveUid + "/playlists/" + kind, "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                JSONArray tracksArr = result != null ? result.optJSONArray("tracks") : null;

                List<MglaYandexMusicModel.Track> list = new ArrayList<>();
                if (tracksArr != null) {
                    for (int i = 0; i < tracksArr.length(); i++) {
                        JSONObject item = tracksArr.optJSONObject(i);
                        if (item != null) {
                            JSONObject trackObj = item.optJSONObject("track");
                            if (trackObj != null) {
                                MglaYandexMusicModel.Track t = MglaYandexMusicModel.Track.fromApiJson(trackObj);
                                if (t != null) {
                                    t.isLiked = MglaYandexMusicStore.getInstance().isLiked(t.id);
                                    list.add(t);
                                }
                            }
                        }
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(list);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static final class RotorBatch {
        public final String sessionId;
        public final String batchId;
        public final List<MglaYandexMusicModel.Track> tracks;

        public RotorBatch(String sessionId, String batchId, List<MglaYandexMusicModel.Track> tracks) {
            this.sessionId = sessionId;
            this.batchId = batchId;
            this.tracks = tracks != null ? tracks : new ArrayList<>();
        }
    }

    /** Start a recommendation wave session (Моя волна / wave by playlist/track/artist). */
    public static void startRotorSession(List<String> seeds, Callback<RotorBatch> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                List<String> seedList = seeds != null ? seeds : new ArrayList<>();
                if (seedList.isEmpty()) {
                    seedList.add("user:onyourwave");
                }
                JSONObject body = new JSONObject();
                JSONArray seedsArr = new JSONArray();
                for (String seed : seedList) {
                    if (!TextUtils.isEmpty(seed)) {
                        seedsArr.put(seed);
                    }
                }
                body.put("seeds", seedsArr);
                body.put("includeTracksInResponse", true);
                body.put("includeWaveModel", true);
                body.put("interactive", true);

                HttpURLConnection conn = setupConnection(BASE_URL + "/rotor/session/new", "POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                if (result == null) {
                    result = root;
                }
                RotorBatch batch = parseRotorBatch(result);
                if (batch.tracks.isEmpty()) {
                    throw new Exception("Empty rotor session sequence");
                }
                if (seedList.contains("user:onyourwave")) {
                    MglaYandexMusicStore.getInstance().setCachedRotor(batch.tracks);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(batch);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    /** Fetch next batch for an existing wave session. */
    public static void continueRotorSession(String sessionId, List<String> queueTrackIds, Callback<RotorBatch> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (TextUtils.isEmpty(sessionId)) {
                    throw new Exception("Missing rotor session id");
                }
                JSONObject body = new JSONObject();
                JSONArray queueArr = new JSONArray();
                if (queueTrackIds != null) {
                    for (String id : queueTrackIds) {
                        if (!TextUtils.isEmpty(id)) {
                            queueArr.put(id);
                        }
                    }
                }
                body.put("queue", queueArr);

                HttpURLConnection conn = setupConnection(BASE_URL + "/rotor/session/" + Uri.encode(sessionId) + "/tracks", "POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                if (result == null) {
                    result = root;
                }
                RotorBatch parsed = parseRotorBatch(result);
                final RotorBatch batch = TextUtils.isEmpty(parsed.sessionId)
                        ? new RotorBatch(sessionId, parsed.batchId, parsed.tracks)
                        : parsed;
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(batch);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    /** Legacy station endpoint (fallback). Prefer {@link #startRotorSession}. */
    public static void fetchRotorTracks(String station, Callback<List<MglaYandexMusicModel.Track>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String targetStation = TextUtils.isEmpty(station) ? "user:onyourwave" : station;
                String url = BASE_URL + "/rotor/station/" + Uri.encode(targetStation) + "/tracks?settings2=true";
                HttpURLConnection conn = setupConnection(url, "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                List<MglaYandexMusicModel.Track> list = parseRotorSequence(result != null ? result.optJSONArray("sequence") : null);

                if (!list.isEmpty() && "user:onyourwave".equals(targetStation)) {
                    MglaYandexMusicStore.getInstance().setCachedRotor(list);
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(list);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    private static RotorBatch parseRotorBatch(JSONObject result) {
        if (result == null) {
            return new RotorBatch(null, null, new ArrayList<>());
        }
        String sessionId = result.optString("radioSessionId", result.optString("sessionId", null));
        String batchId = result.optString("batchId", null);
        List<MglaYandexMusicModel.Track> tracks = parseRotorSequence(result.optJSONArray("sequence"));
        return new RotorBatch(sessionId, batchId, tracks);
    }

    private static List<MglaYandexMusicModel.Track> parseRotorSequence(JSONArray seq) {
        List<MglaYandexMusicModel.Track> list = new ArrayList<>();
        if (seq == null) {
            return list;
        }
        for (int i = 0; i < seq.length(); i++) {
            JSONObject item = seq.optJSONObject(i);
            if (item == null) continue;
            JSONObject trackObj = item.optJSONObject("track");
            if (trackObj == null) continue;
            MglaYandexMusicModel.Track t = MglaYandexMusicModel.Track.fromApiJson(trackObj);
            if (t != null) {
                t.isLiked = MglaYandexMusicStore.getInstance().isLiked(t.id);
                list.add(t);
            }
        }
        return list;
    }

    public static void fetchArtistBrief(String artistId, Callback<MglaYandexMusicModel.ArtistBrief> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (TextUtils.isEmpty(artistId)) {
                    throw new Exception("Artist id is empty");
                }
                HttpURLConnection conn = setupConnection(BASE_URL + "/artists/" + Uri.encode(artistId) + "/brief-info", "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                if (result == null) {
                    throw new Exception("Invalid artist brief response");
                }

                MglaYandexMusicModel.ArtistBrief brief = new MglaYandexMusicModel.ArtistBrief();
                brief.artist = MglaYandexMusicModel.Artist.fromApiJson(result.optJSONObject("artist"));
                if (brief.artist == null) {
                    brief.artist = new MglaYandexMusicModel.Artist();
                    brief.artist.id = artistId;
                    brief.artist.name = "Исполнитель";
                }

                JSONArray popular = result.optJSONArray("popularTracks");
                if (popular != null) {
                    for (int i = 0; i < popular.length(); i++) {
                        MglaYandexMusicModel.Track t = MglaYandexMusicModel.Track.fromApiJson(popular.optJSONObject(i));
                        if (t != null) {
                            t.isLiked = MglaYandexMusicStore.getInstance().isLiked(t.id);
                            brief.popularTracks.add(t);
                        }
                    }
                }

                JSONArray albums = result.optJSONArray("albums");
                if (albums != null) {
                    for (int i = 0; i < albums.length(); i++) {
                        MglaYandexMusicModel.Album album = MglaYandexMusicModel.Album.fromApiJson(albums.optJSONObject(i));
                        if (album != null && !TextUtils.isEmpty(album.id)) {
                            brief.albums.add(album);
                        }
                    }
                }
                if (brief.albums.isEmpty()) {
                    JSONArray lastReleases = result.optJSONArray("lastReleases");
                    if (lastReleases != null) {
                        for (int i = 0; i < lastReleases.length(); i++) {
                            MglaYandexMusicModel.Album album = MglaYandexMusicModel.Album.fromApiJson(lastReleases.optJSONObject(i));
                            if (album != null && !TextUtils.isEmpty(album.id)) {
                                brief.albums.add(album);
                            }
                        }
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(brief);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void fetchArtistTracks(String artistId, int page, int pageSize, Callback<List<MglaYandexMusicModel.Track>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (TextUtils.isEmpty(artistId)) {
                    throw new Exception("Artist id is empty");
                }
                String url = BASE_URL + "/artists/" + Uri.encode(artistId) + "/tracks?page=" + page + "&page-size=" + pageSize;
                HttpURLConnection conn = setupConnection(url, "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                JSONArray tracksArr = result != null ? result.optJSONArray("tracks") : null;
                if (tracksArr == null && result != null) {
                    tracksArr = result.optJSONArray("items");
                }

                List<MglaYandexMusicModel.Track> list = new ArrayList<>();
                if (tracksArr != null) {
                    for (int i = 0; i < tracksArr.length(); i++) {
                        MglaYandexMusicModel.Track t = MglaYandexMusicModel.Track.fromApiJson(tracksArr.optJSONObject(i));
                        if (t != null) {
                            t.isLiked = MglaYandexMusicStore.getInstance().isLiked(t.id);
                            list.add(t);
                        }
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(list);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void fetchArtistAlbums(String artistId, Callback<List<MglaYandexMusicModel.Album>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (TextUtils.isEmpty(artistId)) {
                    throw new Exception("Artist id is empty");
                }
                String url = BASE_URL + "/artists/" + Uri.encode(artistId) + "/direct-albums?page=0&page-size=50&sort-by=rating";
                HttpURLConnection conn = setupConnection(url, "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                JSONArray albumsArr = result != null ? result.optJSONArray("albums") : null;
                if (albumsArr == null && result != null) {
                    albumsArr = result.optJSONArray("items");
                }

                List<MglaYandexMusicModel.Album> list = new ArrayList<>();
                if (albumsArr != null) {
                    for (int i = 0; i < albumsArr.length(); i++) {
                        MglaYandexMusicModel.Album album = MglaYandexMusicModel.Album.fromApiJson(albumsArr.optJSONObject(i));
                        if (album != null && !TextUtils.isEmpty(album.id)) {
                            list.add(album);
                        }
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(list);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void fetchAlbumTracks(String albumId, Callback<List<MglaYandexMusicModel.Track>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (TextUtils.isEmpty(albumId)) {
                    throw new Exception("Album id is empty");
                }
                HttpURLConnection conn = setupConnection(BASE_URL + "/albums/" + Uri.encode(albumId) + "/with-tracks", "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                JSONArray volumes = result != null ? result.optJSONArray("volumes") : null;

                List<MglaYandexMusicModel.Track> list = new ArrayList<>();
                if (volumes != null) {
                    for (int v = 0; v < volumes.length(); v++) {
                        JSONArray vol = volumes.optJSONArray(v);
                        if (vol == null) continue;
                        for (int i = 0; i < vol.length(); i++) {
                            MglaYandexMusicModel.Track t = MglaYandexMusicModel.Track.fromApiJson(vol.optJSONObject(i));
                            if (t != null) {
                                t.isLiked = MglaYandexMusicStore.getInstance().isLiked(t.id);
                                list.add(t);
                            }
                        }
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(list);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void searchTracks(String query, Callback<List<MglaYandexMusicModel.Track>> callback) {
        if (TextUtils.isEmpty(query)) {
            if (callback != null) callback.onSuccess(new ArrayList<>());
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String url = BASE_URL + "/search?text=" + Uri.encode(query.trim()) + "&type=track&page=0";
                HttpURLConnection conn = setupConnection(url, "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                JSONObject tracksObj = result != null ? result.optJSONObject("tracks") : null;
                JSONArray items = tracksObj != null ? tracksObj.optJSONArray("items") : null;

                List<MglaYandexMusicModel.Track> list = new ArrayList<>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject obj = items.optJSONObject(i);
                        if (obj != null) {
                            MglaYandexMusicModel.Track t = MglaYandexMusicModel.Track.fromApiJson(obj);
                            if (t != null) {
                                t.isLiked = MglaYandexMusicStore.getInstance().isLiked(t.id);
                                list.add(t);
                            }
                        }
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(list);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void toggleLike(String trackId, boolean like, Callback<Boolean> callback) {
        if (TextUtils.isEmpty(trackId)) return;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                MglaYandexMusicModel.UserInfo user = MglaYandexMusicStore.getInstance().getUserInfo();
                String uid = user != null ? user.uid : "";
                if (TextUtils.isEmpty(uid)) {
                    throw new Exception("Cannot toggle like: User ID is empty");
                }

                String url;
                String body;
                if (like) {
                    url = BASE_URL + "/users/" + uid + "/likes/tracks/add-multiple";
                    body = "track-ids=" + trackId;
                } else {
                    url = BASE_URL + "/users/" + uid + "/likes/tracks/remove";
                    body = "track-id=" + trackId;
                }

                HttpURLConnection conn = setupConnection(url, "POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                readResponse(conn);
                MglaYandexMusicStore.getInstance().setLiked(trackId, like);

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(like);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void fetchLikedArtists(Callback<List<MglaYandexMusicModel.Artist>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                MglaYandexMusicModel.UserInfo user = MglaYandexMusicStore.getInstance().getUserInfo();
                String uid = user != null ? user.uid : null;
                if (TextUtils.isEmpty(uid)) {
                    HttpURLConnection connStatus = setupConnection(BASE_URL + "/account/status", "GET");
                    String respStatus = readResponse(connStatus);
                    JSONObject rootStatus = new JSONObject(respStatus);
                    JSONObject resStatus = rootStatus.optJSONObject("result");
                    if (resStatus != null && resStatus.has("account")) {
                        uid = String.valueOf(resStatus.getJSONObject("account").opt("uid"));
                    }
                }
                if (TextUtils.isEmpty(uid)) {
                    throw new Exception("Cannot fetch artists: User ID is unknown");
                }

                HttpURLConnection conn = setupConnection(BASE_URL + "/users/" + uid + "/likes/artists", "GET");
                String resp = readResponse(conn);
                JSONObject root = new JSONObject(resp);
                JSONObject result = root.optJSONObject("result");
                JSONArray artistsArr = null;
                if (result != null) {
                    artistsArr = result.optJSONArray("artists");
                    if (artistsArr == null && result.has("library")) {
                        artistsArr = result.getJSONObject("library").optJSONArray("artists");
                    }
                }
                if (artistsArr == null) {
                    artistsArr = root.optJSONArray("result");
                }

                List<MglaYandexMusicModel.Artist> list = new ArrayList<>();
                if (artistsArr != null) {
                    for (int i = 0; i < artistsArr.length(); i++) {
                        JSONObject obj = artistsArr.optJSONObject(i);
                        if (obj != null) {
                            if (obj.has("artist")) {
                                obj = obj.optJSONObject("artist");
                            }
                            MglaYandexMusicModel.Artist a = MglaYandexMusicModel.Artist.fromApiJson(obj);
                            if (a != null && !TextUtils.isEmpty(a.id)) {
                                list.add(a);
                            }
                        }
                    }
                }

                MglaYandexMusicStore.getInstance().setCachedArtists(list);

                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(list);
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    public static void resolveDirectAudioUrl(String trackId, Callback<String> callback) {
        resolveAudioStream(trackId, new Callback<AudioStream>() {
            @Override
            public void onSuccess(AudioStream result) {
                if (callback != null) {
                    callback.onSuccess(result != null ? result.url : null);
                }
            }

            @Override
            public void onError(Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }

    public static void resolveAudioStream(String trackId, Callback<AudioStream> callback) {
        if (TextUtils.isEmpty(trackId)) {
            if (callback != null) callback.onError(new Exception("Invalid track ID"));
            return;
        }

        StreamCacheEntry cached = directUrlCache.get(trackId);
        if (cached != null && cached.isValid()) {
            if (callback != null) callback.onSuccess(cached.stream);
            return;
        }

        Utilities.globalQueue.postRunnable(() -> {
            try {
                AudioStream stream = resolveAudioStreamSync(trackId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onSuccess(stream);
                });
            } catch (Exception e) {
                FileLog.e("MglaYandexMusicApi: error resolving track " + trackId, e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onError(e);
                });
            }
        });
    }

    /** Blocking resolve — call only off the main thread. */
    public static AudioStream resolveAudioStreamSync(String trackId) throws Exception {
        if (TextUtils.isEmpty(trackId)) {
            throw new Exception("Invalid track ID");
        }
        StreamCacheEntry cached = directUrlCache.get(trackId);
        if (cached != null && cached.isValid()) {
            return cached.stream;
        }
        AudioStream stream = resolveAudioStreamInternal(trackId);
        if (stream == null || TextUtils.isEmpty(stream.url)) {
            throw new Exception("Empty stream for track " + trackId);
        }
        directUrlCache.put(trackId, new StreamCacheEntry(stream, stream.isHls() ? 120_000L : 180_000L));
        FileLog.d("MglaYandexMusicApi: resolved " + stream.type + " for " + trackId + " -> " + stream.url);
        return stream;
    }

    private static AudioStream resolveAudioStreamInternal(String trackId) throws Exception {
        // 1) Modern web API: raw progressive URL, prefer local cache for ExoPlayer reliability
        try {
            AudioStream fileInfo = resolveViaGetFileInfo(trackId);
            if (fileInfo != null) {
                if (!fileInfo.isHls()) {
                    try {
                        return downloadToLocalCache(trackId, fileInfo.url);
                    } catch (Exception downloadError) {
                        FileLog.e("MglaYandexMusicApi: local cache download failed, streaming remote", downloadError);
                        return fileInfo;
                    }
                }
                return fileInfo;
            }
        } catch (Exception e) {
            FileLog.e("MglaYandexMusicApi: get-file-info failed for " + trackId, e);
        }

        // 2) Classic download-info → signed get-mp3 → local file
        JSONArray classic = fetchDownloadInfo(trackId, false);
        if (classic != null) {
            try {
                AudioStream progressive = pickAndBuildProgressive(classic, trackId);
                if (progressive != null) {
                    try {
                        return downloadToLocalCache(trackId, progressive.url);
                    } catch (Exception downloadError) {
                        FileLog.e("MglaYandexMusicApi: classic download failed, streaming remote", downloadError);
                        return progressive;
                    }
                }
            } catch (Exception e) {
                FileLog.e("MglaYandexMusicApi: classic mp3 resolve failed for " + trackId, e);
            }
        }

        // 3) Streaming / HLS (HLS streamed; progressive downloaded when possible)
        JSONArray streaming = fetchDownloadInfo(trackId, true);
        if (streaming != null) {
            try {
                AudioStream progressive = pickAndBuildProgressive(streaming, trackId);
                if (progressive != null) {
                    try {
                        return downloadToLocalCache(trackId, progressive.url);
                    } catch (Exception downloadError) {
                        return progressive;
                    }
                }
            } catch (Exception e) {
                FileLog.e("MglaYandexMusicApi: streaming progressive resolve failed for " + trackId, e);
            }
            AudioStream hls = pickHls(streaming);
            if (hls != null) {
                return hls;
            }
        }

        throw new Exception("No playable download-info for track " + trackId);
    }

    private static AudioStream resolveViaGetFileInfo(String trackId) {
        String pureId = trackId;
        int colon = trackId.indexOf(':');
        if (colon > 0) {
            pureId = trackId.substring(0, colon);
        }

        String[] qualities = new String[]{"nq", "lq"};
        String[] bases = new String[]{BASE_URL_RU, BASE_URL};
        for (String base : bases) {
            for (String quality : qualities) {
                try {
                    String url = requestGetFileInfo(base, pureId, quality, "mp3");
                    if (!TextUtils.isEmpty(url)) {
                        String type = looksLikeHls(url, "") ? "hls" : "other";
                        return new AudioStream(url, type);
                    }
                } catch (Exception e) {
                    FileLog.e("MglaYandexMusicApi: get-file-info " + base + " q=" + quality, e);
                }
            }
        }
        return null;
    }

    private static String requestGetFileInfo(String apiBase, String trackId, String quality, String codec) throws Exception {
        String transport = "raw";
        long ts = System.currentTimeMillis() / 1000L;
        String dataToSign = ts + trackId + quality + codec + transport;
        String sign = hmacSha256Base64NoPad(dataToSign, FILE_INFO_SECRET);
        String apiUrl = apiBase + "/get-file-info?ts=" + ts
                + "&trackId=" + Uri.encode(trackId)
                + "&quality=" + Uri.encode(quality)
                + "&codecs=" + Uri.encode(codec)
                + "&transports=" + Uri.encode(transport)
                + "&sign=" + Uri.encode(sign);

        HttpURLConnection conn = MglaDirectHttp.open(apiUrl);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Yandex-Music-Client", CLIENT_WEB);
        conn.setRequestProperty("X-Yandex-Music-Without-Invocation-Info", "1");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("Referer", "https://music.yandex.ru/");
        String auth = getAuthHeader();
        if (auth != null) {
            conn.setRequestProperty("Authorization", auth);
        }

        String resp = readResponse(conn);
        JSONObject root = new JSONObject(resp);
        JSONObject downloadInfo = root.optJSONObject("downloadInfo");
        if (downloadInfo == null) {
            JSONObject result = root.optJSONObject("result");
            if (result != null) {
                downloadInfo = result.optJSONObject("downloadInfo");
            }
        }
        if (downloadInfo == null) {
            return null;
        }
        String url = downloadInfo.optString("url", null);
        if (TextUtils.isEmpty(url)) {
            url = downloadInfo.optString("downloadUrl", null);
        }
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            return Uri.decode(url);
        } catch (Throwable ignored) {
            return url;
        }
    }

    private static String hmacSha256Base64NoPad(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.encodeToString(raw, Base64.NO_WRAP);
        int pad = encoded.indexOf('=');
        return pad >= 0 ? encoded.substring(0, pad) : encoded;
    }

    /**
     * Download remote audio to app cache and return a local file:// stream.
     * Use when ExoPlayer fails with Source error on CDN URLs.
     */
    public static AudioStream downloadToLocalCache(String trackId, String remoteUrl) throws Exception {
        if (TextUtils.isEmpty(remoteUrl)) {
            throw new Exception("Empty remote url");
        }
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "mgla_ym");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("Cannot create cache dir");
        }
        String safeId = trackId != null ? trackId.replaceAll("[^a-zA-Z0-9._-]", "_") : "track";
        File out = new File(dir, safeId + ".mp3");
        if (out.exists() && out.length() > 1024) {
            return new AudioStream(Uri.fromFile(out).toString(), "other");
        }

        HttpURLConnection conn = MglaDirectHttp.open(remoteUrl);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Referer", "https://music.yandex.ru/");
        // Do NOT send Authorization to CDN — it often causes 403.

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("CDN HTTP " + code);
        }
        InputStream is = conn.getInputStream();
        File tmp = new File(dir, safeId + ".tmp");
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
        if (total < 1024) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new Exception("Downloaded file too small (" + total + " bytes)");
        }
        if (out.exists()) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new Exception("Cannot rename cache file");
        }
        return new AudioStream(Uri.fromFile(out).toString(), "other");
    }

    private static JSONArray fetchDownloadInfo(String trackId, boolean streaming) {
        try {
            String downloadInfoUrl = BASE_URL + "/tracks/" + trackId + "/download-info"
                    + (streaming ? "?can_use_streaming=true" : "");
            HttpURLConnection conn = setupConnection(downloadInfoUrl, "GET");
            String resp = readResponse(conn);
            JSONObject root = new JSONObject(resp);
            JSONArray resultArr = root.optJSONArray("result");
            if (resultArr == null || resultArr.length() == 0) {
                return null;
            }
            return resultArr;
        } catch (Exception e) {
            FileLog.e("MglaYandexMusicApi: download-info failed streaming=" + streaming + " track=" + trackId, e);
            return null;
        }
    }

    private static AudioStream pickHls(JSONArray resultArr) {
        if (resultArr == null) return null;
        String bestUrl = null;
        int maxBitrate = -1;
        for (int i = 0; i < resultArr.length(); i++) {
            JSONObject item = resultArr.optJSONObject(i);
            if (item == null) continue;
            String url = firstNonEmpty(
                    item.optString("downloadInfoUrl"),
                    item.optString("download_info_url"),
                    item.optString("direct_link"),
                    item.optString("directLink")
            );
            String container = item.optString("container", "");
            if (!looksLikeHls(url, container) && !item.optBoolean("direct", false)) {
                continue;
            }
            if (!looksLikeHls(url, container)) {
                continue;
            }
            int bitrate = item.optInt("bitrateInKbps", 0);
            if (bitrate > maxBitrate && !TextUtils.isEmpty(url)) {
                maxBitrate = bitrate;
                bestUrl = url;
            }
        }
        return !TextUtils.isEmpty(bestUrl) ? new AudioStream(bestUrl, "hls") : null;
    }

    private static AudioStream pickAndBuildProgressive(JSONArray resultArr, String trackId) throws Exception {
        if (resultArr == null) return null;

        String storageInfoUrl = null;
        String directMp3 = null;
        int maxBitrate = -1;

        for (int i = 0; i < resultArr.length(); i++) {
            JSONObject item = resultArr.optJSONObject(i);
            if (item == null) continue;

            String codec = item.optString("codec", "");
            int bitrate = item.optInt("bitrateInKbps", 0);
            String container = item.optString("container", "");
            String url = firstNonEmpty(
                    item.optString("downloadInfoUrl"),
                    item.optString("download_info_url"),
                    item.optString("direct_link"),
                    item.optString("directLink")
            );
            if (TextUtils.isEmpty(url) || looksLikeHls(url, container)) {
                continue;
            }

            if (looksLikeDirectMp3(url)) {
                if (bitrate > maxBitrate || directMp3 == null) {
                    maxBitrate = Math.max(maxBitrate, bitrate);
                    directMp3 = url;
                }
                continue;
            }

            // Classic storage descriptor URL (XML/JSON → signed get-mp3)
            if ("mp3".equalsIgnoreCase(codec) || "aac".equalsIgnoreCase(codec) || TextUtils.isEmpty(codec)) {
                if (bitrate > maxBitrate || storageInfoUrl == null) {
                    maxBitrate = Math.max(maxBitrate, bitrate);
                    storageInfoUrl = url;
                }
            }
        }

        if (!TextUtils.isEmpty(directMp3)) {
            return new AudioStream(appendTrackId(directMp3, trackId), "other");
        }
        if (TextUtils.isEmpty(storageInfoUrl)) {
            return null;
        }
        if (looksLikeDirectMp3(storageInfoUrl)) {
            return new AudioStream(appendTrackId(storageInfoUrl, trackId), "other");
        }

        return new AudioStream(buildSignedMp3Url(storageInfoUrl, trackId), "other");
    }

    private static String appendTrackId(String url, String trackId) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(trackId) || url.contains("track-id=")) {
            return url;
        }
        String pureId = trackId;
        int colon = trackId.indexOf(':');
        if (colon > 0) {
            pureId = trackId.substring(0, colon);
        }
        return url + (url.contains("?") ? "&" : "?") + "track-id=" + Uri.encode(pureId) + "&from=mgla";
    }

    private static String buildSignedMp3Url(String storageInfoUrl, String trackId) throws Exception {
        String storageResp = null;
        Exception lastError = null;
        // Prefer JSON, fall back to raw XML (as official clients do)
        String[] candidates = new String[]{
                storageInfoUrl + (storageInfoUrl.contains("?") ? "&" : "?") + "format=json",
                storageInfoUrl
        };
        for (String candidate : candidates) {
            try {
                HttpURLConnection storageConn = MglaDirectHttp.open(candidate);
                storageConn.setConnectTimeout(15000);
                storageConn.setReadTimeout(15000);
                storageConn.setRequestProperty("User-Agent", USER_AGENT);
                storageConn.setRequestProperty("X-Yandex-Music-Client", CLIENT_ANDROID);
                storageConn.setRequestProperty("Referer", "https://music.yandex.ru/");
                String auth = getAuthHeader();
                if (auth != null) {
                    storageConn.setRequestProperty("Authorization", auth);
                }
                storageResp = readResponse(storageConn);
                if (!TextUtils.isEmpty(storageResp)) {
                    break;
                }
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (TextUtils.isEmpty(storageResp)) {
            throw lastError != null ? lastError : new Exception("Empty storage response");
        }

        String host = null, path = null, ts = null, s = null;
        String trimmed = storageResp.trim();
        if (trimmed.startsWith("{")) {
            JSONObject j = new JSONObject(trimmed);
            host = j.optString("host");
            path = j.optString("path");
            ts = j.optString("ts");
            s = j.optString("s");
        }
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(path) || TextUtils.isEmpty(s)) {
            host = extractXmlTag(storageResp, "host");
            path = extractXmlTag(storageResp, "path");
            ts = extractXmlTag(storageResp, "ts");
            s = extractXmlTag(storageResp, "s");
        }

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(path) || TextUtils.isEmpty(ts) || TextUtils.isEmpty(s)) {
            throw new Exception("Malformed storage response");
        }

        String pathWithoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        String sign = md5Hex(SALT + pathWithoutLeadingSlash + s);
        String hostPrefix = host.startsWith("http://") || host.startsWith("https://") ? host : "https://" + host;
        String pathWithSlash = path.startsWith("/") ? path : "/" + path;
        String direct = hostPrefix + "/get-mp3/" + sign + "/" + ts + pathWithSlash;
        return appendTrackId(direct, trackId);
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) return v;
        }
        return null;
    }

    private static String extractXmlTag(String xml, String tag) {
        Pattern p = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
