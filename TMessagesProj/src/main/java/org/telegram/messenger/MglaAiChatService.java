package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conversation transport.  OpenRouter is consumed as SSE, therefore callers receive
 * tokens as they arrive instead of waiting for a whole answer.  One request owns one
 * cancellable object; this is important when a fragment is destroyed or the user taps stop.
 */
public final class MglaAiChatService {
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_LOCAL = "local";
    public static final String DEFAULT_MODEL = "nvidia/nemotron-3-super-120b-a12b:free";
    private static final String URL_CHAT = "https://openrouter.ai/api/v1/chat/completions";

    public interface Callback {
        void onDelta(String text);
        void onComplete();
        void onError(String message);
    }

    public static final class Request {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpURLConnection connection;
        public void cancel() {
            cancelled.set(true);
            HttpURLConnection c = connection;
            if (c != null) c.disconnect();
        }
        boolean isCancelled() { return cancelled.get(); }
    }

    private MglaAiChatService() { }

    public static Request send(String provider, String model, ArrayList<MglaAiChatStorage.Message> messages, ArrayList<String> imagePaths, float temperature, int maxTokens, Callback callback) {
        Request request = new Request();
        new Thread(() -> {
            try {
                if (PROVIDER_LOCAL.equals(provider)) {
                    // A local model is loaded lazily by the native runtime, never while scrolling the chat.
                    if (imagePaths != null && !imagePaths.isEmpty()) {
                        throw new Exception("Выбранная локальная GGUF-модель не поддерживает изображения. Выберите vision-модель в OpenRouter.");
                    }
                    MglaLocalChatRuntime.stream(messages, temperature, maxTokens, request, callback);
                } else {
                    streamOpenRouter(model, messages, imagePaths, temperature, maxTokens, request, callback);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                if (!request.isCancelled()) uiError(callback, readableError(e));
            }
        }, "MglaAiChat").start();
        return request;
    }

    private static void streamOpenRouter(String model, ArrayList<MglaAiChatStorage.Message> messages, ArrayList<String> imagePaths, float temperature, int maxTokens, Request request, Callback callback) throws Exception {
        if (TextUtils.isEmpty(BuildConfig.OPENROUTER_API_KEY)) {
            if (MglaHubHttp.isConfigured()) {
                StringBuilder transcript = new StringBuilder("Продолжай диалог, учитывая предыдущие сообщения.\n\n");
                for (MglaAiChatStorage.Message m : messages) transcript.append("assistant".equals(m.role) ? "Ассистент: " : "Пользователь: ").append(m.text).append('\n');
                org.telegram.ui.AiAssistant.getInstance().sendMessage(transcript.toString(), new org.telegram.ui.AiAssistant.AiCallback() {
                    @Override public void onResponse(String text) {
                        if (request.isCancelled()) return;
                        long delay = 0;
                        for (int i = 0; i < text.length(); i += 18) {
                            final String part = text.substring(i, Math.min(text.length(), i + 18));
                            AndroidUtilities.runOnUIThread(() -> { if (!request.isCancelled()) callback.onDelta(part); }, delay);
                            delay += 18;
                        }
                        final long completeDelay = delay;
                        if (!request.isCancelled()) AndroidUtilities.runOnUIThread(() -> { if (!request.isCancelled()) callback.onComplete(); }, completeDelay);
                    }
                    @Override public void onError(String error) { if (!request.isCancelled()) uiError(callback, error); }
                });
                return;
            }
            throw new Exception("Для чата OpenRouter нужен ключ в конфигурации сборки");
        }
        JSONObject body = new JSONObject();
        body.put("model", TextUtils.isEmpty(model) ? DEFAULT_MODEL : model);
        body.put("stream", true);
        body.put("temperature", Math.max(0f, Math.min(2f, temperature)));
        body.put("max_tokens", Math.max(32, Math.min(4096, maxTokens)));
        JSONArray list = new JSONArray();
        int lastUserMessage = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role)) { lastUserMessage = i; break; }
        }
        for (int i = 0; i < messages.size(); i++) {
            MglaAiChatStorage.Message m = messages.get(i);
            if (!TextUtils.isEmpty(m.text)) {
                JSONObject item = new JSONObject(); item.put("role", m.role);
                if (i == lastUserMessage && imagePaths != null && !imagePaths.isEmpty()) {
                    JSONArray content = new JSONArray();
                    content.put(new JSONObject().put("type", "text").put("text", m.text));
                    for (String path : imagePaths) {
                        content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", imageDataUrl(path))));
                    }
                    item.put("content", content);
                } else {
                    item.put("content", m.text);
                }
                list.put(item);
            }
        }
        body.put("messages", list);

        HttpURLConnection conn = (HttpURLConnection) new URL(URL_CHAT).openConnection();
        request.connection = conn;
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.OPENROUTER_API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("HTTP-Referer", "https://mgla.app");
        conn.setRequestProperty("X-Title", "Mgla");
        try (OutputStream out = conn.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (code >= 400) throw new Exception("OpenRouter: HTTP " + code + " — " + readLimited(stream, 1200));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while (!request.isCancelled() && (line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                try {
                    JSONObject json = new JSONObject(data);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.optJSONObject(0).optJSONObject("delta");
                    String token = delta == null ? null : delta.optString("content", null);
                    if (!TextUtils.isEmpty(token)) AndroidUtilities.runOnUIThread(() -> callback.onDelta(token));
                } catch (Throwable ignored) {
                    // Providers occasionally send non-content SSE events; they are harmless.
                }
            }
        } finally { conn.disconnect(); request.connection = null; }
        if (!request.isCancelled()) AndroidUtilities.runOnUIThread(callback::onComplete);
    }

    private static String readLimited(InputStream input, int max) {
        if (input == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] b = new char[max]; int n = r.read(b); return n > 0 ? new String(b, 0, n) : "";
        } catch (Throwable ignore) { return ""; }
    }

    /** Bounded conversion prevents a few camera originals from exhausting the app heap. */
    private static String imageDataUrl(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) throw new Exception("Не удалось прочитать выбранное изображение");
        if (file.length() > 6L * 1024L * 1024L) throw new Exception("Изображение больше 6 МБ. Выберите файл поменьше.");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != bytes.length) throw new Exception("Не удалось прочитать выбранное изображение");
        }
        String lower = file.getName().toLowerCase();
        String mime = lower.endsWith(".png") ? "image/png" : lower.endsWith(".webp") ? "image/webp" : "image/jpeg";
        return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static void uiError(Callback callback, String message) { AndroidUtilities.runOnUIThread(() -> callback.onError(message)); }
    private static String readableError(Throwable e) { return !TextUtils.isEmpty(e.getMessage()) ? e.getMessage() : "Не удалось получить ответ"; }
}
