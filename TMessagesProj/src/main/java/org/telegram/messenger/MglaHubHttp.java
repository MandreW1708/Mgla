package org.telegram.messenger;

import android.text.TextUtils;

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
 * HTTPS к ИИ-прокси ({@code /mgla-ai/…}):
 * <ul>
 *   <li>обычный обход → {@code mglabot} / Германия2 ({@code mglahub})</li>
 *   <li>Ultra-обход → {@code fin.mgla.mooo.com}</li>
 *   <li>обход выключен / VPN-пауза → напрямую {@code mglahub.mooo.com}
 *       (каталог моделей и чат без ключа в APK; иначе список моделей не грузится)</li>
 * </ul>
 * Features/stats/updates при обходе — тот же стек
 * (см. {@link MglaRemoteConfig#shouldFetchViaRelay()}).
 */
public final class MglaHubHttp {

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private MglaHubHttp() {
    }

    /** Релей или хаб готовы принять /mgla-ai (обход не обязан быть включён). */
    public static boolean isConfigured() {
        return MglaWsConfig.isRelayConfigured() || MglaWsConfig.isHubConfigured();
    }

    /**
     * Gemini / OpenRouter через активный обход: обычный → DE1/DE2, Ultra → Finland.
     * {@code false} если обход выключен или на паузе из‑за VPN — тогда
     * {@link #request} бьёт в mglahub напрямую (модели/чат), а при наличии
     * {@code OPENROUTER_API_KEY} в сборке чат может идти в OpenRouter напрямую.
     */
    public static boolean shouldProxyAi() {
        try {
            if (!MglaWsConfig.isEnabled() || !MglaWsConfig.isRelayConfigured()) {
                return false;
            }
            return !VpnMonitor.getInstance().isVpnActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Response get(String path, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return request("GET", path, null, connectTimeoutMs, readTimeoutMs);
    }

    public static Response post(String path, byte[] jsonBody, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return request("POST", path, jsonBody, connectTimeoutMs, readTimeoutMs);
    }

    private static Response request(String method, String path, byte[] jsonBody, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        final boolean viaRelay = shouldProxyAi();
        final String host;
        final String token;
        if (viaRelay) {
            // Ultra → Finland; normal → Germany1/2.
            host = MglaWsConfig.getRelayHost();
            token = MglaWsConfig.getRelayToken();
        } else {
            if (!MglaWsConfig.isHubConfigured()) {
                throw new Exception("хаб ИИ не настроен");
            }
            host = MglaWsConfig.getHubHost();
            token = MglaWsConfig.getHubToken();
        }
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(token)) {
            throw new Exception(viaRelay ? "релей ИИ не настроен" : "хаб ИИ не настроен");
        }
        String normalized = path.startsWith("/") ? path : ("/" + path);
        HttpsURLConnection conn = MglaDirectHttp.openHttps("https://" + host + normalized);
        try {
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod(method);
            conn.setRequestProperty("X-Mgla-Token", token);
            conn.setRequestProperty("X-Mgla-Client", "mgla");
            boolean hasBody = jsonBody != null && jsonBody.length > 0 && !"GET".equals(method);
            if (hasBody) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            }
            // Handshake before pin check (getOutputStream / connect).
            if (hasBody) {
                try (OutputStream os = conn.getOutputStream()) {
                    if (viaRelay) {
                        String[] pins = MglaWsConfig.getRelaySpkiPins(host);
                        if (pins != null && pins.length > 0) {
                            verifySpkiPin(conn.getServerCertificates(), pins[0]);
                        }
                    } else if (MglaWsConfig.DEFAULT_HUB_HOST.equalsIgnoreCase(host)) {
                        verifySpkiPin(conn.getServerCertificates(), MglaWsConfig.HUB_SPKI_SHA256_BASE64);
                    }
                    os.write(jsonBody);
                }
            } else {
                conn.connect();
                if (viaRelay) {
                    String[] pins = MglaWsConfig.getRelaySpkiPins(host);
                    if (pins != null && pins.length > 0) {
                        verifySpkiPin(conn.getServerCertificates(), pins[0]);
                    }
                } else if (MglaWsConfig.DEFAULT_HUB_HOST.equalsIgnoreCase(host)) {
                    verifySpkiPin(conn.getServerCertificates(), MglaWsConfig.HUB_SPKI_SHA256_BASE64);
                }
            }
            int code = conn.getResponseCode();
            InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = readUtf8(is, code < 400 ? 512 * 1024 : 8192);
            return new Response(code, body);
        } finally {
            conn.disconnect();
        }
    }

    public static String extractJsonStringField(String json, String field) {
        if (json == null || field == null) {
            return null;
        }
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            key = "\"" + field + "\": \"";
            start = json.indexOf(key);
        }
        if (start < 0) {
            return null;
        }
        start += key.length();
        int end = start;
        boolean escaped = false;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            }
            end++;
        }
        return json.substring(start, end)
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    private static void verifySpkiPin(Certificate[] chain, String expectedBase64) throws Exception {
        if (chain == null || chain.length == 0) {
            throw new SecurityException("no certificate");
        }
        if (TextUtils.isEmpty(expectedBase64)) {
            return;
        }
        byte[] spki = chain[0].getPublicKey().getEncoded();
        String actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki));
        if (!expectedBase64.equals(actual)) {
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
                throw new Exception("ответ прокси слишком большой");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
