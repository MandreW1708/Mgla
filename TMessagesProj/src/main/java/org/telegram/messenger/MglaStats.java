package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Anonymous product analytics sent to the Mgla relay host ({@code /mgla-stats/v1/batch}).
 * <p>
 * Collected: daily usage counters (screens opened, Mgla settings toggled, AI features used,
 * settings-search misses), a snapshot of Mgla settings, app version and device model.
 * Keyed by a random install id only — never Telegram user ids, phone numbers, chats,
 * contacts or message text.
 */
public final class MglaStats {

    private static final String PREFS = "mgla_stats";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_FIRST_SEEN = "first_seen";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_LAST_UPLOAD = "last_upload";

    private static final long UPLOAD_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final long SAVE_DELAY_MS = 2000;
    private static final int MAX_DAYS = 14;
    private static final int MAX_NAMES_PER_DAY = 400;
    private static final int MAX_QUERY_LEN = 40;

    private static final Pattern NAME_SANITIZE = Pattern.compile("[^A-Za-z0-9_:./=-]");
    /** Settings keys that may hold credentials, endpoints or other identifying values. */
    private static final String[] SENSITIVE_KEY_PARTS = {
        "token", "secret", "api_key", "apikey", "pass", "user", "host", "_ip", "sni",
        "strategy", "saved", "port", "path", "model", "last_", "_date", "today", "used", "request", "custom"
    };

    private static final Object lock = new Object();
    /** day -> (counter name -> count) */
    private static final Map<String, Map<String, Integer>> counters = new HashMap<>();
    /** day -> (settings-search miss -> count) */
    private static final Map<String, Map<String, Integer>> searchMisses = new HashMap<>();

    private static boolean initialized;
    private static boolean uploading;
    private static String lastMiss;
    private static String lastMissDay;
    private static long lastMissTime;
    private static SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
    private static final Runnable saveRunnable = MglaStats::saveNow;
    private static final Runnable periodicUpload = new Runnable() {
        @Override
        public void run() {
            maybeUpload(false);
            Utilities.globalQueue.postRunnable(this, UPLOAD_INTERVAL_MS);
        }
    };

    private MglaStats() {
    }

    public static void init() {
        synchronized (lock) {
            if (initialized) {
                return;
            }
            initialized = true;
            loadPending();
        }
        SharedPreferences prefs = prefs();
        if (prefs.getLong(KEY_FIRST_SEEN, 0) == 0) {
            prefs.edit().putLong(KEY_FIRST_SEEN, System.currentTimeMillis()).apply();
        }
        if (prefs.contains(KEY_ENABLED)) {
            prefs.edit().remove(KEY_ENABLED).apply();
        }
        settingsListener = (sp, key) -> {
            if (key == null || isSensitiveKey(key)) {
                return;
            }
            Object value = sp.getAll().get(key);
            if (value instanceof Boolean) {
                count("set:" + key + "=" + value);
            } else if (value instanceof Integer || value instanceof Float) {
                count("set:" + key + "=" + value);
            } else if ("ai_provider".equals(key) && value instanceof String) {
                count("set:" + key + "=" + value);
            }
        };
        ApplicationLoader.applicationContext.getSharedPreferences("mgla_config", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(settingsListener);
        Utilities.globalQueue.postRunnable(periodicUpload, 15_000);
    }

    public static boolean isEnabled() {
        return true;
    }

    public static void count(String name) {
        count(name, 1);
    }

    public static void count(String name, int delta) {
        if (TextUtils.isEmpty(name) || delta <= 0 || !isEnabled()) {
            return;
        }
        String clean = NAME_SANITIZE.matcher(name).replaceAll("_");
        if (clean.length() > 64) {
            clean = clean.substring(0, 64);
        }
        synchronized (lock) {
            Map<String, Integer> day = dayMap(counters, today());
            if (day.size() >= MAX_NAMES_PER_DAY && !day.containsKey(clean)) {
                return;
            }
            Integer old = day.get(clean);
            day.put(clean, (old == null ? 0 : old) + delta);
        }
        scheduleSave();
    }

    public static void screen(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        Class<?> cls = fragment.getClass();
        String name = cls.getSimpleName();
        while (TextUtils.isEmpty(name) && cls.getSuperclass() != null) {
            cls = cls.getSuperclass();
            name = cls.getSimpleName();
        }
        count("screen:" + name);
    }

    /**
     * Records settings searches that found nothing — the clearest signal of features people
     * expect but don't have. Incremental typing ("при", "приз", "призр") collapses to the last query.
     */
    public static void settingsSearch(String query, int results) {
        if (!isEnabled() || query == null) {
            return;
        }
        String q = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (q.length() < 3 || results > 0) {
            return;
        }
        if (q.length() > MAX_QUERY_LEN) {
            q = q.substring(0, MAX_QUERY_LEN);
        }
        long now = System.currentTimeMillis();
        String day = today();
        synchronized (lock) {
            Map<String, Integer> misses = dayMap(searchMisses, day);
            if (lastMiss != null && day.equals(lastMissDay) && now - lastMissTime < 15_000
                    && (q.startsWith(lastMiss) || lastMiss.startsWith(q))) {
                Integer prev = misses.get(lastMiss);
                if (prev != null) {
                    if (prev <= 1) {
                        misses.remove(lastMiss);
                    } else {
                        misses.put(lastMiss, prev - 1);
                    }
                }
            }
            if (misses.size() < MAX_NAMES_PER_DAY || misses.containsKey(q)) {
                Integer old = misses.get(q);
                misses.put(q, (old == null ? 0 : old) + 1);
            }
            lastMiss = q;
            lastMissDay = day;
            lastMissTime = now;
        }
        scheduleSave();
    }

    public static void maybeUpload(boolean force) {
        if (!isEnabled() || !MglaWsConfig.isRelayConfigured()) {
            return;
        }
        long last = prefs().getLong(KEY_LAST_UPLOAD, 0);
        if (!force && System.currentTimeMillis() - last < UPLOAD_INTERVAL_MS) {
            return;
        }
        final Map<String, Map<String, Integer>> sentCounters;
        final Map<String, Map<String, Integer>> sentMisses;
        synchronized (lock) {
            if (uploading) {
                return;
            }
            uploading = true;
            sentCounters = deepCopy(counters);
            sentMisses = deepCopy(searchMisses);
            counters.clear();
            searchMisses.clear();
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            try {
                ok = post(buildPayload(sentCounters, sentMisses));
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("MglaStats upload failed", e);
                }
            }
            synchronized (lock) {
                if (!ok) {
                    merge(counters, sentCounters);
                    merge(searchMisses, sentMisses);
                }
                uploading = false;
            }
            if (ok) {
                prefs().edit().putLong(KEY_LAST_UPLOAD, System.currentTimeMillis()).apply();
            }
            saveNow();
        });
    }

    private static String buildPayload(Map<String, Map<String, Integer>> sentCounters,
                                       Map<String, Map<String, Integer>> sentMisses) throws Exception {
        JSONObject root = new JSONObject();
        root.put("v", 1);
        root.put("iid", installId());

        JSONObject app = new JSONObject();
        app.put("ver", BuildVars.MGLA_VERSION_STRING);
        app.put("tg", BuildVars.BUILD_VERSION_STRING);
        long firstSeen = prefs().getLong(KEY_FIRST_SEEN, System.currentTimeMillis());
        app.put("age_days", Math.max(0, (System.currentTimeMillis() - firstSeen) / 86_400_000L));
        root.put("app", app);

        JSONObject dev = new JSONObject();
        dev.put("sdk", Build.VERSION.SDK_INT);
        dev.put("man", Build.MANUFACTURER);
        dev.put("model", Build.MODEL);
        dev.put("lang", Locale.getDefault().getLanguage());
        dev.put("rom", !TextUtils.isEmpty(AndroidUtilities.getSystemProperty("ro.miui.ui.version.code")) ? "miui" : "");
        root.put("dev", dev);

        JSONObject days = new JSONObject();
        for (Map.Entry<String, Map<String, Integer>> e : sentCounters.entrySet()) {
            JSONObject day = days.optJSONObject(e.getKey());
            if (day == null) {
                day = new JSONObject();
                days.put(e.getKey(), day);
            }
            day.put("c", new JSONObject(e.getValue()));
        }
        for (Map.Entry<String, Map<String, Integer>> e : sentMisses.entrySet()) {
            JSONObject day = days.optJSONObject(e.getKey());
            if (day == null) {
                day = new JSONObject();
                days.put(e.getKey(), day);
            }
            day.put("m", new JSONObject(e.getValue()));
        }
        root.put("days", days);
        root.put("settings", settingsSnapshot());
        return root.toString();
    }

    private static JSONObject settingsSnapshot() throws Exception {
        JSONObject out = new JSONObject();
        Map<String, ?> all = ApplicationLoader.applicationContext
            .getSharedPreferences("mgla_config", Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (key == null || isSensitiveKey(key)) {
                continue;
            }
            if (value instanceof Boolean || value instanceof Integer || value instanceof Float) {
                out.put(key, value);
            } else if ("ai_provider".equals(key) && value instanceof String) {
                out.put(key, value);
            }
        }
        out.put("camera_api", SharedConfig.cameraApi);
        out.put("camera_x_60fps", SharedConfig.cameraX60Fps);
        int accounts = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts++;
            }
        }
        out.put("accounts", accounts);
        return out;
    }

    private static boolean post(String json) throws Exception {
        String host = MglaWsConfig.getHubHost();
        URL url = new URL("https://" + host + "/mgla-stats/v1/batch");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Mgla-Token", MglaWsConfig.getHubToken());
            conn.connect();
            if (MglaWsConfig.DEFAULT_HUB_HOST.equalsIgnoreCase(host)) {
                verifyHubPin(conn.getServerCertificates());
            }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            try (InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                if (is != null) {
                    drain(is);
                }
            }
            return code >= 200 && code < 300;
        } finally {
            conn.disconnect();
        }
    }

    private static void verifyHubPin(Certificate[] chain) throws Exception {
        if (chain == null || chain.length == 0) {
            throw new SecurityException("no certificate");
        }
        byte[] spki = chain[0].getPublicKey().getEncoded();
        String actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki));
        if (!MglaWsConfig.HUB_SPKI_SHA256_BASE64.equals(actual)) {
            throw new SecurityException("SPKI pin mismatch");
        }
    }

    private static void drain(InputStream is) throws Exception {
        byte[] buf = new byte[1024];
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) > 0 && ignored.size() < 8192) {
            ignored.write(buf, 0, n);
        }
    }

    private static boolean isSensitiveKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        for (String part : SENSITIVE_KEY_PARTS) {
            if (k.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static String installId() {
        SharedPreferences prefs = prefs();
        String id = prefs.getString(KEY_INSTALL_ID, null);
        if (id == null || id.length() != 32) {
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            id = Utilities.bytesToHex(bytes).toLowerCase(Locale.ROOT);
            prefs.edit().putString(KEY_INSTALL_ID, id).apply();
        }
        return id;
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static Map<String, Integer> dayMap(Map<String, Map<String, Integer>> store, String day) {
        Map<String, Integer> map = store.get(day);
        if (map == null) {
            map = new HashMap<>();
            store.put(day, map);
            trimDays(store);
        }
        return map;
    }

    private static void trimDays(Map<String, Map<String, Integer>> store) {
        while (store.size() > MAX_DAYS) {
            String oldest = null;
            for (String d : store.keySet()) {
                if (oldest == null || d.compareTo(oldest) < 0) {
                    oldest = d;
                }
            }
            store.remove(oldest);
        }
    }

    private static Map<String, Map<String, Integer>> deepCopy(Map<String, Map<String, Integer>> src) {
        Map<String, Map<String, Integer>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : src.entrySet()) {
            out.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        return out;
    }

    private static void merge(Map<String, Map<String, Integer>> into, Map<String, Map<String, Integer>> from) {
        for (Map.Entry<String, Map<String, Integer>> e : from.entrySet()) {
            Map<String, Integer> day = dayMap(into, e.getKey());
            for (Map.Entry<String, Integer> c : e.getValue().entrySet()) {
                Integer old = day.get(c.getKey());
                day.put(c.getKey(), (old == null ? 0 : old) + c.getValue());
            }
        }
    }

    private static void scheduleSave() {
        Utilities.globalQueue.cancelRunnable(saveRunnable);
        Utilities.globalQueue.postRunnable(saveRunnable, SAVE_DELAY_MS);
    }

    private static void saveNow() {
        try {
            JSONObject root = new JSONObject();
            synchronized (lock) {
                root.put("c", toJson(counters));
                root.put("m", toJson(searchMisses));
            }
            prefs().edit().putString(KEY_PENDING, root.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void loadPending() {
        String raw = prefs().getString(KEY_PENDING, null);
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(raw);
            fromJson(root.optJSONObject("c"), counters);
            fromJson(root.optJSONObject("m"), searchMisses);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static JSONObject toJson(Map<String, Map<String, Integer>> store) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Map<String, Integer>> e : store.entrySet()) {
            out.put(e.getKey(), new JSONObject(e.getValue()));
        }
        return out;
    }

    private static void fromJson(JSONObject json, Map<String, Map<String, Integer>> store) {
        if (json == null) {
            return;
        }
        Iterator<String> days = json.keys();
        while (days.hasNext()) {
            String day = days.next();
            JSONObject c = json.optJSONObject(day);
            if (c == null) {
                continue;
            }
            Map<String, Integer> map = new HashMap<>();
            Iterator<String> names = c.keys();
            while (names.hasNext()) {
                String name = names.next();
                map.put(name, c.optInt(name, 0));
            }
            store.put(day, map);
        }
        trimDays(store);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
