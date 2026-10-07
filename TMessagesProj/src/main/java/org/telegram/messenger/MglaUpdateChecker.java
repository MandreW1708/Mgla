package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MglaUpdateSheet;
import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;

/**
 * Polls mglahub (or mglabot when WS relay is active) for a newer build.
 * Hub decides by app versionName and/or versionCode; Mgla version is display-only.
 */
public final class MglaUpdateChecker {

    private static final String PREFS = "mgla_updates";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    /** Dismiss key is app_version + version_code (primary update indicators). */
    private static final String KEY_DISMISSED = "dismissed_update_key";
    private static final String KEY_PENDING_JSON = "pending_json";
    private static final long POLL_INTERVAL_MS = 6L * 60 * 60 * 1000;
    /** Min gap for force checks (app resume / settings). */
    private static final long FORCE_MIN_INTERVAL_MS = 45_000;

    private static final Object lock = new Object();
    private static boolean checking;
    private static boolean sheetShowing;

    private MglaUpdateChecker() {
    }

    public static void init() {
        Utilities.globalQueue.postRunnable(() -> maybeCheck(false), 8_000);
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                maybeCheck(false);
                Utilities.globalQueue.postRunnable(this, POLL_INTERVAL_MS);
            }
        }, POLL_INTERVAL_MS);
    }

    public static void maybeCheck(boolean force) {
        boolean viaRelay = MglaRemoteConfig.shouldFetchViaRelay();
        if (viaRelay) {
            if (!MglaWsConfig.isRelayConfigured()) {
                return;
            }
        } else if (!MglaWsConfig.isHubConfigured()) {
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences prefs = prefs();
        long last = prefs != null ? prefs.getLong(KEY_LAST_CHECK, 0) : 0;
        long minGap = force ? FORCE_MIN_INTERVAL_MS : POLL_INTERVAL_MS;
        if (now - last < minGap) {
            return;
        }
        synchronized (lock) {
            if (checking) {
                return;
            }
            checking = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                MglaUpdateInfo info = fetchOnce(viaRelay);
                if (prefs != null) {
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                }
                if (info == null || !isStillNewerThanInstalled(info)) {
                    clearPending();
                    return;
                }
                String dismissed = prefs != null ? prefs.getString(KEY_DISMISSED, "") : "";
                if (!TextUtils.isEmpty(dismissed) && dismissed.equals(dismissKey(info))) {
                    return;
                }
                persistPending(info);
                AndroidUtilities.runOnUIThread(() -> showIfPossible(info));
            } catch (Throwable e) {
                FileLog.e("MglaUpdateChecker failed", e);
            } finally {
                synchronized (lock) {
                    checking = false;
                }
            }
        });
    }

    public static void dismiss(MglaUpdateInfo info) {
        if (info == null) {
            return;
        }
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putString(KEY_DISMISSED, dismissKey(info)).apply();
        }
        clearPending();
    }

    private static String dismissKey(MglaUpdateInfo info) {
        return (info.appVersion == null ? "" : info.appVersion) + "|" + info.versionCode;
    }

    /** Same base-code rule as the hub: APP_VERSION_CODE vs APK versionCode/10. */
    static boolean isStillNewerThanInstalled(MglaUpdateInfo info) {
        if (info == null) {
            return false;
        }
        int local = currentVersionCode();
        int hub = info.versionCode;
        int localBase = local >= 10000 ? local / 10 : local;
        int hubBase = hub >= 10000 ? hub / 10 : hub;
        if (hubBase > localBase) {
            return true;
        }
        String localApp = BuildVars.BUILD_VERSION_STRING == null ? "" : BuildVars.BUILD_VERSION_STRING;
        String hubApp = info.appVersion == null ? "" : info.appVersion;
        return versionStringGreater(hubApp, localApp);
    }

    private static boolean versionStringGreater(String a, String b) {
        if (TextUtils.isEmpty(a)) {
            return false;
        }
        String[] pa = a.replace("v", "").replace("V", "").split("\\.");
        String[] pb = (b == null ? "0" : b).replace("v", "").replace("V", "").split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int na = 0, nb = 0;
            try {
                if (i < pa.length) {
                    na = Integer.parseInt(pa[i].replaceAll("[^0-9].*", ""));
                }
            } catch (Exception ignored) {
            }
            try {
                if (i < pb.length) {
                    nb = Integer.parseInt(pb[i].replaceAll("[^0-9].*", ""));
                }
            } catch (Exception ignored) {
            }
            if (na != nb) {
                return na > nb;
            }
        }
        return false;
    }

    public static void showPendingIfAny(Activity activity) {
        MglaUpdateInfo pending = loadPending();
        if (pending == null || activity == null) {
            return;
        }
        if (!isStillNewerThanInstalled(pending)) {
            clearPending();
            return;
        }
        showIfPossible(pending);
    }

    private static void showIfPossible(MglaUpdateInfo info) {
        if (info == null || sheetShowing || !isStillNewerThanInstalled(info)) {
            if (info != null && !isStillNewerThanInstalled(info)) {
                clearPending();
            }
            return;
        }
        Activity activity = LaunchActivity.instance;
        if (activity == null || activity.isFinishing()) {
            return;
        }
        sheetShowing = true;
        try {
            MglaUpdateSheet.show(activity, info, () -> sheetShowing = false);
        } catch (Throwable e) {
            sheetShowing = false;
            FileLog.e(e);
        }
    }

    private static MglaUpdateInfo fetchOnce(boolean viaRelay) throws Exception {
        JSONObject body = new JSONObject();
        body.put("mgla_version", BuildVars.MGLA_VERSION_STRING);
        body.put("app_version", BuildVars.BUILD_VERSION_STRING);
        body.put("version_code", currentVersionCode());

        final String host;
        final String path;
        final String token;
        final String expectedPin;
        final boolean pinDefaultHost;
        if (viaRelay) {
            host = MglaWsConfig.getRelayHost();
            path = "/mgla-ai/v1/update-check";
            token = MglaWsConfig.getRelayToken();
            expectedPin = MglaWsConfig.RELAY_SPKI_SHA256_BASE64;
            pinDefaultHost = MglaWsConfig.DEFAULT_RELAY_HOST.equalsIgnoreCase(host);
        } else {
            host = MglaWsConfig.getHubHost();
            path = "/mgla-updates/v1/check";
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
                if (pinDefaultHost) {
                    verifySpkiPin(conn.getServerCertificates(), expectedPin);
                }
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = readUtf8(is, 64 * 1024);
            if (code != 200) {
                FileLog.e("MglaUpdateChecker HTTP " + code + " via=" + (viaRelay ? "relay" : "hub") + ": " + response);
                return null;
            }
            return MglaUpdateInfo.fromJson(response);
        } finally {
            conn.disconnect();
        }
    }

    /** Absolute HTTPS URL for the APK on the same host used for the check. */
    public static String apkDownloadUrl(MglaUpdateInfo info) {
        boolean viaRelay = MglaRemoteConfig.shouldFetchViaRelay();
        String host = viaRelay ? MglaWsConfig.getRelayHost() : MglaWsConfig.getHubHost();
        String path;
        if (viaRelay) {
            path = "/mgla-ai/v1/update-apk";
        } else {
            path = TextUtils.isEmpty(info.apkPath) ? "/mgla-updates/v1/apk" : info.apkPath;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "https://" + host + path;
    }

    public static String apkAuthToken() {
        return MglaRemoteConfig.shouldFetchViaRelay()
            ? MglaWsConfig.getRelayToken()
            : MglaWsConfig.getHubToken();
    }

    public static boolean apkPinIsRelay() {
        return MglaRemoteConfig.shouldFetchViaRelay();
    }

    public static int currentVersionCode() {
        try {
            PackageInfo p = ApplicationLoader.applicationContext.getPackageManager()
                .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return p.versionCode;
        } catch (Exception e) {
            return SharedConfig.buildVersion();
        }
    }

    private static void persistPending(MglaUpdateInfo info) {
        SharedPreferences prefs = prefs();
        if (prefs == null || info == null) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("mgla_version", info.mglaVersion);
            o.put("app_version", info.appVersion);
            o.put("version_code", info.versionCode);
            o.put("changelog", info.changelog);
            o.put("file_size", info.fileSize);
            o.put("file_sha256", info.fileSha256);
            o.put("mandatory", info.mandatory);
            o.put("apk_path", info.apkPath);
            o.put("update", true);
            prefs.edit().putString(KEY_PENDING_JSON, o.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static MglaUpdateInfo loadPending() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return null;
        }
        String raw = prefs.getString(KEY_PENDING_JSON, null);
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            return MglaUpdateInfo.fromJson(raw);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void clearPending() {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().remove(KEY_PENDING_JSON).apply();
        }
    }

    public static void verifySpkiPin(Certificate[] chain, String expectedPin) throws Exception {
        if (chain == null || chain.length == 0) {
            throw new SecurityException("no certificate");
        }
        byte[] spki = chain[0].getPublicKey().getEncoded();
        String actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki));
        if (!expectedPin.equals(actual)) {
            throw new SecurityException("SPKI pin mismatch");
        }
    }

    public static String readUtf8(InputStream is, int maxBytes) throws Exception {
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
