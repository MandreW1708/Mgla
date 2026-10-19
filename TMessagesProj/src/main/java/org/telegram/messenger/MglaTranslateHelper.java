package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.Components.TranslateAlert2;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Chat auto-translate backends for Mgla (Google gtx + Yandex public API).
 * Results are applied through the stock {@link TranslateController} message fields.
 */
public final class MglaTranslateHelper {

    public interface Callback {
        /** @param result translated text, or null on failure */
        void onDone(String result, boolean rateLimit);
    }

    private MglaTranslateHelper() {
    }

    public static void translate(String text, String fromLang, String toLang, Callback done) {
        if (done == null) {
            return;
        }
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(toLang)) {
            AndroidUtilities.runOnUIThread(() -> done.onDone(null, false));
            return;
        }
        String provider = MglaChatsConfig.getAutoTranslateProvider();
        if (MglaChatsConfig.PROVIDER_YANDEX.equals(provider)) {
            translateYandex(text, fromLang, toLang, done);
        } else {
            // Reuse Telegram's Google gtx client.
            TranslateAlert2.alternativeTranslate(text, fromLang, toLang, (result, rateLimit) ->
                    done.onDone(result, rateLimit));
        }
    }

    private static void translateYandex(String text, String fromLang, String toLang, Callback done) {
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection connection = null;
            try {
                String lang = TextUtils.isEmpty(fromLang) || "und".equals(fromLang)
                        ? toLang
                        : (fromLang + "-" + toLang);
                String body = "text=" + URLEncoder.encode(text, "UTF-8")
                        + "&lang=" + URLEncoder.encode(lang, "UTF-8")
                        + "&srv=android";
                URL url = new URL("https://translate.yandex.net/api/v1/tr.json/translate");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = connection.getResponseCode();
                if (code == 429) {
                    AndroidUtilities.runOnUIThread(() -> done.onDone(null, true));
                    return;
                }
                if (code < 200 || code >= 300) {
                    AndroidUtilities.runOnUIThread(() -> done.onDone(null, false));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                try (Reader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    int c;
                    while ((c = reader.read()) != -1) {
                        sb.append((char) c);
                    }
                }
                JSONObject json = new JSONObject(sb.toString());
                JSONArray arr = json.optJSONArray("text");
                String result = null;
                if (arr != null && arr.length() > 0) {
                    StringBuilder out = new StringBuilder();
                    for (int i = 0; i < arr.length(); i++) {
                        if (i > 0) {
                            out.append('\n');
                        }
                        out.append(arr.optString(i, ""));
                    }
                    result = out.toString();
                }
                final String finalResult = result;
                AndroidUtilities.runOnUIThread(() -> done.onDone(finalResult, false));
            } catch (Throwable e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> done.onDone(null, false));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }
}
