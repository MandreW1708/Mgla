package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Data models for Yandex Music integration in Mgla.
 */
public final class MglaYandexMusicModel {

    private MglaYandexMusicModel() {
    }

    public static class Track implements Serializable {
        public String id;
        public String title;
        public String artist;
        public String album;
        public String coverUri;
        public long durationMs;
        public boolean isLiked;

        public String albumId;
        public boolean isExplicit;

        public Track() {
        }

        public Track(String id, String title, String artist, String album, String coverUri, long durationMs) {
            this.id = id;
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.coverUri = coverUri;
            this.durationMs = durationMs;
        }

        public String getCoverUrl(int size) {
            if (TextUtils.isEmpty(coverUri)) {
                return null;
            }
            String s = size + "x" + size;
            String url = coverUri.replace("%%", s);
            if (url.startsWith("//")) {
                url = "https:" + url;
            } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            return url;
        }

        public String getFormattedDuration() {
            if (durationMs <= 0) {
                return "0:00";
            }
            long totalSeconds = durationMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            return String.format(Locale.US, "%d:%02d", minutes, seconds);
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("title", title);
                obj.put("artist", artist);
                obj.put("album", album);
                obj.put("albumId", albumId);
                obj.put("coverUri", coverUri);
                obj.put("durationMs", durationMs);
                obj.put("isLiked", isLiked);
                obj.put("isExplicit", isExplicit);
            } catch (Exception ignored) {
            }
            return obj;
        }

        public static Track fromJson(JSONObject obj) {
            if (obj == null) return null;
            Track t = new Track();
            t.id = obj.optString("id");
            t.title = obj.optString("title");
            t.artist = obj.optString("artist");
            t.album = obj.optString("album");
            t.albumId = obj.optString("albumId", null);
            t.coverUri = obj.optString("coverUri", null);
            t.durationMs = obj.optLong("durationMs", 0);
            t.isLiked = obj.optBoolean("isLiked", false);
            t.isExplicit = obj.optBoolean("isExplicit", false);
            return t;
        }

        public static Track fromApiJson(JSONObject obj) {
            if (obj == null) return null;
            Track t = new Track();
            t.id = String.valueOf(obj.opt("id"));
            t.title = obj.optString("title", "Без названия");

            JSONArray artistsArr = obj.optJSONArray("artists");
            if (artistsArr != null && artistsArr.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < artistsArr.length(); i++) {
                    JSONObject a = artistsArr.optJSONObject(i);
                    if (a != null) {
                        String name = a.optString("name", "");
                        if (!TextUtils.isEmpty(name)) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(name);
                        }
                    }
                }
                t.artist = sb.toString();
            } else {
                t.artist = "Неизвестный исполнитель";
            }

            JSONArray albumsArr = obj.optJSONArray("albums");
            if (albumsArr != null && albumsArr.length() > 0) {
                JSONObject alb = albumsArr.optJSONObject(0);
                if (alb != null) {
                    t.album = alb.optString("title", "");
                    t.albumId = String.valueOf(alb.opt("id"));
                    t.coverUri = alb.optString("coverUri", null);
                }
            }
            if (TextUtils.isEmpty(t.coverUri)) {
                t.coverUri = obj.optString("coverUri", null);
            }

            t.durationMs = obj.optLong("durationMs", 0);
            t.isExplicit = obj.optBoolean("explicit", false) || obj.optInt("contentWarning", 0) > 0;
            return t;
        }
    }

    public static class Artist implements Serializable {
        public String id;
        public String name;
        public String coverUri;

        public String getCoverUrl(int size) {
            if (TextUtils.isEmpty(coverUri)) {
                return null;
            }
            String s = size + "x" + size;
            String url = coverUri.replace("%%", s);
            if (url.startsWith("//")) {
                url = "https:" + url;
            } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            return url;
        }

        public static Artist fromApiJson(JSONObject obj) {
            if (obj == null) return null;
            Artist a = new Artist();
            a.id = String.valueOf(obj.opt("id"));
            a.name = obj.optString("name", "Исполнитель");
            JSONObject cover = obj.optJSONObject("cover");
            if (cover != null) {
                a.coverUri = cover.optString("uri", null);
            }
            if (TextUtils.isEmpty(a.coverUri)) {
                a.coverUri = obj.optString("ogImage", null);
            }
            return a;
        }
    }

    public static class Album implements Serializable {
        public String id;
        public String title;
        public String coverUri;
        public int year;
        public int trackCount;
        public String artists;

        public String getCoverUrl(int size) {
            if (TextUtils.isEmpty(coverUri)) {
                return null;
            }
            String s = size + "x" + size;
            String url = coverUri.replace("%%", s);
            if (url.startsWith("//")) {
                url = "https:" + url;
            } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            return url;
        }

        public static Album fromApiJson(JSONObject obj) {
            if (obj == null) return null;
            Album a = new Album();
            a.id = String.valueOf(obj.opt("id"));
            a.title = obj.optString("title", "Альбом");
            a.year = obj.optInt("year", 0);
            a.trackCount = obj.optInt("trackCount", 0);
            a.coverUri = obj.optString("coverUri", null);
            JSONObject cover = obj.optJSONObject("cover");
            if (TextUtils.isEmpty(a.coverUri) && cover != null) {
                a.coverUri = cover.optString("uri", null);
            }
            JSONArray artistsArr = obj.optJSONArray("artists");
            if (artistsArr != null && artistsArr.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < artistsArr.length(); i++) {
                    JSONObject artist = artistsArr.optJSONObject(i);
                    if (artist == null) continue;
                    String name = artist.optString("name", "");
                    if (TextUtils.isEmpty(name)) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(name);
                }
                a.artists = sb.toString();
            }
            return a;
        }
    }

    /** Artist landing payload: popular tracks + albums. */
    public static class ArtistBrief implements Serializable {
        public Artist artist;
        public final List<Track> popularTracks = new ArrayList<>();
        public final List<Album> albums = new ArrayList<>();
    }

    public static class Playlist implements Serializable {
        public String uid;
        public long kind;
        public String title;
        public String coverUri;
        public int trackCount;
        public String ownerName;

        public Playlist() {
        }

        public Playlist(String uid, long kind, String title, String coverUri, int trackCount, String ownerName) {
            this.uid = uid;
            this.kind = kind;
            this.title = title != null ? title : "";
            this.coverUri = coverUri;
            this.trackCount = trackCount;
            this.ownerName = ownerName != null ? ownerName : "";
        }

        public String getCoverUrl(int size) {
            if (TextUtils.isEmpty(coverUri)) {
                return null;
            }
            String s = size + "x" + size;
            String url = coverUri.replace("%%", s);
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            return url;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("uid", uid);
                obj.put("kind", kind);
                obj.put("title", title);
                obj.put("coverUri", coverUri);
                obj.put("trackCount", trackCount);
                obj.put("ownerName", ownerName);
            } catch (Exception ignored) {
            }
            return obj;
        }

        public static Playlist fromJson(JSONObject obj) {
            if (obj == null) return null;
            Playlist p = new Playlist();
            p.uid = obj.optString("uid");
            p.kind = obj.optLong("kind");
            p.title = obj.optString("title");
            p.coverUri = obj.optString("coverUri", null);
            p.trackCount = obj.optInt("trackCount", 0);
            p.ownerName = obj.optString("ownerName");
            return p;
        }

        public static Playlist fromApiJson(JSONObject obj) {
            if (obj == null) return null;
            Playlist p = new Playlist();
            p.kind = obj.optLong("kind", 0);
            p.title = obj.optString("title", "Плейлист");
            p.trackCount = obj.optInt("trackCount", 0);

            JSONObject owner = obj.optJSONObject("owner");
            if (owner != null) {
                p.uid = String.valueOf(owner.opt("uid"));
                p.ownerName = owner.optString("name", owner.optString("login", ""));
            } else {
                p.uid = String.valueOf(obj.opt("uid"));
            }

            JSONObject cover = obj.optJSONObject("cover");
            if (cover != null) {
                p.coverUri = cover.optString("uri", null);
            }
            if (TextUtils.isEmpty(p.coverUri)) {
                p.coverUri = obj.optString("coverUri", null);
            }
            return p;
        }
    }

    /** Single wave preset / activity / setting chip. */
    public static class WaveOption implements Serializable {
        public String title;
        public String seed;

        public WaveOption() {
        }

        public WaveOption(String title, String seed) {
            this.title = title != null ? title : "";
            this.seed = seed != null ? seed : "";
        }
    }

    /** Parsed {@code /rotor/wave/settings} payload (activities + setting chips). */
    public static class WaveSettingsData implements Serializable {
        public final List<WaveOption> activities = new ArrayList<>();
        public final List<WaveOption> diversities = new ArrayList<>();
        public final List<WaveOption> moodEnergies = new ArrayList<>();
        public final List<WaveOption> languages = new ArrayList<>();

        public static WaveSettingsData fallback() {
            WaveSettingsData d = new WaveSettingsData();
            d.diversities.add(new WaveOption("Любимое", "settingDiversity:favorite"));
            d.diversities.add(new WaveOption("Популярное", "settingDiversity:popular"));
            d.diversities.add(new WaveOption("Незнакомое", "settingDiversity:discover"));
            d.diversities.add(new WaveOption("Разное", "settingDiversity:default"));

            d.moodEnergies.add(new WaveOption("Бодрое", "settingMoodEnergy:fun"));
            d.moodEnergies.add(new WaveOption("Энергичное", "settingMoodEnergy:active"));
            d.moodEnergies.add(new WaveOption("Спокойное", "settingMoodEnergy:calm"));
            d.moodEnergies.add(new WaveOption("Грустное", "settingMoodEnergy:sad"));
            d.moodEnergies.add(new WaveOption("Любое", "settingMoodEnergy:all"));

            d.languages.add(new WaveOption("Русский", "settingLanguage:russian"));
            d.languages.add(new WaveOption("Иностранный", "settingLanguage:not-russian"));
            d.languages.add(new WaveOption("Любой", "settingLanguage:any"));

            d.activities.add(new WaveOption("Просыпаюсь", "activity:wake-up"));
            d.activities.add(new WaveOption("Тренировка", "activity:workout"));
            d.activities.add(new WaveOption("За рулём", "activity:driving"));
            d.activities.add(new WaveOption("Работа", "activity:work-background"));
            d.activities.add(new WaveOption("Вечеринка", "activity:party"));
            d.activities.add(new WaveOption("Романтика", "activity:romance"));
            d.activities.add(new WaveOption("Ужин", "activity:dinner"));
            d.activities.add(new WaveOption("Сон", "activity:sleep"));
            return d;
        }
    }

    public static class UserInfo implements Serializable {
        public String uid;
        public String login;
        public String displayName;
        public String avatarUrl;
        public boolean hasPlus;

        public UserInfo() {
        }

        public UserInfo(String uid, String login, String displayName, String avatarUrl, boolean hasPlus) {
            this.uid = uid;
            this.login = login != null ? login : "";
            this.displayName = displayName != null ? displayName : "";
            this.avatarUrl = avatarUrl;
            this.hasPlus = hasPlus;
        }

        public String getAvatarUrl(int size) {
            if (TextUtils.isEmpty(avatarUrl)) {
                return null;
            }
            String url = avatarUrl.replace("%%", size + "x" + size);
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            return url;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("uid", uid);
                obj.put("login", login);
                obj.put("displayName", displayName);
                obj.put("avatarUrl", avatarUrl);
                obj.put("hasPlus", hasPlus);
            } catch (Exception ignored) {
            }
            return obj;
        }

        public static UserInfo fromJson(JSONObject obj) {
            if (obj == null) return null;
            UserInfo u = new UserInfo();
            u.uid = obj.optString("uid");
            u.login = obj.optString("login");
            u.displayName = obj.optString("displayName");
            u.avatarUrl = obj.optString("avatarUrl", null);
            u.hasPlus = obj.optBoolean("hasPlus", false);
            return u;
        }
    }
}
