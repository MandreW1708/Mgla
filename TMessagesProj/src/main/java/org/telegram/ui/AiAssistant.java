package org.telegram.ui;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * AI assistant powered by OpenRouter.
 * Set your API key in {@link #API_KEY} before use.
 */
public class AiAssistant {

    private static final String API_KEY = "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";

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String MODEL = "nvidia/nemotron-3-nano-30b-a3b:free";

    private static volatile AiAssistant instance;

    public static AiAssistant getInstance() {
        if (instance == null) {
            synchronized (AiAssistant.class) {
                if (instance == null) {
                    instance = new AiAssistant();
                }
            }
        }
        return instance;
    }

    private AiAssistant() {
    }

    /**
     * Проверяет, задан ли API-ключ.
     */
    public boolean isConfigured() {
        return !TextUtils.isEmpty(API_KEY);
    }

    /**
     * Отправляет запрос к OpenRouter и возвращает ответ.
     *
     * @param userMessage сообщение пользователя
     * @param callback    вызывается с ответом (на UI-потоке) или ошибкой
     */
    public void sendMessage(String userMessage, AiCallback callback) {
        if (!isConfigured()) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onError("API ключ не задан"));
            }
            return;
        }

        new Thread(() -> {
            try {
                String response = callOpenRouter(userMessage);
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.onResponse(response));
                }
            } catch (Exception e) {
                FileLog.e("AiAssistant", e);
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "Ошибка сети"));
                }
            }
        }).start();
    }

    private String callOpenRouter(String userMessage) throws Exception {
        URL url = new URL(OPENROUTER_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("HTTP-Referer", "https://mgla.app");
        conn.setRequestProperty("X-Title", "Mgla");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        String jsonBody = "{"
            + "\"model\":\"" + MODEL + "\","
            + "\"messages\":["
            +     "{\"role\":\"system\",\"content\":\"Ты — полезный AI-ассистент в мессенджере Mgla. Отвечай кратко, на русском языке.\"},"
            +     "{\"role\":\"user\",\"content\":\"" + escapeJson(userMessage) + "\"}"
            + "],"
            + "\"max_tokens\":1024"
            + "}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        if (code == 200) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return parseResponse(sb.toString());
        } else {
            StringBuilder err = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    err.append(line);
                }
            }
            throw new Exception("OpenRouter ошибка " + code + ": " + err.toString());
        }
    }

    private String parseResponse(String json) {
        try {
            // Простой парсинг JSON без библиотек
            String key = "\"content\":\"";
            int start = json.indexOf(key);
            if (start < 0) {
                key = "\"content\": \"";
                start = json.indexOf(key);
            }
            if (start < 0) return json;
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
        } catch (Exception e) {
            FileLog.e(e);
            return json;
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public interface AiCallback {
        void onResponse(String text);
        void onError(String error);
    }
}
