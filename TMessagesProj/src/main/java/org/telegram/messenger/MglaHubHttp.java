package org.telegram.messenger;

import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTPS к ИИ-прокси на том же хосте, что и WS-обход ({@code mglabot.mooo.com}).
 * OpenRouter/Gemini с телефона не вызываются напрямую — только через этот прокси.
 * <p>
 * Статистика ({@link MglaStats}) при включённом обходе тоже идёт через этот хост
 * ({@code /mgla-ai/v1/stats-batch} → mglahub), иначе напрямую на хаб.
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

    /** ИИ-прокси доступен, если настроен WS-релей (тот же хост/токен). */
    public static boolean isConfigured() {
        return MglaWsConfig.isRelayConfigured();
    }

    public static Response post(String path, byte[] jsonBody, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        String host = MglaWsConfig.getRelayHost();
        String normalized = path.startsWith("/") ? path : ("/" + path);
        URL url = new URL("https://" + host + normalized);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Mgla-Token", MglaWsConfig.getRelayToken());
            conn.connect();
            if (MglaWsConfig.DEFAULT_RELAY_HOST.equalsIgnoreCase(host)) {
                verifyRelayPin(conn.getServerCertificates());
            }
            if (jsonBody != null && jsonBody.length > 0) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody);
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

    private static void verifyRelayPin(Certificate[] chain) throws Exception {
        if (chain == null || chain.length == 0) {
            throw new SecurityException("no certificate");
        }
        byte[] spki = chain[0].getPublicKey().getEncoded();
        String actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki));
        if (!MglaWsConfig.RELAY_SPKI_SHA256_BASE64.equals(actual)) {
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
