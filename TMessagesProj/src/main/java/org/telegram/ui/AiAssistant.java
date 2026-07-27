package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * AI assistant powered by OpenRouter.
 * Set your API key in {@link #API_KEY} before use.
 */
public class AiAssistant {

    private static final String API_KEY = "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";

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String[] MODELS = {
        "deepseek/deepseek-v4-flash",
        "openrouter/owl-alpha",
        "google/gemma-4-26b-a4b-it:free"
    };

    public static final int DAILY_LIMIT = 10;
    private static final String PREFS_NAME = "mgla_config";
    private static final String KEY_DATE = "ai_editor_date";
    private static final String KEY_COUNT = "ai_editor_count";
    private static final String KEY_LAST_REQUEST = "ai_last_request";

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

    public static String getProvider() {
        return prefs().getString("ai_provider", "openrouter");
    }

    /**
     * Проверяет, задан ли API-ключ.
     */
    public boolean isConfigured() {
        if ("gemini".equals(getProvider())) {
            String apiKey = prefs().getString("ai_transcribe_api_key", "");
            return !TextUtils.isEmpty(apiKey);
        }
        return !TextUtils.isEmpty(API_KEY);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** Сколько запросов уже сделано сегодня (0 после сброса). */
    public static int getUsedToday() {
        SharedPreferences prefs = prefs();
        String today = todayKey();
        if (!today.equals(prefs.getString(KEY_DATE, ""))) {
            return 0;
        }
        return prefs.getInt(KEY_COUNT, 0);
    }

    public static String getUsageLabel() {
        if ("gemini".equals(getProvider())) {
            return "∞";
        }
        return getUsedToday() + " / " + DAILY_LIMIT;
    }

    public static String getResetInLabel() {
        Calendar now = Calendar.getInstance();
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        long diffMs = midnight.getTimeInMillis() - now.getTimeInMillis();
        long hours = diffMs / (1000 * 60 * 60);
        long minutes = (diffMs / (1000 * 60)) % 60;
        return hours + " ч " + minutes + " мин";
    }

    /**
     * Учитывает запрос в дневном лимите. Возвращает false, если лимит исчерпан.
     */
    private synchronized boolean consumeDailyQuota() {
        SharedPreferences prefs = prefs();
        String today = todayKey();
        String savedDate = prefs.getString(KEY_DATE, "");
        int count;
        if (!today.equals(savedDate)) {
            prefs.edit().putString(KEY_DATE, today).putInt(KEY_COUNT, 1).apply();
            return true;
        }
        count = prefs.getInt(KEY_COUNT, 0);
        if (count >= DAILY_LIMIT) {
            return false;
        }
        prefs.edit().putInt(KEY_COUNT, count + 1).apply();
        return true;
    }

    /**
     * Отправляет запрос к OpenRouter и возвращает ответ.
     * Любой вызов учитывается в дневном лимите ({@link #DAILY_LIMIT}).
     *
     * @param userMessage сообщение пользователя
     * @param callback    вызывается с ответом (на UI-потоке) или ошибкой
     */
    public void sendMessage(String userMessage, AiCallback callback) {
        if (!isConfigured()) {
            if (callback != null) {
                String errorMsg = "gemini".equals(getProvider()) ? "API ключ Gemini не задан в разделе ИИ-расшифровка" : "API ключ не задан";
                AndroidUtilities.runOnUIThread(() -> callback.onError(errorMsg));
            }
            return;
        }

        SharedPreferences prefs = prefs();
        if (!"gemini".equals(getProvider())) {
            long now = System.currentTimeMillis();
            long last = prefs.getLong(KEY_LAST_REQUEST, 0);
            if (now - last < 10_000) {
                long remain = 10 - (now - last) / 1000;
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Подождите " + remain + " сек."));
                }
                return;
            }

            if (!consumeDailyQuota()) {
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() ->
                        callback.onError("Лимит " + DAILY_LIMIT + " запросов в день исчерпан. До сброса: " + getResetInLabel()));
                }
                return;
            }

            prefs.edit().putLong(KEY_LAST_REQUEST, now).apply();
        }

        new Thread(() -> {
            try {
                String response;
                if ("gemini".equals(getProvider())) {
                    response = callGemini(userMessage);
                } else {
                    response = callOpenRouter(userMessage);
                }
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

    private String getSystemPrompt() {
        String langName = "Russian";
        try {
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            if (info != null && !TextUtils.isEmpty(info.name)) {
                langName = info.name + (!TextUtils.isEmpty(info.nameEnglish) ? " (" + info.nameEnglish + ")" : "");
            } else if (LocaleController.getInstance().getCurrentLocale() != null) {
                langName = LocaleController.getInstance().getCurrentLocale().getDisplayName(Locale.ENGLISH);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "Ты — инструмент обработки текста. Каждый запрос НЕЗАВИСИМЫЙ. НЕТ истории. НЕТ памяти. НЕ здоровайся. НЕ прощайся. НЕ комментируй. НЕ упоминай предыдущие запросы. ТОЛЬКО результат. ВАЖНО: Всегда отвечай на языке, который установлен в настройках клиента по умолчанию (" + langName + "), если в самом запросе явно не требуется перевод на другой язык.";
    }

    private String callOpenRouter(String userMessage) throws Exception {
        long seed = System.nanoTime() ^ (long)(Math.random() * Long.MAX_VALUE);
        String systemPrompt = getSystemPrompt();
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

    private String callGemini(String userMessage) throws Exception {
        SharedPreferences prefs = prefs();
        String apiKey = prefs.getString("ai_transcribe_api_key", "");
        String model = prefs.getString("ai_transcribe_model", "gemini-2.0-flash");
        if (TextUtils.isEmpty(apiKey)) {
            throw new Exception("API ключ Gemini не задан в разделе ИИ-расшифровка");
        }

        String systemPrompt = getSystemPrompt();
        String fullPrompt = systemPrompt + "\n\n" + userMessage;

        String jsonBody = "{"
            + "\"contents\":[{"
            +   "\"parts\":[{\"text\":\"" + escapeJson(fullPrompt) + "\"}]"
            + "}],"
            + "\"generationConfig\":{\"temperature\":0.3,\"maxOutputTokens\":1024}"
            + "}";

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

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
            return parseGeminiResponse(sb.toString());
        } else {
            StringBuilder err = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    err.append(line);
                }
            }
            throw new Exception("Gemini ошибка " + code + " (" + model + "): " + err.toString());
        }
    }

    private String parseGeminiResponse(String json) {
        try {
            String key = "\"text\": \"";
            int start = json.indexOf(key);
            if (start < 0) {
                key = "\"text\":\"";
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

    public interface AiCallback {
        void onResponse(String text);
        void onError(String error);
    }
}
