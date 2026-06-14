package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class GeminiTranscriber {

    public static void transcribe(MessageObject messageObject, int account, Callback callback) {
        if (messageObject == null || messageObject.messageOwner == null) return;

        Context ctx = org.telegram.messenger.ApplicationLoader.applicationContext;
        SharedPreferences prefs = ctx.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
        String apiKey = prefs.getString("ai_transcribe_api_key", "");
        String model = prefs.getString("ai_transcribe_model", "gemini-2.0-flash");

        if (TextUtils.isEmpty(apiKey)) {
            notifyError(callback, "Не задан API ключ Gemini");
            return;
        }

        File audioFile = getAudioFile(messageObject);
        if (audioFile == null || !audioFile.exists()) {
            notifyError(callback, "Файл не найден");
            return;
        }

        new Thread(() -> {
            try {
                String text = callGemini(apiKey, model, audioFile);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) {
                        callback.onSuccess(text);
                    }
                });
            } catch (Exception e) {
                FileLog.e("GeminiTranscriber", e);
                notifyError(callback, e.getMessage() != null ? e.getMessage() : "Ошибка расшифровки");
            }
        }).start();
    }

    private static void notifyError(Callback callback, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (callback != null) {
                callback.onError(error);
            }
        });
    }

    private static File getAudioFile(MessageObject msg) {
        File file = null;
        if (!TextUtils.isEmpty(msg.messageOwner.attachPath)) {
            file = new File(msg.messageOwner.attachPath);
        }
        if (file == null || !file.exists()) {
            file = FileLoader.getInstance(msg.currentAccount).getPathToMessage(msg.messageOwner);
        }
        return file;
    }

    private static String callGemini(String apiKey, String model, File audioFile) throws Exception {
        // Read file as base64
        byte[] audioBytes = new byte[(int) audioFile.length()];
        try (FileInputStream fis = new FileInputStream(audioFile)) {
            fis.read(audioBytes);
        }
        String base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP);

        // Detect MIME type: video messages are MP4, voice messages are OGG/OPUS
        String fileName = audioFile.getName().toLowerCase();
        String mimeType;
        if (fileName.endsWith(".mp4") || fileName.endsWith(".m4v")) {
            mimeType = "video/mp4";
        } else if (fileName.endsWith(".ogg") || fileName.endsWith(".opus")) {
            mimeType = "audio/ogg";
        } else if (fileName.endsWith(".mp3")) {
            mimeType = "audio/mp3";
        } else {
            mimeType = "audio/ogg"; // fallback
        }

        boolean isVideo = mimeType.startsWith("video");
        String jsonBody = "{"
            + "\"contents\":[{"
            +   "\"parts\":["
            +     "{\"text\":\"Transcribe the following " + (isVideo ? "video" : "audio") + " accurately. Return ONLY the transcribed text in Russian, no additional commentary.\"},"
            +     "{\"inline_data\":{\"mime_type\":\"" + mimeType + "\",\"data\":\"" + base64Audio + "\"}}"
            +   "]"
            + "}],"
            + "\"generationConfig\":{\"temperature\":0.1,\"maxOutputTokens\":1024}"
            + "}";

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        if (code == 200) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return parseGeminiResponse(sb.toString());
        } else {
            StringBuilder err = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) err.append(line);
            }
            throw new Exception("Gemini HTTP " + code + ": " + err.toString());
        }
    }

    private static String parseGeminiResponse(String json) {
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
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        } catch (Exception e) {
            return json;
        }
    }

    public interface Callback {
        void onSuccess(String text);
        void onError(String error);
    }
}
