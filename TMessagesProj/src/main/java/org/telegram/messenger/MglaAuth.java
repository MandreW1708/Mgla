package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Per-install access tokens for hub/relay.
 * <p>
 * Prefers short-lived JWT from mglahub; falls back to BuildConfig legacy tokens so
 * existing installs keep working if enrollment is unreachable.
 */
public final class MglaAuth {

    private static final String PREFS = "mgla_auth";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_ACCESS_EXPIRES_AT = "access_expires_at_ms";
    private static final String KEY_IID = "auth_iid";

    private static final long REFRESH_SKEW_MS = 5L * 60L * 1000L;
    private static final Object lock = new Object();
    private static boolean initStarted;
    private static boolean ensuring;

    private MglaAuth() {
    }

    public static void init() {
        synchronized (lock) {
            if (initStarted) {
                return;
            }
            initStarted = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                ensureAccessToken(false);
            } catch (Throwable e) {
                FileLog.e("MglaAuth init", e);
            }
        });
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureAccessToken(false);
                } catch (Throwable e) {
                    FileLog.e("MglaAuth refresh", e);
                }
                Utilities.globalQueue.postRunnable(this, 30L * 60L * 1000L);
            }
        }, 30L * 60L * 1000L);
    }

    /**
     * Token for X-Mgla-Token on relay and hub. Never empty if legacy BuildConfig exists.
     * Does not perform network I/O — safe to call from the WS connect path.
     */
    public static String getAccessToken() {
        String cached = peekCachedAccessToken();
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }
        // Schedule refresh in background; return legacy immediately so connects don't stall.
        try {
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    ensureAccessToken(false);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        return legacyFallbackToken();
    }

    /** Non-blocking: valid cached JWT or null. */
    public static String peekCachedAccessToken() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return null;
        }
        String access = prefs.getString(KEY_ACCESS, null);
        long exp = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0);
        if (!TextUtils.isEmpty(access) && exp > System.currentTimeMillis() + 30_000) {
            return access;
        }
        return null;
    }

    public static boolean hasSessionToken() {
        return !TextUtils.isEmpty(peekCachedAccessToken());
    }

    private static String legacyFallbackToken() {
        String hub = BuildConfig.MGLA_HUB_TOKEN;
        if (!TextUtils.isEmpty(hub)) {
            return hub.trim();
        }
        String relay = BuildConfig.MGLA_WS_RELAY_TOKEN;
        return relay == null ? "" : relay.trim();
    }

    private static void ensureAccessToken(boolean force) {
        synchronized (lock) {
            if (ensuring) {
                return;
            }
            SharedPreferences prefs = prefs();
            if (prefs == null) {
                return;
            }
            long now = System.currentTimeMillis();
            String access = prefs.getString(KEY_ACCESS, null);
            long exp = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0);
            if (!force && !TextUtils.isEmpty(access) && exp - REFRESH_SKEW_MS > now) {
                return;
            }
            ensuring = true;
        }
        try {
            SharedPreferences prefs = prefs();
            if (prefs == null) {
                return;
            }
            String refresh = prefs.getString(KEY_REFRESH, null);
            String iid = ensureIid(prefs);
            boolean preferRelay = preferRelayPath();
            JSONObject body = null;
            if (!TextUtils.isEmpty(refresh)) {
                body = refreshOnServer(iid, refresh, preferRelay);
                if (body == null) {
                    body = refreshOnServer(iid, refresh, !preferRelay);
                }
                if (body == null) {
                    prefs.edit().remove(KEY_REFRESH).remove(KEY_ACCESS).remove(KEY_ACCESS_EXPIRES_AT).apply();
                }
            }
            if (body == null) {
                body = registerOnServer(iid, preferRelay);
                if (body == null) {
                    body = registerOnServer(iid, !preferRelay);
                }
            }
            if (body == null) {
                return;
            }
            String newAccess = body.optString("access_token", "");
            String newRefresh = body.optString("refresh_token", "");
            int expiresIn = body.optInt("expires_in", 0);
            if (TextUtils.isEmpty(newAccess) || TextUtils.isEmpty(newRefresh) || expiresIn <= 0) {
                return;
            }
            prefs.edit()
                .putString(KEY_ACCESS, newAccess)
                .putString(KEY_REFRESH, newRefresh)
                .putString(KEY_IID, iid)
                .putLong(KEY_ACCESS_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
                .apply();
        } catch (Throwable e) {
            FileLog.e("MglaAuth ensure failed", e);
        } finally {
            synchronized (lock) {
                ensuring = false;
            }
        }
    }

    /** Avoid calling getRelayToken()/isRelayConfigured() here (would recurse). */
    private static boolean preferRelayPath() {
        try {
            if (!MglaWsConfig.isEnabled()) {
                return false;
            }
            if (TextUtils.isEmpty(MglaWsConfig.getRelayHost())) {
                return false;
            }
            return !VpnMonitor.getInstance().isVpnActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String ensureIid(SharedPreferences authPrefs) {
        String fromStats = MglaStats.getInstallId();
        if (!TextUtils.isEmpty(fromStats)) {
            authPrefs.edit().putString(KEY_IID, fromStats).apply();
            return fromStats;
        }
        String existing = authPrefs.getString(KEY_IID, null);
        if (!TextUtils.isEmpty(existing) && existing.length() == 32) {
            return existing;
        }
        return fromStats;
    }

    private static JSONObject registerOnServer(String iid, boolean viaRelay) {
        try {
            JSONObject body = new JSONObject();
            body.put("iid", iid);
            body.put("app_version", BuildVars.BUILD_VERSION_STRING);
            body.put("mgla_version", BuildVars.MGLA_VERSION_STRING);
            String path = viaRelay ? "/mgla-ai/v1/auth/register" : "/mgla-auth/v1/register";
            return postJson(viaRelay, path, body);
        } catch (Throwable e) {
            FileLog.e("MglaAuth register failed viaRelay=" + viaRelay, e);
            return null;
        }
    }

    private static JSONObject refreshOnServer(String iid, String refresh, boolean viaRelay) {
        try {
            JSONObject body = new JSONObject();
            body.put("iid", iid);
            body.put("refresh_token", refresh);
            String path = viaRelay ? "/mgla-ai/v1/auth/token" : "/mgla-auth/v1/token";
            return postJson(viaRelay, path, body);
        } catch (Throwable e) {
            FileLog.e("MglaAuth refresh failed viaRelay=" + viaRelay, e);
            return null;
        }
    }

    private static JSONObject postJson(boolean viaRelay, String path, JSONObject body) throws Exception {
        String host = viaRelay ? MglaWsConfig.getRelayHost() : MglaWsConfig.getHubHost();
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        String url = "https://" + host + path;
        HttpsURLConnection conn = MglaDirectHttp.openHttps(url);
        try {
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(15_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Mgla-Client", "mgla");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                String expectedPin = viaRelay
                    ? MglaWsConfig.RELAY_SPKI_SHA256_BASE64
                    : MglaWsConfig.HUB_SPKI_SHA256_BASE64;
                boolean pinHost = viaRelay
                    ? MglaWsConfig.DEFAULT_RELAY_HOST.equalsIgnoreCase(host)
                    : MglaWsConfig.DEFAULT_HUB_HOST.equalsIgnoreCase(host);
                if (pinHost) {
                    MglaUpdateChecker.verifySpkiPin(conn.getServerCertificates(), expectedPin);
                }
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = MglaUpdateChecker.readUtf8(is, 64 * 1024);
            if (code != 200) {
                FileLog.e("MglaAuth HTTP " + code + " " + path + ": " + response);
                return null;
            }
            return new JSONObject(response);
        } finally {
            conn.disconnect();
        }
    }

    private static SharedPreferences prefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
