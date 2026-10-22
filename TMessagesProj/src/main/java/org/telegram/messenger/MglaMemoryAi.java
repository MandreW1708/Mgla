package org.telegram.messenger;

import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Mgla: транспорт «Живой памяти» к облачным LLM.
 *
 * Отдельный от {@link org.telegram.ui.AiAssistant} канал со своим дневным лимитом
 * (см. {@link MglaMemoryConfig#AI_DAILY_LIMIT}), чтобы фоновый анализ переписок
 * не съедал квоту чат-ассистента. Провайдер выбирается настройкой памяти:
 * Gemini (ключ пользователя) → OpenRouter (ключ сборки или хаб) → недоступно.
 *
 * Все методы синхронные — вызывать с фонового потока. При ошибке/отсутствии
 * провайдера возвращают {@code null} (вызывающий код переходит на локальную
 * эвристику).
 */
public class MglaMemoryAi {

    private static final String[] OPENROUTER_MODELS = {
        "nvidia/nemotron-3-super-120b-a12b:free",
        "nvidia/nemotron-3-ultra-550b-a55b:free",
        "nvidia/nemotron-3.5-lightning:free",
        "google/gemma-4-31b-it:free"
    };

    private MglaMemoryAi() {
    }

    /**
     * Извлекает факты из батча сообщений.
     * @return строки {@code TYPE|WHO|STATUS|текст} или null.
     */
    public static String extractFacts(String batchText) {
        String system = "Ты — архивариус переписки. Извлеки из диалога только то, что прямо сказано: "
            + "решения, обещания, задачи, открытые вопросы, события, идеи, темы и проверяемые факты. "
            + "Отвечай СТРОГО по одному факту на строку в формате: TYPE|WHO|STATUS|текст факта. "
            + "TYPE: decision | promise | plan | topic | event | question | idea | fact. "
            + "WHO: имя автора из сообщений (ровно как в тексте) или «я» для сообщений помеченных (я). "
            + "STATUS: open | done | unknown. "
            + "decision — только если решение уже принято («решили», «договорились»). "
            + "Слова «может», «возможно», «наверное», «кажется», «думаю» — это idea или question, никогда не decision. "
            + "fact — только утверждение о том, что уже есть (цифра, название, состояние). "
            + "topic — краткое обобщённое название темы из 3–6 слов (например «Обновления сервера Mgla»), "
            + "не цитата сообщения и не одно случайное слово. "
            + "Не выдумывай. Без пояснений и markdown. Максимум 12 строк. Если фактов нет — ответь пустотой.";
        return call(system, batchText, 1024, 0.2);
    }

    /**
     * Отвечает на вопрос пользователя по выписке из памяти.
     * @return готовый ответ или null.
     */
    public static String answerQuestion(String question, String memoryExcerpt) {
        String system = "Ты — память переписок пользователя. Ответь на вопрос, опираясь ТОЛЬКО на приведённую "
            + "выписку из памяти. Кратко: сначала суть, потом решения и то, что ещё открыто. "
            + "Указывай даты. Если выписка не содержит ответа — прямо скажи, что точного ответа нет, и ничего не додумывай. "
            + "Записи с пометкой «предположение» не называй решениями. "
            + "Не утверждай, что обещание не выполнено, если в выписке нет подтверждения. "
            + "Без markdown-заголовков, списки можно.";
        return call(system, "Вопрос пользователя: " + question + "\n\nВыписка из памяти:\n" + memoryExcerpt, 1024, 0.3);
    }

    /** Общий вызов с учётом провайдера и дневной квоты. */
    private static String call(String systemPrompt, String userText, int maxTokens, double temperature) {
        String provider = MglaMemoryConfig.getProvider();
        try {
            if (MglaMemoryConfig.PROVIDER_GEMINI.equals(provider)) {
                if (TextUtils.isEmpty(MglaMemoryConfig.prefs().getString("ai_transcribe_api_key", ""))) {
                    return null;
                }
                if (!MglaMemoryConfig.consumeAiQuota()) {
                    return null;
                }
                return callGemini(systemPrompt, userText, maxTokens, temperature);
            }
            if (MglaMemoryConfig.PROVIDER_OPENROUTER.equals(provider)) {
                if (!MglaHubHttp.isConfigured() && TextUtils.isEmpty(BuildConfig.OPENROUTER_API_KEY)) {
                    return null;
                }
                if (!MglaMemoryConfig.consumeAiQuota()) {
                    return null;
                }
                return callOpenRouter(systemPrompt, userText, maxTokens, temperature);
            }
            // auto: Gemini → OpenRouter
            if (!TextUtils.isEmpty(MglaMemoryConfig.prefs().getString("ai_transcribe_api_key", ""))) {
                if (!MglaMemoryConfig.consumeAiQuota()) {
                    return null;
                }
                String r = callGemini(systemPrompt, userText, maxTokens, temperature);
                if (r != null) {
                    return r;
                }
            }
            if (MglaHubHttp.isConfigured() || !TextUtils.isEmpty(BuildConfig.OPENROUTER_API_KEY)) {
                if (!MglaMemoryConfig.consumeAiQuota()) {
                    return null;
                }
                return callOpenRouter(systemPrompt, userText, maxTokens, temperature);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    // ——— Gemini ———

    private static String callGemini(String systemPrompt, String userText, int maxTokens, double temperature) throws Exception {
        String apiKey = MglaMemoryConfig.prefs().getString("ai_transcribe_api_key", "");
        String model = MglaMemoryConfig.prefs().getString("ai_transcribe_model", "gemini-2.0-flash");
        String fullPrompt = systemPrompt + "\n\n" + userText;
        String text;
        if (MglaHubHttp.shouldProxyAi()) {
            String hubJson = "{\"api_key\":\"" + MglaChatDna.escapeJson(apiKey) + "\",\"model\":\""
                + MglaChatDna.escapeJson(model) + "\",\"text\":\"" + MglaChatDna.escapeJson(fullPrompt) + "\"}";
            MglaHubHttp.Response response = MglaHubHttp.post(
                "/mgla-ai/v1/gemini", hubJson.getBytes(StandardCharsets.UTF_8), 30_000, 60_000);
            if (response.code != 200) {
                return null;
            }
            text = MglaHubHttp.extractJsonStringField(response.body, "content");
        } else {
            String json = "{\"contents\":[{\"parts\":[{\"text\":\""
                + MglaChatDna.escapeJson(fullPrompt) + "\"}]}],"
                + "\"generationConfig\":{\"temperature\":" + temperature + ",\"maxOutputTokens\":" + maxTokens + "}}";
            HttpURLConnection conn = MglaDirectHttp.open(
                "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + apiKey);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    body.append(line);
                }
            }
            text = MglaChatDna.extractText(body.toString());
        }
        return TextUtils.isEmpty(text) ? null : text.trim();
    }

    // ——— OpenRouter ———

    private static String callOpenRouter(String systemPrompt, String userText, int maxTokens, double temperature) throws Exception {
        // Bypass → relay; no bypass → mglahub (MglaHubHttp routes). Same Free utility key.
        if (MglaHubHttp.isConfigured()) {
            String lang = "ru";
            try {
                lang = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo().shortName;
            } catch (Throwable ignore) {
            }
            // Always Free catalog + Free OpenRouter key — never Plus/Ultra billing.
            StringBuilder json = new StringBuilder(256);
            json.append("{\"message\":\"").append(MglaChatDna.escapeJson(systemPrompt + "\n\n" + userText))
                    .append("\",\"lang\":\"").append(MglaChatDna.escapeJson(lang))
                    .append("\",\"plan\":\"free\",\"utility\":true");
            try {
                long tgId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
                if (tgId > 0) {
                    json.append(",\"tg_id\":").append(tgId);
                }
            } catch (Throwable ignored) {
            }
            json.append('}');
            MglaHubHttp.Response response = MglaHubHttp.post(
                "/mgla-ai/v1/chat", json.toString().getBytes(StandardCharsets.UTF_8), 30_000, 90_000);
            if (response.code != 200) {
                return null;
            }
            String content = MglaHubHttp.extractJsonStringField(response.body, "content");
            return TextUtils.isEmpty(content) ? null : content.trim();
        }
        Exception lastException = null;
        for (int i = 0; i < OPENROUTER_MODELS.length; i++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://openrouter.ai/api/v1/chat/completions").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.OPENROUTER_API_KEY);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("HTTP-Referer", "https://mgla.app");
                conn.setRequestProperty("X-Title", "Mgla");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                String jsonBody = "{"
                    + "\"model\":\"" + OPENROUTER_MODELS[i] + "\","
                    + "\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"" + MglaChatDna.escapeJson(systemPrompt) + "\"},"
                    + "{\"role\":\"user\",\"content\":\"" + MglaChatDna.escapeJson(userText) + "\"}"
                    + "],"
                    + "\"temperature\":" + temperature + ","
                    + "\"max_tokens\":" + maxTokens
                    + "}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
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
                    return parseChatCompletion(sb.toString());
                }
                lastException = new Exception("OpenRouter HTTP " + code);
                if (code == 404 || code == 429) {
                    continue;
                }
                return null;
            } catch (Exception e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return null;
    }

    /** Достаёт content первого choice из ответа chat/completions (без JSON-библиотек). */
    private static String parseChatCompletion(String json) {
        String key = "\"content\": \"";
        int start = json.indexOf(key);
        if (start < 0) {
            key = "\"content\":\"";
            start = json.indexOf(key);
        }
        if (start < 0) {
            return null;
        }
        start += key.length();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                if (c == 'n') {
                    sb.append('\n');
                } else if (c == '"') {
                    sb.append('"');
                } else if (c == '\\') {
                    sb.append('\\');
                } else {
                    sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
