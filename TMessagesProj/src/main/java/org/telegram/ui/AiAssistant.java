package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
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
    private static final String[] MODELS = {
        "nex-agi/nex-n2-pro:free",
        "openrouter/owl-alpha",
        "google/gemma-4-26b-a4b-it:free"
    };

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

        // Anti-spam: 10-second cooldown
        SharedPreferences prefs = ApplicationLoader.applicationContext
            .getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong("ai_last_request", 0);
        if (now - last < 10_000) {
            long remain = 10 - (now - last) / 1000;
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onError("Подождите " + remain + " сек."));
            }
            return;
        }
        prefs.edit().putLong("ai_last_request", now).apply();

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
        long seed = System.nanoTime() ^ (long)(Math.random() * Long.MAX_VALUE);
        String systemPrompt = "Ты — инструмент обработки текста. Каждый запрос НЕЗАВИСИМЫЙ. НЕТ истории. НЕТ памяти. НЕ здоровайся. НЕ прощайся. НЕ комментируй. НЕ упоминай предыдущие запросы. ТОЛЬКО результат.";
        String userContent = escapeJson(userMessage);

        Exception lastException = null;

        for (int i = 0; i < MODELS.length; i++) {
            String model = MODELS[i];
            try {
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
                    + "\"model\":\"" + model + "\","
                    + "\"messages\":["
                    +     "{\"role\":\"system\",\"content\":\"" + systemPrompt + "\"},"
                    +     "{\"role\":\"user\",\"content\":\"" + userContent + "\"}"
                    + "],"
                    + "\"temperature\":0.3,"
                    + "\"max_tokens\":1024,"
                    + "\"seed\":" + seed
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
                    lastException = new Exception("OpenRouter ошибка " + code + " (" + model + "): " + err.toString());
                    // Если 404 или модель недоступна — пробуем следующую
                    if (code == 404 || code == 429) {
                        continue;
                    }
                    throw lastException;
                }
            } catch (Exception e) {
                lastException = e;
                // Пробуем следующую модель
                if (i < MODELS.length - 1) {
                    continue;
                }
            }
        }

        throw lastException != null ? lastException : new Exception("Все модели недоступны");
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
