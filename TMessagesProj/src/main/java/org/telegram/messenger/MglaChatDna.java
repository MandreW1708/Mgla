package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Mgla: Chat DNA — живой визуальный профиль разговора.
 * Собирает статистику по чату из локальной БД MessagesStorage и генерирует
 * «главные темы» через Gemini (API пользователя) или локальную модель.
 */
public class MglaChatDna {

    public static final String PREFS = "mgla_config";
    public static final String PREF_TOPICS_PROVIDER = "dna_topics_provider";
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_LOCAL = "local";
    public static final String PREF_LOCAL_ENGINE = "dna_local_engine";
    public static final String LOCAL_ENGINE_DICTIONARY = "dictionary";
    public static final String LOCAL_ENGINE_GGUF = "gguf";

    /** Собранная статистика чата. */
    public static class Stats {
        public int totalMessages;
        public int reactions;
        public int photos;
        public int voice;
        public int links;
        public int[] hourly = new int[24];
        public int avgWords;
        public int typicalReplySeconds;
        public String mostActiveTodayName;
        public int todayCount;
        public int usualDailyCount;
        public int peakHourFrom;
        public int peakHourTo;
        public String recentFocus;
    }

    public interface Callback {
        /** Статистика собрана — приходит до тем, пока модель думает. */
        void onStats(Stats stats);

        void onResult(Stats stats, ArrayList<String> topics, String provider);
    }

    /** Прогресс нативной генерации: generated < 0 — загрузка модели. */
    public interface ProgressSink {
        /** textSoFar — накопленный текст генерации (null при загрузке модели). */
        void onProgress(int generated, int maxTokens, String textSoFar);
    }

    /** Активный приёмник прогресса, пока открыт экран Chat DNA. */
    public static volatile ProgressSink progressSink;

    interface TopicsCallback {
        void onDone(ArrayList<String> topics, String provider);
    }

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getTopicsProvider() {
        return prefs().getString(PREF_TOPICS_PROVIDER, PROVIDER_LOCAL);
    }

    public static void setTopicsProvider(String provider) {
        prefs().edit().putString(PREF_TOPICS_PROVIDER, provider).apply();
    }

    public static String getTopicsProviderTitle() {
        return PROVIDER_GEMINI.equals(getTopicsProvider()) ? "Gemini (Ваш API)" : "Локальная модель";
    }

    public static String getLocalEngine() {
        return prefs().getString(PREF_LOCAL_ENGINE, LOCAL_ENGINE_DICTIONARY);
    }

    public static void setLocalEngine(String engine) {
        prefs().edit().putString(PREF_LOCAL_ENGINE,
            LOCAL_ENGINE_GGUF.equals(engine) ? LOCAL_ENGINE_GGUF : LOCAL_ENGINE_DICTIONARY).apply();
    }

    public static boolean isGgufRuntimeSelected() {
        return LOCAL_ENGINE_GGUF.equals(getLocalEngine());
    }

    /** Запускает сбор статистики и генерацию тем. Статистика приходит в UI-потоке
        первой; темы — когда провайдер их сгенерирует (локальная модель думает
        заметное время). */
    public static void collect(int accountId, long dialogId, long periodStartSec, long periodEndSec, Callback callback) {
        MessagesStorage storage = MessagesStorage.getInstance(accountId);
        storage.getStorageQueue().postRunnable(() -> {
            Stats stats;
            try {
                stats = collectStats(accountId, dialogId, periodStartSec, periodEndSec);
            } catch (Throwable e) {
                FileLog.e(e);
                stats = new Stats();
            }
            final Stats result = stats;
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onStats(result));
            }
            generateTopics(accountId, dialogId, periodStartSec, periodEndSec, (topics, provider) -> {
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(result, topics, provider));
                }
            });
        });
    }

    private static Stats collectStats(int accountId, long dialogId, long periodStartSec, long periodEndSec) throws Exception {
        final String bounds = " AND date >= " + (int) periodStartSec + " AND date <= " + (int) periodEndSec;
        Stats stats = new Stats();
        MessagesStorage storage = MessagesStorage.getInstance(accountId);
        SQLiteDatabase db = storage.getDatabase();
        if (db == null) {
            return stats;
        }

        int todayStart = (int) (System.currentTimeMillis() / 1000);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        int todayStartSec = (int) (cal.getTimeInMillis() / 1000);
        int monthAgoSec = todayStartSec - 30 * 24 * 3600;

        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT COUNT(*) FROM messages_v2 WHERE uid = " + dialogId + bounds);
            if (cursor.next()) {
                stats.totalMessages = cursor.intValue(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }

        try {
            cursor = db.queryFinalized("SELECT COUNT(*) FROM messages_v2 WHERE uid = " + dialogId + " AND date >= " + todayStartSec);
            if (cursor.next()) {
                stats.todayCount = cursor.intValue(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }

        try {
            cursor = db.queryFinalized("SELECT COUNT(*) FROM messages_v2 WHERE uid = " + dialogId + " AND date >= " + monthAgoSec);
            if (cursor.next()) {
                stats.usualDailyCount = Math.max(1, cursor.intValue(0) / 30);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }

        // Гистограмма активности по часам (с учётом локального смещения времени).
        int tzOffsetSec = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;
        try {
            cursor = db.queryFinalized(
                "SELECT (date + " + tzOffsetSec + ") % 86400 / 3600 AS h, COUNT(*) FROM messages_v2 WHERE uid = "
                    + dialogId + bounds + " GROUP BY h");
            while (cursor.next()) {
                int h = cursor.intValue(0);
                if (h >= 0 && h < 24) {
                    stats.hourly[h] = cursor.intValue(1);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }

        // Окно максимальной активности (4 часа).
        int bestSum = -1, bestStart = 0;
        for (int start = 0; start < 24; start++) {
            int sum = 0;
            for (int k = 0; k < 4; k++) {
                sum += stats.hourly[(start + k) % 24];
            }
            if (sum > bestSum) {
                bestSum = sum;
                bestStart = start;
            }
        }
        stats.peakHourFrom = bestStart;
        stats.peakHourTo = (bestStart + 3) % 24;

        // Развёрнутый разбор последних сообщений (ограничен по объёму).
        ArrayList<Integer> dates = new ArrayList<>();
        ArrayList<Integer> replyDiffs = new ArrayList<>();
        HashMap<Integer, Integer> midToDate = new HashMap<>();
        HashMap<Long, Integer> todayByUser = new HashMap<>();
        HashSet<String> recentWords = new HashSet<>();
        ArrayList<String> texts = new ArrayList<>();
        int wordSum = 0, wordCount = 0, linkCount = 0, photoCount = 0, voiceCount = 0, reactionCount = 0;
        int scanned = 0;
        try {
            cursor = db.queryFinalized(
                "SELECT date, out, data FROM messages_v2 WHERE uid = " + dialogId + bounds
                    + " ORDER BY date DESC LIMIT 2500");
            while (cursor.next()) {
                int date = cursor.intValue(0);
                scanned++;
                dates.add(date);
                NativeByteBuffer data = cursor.byteBufferValue(2);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = null;
                try {
                    message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                } catch (Throwable ignore) {
                }
                data.reuse();
                if (message == null) {
                    continue;
                }
                midToDate.put(message.id, message.date);
                if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
                    photoCount++;
                } else if (message.media instanceof TLRPC.TL_messageMediaDocument && message.media != null) {
                    TLRPC.TL_messageMediaDocument doc = (TLRPC.TL_messageMediaDocument) message.media;
                    if (doc.document != null && doc.document.attributes != null) {
                        for (TLRPC.DocumentAttribute attr : doc.document.attributes) {
                            if (attr instanceof TLRPC.TL_documentAttributeAudio
                                && ((TLRPC.TL_documentAttributeAudio) attr).voice) {
                                voiceCount++;
                            }
                        }
                    }
                }
                if (message.reactions != null && message.reactions.results != null) {
                    for (TLRPC.ReactionCount rc : message.reactions.results) {
                        reactionCount += Math.max(0, rc.count);
                    }
                }
                String text = message.message != null ? message.message : "";
                if (text.contains("http://") || text.contains("https://") || text.contains("t.me/")) {
                    linkCount++;
                }
                if (!TextUtils.isEmpty(text)) {
                    String[] words = text.trim().split("\\s+");
                    wordSum += words.length;
                    wordCount++;
                    texts.add(text);
                }
                if (message.reply_to != null && message.reply_to.reply_to_msg_id > 0) {
                    Integer targetDate = midToDate.get(message.reply_to.reply_to_msg_id);
                    if (targetDate != null) {
                        int diff = message.date - targetDate;
                        if (diff > 0 && diff < 3600) {
                            replyDiffs.add(diff);
                        }
                    }
                }
                if (message.date >= todayStartSec && message.from_id != null && message.from_id.user_id != 0) {
                    Long uid = message.from_id.user_id;
                    Integer cnt = todayByUser.get(uid);
                    todayByUser.put(uid, cnt == null ? 1 : cnt + 1);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }

        stats.photos = photoCount;
        stats.voice = voiceCount;
        stats.links = linkCount;
        stats.reactions = reactionCount;
        stats.avgWords = wordCount > 0 ? wordSum / wordCount : 0;
        if (!replyDiffs.isEmpty()) {
            Collections.sort(replyDiffs);
            stats.typicalReplySeconds = replyDiffs.get(replyDiffs.size() / 2);
        }

        if (!todayByUser.isEmpty()) {
            long bestUser = 0;
            int bestCount = 0;
            for (HashMap.Entry<Long, Integer> e : todayByUser.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    bestUser = e.getKey();
                }
            }
            if (bestUser != 0) {
                TLRPC.User user = MessagesController.getInstance(accountId).getUser(bestUser);
                stats.mostActiveTodayName = user != null ? UserObject.getFirstName(user, false) : null;
            }
        }

        // Тема последних 30 сообщений — по ключевым словам.
        int from = Math.min(30, texts.size());
        if (from > 0) {
            HashMap<String, Integer> freq = new HashMap<>();
            for (int i = 0; i < from; i++) {
                for (String w : tokenize(texts.get(i))) {
                    Integer c = freq.get(w);
                    freq.put(w, c == null ? 1 : c + 1);
                }
            }
            String best = null;
            int bestC = 0;
            for (HashMap.Entry<String, Integer> e : freq.entrySet()) {
                if (e.getValue() > bestC) {
                    bestC = e.getValue();
                    best = e.getKey();
                }
            }
            stats.recentFocus = best != null ? best.toLowerCase(Locale.ROOT) : null;
        }

        return stats;
    }

    /** Генерация «главных тем»: Gemini (API пользователя) или локальная модель. */
    private static void generateTopics(int accountId, long dialogId, long periodStartSec, long periodEndSec, TopicsCallback done) {
        String provider = getTopicsProvider();
        new Thread(() -> {
            ArrayList<String> topics;
            String actualSource;
            if (PROVIDER_GEMINI.equals(provider)) {
                topics = generateTopicsGemini(accountId, dialogId, periodStartSec, periodEndSec);
                if (topics == null || topics.isEmpty()) {
                    topics = generateTopicsDictionary(accountId, dialogId, periodStartSec, periodEndSec);
                    actualSource = "Словарь (Gemini недоступен)";
                } else {
                    actualSource = "Gemini (Ваш API)";
                }
            } else {
                if (isGgufRuntimeSelected()) {
                    String[] ggufError = new String[1];
                    topics = generateTopicsGguf(accountId, dialogId, periodStartSec, periodEndSec, ggufError);
                    if (topics == null || topics.isEmpty()) {
                        topics = generateTopicsDictionary(accountId, dialogId, periodStartSec, periodEndSec);
                        actualSource = "Словарь (GGUF недоступен: "
                            + (ggufError[0] != null ? ggufError[0] : "нет результата") + ")";
                    } else {
                        actualSource = "GGUF-рантайм: " + MglaLocalModelsManager.getSelectedModelId();
                    }
                } else {
                    topics = generateTopicsDictionary(accountId, dialogId, periodStartSec, periodEndSec);
                    actualSource = "Словарь";
                }
            }
            done.onDone(topics, actualSource);
        }).start();
    }

    private static ArrayList<String> generateTopicsGemini(int accountId, long dialogId, long periodStartSec, long periodEndSec) {
        try {
            SharedPreferences prefs = prefs();
            String apiKey = prefs.getString("ai_transcribe_api_key", "");
            String model = prefs.getString("ai_transcribe_model", "gemini-2.0-flash");
            if (TextUtils.isEmpty(apiKey)) {
                return null;
            }
            ArrayList<String> texts = loadRecentTexts(accountId, dialogId, 30, periodStartSec, periodEndSec);
            if (texts.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < texts.size(); i++) {
                sb.append(i + 1).append(". ").append(texts.get(i).replace('\n', ' ')).append('\n');
            }
            String prompt = "Ты — аналитик переписок. Вот сообщения из чата:\n\n" + sb
                + "\nВыдели 4 главные темы этого разговора, каждая тема — 1-3 слова. "
                + "Ответ строго в формате:\n1. тема\n2. тема\n3. тема\n4. тема";
            String json = "{\"contents\":[{\"parts\":[{\"text\":\"" + escapeJson(prompt) + "\"}]}],"
                + "\"generationConfig\":{\"temperature\":0.3,\"maxOutputTokens\":128}}";
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) body.append(line);
            }
            return parseTopicLines(extractText(body.toString()));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Резервная генерация тем по частотности слов (полностью офлайн). */
    public static ArrayList<String> generateTopicsDictionary(int accountId, long dialogId, long periodStartSec, long periodEndSec) {
        ArrayList<String> texts = loadRecentTexts(accountId, dialogId, 200, periodStartSec, periodEndSec);
        return MglaLocalAiEngine.topicsFromTexts(texts);
    }

    private static ArrayList<String> generateTopicsGguf(int accountId, long dialogId, long periodStartSec, long periodEndSec, String[] errorOut) {
        java.io.File model = MglaLocalModelsManager.getLoadedModel(ApplicationLoader.applicationContext);
        if (model == null) {
            setGgufError(errorOut, "модель не загружена");
            return null;
        }
        // Сообщения за период для кластеризации — их может быть в разы больше,
        // чем влезло бы в контекст модели: темы выделяем в Java, а модель
        // получает лишь компактные группы и называет их.
        ArrayList<String> texts = loadRecentTexts(accountId, dialogId, 500, periodStartSec, periodEndSec);
        if (texts.isEmpty()) {
            setGgufError(errorOut, "нет текстовых сообщений");
            return null;
        }
        ArrayList<TopicCluster> clusters = clusterTexts(texts);
        if (clusters.isEmpty()) {
            setGgufError(errorOut, "темы не выделились");
            return null;
        }
        String prompt = buildNamingPrompt(clusters);
        FileLog.d("MglaChatDna: GGUF naming promptChars=" + prompt.length() + " model=" + model.getName());
        final ProgressSink sink = progressSink;
        if (sink != null) {
            MglaGgufRuntime.setProgressListener((generated, max, text) ->
                AndroidUtilities.runOnUIThread(() -> sink.onProgress(generated, max, text)));
        }
        String raw;
        try {
            raw = MglaGgufRuntime.generateTopics(model, prompt);
        } finally {
            MglaGgufRuntime.setProgressListener(null);
        }
        ArrayList<String> topics = finalizeTopics(parseTopicLines(cleanModelText(raw)), clusters);
        if (topics.isEmpty()) {
            setGgufError(errorOut, "ответ модели не распознан");
        }
        return topics;
    }

    /** Группа похожих сообщений: словарь, частоты слов, пара примеров. */
    private static class TopicCluster {
        final HashSet<String> vocab = new HashSet<>();
        final HashMap<String, Integer> wordFreq = new HashMap<>();
        final ArrayList<String> examples = new ArrayList<>();
        int messages;
        int wordsTotal;

        void add(String message, ArrayList<String> words) {
            messages++;
            wordsTotal += words.size();
            for (String w : words) {
                Integer c = wordFreq.get(w);
                wordFreq.put(w, c == null ? 1 : c + 1);
                vocab.add(w);
            }
            if (examples.size() < 2 && message.length() >= 12 && message.length() <= 160) {
                examples.add(message);
            }
        }

        int repeatedWords() {
            int n = 0;
            for (int v : wordFreq.values()) {
                if (v >= 2) n++;
            }
            return n;
        }
    }

    /** Жадная кластеризация по пересечению значимых слов. */
    private static ArrayList<TopicCluster> clusterTexts(ArrayList<String> texts) {
        ArrayList<TopicCluster> clusters = new ArrayList<>();
        for (String text : texts) {
            String trimmed = text.trim();
            ArrayList<String> words = tokenize(trimmed);
            if (words.isEmpty()) {
                continue;
            }
            HashSet<String> uniq = new HashSet<>(words);
            TopicCluster best = null;
            double bestScore = 0;
            for (TopicCluster c : clusters) {
                int common = 0;
                for (String w : uniq) {
                    if (c.vocab.contains(w)) {
                        common++;
                    }
                }
                double score = common / (double) Math.min(uniq.size(), c.vocab.size());
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            if (best == null || bestScore < 0.34) {
                best = new TopicCluster();
                clusters.add(best);
            }
            best.add(trimmed, words);
        }
        Collections.sort(clusters, (a, b) -> Integer.compare(b.wordsTotal, a.wordsTotal));
        // Осмысленные темы: с повторяющимися словами или несколькими сообщениями.
        ArrayList<TopicCluster> meaningful = new ArrayList<>();
        for (TopicCluster c : clusters) {
            if (c.messages >= 2 || c.repeatedWords() >= 3) {
                meaningful.add(c);
            }
            if (meaningful.size() >= 6) {
                break;
            }
        }
        if (meaningful.isEmpty() && !clusters.isEmpty()) {
            meaningful.add(clusters.get(0));
        }
        return meaningful;
    }

    private static String topWords(TopicCluster cluster, int limit) {
        ArrayList<HashMap.Entry<String, Integer>> sorted = new ArrayList<>(cluster.wordFreq.entrySet());
        Collections.sort(sorted, (a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(sorted.get(i).getKey());
        }
        return sb.toString();
    }

    /** Компактный промпт: модель только называет готовые группы, а не читает переписку. */
    private static String buildNamingPrompt(ArrayList<TopicCluster> clusters) {
        StringBuilder sb = new StringBuilder();
        sb.append("Темы переписки. Для каждой группы напиши короткое название — описание из 2-4 слов.\n\n");
        sb.append("Пример:\n");
        sb.append("Группа 1: слова: работа, проект, дедлайн, сдача; пример: \u00abнадо доделать проект к пятнице\u00bb\n");
        sb.append("Название: Работа над проектом и сроки\n");
        sb.append("Группа 2: слова: отпуск, билеты, море; пример: \u00abкупили билеты в сочи\u00bb\n");
        sb.append("Название: Планирование отпуска и поездки\n\n");
        sb.append("Теперь твои группы:\n");
        int n = Math.min(3, clusters.size());
        for (int i = 0; i < n; i++) {
            TopicCluster c = clusters.get(i);
            sb.append("Группа ").append(i + 1).append(": слова: ").append(topWords(c, 6));
            if (!c.examples.isEmpty()) {
                String example = c.examples.get(0).replace('\n', ' ');
                if (example.length() > 80) {
                    example = example.substring(0, 80);
                }
                sb.append("; пример: \u00ab").append(example).append('\u00bb');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Названия от модели; слоты, где модель ответила мусором, закрываем словами кластера. */
    private static ArrayList<String> finalizeTopics(ArrayList<String> parsed, ArrayList<TopicCluster> clusters) {
        ArrayList<String> topics = new ArrayList<>();
        int n = Math.min(3, clusters.size());
        for (int i = 0; i < n; i++) {
            String t = i < parsed.size() ? sanitizeTopic(parsed.get(i)) : null;
            if (!isValidTopic(t)) {
                t = topWords(clusters.get(i), 3).replace(", ", " \u2022 ");
            }
            topics.add(t);
        }
        return topics;
    }

    private static String cleanModelText(String raw) {
        return raw == null ? "" : raw.replace("*", "");
    }

    private static String sanitizeTopic(String t) {
        if (t == null) {
            return null;
        }
        t = t.replace("*", "").replace("\u00ab", "").replace("\u00bb", "").replace("\"", "").trim();
        while (t.endsWith(".") || t.endsWith(":") || t.endsWith(",")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    private static boolean isValidTopic(String t) {
        if (t == null || t.length() < 3 || t.length() > 40) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        String[] meta = {"вот ", "ответ", "вопрос", "тема", "название", "группа", "конечно", "контекст", "примеры", "пример"};
        for (String m : meta) {
            if (lower.startsWith(m)) {
                return false;
            }
        }
        int words = lower.split("\\s+").length;
        return words >= 2 && words <= 6;
    }

    private static void setGgufError(String[] errorOut, String reason) {
        FileLog.e("MglaChatDna: GGUF — " + reason);
        if (errorOut != null && errorOut.length > 0) {
            errorOut[0] = reason;
        }
    }

    private static ArrayList<String> loadRecentTexts(int accountId, long dialogId, int limit, long periodStartSec, long periodEndSec) {
        ArrayList<String> texts = new ArrayList<>();
        MessagesStorage storage = MessagesStorage.getInstance(accountId);
        SQLiteDatabase db = storage.getDatabase();
        if (db == null) {
            return texts;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized(
                "SELECT data FROM messages_v2 WHERE uid = " + dialogId
                    + " AND date >= " + (int) periodStartSec + " AND date <= " + (int) periodEndSec
                    + " ORDER BY date DESC LIMIT " + limit);
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) continue;
                TLRPC.Message message = null;
                try {
                    message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                } catch (Throwable ignore) {
                }
                data.reuse();
                if (message != null && !TextUtils.isEmpty(message.message)) {
                    texts.add(message.message);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        return texts;
    }

    // ——— Утилиты ———

    static ArrayList<String> tokenize(String text) {
        ArrayList<String> out = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-zа-яё0-9]+")) {
            if (w.length() > 3 && !MglaLocalAiEngine.STOPWORDS.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    static ArrayList<String> parseTopicLines(String text) {
        ArrayList<String> topics = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return topics;
        for (String line : text.split("\n")) {
            String t = line.trim().replaceFirst("^\\d+[.)]\\s*", "").replaceFirst("^[-•*]\\s*", "")
                .replaceFirst("(?i)^название\\s*[:.]?\\s*", "").trim();
            if (t.length() > 1 && t.length() < 40 && topics.size() < 4) {
                topics.add(t);
            }
        }
        return topics;
    }

    static String extractText(String json) {
        String key = "\"text\": \"";
        int start = json.indexOf(key);
        if (start < 0) {
            key = "\"text\":\"";
            start = json.indexOf(key);
        }
        if (start < 0) return json;
        start += key.length();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c == 'n' ? '\n' : c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
