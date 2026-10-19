package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MglaUpdateSheet;
import org.telegram.utils.proxy.WebProxyTransport;
import org.telegram.utils.wsbypass.MglaWsBypass;
import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;

/**
 * Polls mglahub (or mglabot when auto-bypass is active — over HTTPS on the WS route,
 * through the WEB proxy update tunnel on the WEB route) for a newer build.
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
        long[] tgIds = collectTelegramIds();
        if (tgIds.length > 0) {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (long id : tgIds) {
                arr.put(id);
            }
            body.put("telegram_ids", arr);
            body.put("telegram_id", tgIds[0]);
        }

        Channel channel = viaRelay ? (isWebRouteActive() ? Channel.WEB : Channel.RELAY) : Channel.HUB;
        String path = channel == Channel.HUB ? "/mgla-updates/v1/check" : "/mgla-ai/v1/update-check";
        UpdateConnection uc = UpdateConnection.open(channel, path);
        try {
            HttpURLConnection conn = uc.conn;
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(channel == Channel.WEB ? 30_000 : 12_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String tgHeader = telegramIdsHeader(tgIds);
            if (!TextUtils.isEmpty(tgHeader)) {
                conn.setRequestProperty("X-Mgla-Tg-Id", tgHeader);
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                uc.verifyPin();
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String response = readUtf8(is, 64 * 1024);
            if (code != 200) {
                FileLog.e("MglaUpdateChecker HTTP " + code + " via=" + channel + ": " + response);
                return null;
            }
            return MglaUpdateInfo.fromJson(response);
        } finally {
            uc.close();
        }
    }

    /** HUB: напрямую на хаб; RELAY: HTTPS на релей; WEB: через туннель WEB proxy к релею. */
    public enum Channel { HUB, RELAY, WEB }

    /**
     * Авто-обход на резервном WEB proxy mglabot (туннель обновлений).
     * Ultra WEB на mglahub сюда не входит — у хаба нет mgla-upd туннеля.
     */
    private static boolean isWebRouteActive() {
        try {
            MglaWsBypass bypass = MglaWsBypass.getInstance();
            if (!bypass.isProxyApplied()
                || bypass.getRoute() != MglaWsBypass.Route.WEB
                || !MglaWsConfig.isWebUpdateTunnelConfigured()
                || !WebProxyTransport.isSupported()) {
                return false;
            }
            String webHost = MglaWsConfig.getWebProxyHost();
            String updateHost = MglaWsConfig.getWebUpdateProxyHost();
            return !TextUtils.isEmpty(webHost)
                && webHost.equalsIgnoreCase(updateHost);
        } catch (Throwable e) {
            return false;
        }
    }

    public static Channel currentChannel() {
        if (!MglaRemoteConfig.shouldFetchViaRelay()) {
            return Channel.HUB;
        }
        return isWebRouteActive() ? Channel.WEB : Channel.RELAY;
    }

    /** APK download on the channel matching the current auto-bypass state. */
    public static UpdateConnection openApkConnection(MglaUpdateInfo info) throws Exception {
        Channel channel = currentChannel();
        String path;
        if (channel == Channel.HUB) {
            path = TextUtils.isEmpty(info.apkPath) ? "/mgla-updates/v1/apk" : info.apkPath;
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
        } else {
            path = "/mgla-ai/v1/update-apk";
        }
        return UpdateConnection.open(channel, path);
    }

    public static final class UpdateConnection {
        public final HttpURLConnection conn;
        public final Channel channel;
        private boolean tunnelHeld;

        private UpdateConnection(HttpURLConnection conn, Channel channel, boolean tunnelHeld) {
            this.conn = conn;
            this.channel = channel;
            this.tunnelHeld = tunnelHeld;
        }

        static UpdateConnection open(Channel channel, String path) throws Exception {
            if (channel == Channel.WEB) {
                int port = WebProxyTransport.acquireUpdateTunnel(
                    MglaWsConfig.getWebUpdateProxyHost(), MglaWsConfig.getWebUpdateSecret());
                if (port <= 0) {
                    throw new IOException("WEB proxy недоступен");
                }
                try {
                    if (!WebProxyTransport.awaitUpdateTunnelReady(25_000)) {
                        throw new IOException("WEB proxy не подключился");
                    }
                    HttpURLConnection conn = MglaDirectHttp.open("http://127.0.0.1:" + port + path);
                    conn.setRequestProperty("X-Mgla-Token", MglaWsConfig.getRelayToken());
                    conn.setRequestProperty("X-Mgla-Client", "mgla");
                    attachTelegramIds(conn);
                    return new UpdateConnection(conn, channel, true);
                } catch (Exception e) {
                    WebProxyTransport.releaseUpdateTunnel();
                    throw e;
                }
            }
            boolean relay = channel == Channel.RELAY;
            String host = relay ? MglaWsConfig.getRelayHost() : MglaWsConfig.getHubHost();
            HttpsURLConnection conn = MglaDirectHttp.openHttps("https://" + host + path);
            conn.setRequestProperty("X-Mgla-Token", relay ? MglaWsConfig.getRelayToken() : MglaWsConfig.getHubToken());
            conn.setRequestProperty("X-Mgla-Client", "mgla");
            attachTelegramIds(conn);
            return new UpdateConnection(conn, channel, false);
        }

        private static void attachTelegramIds(HttpURLConnection conn) {
            String tgHeader = telegramIdsHeader(collectTelegramIds());
            if (!TextUtils.isEmpty(tgHeader)) {
                conn.setRequestProperty("X-Mgla-Tg-Id", tgHeader);
            }
        }

        /** Call after the connection is established. WEB rides the WebView's TLS to the relay host. */
        public void verifyPin() throws Exception {
            if (!(conn instanceof HttpsURLConnection)) {
                return;
            }
            String host = conn.getURL().getHost();
            if (channel == Channel.RELAY && MglaWsConfig.DEFAULT_RELAY_HOST.equalsIgnoreCase(host)) {
                verifySpkiPin(((HttpsURLConnection) conn).getServerCertificates(), MglaWsConfig.RELAY_SPKI_SHA256_BASE64);
            } else if (channel == Channel.HUB && MglaWsConfig.DEFAULT_HUB_HOST.equalsIgnoreCase(host)) {
                verifySpkiPin(((HttpsURLConnection) conn).getServerCertificates(), MglaWsConfig.HUB_SPKI_SHA256_BASE64);
            }
        }

        public void close() {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
            if (tunnelHeld) {
                tunnelHeld = false;
                WebProxyTransport.releaseUpdateTunnel();
            }
        }
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

    /** All activated account telegram user ids (selected account first). */
    static long[] collectTelegramIds() {
        try {
            java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
            int selected = UserConfig.selectedAccount;
            if (UserConfig.isValidAccount(selected)) {
                long id = UserConfig.getInstance(selected).getClientUserId();
                if (id > 0) {
                    ids.add(id);
                }
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (a == selected || !UserConfig.isValidAccount(a)) {
                    continue;
                }
                long id = UserConfig.getInstance(a).getClientUserId();
                if (id > 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            }
            long[] out = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                out[i] = ids.get(i);
            }
            return out;
        } catch (Throwable e) {
            return new long[0];
        }
    }

    static String telegramIdsHeader(long[] ids) {
        if (ids == null || ids.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (long id : ids) {
            if (id <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
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
