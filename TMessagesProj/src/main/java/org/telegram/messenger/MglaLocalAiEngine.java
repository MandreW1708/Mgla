package org.telegram.messenger;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Mgla: локальная «модель» для генерации главных тем Chat DNA.
 * Если скачана одна из локальных ИИ-моделей ({@link MglaLocalModelsManager}) —
 * используется она (упрощённый локальный инференс поверх текстов).
 * Иначе — встроенный офлайн-экстрактор тем по частотности слов.
 */
public class MglaLocalAiEngine {

    static final Set<String> STOPWORDS = new HashSet<>();

    static {
        String[] words = {
            "это", "как", "что", "все", "его", "она", "так", "но", "да", "нет", "тут", "там", "был", "быть",
            "есть", "нет", "мне", "тебе", "вас", "нас", "их", "чего", "когда", "где", "какой", "который",
            "очень", "только", "еще", "уже", "после", "перед", "между", "через", "при", "для", "без", "про",
            "the", "and", "for", "you", "that", "this", "with", "have", "was", "are", "but", "not", "can",
            "from", "they", "will", "would", "there", "their", "what", "about", "which", "when", "your",
            "how", "why", "who", "all", "any", "our", "out", "just", "like", "some", "than", "then", "them",
            "было", "были", "может", "могут", "нужно", "надо", "сейчас", "потом", "пока", "если", "чтобы",
            "because", "could", "should", "other", "more", "very", "into", "over", "also", "its", "been"
        };
        Collections.addAll(STOPWORDS, words);
    }

    /** Генерирует 4 главные темы из последних сообщений чата. */
    public static ArrayList<String> generateTopics(Context context, int accountId, long dialogId) {
        ArrayList<String> texts = loadTexts(accountId, dialogId, 250);
        File model = MglaLocalModelsManager.getLoadedModel(context);
        if (model != null) {
            // Локальная скачанная модель: расширенный анализ с попыткой
            // использовать модельные веса (если поддерживается форматом).
            ArrayList<String> topics = MglaLocalModelsManager.inferTopics(model, texts);
            if (topics != null && !topics.isEmpty()) {
                return topics;
            }
        }
        return topicsFromTexts(texts);
    }

    /** Встроенный офлайн-экстрактор тем: частотность значимых слов. */
    public static ArrayList<String> topicsFromTexts(ArrayList<String> texts) {
        ArrayList<String> topics = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return topics;
        }
        HashMap<String, Integer> freq = new HashMap<>();
        for (String text : texts) {
            if (text == null) continue;
            HashSet<String> seen = new HashSet<>();
            for (String w : text.toLowerCase(Locale.ROOT).split("[^a-zа-яё0-9]+")) {
                if (w.length() > 3 && !STOPWORDS.contains(w) && !seen.contains(w)) {
                    seen.add(w);
                    Integer c = freq.get(w);
                    freq.put(w, c == null ? 1 : c + 1);
                }
            }
        }
        ArrayList<HashMap.Entry<String, Integer>> sorted = new ArrayList<>(freq.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        for (HashMap.Entry<String, Integer> e : sorted) {
            String word = e.getKey();
            boolean duplicate = false;
            for (String t : topics) {
                if (t.contains(word) || word.contains(t)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                topics.add(word);
            }
            if (topics.size() >= 4) {
                break;
            }
        }
        return topics;
    }

    private static ArrayList<String> loadTexts(int accountId, long dialogId, int limit) {
        ArrayList<String> texts = new ArrayList<>();
        MessagesStorage storage = MessagesStorage.getInstance(accountId);
        SQLiteDatabase db = storage.getDatabase();
        if (db == null) {
            return texts;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized(
                "SELECT data FROM messages_v2 WHERE uid = " + dialogId + " ORDER BY date DESC LIMIT " + limit);
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
}
