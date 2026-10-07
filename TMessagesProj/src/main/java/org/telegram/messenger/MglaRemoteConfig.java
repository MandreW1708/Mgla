package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Polls mglahub for remote feature flags ({@code POST /mgla-config/v1/features}).
 * Sends Telegram user ids of activated accounts; never used for anonymous stats.
 */
public final class MglaRemoteConfig {

    private static final String PREFS = "mgla_remote_config";
    private static final String KEY_LAST_FETCH = "last_fetch_ms";
    private static final long POLL_INTERVAL_MS = 20L * 60 * 1000;
    private static final long MIN_RETRY_MS = 60L * 1000;

    private static final Object lock = new Object();
    private static boolean fetching;
    private static final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            maybeFetch(false);
            Utilities.globalQueue.postRunnable(this, POLL_INTERVAL_MS);
        }
    };

    private MglaRemoteConfig() {
    }

    public static void init() {
        Utilities.globalQueue.postRunnable(pollRunnable, 5_000);
    }

    public static void maybeFetch(boolean force) {
        if (!MglaWsConfig.isHubConfigured()) {
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences prefs = prefs();
        long last = prefs != null ? prefs.getLong(KEY_LAST_FETCH, 0) : 0;
        if (!force && now - last < POLL_INTERVAL_MS) {
            return;
        }
        synchronized (lock) {
            if (fetching) {
                return;
            }
            // Avoid hammering on rapid force calls when last attempt was very recent.
            if (!force && now - last < MIN_RETRY_MS) {
                return;
            }
            fetching = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            try {
                ok = fetchOnce();
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("MglaRemoteConfig fetch failed", e);
                }
            } finally {
                synchronized (lock) {
                    fetching = false;
                    if (ok && prefs() != null) {
                        prefs().edit().putLong(KEY_LAST_FETCH, System.currentTimeMillis()).apply();
                    }
                }
            }
        });
    }

    private static boolean fetchOnce() throws Exception {
        List<Long> tgIds = collectAccountIds();
        if (tgIds.isEmpty()) {
            return false;
        }
        JSONObject body = new JSONObject();
        if (tgIds.size() == 1) {
            body.put("tg_id", tgIds.get(0));
        } else {
            JSONArray arr = new JSONArray();
            for (Long id : tgIds) {
                arr.put(id);
            }
            body.put("tg_ids", arr);
            body.put("tg_id", tgIds.get(0));
        }

        String host = MglaWsConfig.getHubHost();
        URL url = new URL("https://" + host + "/mgla-config/v1/features");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(12_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Mgla-Token", MglaWsConfig.getHubToken());
            conn.connect();
            if (MglaWsConfig.DEFAULT_HUB_HOST.equalsIgnoreCase(host)) {
                verifyHubPin(conn.getServerCertificates());
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = readUtf8(is, 64 * 1024);
            if (code != 200) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("MglaRemoteConfig HTTP " + code + ": " + response);
                }
                return false;
            }
            Set<String> disabled = MglaFeatureFlags.parseDisabledJson(response);
            MglaFeatureFlags.applyDisabled(disabled);
            return true;
        } finally {
            conn.disconnect();
        }
    }

    private static List<Long> collectAccountIds() {
        ArrayList<Long> ids = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (uc != null && uc.isClientActivated()) {
                long id = uc.getClientUserId();
                if (id > 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
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

    private static String readUtf8(InputStream is, int maxBytes) throws Exception {
        if (is == null) {
            return "";
        }
        byte[] buf = new byte[4096];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) > 0) {
            if (out.size() + n > maxBytes) {
                throw new Exception("response too large");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
