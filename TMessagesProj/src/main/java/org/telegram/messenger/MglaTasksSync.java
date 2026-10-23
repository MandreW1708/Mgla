package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * Cloud sync for Задачи (Premium+ / Ultra).
 * <ul>
 *   <li>обход вкл → текущий релей ({@code /mgla-ai/v1/tasks-sync} на DE1/DE2/Finland)</li>
 *   <li>обход выкл → напрямую mglahub ({@code /mgla-tasks/v1/sync})</li>
 * </ul>
 */
public final class MglaTasksSync {

    public interface Callback {
        void onSuccess(String message);

        void onError(String message);
    }

    private static final long DEBOUNCE_MS = 2_500L;
    private static final long AUTO_INTERVAL_MS = 15L * 60L * 1000L;
    private static final Object lock = new Object();
    private static final AtomicBoolean syncing = new AtomicBoolean(false);
    private static boolean autoStarted;
    private static long lastAutoAt;
    private static Runnable pendingDebounce;

    private MglaTasksSync() {
    }

    public static void init() {
        synchronized (lock) {
            if (autoStarted) {
                return;
            }
            autoStarted = true;
        }
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    maybeAutoSync(false);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                Utilities.globalQueue.postRunnable(this, AUTO_INTERVAL_MS);
            }
        }, 45_000L);
    }

    /** Debounced push after local edits. */
    public static void scheduleAfterLocalChange() {
        if (!MglaSubscription.canSyncTasks()) {
            return;
        }
        synchronized (lock) {
            if (pendingDebounce != null) {
                AndroidUtilities.cancelRunOnUIThread(pendingDebounce);
            }
            pendingDebounce = () -> {
                pendingDebounce = null;
                syncNow(false, null);
            };
            AndroidUtilities.runOnUIThread(pendingDebounce, DEBOUNCE_MS);
        }
    }

    public static void maybeAutoSync(boolean force) {
        if (!MglaSubscription.canSyncTasks()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastAutoAt < AUTO_INTERVAL_MS) {
            return;
        }
        lastAutoAt = now;
        syncNow(false, null);
    }

    public static void syncNow(boolean interactive, Callback callback) {
        if (!MglaSubscription.canSyncTasks()) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError("Синхронизация задач доступна с Premium, Premium+ и Ultra"));
            }
            return;
        }
        if (!syncing.compareAndSet(false, true)) {
            if (interactive && callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onError("Синхронизация уже идёт…"));
            }
            return;
        }
        MglaTasksStorage.getInstance().ensureLoaded(() ->
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        Result result = performSync();
                        lastAutoAt = System.currentTimeMillis();
                        AndroidUtilities.runOnUIThread(() -> {
                            syncing.set(false);
                            if (callback != null) {
                                callback.onSuccess(result.message);
                            } else if (interactive) {
                                // no-op
                            }
                        });
                    } catch (Throwable e) {
                        FileLog.e(e);
                        final String msg = !TextUtils.isEmpty(e.getMessage())
                                ? e.getMessage()
                                : "Не удалось синхронизировать задачи";
                        AndroidUtilities.runOnUIThread(() -> {
                            syncing.set(false);
                            if (callback != null) {
                                callback.onError(msg);
                            }
                        });
                    }
                }));
    }

    private static final class Result {
        final String message;

        Result(String message) {
            this.message = message;
        }
    }

    private static Result performSync() throws Exception {
        UserConfig uc = UserConfig.getInstance(UserConfig.selectedAccount);
        if (uc == null || !uc.isClientActivated()) {
            throw new Exception("Нет активного аккаунта");
        }
        long tgId = uc.getClientUserId();
        if (tgId <= 0) {
            throw new Exception("Нет Telegram id");
        }

        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        storage.ensureSyncIdsForAccount(UserConfig.selectedAccount);

        JSONObject body = storage.buildSyncPayload(UserConfig.selectedAccount, tgId);
        boolean viaRelay = MglaRemoteConfig.shouldFetchViaRelay();
        final String host;
        final String path;
        final String token;
        if (viaRelay) {
            host = MglaWsConfig.getRelayHost();
            path = "/mgla-ai/v1/tasks-sync";
            token = MglaWsConfig.getRelayToken();
        } else {
            host = MglaWsConfig.getHubHost();
            path = "/mgla-tasks/v1/sync";
            token = MglaWsConfig.getHubToken();
        }
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(token)) {
            throw new Exception(viaRelay ? "Релей не настроен" : "Хаб не настроен");
        }

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        String response;
        int code;
        boolean usedRelay = viaRelay;
        try {
            code = postSync(host, path, token, payload, viaRelay, true);
            // Older DE1 ai_proxy may not know /tasks-sync yet — fall back to hub.
            if (viaRelay && (code == 404 || code == 502 || code == 503)) {
                usedRelay = false;
                code = postSync(
                        MglaWsConfig.getHubHost(),
                        "/mgla-tasks/v1/sync",
                        MglaWsConfig.getHubToken(),
                        payload,
                        false,
                        false);
            }
            response = lastResponseBody;
        } catch (Exception first) {
            if (!viaRelay) {
                throw first;
            }
            usedRelay = false;
            code = postSync(
                    MglaWsConfig.getHubHost(),
                    "/mgla-tasks/v1/sync",
                    MglaWsConfig.getHubToken(),
                    payload,
                    false,
                    false);
            response = lastResponseBody;
        }
        if (code == 403) {
            String err = extractError(response);
            throw new Exception(TextUtils.isEmpty(err)
                    ? "Синхронизация доступна с Premium, Premium+ и Ultra"
                    : err);
        }
        if (code != 200) {
            String err = extractError(response);
            throw new Exception(TextUtils.isEmpty(err) ? ("HTTP " + code) : err);
        }
        JSONObject json = new JSONObject(response);
        int applied = storage.applySyncResponse(UserConfig.selectedAccount, json);
        MglaTasksReminderScheduler.rescheduleAll();
        return new Result("Синхронизировано · задач: " + applied
                + (usedRelay ? " · через обход" : " · напрямую"));
    }

    private static String lastResponseBody = "";

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
            lastResponseBody = readUtf8(is, 2 * 1024 * 1024);
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

    public static String newUuid() {
        return UUID.randomUUID().toString();
    }
}
