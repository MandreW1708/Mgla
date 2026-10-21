package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Storage and preferences for Yandex Music integration in Mgla.
 */
public final class MglaYandexMusicStore {

    private static final String PREFS_NAME = "mgla_yandex_music_prefs";

    private static final String KEY_TOKEN = "oauth_token";
    private static final String KEY_UID = "user_uid";
    private static final String KEY_LOGIN = "user_login";
    private static final String KEY_DISPLAY_NAME = "user_display_name";
    private static final String KEY_AVATAR_URL = "user_avatar_url";
    private static final String KEY_HAS_PLUS = "user_has_plus";

    private static final String KEY_CACHED_FAVORITES = "cached_favorites_json";
    private static final String KEY_CACHED_PLAYLISTS = "cached_playlists_json";
    private static final String KEY_CACHED_ROTOR = "cached_rotor_json";
    private static final String KEY_CACHED_ARTISTS = "cached_artists_json";
    private static final String KEY_LIKED_IDS = "liked_track_ids_set";

    private static final String KEY_SHUFFLE = "playback_shuffle";
    private static final String KEY_REPEAT = "playback_repeat";

    /** My Wave setting seeds (settingDiversity:* / settingMoodEnergy:* / settingLanguage:*). */
    private static final String KEY_WAVE_DIVERSITY = "wave_setting_diversity";
    private static final String KEY_WAVE_MOOD = "wave_setting_mood";
    private static final String KEY_WAVE_LANGUAGE = "wave_setting_language";

    public interface AuthListener {
        void onAuthStateChanged(boolean isAuthorized);
    }

    private static volatile MglaYandexMusicStore instance;

    public static MglaYandexMusicStore getInstance() {
        if (instance == null) {
            synchronized (MglaYandexMusicStore.class) {
                if (instance == null) {
                    instance = new MglaYandexMusicStore();
                }
            }
        }
        return instance;
    }

    private final SharedPreferences prefs;
    private final Set<String> likedIdsCache = new HashSet<>();
    private final List<AuthListener> authListeners = new CopyOnWriteArrayList<>();

    private MglaYandexMusicStore() {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(KEY_LIKED_IDS, null);
        if (set != null) {
            likedIdsCache.addAll(set);
        }
    }

    public void addAuthListener(AuthListener listener) {
        if (listener != null && !authListeners.contains(listener)) {
            authListeners.add(listener);
        }
    }

    public void removeAuthListener(AuthListener listener) {
        authListeners.remove(listener);
    }

    private void notifyAuthChanged(boolean isAuth) {
        AndroidUtilities.runOnUIThread(() -> {
            for (AuthListener listener : authListeners) {
                listener.onAuthStateChanged(isAuth);
            }
        });
    }

    public boolean isAuthorized() {
        return !TextUtils.isEmpty(getToken());
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, null);
    }

    public void saveToken(String token) {
        if (token != null) {
            token = token.trim();
        }
        prefs.edit().putString(KEY_TOKEN, token).apply();
        notifyAuthChanged(!TextUtils.isEmpty(token));
    }

    public void clearAuth() {
        prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_UID)
                .remove(KEY_LOGIN)
                .remove(KEY_DISPLAY_NAME)
                .remove(KEY_AVATAR_URL)
                .remove(KEY_HAS_PLUS)
                .remove(KEY_CACHED_FAVORITES)
                .remove(KEY_CACHED_PLAYLISTS)
                .remove(KEY_CACHED_ROTOR)
                .remove(KEY_LIKED_IDS)
                .apply();
        synchronized (likedIdsCache) {
            likedIdsCache.clear();
        }
        notifyAuthChanged(false);
    }

    public MglaYandexMusicModel.UserInfo getUserInfo() {
        String uid = prefs.getString(KEY_UID, null);
        if (TextUtils.isEmpty(uid)) {
            return null;
        }
        MglaYandexMusicModel.UserInfo info = new MglaYandexMusicModel.UserInfo();
        info.uid = uid;
        info.login = prefs.getString(KEY_LOGIN, "");
        info.displayName = prefs.getString(KEY_DISPLAY_NAME, "");
        info.avatarUrl = prefs.getString(KEY_AVATAR_URL, null);
        info.hasPlus = prefs.getBoolean(KEY_HAS_PLUS, false);
        return info;
    }

    public void saveUserInfo(MglaYandexMusicModel.UserInfo info) {
        if (info == null) return;
        prefs.edit()
                .putString(KEY_UID, info.uid)
                .putString(KEY_LOGIN, info.login)
                .putString(KEY_DISPLAY_NAME, info.displayName)
                .putString(KEY_AVATAR_URL, info.avatarUrl)
                .putBoolean(KEY_HAS_PLUS, info.hasPlus)
                .apply();
    }

    public boolean isLiked(String trackId) {
        if (TextUtils.isEmpty(trackId)) return false;
        synchronized (likedIdsCache) {
            return likedIdsCache.contains(trackId);
        }
    }

    public void setLiked(String trackId, boolean liked) {
        if (TextUtils.isEmpty(trackId)) return;
        synchronized (likedIdsCache) {
            if (liked) {
                likedIdsCache.add(trackId);
            } else {
                likedIdsCache.remove(trackId);
            }
            prefs.edit().putStringSet(KEY_LIKED_IDS, new HashSet<>(likedIdsCache)).apply();
        }
    }

    public void setLikedBatch(List<String> trackIds) {
        if (trackIds == null) return;
        synchronized (likedIdsCache) {
            likedIdsCache.clear();
            likedIdsCache.addAll(trackIds);
            prefs.edit().putStringSet(KEY_LIKED_IDS, new HashSet<>(likedIdsCache)).apply();
        }
    }

    public List<MglaYandexMusicModel.Track> getCachedFavorites() {
        return parseTrackList(prefs.getString(KEY_CACHED_FAVORITES, null));
    }

    public void setCachedFavorites(List<MglaYandexMusicModel.Track> list) {
        saveTrackList(KEY_CACHED_FAVORITES, list);
    }

    public List<MglaYandexMusicModel.Playlist> getCachedPlaylists() {
        String raw = prefs.getString(KEY_CACHED_PLAYLISTS, null);
        List<MglaYandexMusicModel.Playlist> result = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return result;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    MglaYandexMusicModel.Playlist p = MglaYandexMusicModel.Playlist.fromJson(obj);
                    if (p != null) result.add(p);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public void setCachedPlaylists(List<MglaYandexMusicModel.Playlist> list) {
        if (list == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (MglaYandexMusicModel.Playlist p : list) {
                if (p != null) arr.put(p.toJson());
            }
            prefs.edit().putString(KEY_CACHED_PLAYLISTS, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public List<MglaYandexMusicModel.Track> getCachedRotor() {
        return parseTrackList(prefs.getString(KEY_CACHED_ROTOR, null));
    }

    public void setCachedRotor(List<MglaYandexMusicModel.Track> list) {
        saveTrackList(KEY_CACHED_ROTOR, list);
    }

    private List<MglaYandexMusicModel.Track> parseTrackList(String raw) {
        List<MglaYandexMusicModel.Track> result = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return result;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    MglaYandexMusicModel.Track t = MglaYandexMusicModel.Track.fromJson(obj);
                    if (t != null) {
                        t.isLiked = isLiked(t.id);
                        result.add(t);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void saveTrackList(String key, List<MglaYandexMusicModel.Track> list) {
        if (list == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (MglaYandexMusicModel.Track t : list) {
                if (t != null) arr.put(t.toJson());
            }
            prefs.edit().putString(key, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public List<MglaYandexMusicModel.Artist> getCachedArtists() {
        List<MglaYandexMusicModel.Artist> result = new ArrayList<>();
        String raw = prefs.getString(KEY_CACHED_ARTISTS, null);
        if (TextUtils.isEmpty(raw)) return result;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    MglaYandexMusicModel.Artist a = MglaYandexMusicModel.Artist.fromApiJson(obj);
                    if (a != null) result.add(a);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public void setCachedArtists(List<MglaYandexMusicModel.Artist> list) {
        if (list == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (MglaYandexMusicModel.Artist a : list) {
                if (a != null) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", a.id);
                    obj.put("name", a.name);
                    obj.put("coverUri", a.coverUri);
                    arr.put(obj);
                }
            }
            prefs.edit().putString(KEY_CACHED_ARTISTS, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public String getWaveDiversitySeed() {
        return prefs.getString(KEY_WAVE_DIVERSITY, "");
    }

    public void setWaveDiversitySeed(String seed) {
        prefs.edit().putString(KEY_WAVE_DIVERSITY, seed != null ? seed : "").apply();
    }

    public String getWaveMoodSeed() {
        return prefs.getString(KEY_WAVE_MOOD, "");
    }

    public void setWaveMoodSeed(String seed) {
        prefs.edit().putString(KEY_WAVE_MOOD, seed != null ? seed : "").apply();
    }

    public String getWaveLanguageSeed() {
        return prefs.getString(KEY_WAVE_LANGUAGE, "");
    }

    public void setWaveLanguageSeed(String seed) {
        prefs.edit().putString(KEY_WAVE_LANGUAGE, seed != null ? seed : "").apply();
    }

    /**
     * Seeds for personal «Моя волна»: always {@code user:onyourwave}, plus optional
     * diversity / mood / language settings (skip neutral defaults).
     */
    public List<String> buildMyWaveSeeds() {
        List<String> seeds = new ArrayList<>();
        seeds.add("user:onyourwave");
        appendWaveSettingSeed(seeds, getWaveDiversitySeed(), "settingDiversity:default");
        appendWaveSettingSeed(seeds, getWaveMoodSeed(), "settingMoodEnergy:all");
        appendWaveSettingSeed(seeds, getWaveLanguageSeed(), "settingLanguage:any");
        return seeds;
    }

    private static void appendWaveSettingSeed(List<String> seeds, String seed, String neutral) {
        if (TextUtils.isEmpty(seed)) {
            return;
        }
        if (!TextUtils.isEmpty(neutral) && neutral.equals(seed)) {
            return;
        }
        if (!seeds.contains(seed)) {
            seeds.add(seed);
        }
    }

    public boolean isShuffle() {
        return prefs.getBoolean(KEY_SHUFFLE, false);
    }

    public void setShuffle(boolean shuffle) {
        prefs.edit().putBoolean(KEY_SHUFFLE, shuffle).apply();
    }

    public int getRepeatMode() {
        return prefs.getInt(KEY_REPEAT, 0);
    }

    public void setRepeatMode(int mode) {
        prefs.edit().putInt(KEY_REPEAT, mode).apply();
    }
}
