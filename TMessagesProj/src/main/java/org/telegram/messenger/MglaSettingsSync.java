package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * Manual cloud sync of client settings (any paid Mgla plan on the selected account).
 * <ul>
 *   <li>{@link #syncNow} — upload local settings to the cloud (5 min cooldown); does not apply</li>
 *   <li>{@link #applyNow} — download cloud settings and apply them locally</li>
 * </ul>
 */
public final class MglaSettingsSync {

    public interface Callback {
        void onSuccess(String message);

        void onError(String message);
    }

    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static String lastResponseBody = "";

    private MglaSettingsSync() {
    }

    public static boolean isBusy() {
        return busy.get();
    }

    /** @deprecated use {@link #isBusy()} */
    public static boolean isSyncing() {
        return isBusy();
    }

    /**
     * Push local settings to the cloud. Stores the cloud document as pending
     * for optional review; does <b>not</b> apply it on this device.
     */
    public static void syncNow(boolean interactive, Callback callback) {
        if (!MglaSubscription.canSyncSettings()) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError("Синхронизация настроек доступна с Premium, Premium+ и Ultra"));
            }
            return;
        }
        long cooldown = MglaSettingsSnapshot.getSyncCooldownRemainingMs();
        if (cooldown > 0L) {
            if (callback != null) {
                final String msg = "Подождите ещё " + formatCooldown(cooldown);
                AndroidUtilities.runOnUIThread(() -> callback.onError(msg));
            }
            return;
        }
        runExclusive(interactive, callback, () -> {
            JSONObject json = exchange(true);
            MglaSettingsSnapshot.savePending(json);
            MglaSettingsSnapshot.markSyncedNow();
            return "Настройки отправлены в облако"
                    + (json.optJSONObject("settings") != null
                    && json.optJSONObject("settings").length() > 0
                    ? " · на другом устройстве нажмите «Применить»" : "");
        });
    }

    /**
     * Fetch cloud settings for this account and apply them locally.
     * Prefer freshly pulled document; fall back to pending from last sync if offline pull fails.
     */
    public static void applyNow(boolean interactive, Callback callback) {
        if (!MglaSubscription.canSyncSettings()) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError("Применение настроек доступно с Premium, Premium+ и Ultra"));
            }
            return;
        }
        runExclusive(interactive, callback, () -> {
            UserConfig uc = UserConfig.getInstance(UserConfig.selectedAccount);
            if (uc == null || !uc.isClientActivated()) {
                throw new Exception("Нет активного аккаунта");
            }
            long tgId = uc.getClientUserId();
            JSONObject json;
            try {
                json = exchange(false);
                MglaSettingsSnapshot.savePending(json);
            } catch (Exception pullError) {
                json = MglaSettingsSnapshot.loadPending();
                if (json == null) {
                    throw pullError;
                }
                FileLog.e(pullError);
            }
            int applied = MglaSettingsSnapshot.apply(json, tgId);
            MglaSettingsSnapshot.clearPending();
            if (applied <= 0) {
                return "В облаке пока нет настроек — сначала синхронизируйте с другого устройства";
            }
            return "Настройки применены · ключей: " + applied;
        });
    }

    private interface Work {
        String run() throws Exception;
    }

    private static void runExclusive(boolean interactive, Callback callback, Work work) {
        if (!busy.compareAndSet(false, true)) {
            if (interactive && callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onError("Операция уже выполняется…"));
            }
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String message = work.run();
                AndroidUtilities.runOnUIThread(() -> {
                    busy.set(false);
                    if (callback != null) {
                        callback.onSuccess(message);
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
                final String msg = !TextUtils.isEmpty(e.getMessage())
                        ? e.getMessage()
                        : "Не удалось выполнить операцию";
                AndroidUtilities.runOnUIThread(() -> {
                    busy.set(false);
                    if (callback != null) {
                        callback.onError(msg);
                    }
                });
            }
        });
    }

    /**
     * @param push {@code true} — upload local settings; {@code false} — pull only
     */
    private static JSONObject exchange(boolean push) throws Exception {
        UserConfig uc = UserConfig.getInstance(UserConfig.selectedAccount);
        if (uc == null || !uc.isClientActivated()) {
            throw new Exception("Нет активного аккаунта");
        }
        long tgId = uc.getClientUserId();
        if (tgId <= 0) {
            throw new Exception("Нет Telegram id");
        }
        if (MglaSubscription.PLAN_FREE.equals(MglaSubscription.getPlanIdForUser(tgId))) {
            throw new Exception("Доступно с Premium, Premium+ и Ultra");
        }

        JSONObject body;
        if (push) {
            MglaSettingsSnapshot.touchLocalUpdatedAt();
            body = MglaSettingsSnapshot.build(tgId);
        } else {
            body = MglaSettingsSnapshot.buildPullRequest(tgId);
        }

        // Bypass on → selected region (DE1 / DE2 / FI). Bypass off / VPN pause → hub direct.
        // Never use sticky WS sslip aliases for this HTTPS API (they 503 / lack routes).
        final Route primary = resolveRoute(/*preferBypass*/ MglaRemoteConfig.shouldFetchViaRelay());
        final Route fallback = primary.viaBypass
                ? resolveRoute(/*preferBypass*/ false)
                : null;

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        int code;
        try {
            code = postSync(primary.host, primary.path, primary.token, payload, primary.viaBypass, true);
            if (fallback != null && code != 200 && code != 403) {
                code = postSync(fallback.host, fallback.path, fallback.token, payload, false, false);
            }
        } catch (Exception first) {
            if (fallback == null) {
                throw first;
            }
            code = postSync(fallback.host, fallback.path, fallback.token, payload, false, false);
        }
        String response = lastResponseBody;
        if (code == 403) {
            String err = extractError(response);
            throw new Exception(TextUtils.isEmpty(err)
                    ? "Доступно с Premium, Premium+ и Ultra"
                    : err);
        }
        if (code != 200) {
            String err = extractError(response);
            throw new Exception(TextUtils.isEmpty(err) ? ("HTTP " + code) : err);
        }
        return new JSONObject(response);
    }

    private static final class Route {
        final String host;
        final String path;
        final String token;
        final boolean viaBypass;

        Route(String host, String path, String token, boolean viaBypass) {
            this.host = host;
            this.path = path;
            this.token = token;
            this.viaBypass = viaBypass;
        }
    }

    /**
     * Pick sync endpoint for the currently selected bypass (or hub when bypass is off).
     * <ul>
     *   <li>Ultra → Finland {@code /mgla-ai/v1/settings-sync}</li>
     *   <li>Германия1 → DE1 primary {@code /mgla-ai/v1/settings-sync}</li>
     *   <li>Германия2 → hub primary native {@code /mgla-settings/v1/sync}
     *       (same host as DE2; avoid sslip + ai_proxy hairpin)</li>
     *   <li>обход выкл → hub {@code /mgla-settings/v1/sync}</li>
     * </ul>
     */
    private static Route resolveRoute(boolean preferBypass) throws Exception {
        if (preferBypass && MglaWsConfig.isEnabled() && MglaWsConfig.isRelayConfigured()) {
            if (MglaWsConfig.isUltraBypassEnabled()) {
                String host = MglaWsConfig.FINLAND_RELAY_HOST;
                String token = MglaWsConfig.getRelayToken();
                if (TextUtils.isEmpty(token)) {
                    throw new Exception("Релей не настроен");
                }
                return new Route(host, "/mgla-ai/v1/settings-sync", token, true);
            }
            if (MglaWsConfig.REGION_HUB.equals(MglaWsConfig.getBypassRegion())) {
                // DE2 shares the hub box — use native settings path on the primary hostname.
                String host = MglaWsConfig.getHubHost();
                String token = MglaWsConfig.getHubToken();
                if (TextUtils.isEmpty(host) || TextUtils.isEmpty(token)) {
                    throw new Exception("Хаб не настроен");
                }
                return new Route(host, "/mgla-settings/v1/sync", token, true);
            }
            // DE1: primary region host (not sticky sslip alias).
            String host = MglaWsConfig.getRegionRelayHost();
            String token = MglaWsConfig.getRelayToken();
            if (TextUtils.isEmpty(host) || TextUtils.isEmpty(token)) {
                throw new Exception("Релей не настроен");
            }
            return new Route(host, "/mgla-ai/v1/settings-sync", token, true);
        }
        String host = MglaWsConfig.getHubHost();
        String token = MglaWsConfig.getHubToken();
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(token)) {
            throw new Exception("Хаб не настроен");
        }
        return new Route(host, "/mgla-settings/v1/sync", token, false);
    }

    private static String formatCooldown(long ms) {
        long totalSec = Math.max(1L, (ms + 999L) / 1000L);
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        if (min <= 0L) {
            return sec + " с";
        }
        if (sec <= 0L) {
            return min + " мин";
        }
        return min + " мин " + sec + " с";
    }

    private static int postSync(String host, String path, String token, byte[] payload,
                                boolean viaRelay, boolean pinRelay) throws Exception {
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(token)) {
            throw new Exception(viaRelay ? "Релей не настроен" : "Хаб не настроен");
        }
        HttpsURLConnection conn = MglaDirectHttp.openHttps("https://" + host + path);
        try {
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(45_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Mgla-Token", token);
            conn.setRequestProperty("X-Mgla-Client", "mgla");
            try (OutputStream os = conn.getOutputStream()) {
                if (pinRelay && viaRelay) {
                    String[] pins = MglaWsConfig.getRelaySpkiPins(host);
                    if (pins != null && pins.length > 0) {
                        verifySpki(conn, pins[0]);
                    }
                } else if (MglaWsConfig.DEFAULT_HUB_HOST.equalsIgnoreCase(host)) {
                    verifySpki(conn, MglaWsConfig.HUB_SPKI_SHA256_BASE64);
                }
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            lastResponseBody = readUtf8(is, 512 * 1024);
            return code;
        } finally {
            conn.disconnect();
        }
    }

    private static void verifySpki(HttpsURLConnection conn, String expected) throws Exception {
        if (TextUtils.isEmpty(expected)) {
            return;
        }
        java.security.cert.Certificate[] chain = conn.getServerCertificates();
        if (chain == null || chain.length == 0) {
            throw new SecurityException("no certificate");
        }
        byte[] spki = chain[0].getPublicKey().getEncoded();
        String actual = android.util.Base64.encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(spki),
                android.util.Base64.NO_WRAP);
        if (!expected.equals(actual)) {
            throw new SecurityException("SPKI pin mismatch");
        }
    }

    private static String readUtf8(InputStream is, int max) throws Exception {
        if (is == null) {
            return "";
        }
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) > 0) {
            if (out.size() + n > max) {
                throw new Exception("ответ слишком большой");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String extractError(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            return new JSONObject(json).optString("error", null);
        } catch (Throwable ignore) {
            return null;
        }
    }
}
