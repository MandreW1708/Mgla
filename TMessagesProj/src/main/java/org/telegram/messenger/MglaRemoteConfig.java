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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Polls for remote feature flags.
 * <p>
 * When WS-relay bypass is on and not VPN-paused, goes through mglabot
 * ({@code POST /mgla-ai/v1/features}) so the request reaches the network that
 * already works for AI/WS. Otherwise hits mglahub directly
 * ({@code POST /mgla-config/v1/features}).
 */
public final class MglaRemoteConfig {

    private static final String PREFS = "mgla_remote_config";
    private static final String KEY_LAST_FETCH = "last_fetch_ms";
    private static final String KEY_LAST_ERROR = "last_error";
    /** How often the client asks for feature flags. */
    private static final long POLL_INTERVAL_MS = 2L * 60 * 1000;
    private static final long EMPTY_ACCOUNTS_RETRY_MS = 15_000;

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
        // First attempt soon after accounts are loaded by ApplicationLoader.
        Utilities.globalQueue.postRunnable(() -> maybeFetch(true), 3_000);
        Utilities.globalQueue.postRunnable(pollRunnable, POLL_INTERVAL_MS);
    }

    /**
     * Relay path when WS bypass is enabled and not suspended by VPN.
     * VPN pause / relay off → direct mglahub (reachable via VPN or open net).
     */
    /** Public for update checker and other hub polls that share the same routing. */
    public static boolean shouldFetchViaRelay() {
        try {
            if (!MglaWsConfig.isEnabled() || !MglaWsConfig.isRelayConfigured()) {
                return false;
            }
            return !VpnMonitor.getInstance().isVpnActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void maybeFetch(boolean force) {
        boolean viaRelay = shouldFetchViaRelay();
        if (viaRelay) {
            // ok
        } else if (!MglaWsConfig.isHubConfigured()) {
            rememberError("hub not configured");
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
            fetching = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            try {
                ok = fetchOnce();
            } catch (Throwable e) {
                rememberError(String.valueOf(e.getMessage()));
                FileLog.e("MglaRemoteConfig fetch failed", e);
            } finally {
                synchronized (lock) {
                    fetching = false;
                    if (ok) {
                        SharedPreferences p = prefs();
                        if (p != null) {
                            p.edit()
                                .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                                .remove(KEY_LAST_ERROR)
                                .apply();
                        }
                    }
                }
            }
        });
    }

    private static boolean fetchOnce() throws Exception {
        List<Long> tgIds = collectAccountIds();
        if (tgIds.isEmpty()) {
            rememberError("no activated accounts yet");
            Utilities.globalQueue.postRunnable(() -> maybeFetch(true), EMPTY_ACCOUNTS_RETRY_MS);
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

        boolean viaRelay = shouldFetchViaRelay();
        final String host;
        final String path;
        final String token;
        final String expectedPin;
        final boolean pinDefaultHost;
        if (viaRelay) {
            host = MglaWsConfig.getRelayHost();
            path = "/mgla-ai/v1/features";
            token = MglaWsConfig.getRelayToken();
            expectedPin = MglaWsConfig.RELAY_SPKI_SHA256_BASE64;
            pinDefaultHost = MglaWsConfig.DEFAULT_RELAY_HOST.equalsIgnoreCase(host);
        } else {
            host = MglaWsConfig.getHubHost();
            path = "/mgla-config/v1/features";
            token = MglaWsConfig.getHubToken();
            expectedPin = MglaWsConfig.HUB_SPKI_SHA256_BASE64;
            pinDefaultHost = MglaWsConfig.DEFAULT_HUB_HOST.equalsIgnoreCase(host);
        }

        String url = "https://" + host + path;
        HttpsURLConnection conn = MglaDirectHttp.openHttps(url);
        try {
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(12_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Mgla-Token", token);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                // getOutputStream completes the TLS handshake.
                if (pinDefaultHost) {
                    verifySpkiPin(conn.getServerCertificates(), expectedPin);
                }
                os.write(payload);
            }

            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = readUtf8(is, 64 * 1024);
            if (code != 200) {
                rememberError("HTTP " + code + " via=" + (viaRelay ? "relay" : "hub") + ": " + response);
                FileLog.e("MglaRemoteConfig HTTP " + code + " via=" + (viaRelay ? "relay" : "hub") + ": " + response);
                return false;
            }
            Set<String> disabled = MglaFeatureFlags.parseDisabledJson(response);
            MglaFeatureFlags.applyDisabled(disabled);
            FileLog.d("MglaRemoteConfig applied via=" + (viaRelay ? "relay" : "hub") + " disabled=" + disabled);
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

    private static void verifySpkiPin(Certificate[] chain, String expectedPin) throws Exception {
        if (chain == null || chain.length == 0) {
            throw new SecurityException("no certificate");
        }
        byte[] spki = chain[0].getPublicKey().getEncoded();
        String actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki));
        if (!expectedPin.equals(actual)) {
            throw new SecurityException("SPKI pin mismatch actual=" + actual + " expected=" + expectedPin);
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

    private static void rememberError(String message) {
        SharedPreferences p = prefs();
        if (p != null && !TextUtils.isEmpty(message)) {
            p.edit().putString(KEY_LAST_ERROR, message).apply();
        }
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
