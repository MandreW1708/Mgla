package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Mgla: «Живая память о переписках» — персистентная база знаний форка.
 *
 * Три таблицы в основной БД Telegram ({@code cache4.db}):
 * <ul>
 *   <li>{@code mgla_memory_facts} — извлечённые факты: решения, обещания, планы,
 *       темы, события, вопросы (со статусами open/done/dropped);</li>
 *   <li>{@code mgla_memory_links} — рёбра графа связей person/topic/file/event/fact
 *       с нарастающим весом при повторных упоминаниях;</li>
 *   <li>{@code mgla_memory_state} — курсоры инкрементальной выгрузки сообщений по чатам.</li>
 * </ul>
 *
 * Паттерн — как у {@link MglaDeletedStorage}: stateless-класс, static-методы с
 * параметром {@link SQLiteDatabase}, вызовы только с {@code storageQueue}.
 */
public class MglaMemoryStorage {

    public static final String TABLE_FACTS = "mgla_memory_facts";
    public static final String TABLE_LINKS = "mgla_memory_links";
    public static final String TABLE_STATE = "mgla_memory_state";

    // Типы фактов
    public static final String TYPE_DECISION = "decision";
    public static final String TYPE_PROMISE = "promise";
    public static final String TYPE_PLAN = "plan";
    public static final String TYPE_TOPIC = "topic";
    public static final String TYPE_EVENT = "event";
    public static final String TYPE_QUESTION = "question";
    public static final String TYPE_IDEA = "idea";
    public static final String TYPE_FACT = "fact";
    public static final String[] ALL_TYPES = {
        TYPE_DECISION, TYPE_PROMISE, TYPE_PLAN, TYPE_TOPIC, TYPE_EVENT, TYPE_QUESTION, TYPE_IDEA, TYPE_FACT
    };

    // Статусы
    public static final String STATUS_OPEN = "open";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_DROPPED = "dropped";
    /** Запись видна в памяти, но не попадает в ответы. */
    public static final String STATUS_EXCLUDED = "excluded";

    /** Факт памяти — одна строка знания из переписки. */
    public static class MemoryFact {
        public long id;
        public long uid;            // dialog_id
        public long peerId;         // кто сказал (0 = неизвестно)
        public boolean fromSelf;    // моё сообщение
        public String type;         // TYPE_*
        public String text;
        public String status;       // STATUS_*
        public String participants; // "uid1,uid2"
        public String keywords;     // нормализованные токены через пробел
        public String sourceMids;   // "mid1,mid2"
        public int confidence;      // 0..100
        public String provider;     // gemini | openrouter | local
        public String fileName;     // вложение-источник (не в БД; для рёбер графа)
        public long createdAt;      // сек
        public long updatedAt;      // сек
        public long lastSeenAt;     // сек

        public MemoryFact copy() {
            MemoryFact f = new MemoryFact();
            f.id = id; f.uid = uid; f.peerId = peerId; f.fromSelf = fromSelf;
            f.type = type; f.text = text; f.status = status; f.participants = participants;
            f.keywords = keywords; f.sourceMids = sourceMids; f.confidence = confidence;
            f.provider = provider; f.createdAt = createdAt; f.updatedAt = updatedAt;
            f.lastSeenAt = lastSeenAt; f.fileName = fileName;
            return f;
        }
    }

    /** Ребро графа связей. Ключи узлов: "person:123", "topic:работа", "file:x.pdf", "event:переезд". */
    public static class MemoryLink {
        public long id;
        public long uid;
        public String fromKey;
        public String fromType;
        public String toKey;
        public String toType;
        public String relation;     // said | promised | decided | planned | asked | about | mentioned
        public int weight;
        public long firstAt;
        public long lastAt;
    }

    /** Фильтр выборки фактов. uid == 0 — все чаты. */
    public static class MemoryQuery {
        public long uid;
        public HashSet<String> types;       // null = все
        public String status;               // null = все
        public int fromSelf = -1;           // -1 = все, 1 = мои, 0 = чужие
        public long fromDateSec;            // 0 = без ограничения
        public String searchTokens;         // токены через пробел, LIKE по keywords
        public int limit = 100;
        public boolean openFirst;           // сначала открытые
        /** Пропускать записи, которые пользователь запретил использовать в ответах. */
        public boolean usableOnly;
    }

    /** Данные для визуализации графа. */
    public static class MemoryGraph {
        public static class Node {
            public String key;      // "person:123"
            public String type;     // person | topic | file | event
            public String label;
            public int weight;
            public long lastAt;
            public long uid;        // dialog_id для перехода в чат
            public int messageId;   // mid исходного сообщения
            public String summary;  // краткое содержание темы/узла
        }
        public static class Edge {
            public String fromKey;
            public String toKey;
            public String relation;
            public int weight;
        }
        public final ArrayList<Node> nodes = new ArrayList<>();
        public final ArrayList<Edge> edges = new ArrayList<>();
    }

    /** Сводка для шапки экрана. */
    public static class MemoryCounts {
        public int totalFacts;
        public int openFacts;
        public int topics;
        public int promisesOpen;
        public int questionsOpen;
    }

    public static void ensureTable(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.executeFast(
                "CREATE TABLE IF NOT EXISTS " + TABLE_FACTS + "(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "uid INTEGER, " +
                    "peer_id INTEGER, " +
                    "from_self INTEGER DEFAULT 0, " +
                    "type TEXT, " +
                    "text TEXT, " +
                    "status TEXT DEFAULT 'open', " +
                    "participants TEXT, " +
                    "keywords TEXT, " +
                    "source_mids TEXT, " +
                    "confidence INTEGER DEFAULT 100, " +
                    "provider TEXT, " +
                    "created_at INTEGER, " +
                    "updated_at INTEGER, " +
                    "last_seen_at INTEGER" +
                ")"
            ).stepThis().dispose();
            database.executeFast(
                "CREATE INDEX IF NOT EXISTS mgla_memory_facts_uid ON " + TABLE_FACTS + "(uid, last_seen_at DESC)"
            ).stepThis().dispose();
            database.executeFast(
                "CREATE INDEX IF NOT EXISTS mgla_memory_facts_type ON " + TABLE_FACTS + "(type, status, last_seen_at DESC)"
            ).stepThis().dispose();

            database.executeFast(
                "CREATE TABLE IF NOT EXISTS " + TABLE_LINKS + "(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "uid INTEGER, " +
                    "from_key TEXT, " +
                    "from_type TEXT, " +
                    "to_key TEXT, " +
                    "to_type TEXT, " +
                    "relation TEXT, " +
                    "weight INTEGER DEFAULT 1, " +
                    "first_at INTEGER, " +
                    "last_at INTEGER" +
                ")"
            ).stepThis().dispose();
            database.executeFast(
                "CREATE UNIQUE INDEX IF NOT EXISTS mgla_memory_links_uq ON " + TABLE_LINKS
                    + "(uid, from_key, to_key, relation)"
            ).stepThis().dispose();
            database.executeFast(
                "CREATE INDEX IF NOT EXISTS mgla_memory_links_from ON " + TABLE_LINKS + "(from_key, weight DESC)"
            ).stepThis().dispose();

            database.executeFast(
                "CREATE TABLE IF NOT EXISTS " + TABLE_STATE + "(" +
                    "uid INTEGER PRIMARY KEY, " +
                    "last_mid INTEGER DEFAULT 0, " +
                    "last_run_at INTEGER, " +
                    "total_facts INTEGER DEFAULT 0" +
                ")"
            ).stepThis().dispose();
        } catch (SQLiteException e) {
            FileLog.e(e);
        }
    }

    // region facts

    /**
     * Сохраняет факт. Если у чата уже есть факт того же типа с пересечением ключевых
     * слов ≥ 50% — обновляет существующую запись (вес, время, источники), иначе INSERT.
     * @return id записи.
     */
    public static long saveFact(SQLiteDatabase database, MemoryFact fact) {
        if (database == null || fact == null || TextUtils.isEmpty(fact.text)) {
            return -1;
        }
        ensureTable(database);
        if (TextUtils.isEmpty(fact.keywords)) {
            fact.keywords = keywordsOf(fact.text);
        }
        long now = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime();
        if (fact.createdAt == 0) fact.createdAt = now;
        fact.updatedAt = now;
        if (fact.lastSeenAt == 0) fact.lastSeenAt = now;
        if (TextUtils.isEmpty(fact.status)) fact.status = STATUS_OPEN;

        try {
            long existingId = findSimilar(database, fact);
            SQLitePreparedStatement state;
            if (existingId > 0) {
                state = database.executeFast(
                    "UPDATE " + TABLE_FACTS + " SET text = ?, status = ?, participants = ?, source_mids = ?, " +
                        "confidence = MAX(confidence, ?), provider = ?, updated_at = ?, last_seen_at = ? WHERE id = ?"
                );
                state.requery();
                state.bindString(1, fact.text);
                state.bindString(2, fact.status);
                state.bindString(3, safe(fact.participants));
                state.bindString(4, safe(fact.sourceMids));
                state.bindInteger(5, fact.confidence);
                state.bindString(6, safe(fact.provider));
                state.bindLong(7, fact.updatedAt);
                state.bindLong(8, fact.lastSeenAt);
                state.bindLong(9, existingId);
            } else {
                state = database.executeFast(
                    "INSERT INTO " + TABLE_FACTS
                        + "(uid, peer_id, from_self, type, text, status, participants, keywords, source_mids, " +
                        "confidence, provider, created_at, updated_at, last_seen_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                );
                state.requery();
                state.bindLong(1, fact.uid);
                state.bindLong(2, fact.peerId);
                state.bindInteger(3, fact.fromSelf ? 1 : 0);
                state.bindString(4, safe(fact.type));
                state.bindString(5, fact.text);
                state.bindString(6, fact.status);
                state.bindString(7, safe(fact.participants));
                state.bindString(8, fact.keywords);
                state.bindString(9, safe(fact.sourceMids));
                state.bindInteger(10, fact.confidence);
                state.bindString(11, safe(fact.provider));
                state.bindLong(12, fact.createdAt);
                state.bindLong(13, fact.updatedAt);
                state.bindLong(14, fact.lastSeenAt);
            }
            state.step();
            state.dispose();
            long id = existingId > 0 ? existingId : lastInsertId(database);
            if (id > 0) {
                fact.id = id;
                saveLinksForFact(database, fact);
            }
            return id;
        } catch (Exception e) {
            FileLog.e(e);
            return -1;
        }
    }

    /** Ищет похожий факт того же чата и типа (токенное пересечение ≥ 50%). */
    private static long findSimilar(SQLiteDatabase database, MemoryFact fact) {
        HashSet<String> tokens = new HashSet<>(Arrays.asList(fact.keywords.split(" ")));
        if (tokens.isEmpty()) {
            return -1;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(
                "SELECT id, keywords FROM " + TABLE_FACTS
                    + " WHERE uid = ? AND type = ? ORDER BY last_seen_at DESC LIMIT 50",
                fact.uid, safe(fact.type));
            long bestId = -1;
            double bestScore = 0;
            while (cursor.next()) {
                String kw = cursor.stringValue(1);
                if (TextUtils.isEmpty(kw)) continue;
                HashSet<String> have = new HashSet<>(Arrays.asList(kw.split(" ")));
                if (have.isEmpty()) continue;
                int common = 0;
                for (String t : tokens) {
                    if (have.contains(t)) common++;
                }
                double score = (double) common / Math.max(tokens.size(), have.size());
                if (score >= 0.5 && score > bestScore) {
                    bestScore = score;
                    bestId = cursor.longValue(0);
                }
            }
            return bestId;
        } catch (Exception e) {
            FileLog.e(e);
            return -1;
        } finally {
            if (cursor != null) cursor.dispose();
        }
    }

    /** Записывает рёбра графа для сохранённого факта. */
    private static void saveLinksForFact(SQLiteDatabase database, MemoryFact fact) {
        if (fact.id <= 0) return;
        String personKey = fact.fromSelf ? "person:self" : (fact.peerId != 0 ? "person:" + fact.peerId : null);
        String relation;
        switch (safe(fact.type)) {
            case TYPE_PROMISE: relation = "promised"; break;
            case TYPE_DECISION: relation = "decided"; break;
            case TYPE_PLAN: relation = "planned"; break;
            case TYPE_QUESTION: relation = "asked"; break;
            default: relation = "said"; break;
        }
        if (personKey != null) {
            upsertLink(database, fact.uid, personKey, "person",
                "fact:" + fact.id, "fact", relation, fact.lastSeenAt);
            // Тематические факты — прямое ребро человек→тема для графа
            if (TYPE_TOPIC.equals(fact.type)) {
                upsertLink(database, fact.uid, personKey, "person",
                    "topic:" + fact.text, "topic", "about", fact.lastSeenAt);
            }
        }
        if (!TextUtils.isEmpty(fact.participants)) {
            for (String p : fact.participants.split(",")) {
                p = p.trim();
                if (p.isEmpty() || p.equals("self") || p.equals("0")) continue;
                if (personKey != null) {
                    upsertLink(database, fact.uid, personKey, "person",
                        "person:" + p, "person", "mentioned", fact.lastSeenAt);
                }
            }
        }
        // Вложения из исходных сообщений — рёбра файл→факт
        if (!TextUtils.isEmpty(fact.text)) {
            // имена файлов ловит экстрактор и кладёт в links напрямую; здесь не дублируем
        }
    }

    public static void updateFactText(SQLiteDatabase database, long id, String text) {
        if (database == null || id <= 0 || TextUtils.isEmpty(text)) {
            return;
        }
        try {
            long now = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime();
            SQLitePreparedStatement state = database.executeFast(
                "UPDATE " + TABLE_FACTS + " SET text = ?, keywords = ?, updated_at = ? WHERE id = ?");
            state.requery();
            state.bindString(1, text.trim());
            state.bindString(2, keywordsOf(text));
            state.bindLong(3, now);
            state.bindLong(4, id);
            state.step();
            state.dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void setFactStatus(SQLiteDatabase database, long id, String status) {
        if (database == null || id <= 0) {
            return;
        }
        try {
            long now = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime();
            SQLitePreparedStatement state = database.executeFast(
                "UPDATE " + TABLE_FACTS + " SET status = ?, updated_at = ? WHERE id = ?");
            state.requery();
            state.bindString(1, status);
            state.bindLong(2, now);
            state.bindLong(3, id);
            state.step();
            state.dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Отмечает открытые факты чата выполненными, если их ключевые слова встречаются в тексте. */
    public static int markDoneByText(SQLiteDatabase database, long uid, String messageText) {
        if (database == null || uid == 0 || TextUtils.isEmpty(messageText)) {
            return 0;
        }
        HashSet<String> tokens = new HashSet<>(Arrays.asList(keywordsOf(messageText).split(" ")));
        if (tokens.isEmpty()) return 0;
        ArrayList<MemoryFact> open = new ArrayList<>();
        MemoryQuery query = new MemoryQuery();
        query.uid = uid;
        query.status = STATUS_OPEN;
        query.limit = 200;
        open.addAll(searchFacts(database, query));
        int marked = 0;
        for (MemoryFact f : open) {
            if (TextUtils.isEmpty(f.keywords)) continue;
            HashSet<String> have = new HashSet<>(Arrays.asList(f.keywords.split(" ")));
            int common = 0;
            for (String t : have) {
                if (tokens.contains(t)) common++;
            }
            if (!have.isEmpty() && common * 2 >= have.size()) {
                setFactStatus(database, f.id, STATUS_DONE);
                marked++;
            }
        }
        return marked;
    }

    public static void deleteFact(SQLiteDatabase database, long id) {
        if (database == null || id <= 0) {
            return;
        }
        try {
            database.executeFast("DELETE FROM " + TABLE_FACTS + " WHERE id = " + id).stepThis().dispose();
            database.executeFast("DELETE FROM " + TABLE_LINKS + " WHERE to_key = 'fact:" + id + "'").stepThis().dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static ArrayList<MemoryFact> searchFacts(SQLiteDatabase database, MemoryQuery query) {
        ArrayList<MemoryFact> result = new ArrayList<>();
        if (database == null || query == null) {
            return result;
        }
        StringBuilder sql = new StringBuilder("SELECT id, uid, peer_id, from_self, type, text, status, participants, keywords, source_mids, confidence, provider, created_at, updated_at, last_seen_at FROM ")
            .append(TABLE_FACTS).append(" WHERE 1=1");
        ArrayList<Object> args = new ArrayList<>();
        if (query.uid != 0) {
            sql.append(" AND uid = ?");
            args.add(query.uid);
        }
        if (query.types != null && !query.types.isEmpty()) {
            sql.append(" AND type IN (");
            int i = 0;
            for (String t : query.types) {
                if (i++ > 0) sql.append(',');
                sql.append('?');
                args.add(t);
            }
            sql.append(')');
        }
        if (!TextUtils.isEmpty(query.status)) {
            sql.append(" AND status = ?");
            args.add(query.status);
        } else if (query.usableOnly) {
            sql.append(" AND status != ?");
            args.add(STATUS_EXCLUDED);
        }
        if (query.fromSelf >= 0) {
            sql.append(" AND from_self = ?");
            args.add(query.fromSelf);
        }
        if (query.fromDateSec > 0) {
            sql.append(" AND last_seen_at >= ?");
            args.add(query.fromDateSec);
        }
        if (!TextUtils.isEmpty(query.searchTokens)) {
            for (String tok : query.searchTokens.split(" ")) {
                if (tok.length() < 3) continue;
                sql.append(" AND keywords LIKE ? ESCAPE '\\'");
                args.add("%" + escapeLike(tok.toLowerCase(Locale.ROOT)) + "%");
            }
        }
        sql.append(" ORDER BY ");
        if (query.openFirst) {
            sql.append("(CASE WHEN status = 'open' THEN 0 ELSE 1 END), ");
        }
        sql.append("last_seen_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(query.limit <= 0 ? 100 : query.limit, 500)));

        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(sql.toString(), args.toArray());
            while (cursor.next()) {
                result.add(readFact(cursor));
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        return result;
    }

    private static MemoryFact readFact(SQLiteCursor cursor) throws SQLiteException {
        MemoryFact f = new MemoryFact();
        f.id = cursor.longValue(0);
        f.uid = cursor.longValue(1);
        f.peerId = cursor.longValue(2);
        f.fromSelf = cursor.intValue(3) == 1;
        f.type = cursor.stringValue(4);
        f.text = cursor.stringValue(5);
        f.status = cursor.stringValue(6);
        f.participants = cursor.stringValue(7);
        f.keywords = cursor.stringValue(8);
        f.sourceMids = cursor.stringValue(9);
        f.confidence = cursor.intValue(10);
        f.provider = cursor.stringValue(11);
        f.createdAt = cursor.longValue(12);
        f.updatedAt = cursor.longValue(13);
        f.lastSeenAt = cursor.longValue(14);
        return f;
    }

    public static MemoryCounts counts(SQLiteDatabase database) {
        MemoryCounts c = new MemoryCounts();
        if (database == null) {
            return c;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT COUNT(*), SUM(CASE WHEN status = 'open' THEN 1 ELSE 0 END) FROM " + TABLE_FACTS);
            if (cursor.next()) {
                c.totalFacts = cursor.intValue(0);
                c.openFacts = cursor.intValue(1);
            }
            cursor.dispose();
            cursor = database.queryFinalized(
                "SELECT COUNT(*) FROM " + TABLE_FACTS + " WHERE type = '" + TYPE_TOPIC + "'");
            if (cursor.next()) {
                c.topics = cursor.intValue(0);
            }
            cursor.dispose();
            cursor = database.queryFinalized(
                "SELECT COUNT(*) FROM " + TABLE_FACTS + " WHERE type = '" + TYPE_PROMISE + "' AND status = 'open'");
            if (cursor.next()) {
                c.promisesOpen = cursor.intValue(0);
            }
            cursor.dispose();
            cursor = database.queryFinalized(
                "SELECT COUNT(*) FROM " + TABLE_FACTS + " WHERE type = '" + TYPE_QUESTION + "' AND status = 'open'");
            if (cursor.next()) {
                c.questionsOpen = cursor.intValue(0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        return c;
    }

    // endregion

    // region links / graph

    public static void upsertLink(SQLiteDatabase database, long uid, String fromKey, String fromType,
                                  String toKey, String toType, String relation, long nowSec) {
        if (database == null || TextUtils.isEmpty(fromKey) || TextUtils.isEmpty(toKey)) {
            return;
        }
        SQLitePreparedStatement state = null;
        try {
            state = database.executeFast(
                "INSERT INTO " + TABLE_LINKS
                    + "(uid, from_key, from_type, to_key, to_type, relation, weight, first_at, last_at) VALUES(?, ?, ?, ?, ?, ?, 1, ?, ?)"
                    + " ON CONFLICT(uid, from_key, to_key, relation) DO UPDATE SET weight = weight + 1, last_at = excluded.last_at");
            state.requery();
            state.bindLong(1, uid);
            state.bindString(2, fromKey);
            state.bindString(3, safe(fromType));
            state.bindString(4, toKey);
            state.bindString(5, safe(toType));
            state.bindString(6, safe(relation));
            state.bindLong(7, nowSec);
            state.bindLong(8, nowSec);
            state.step();
        } catch (Exception e) {
            // ON CONFLICT доступен с SQLite 3.24+; если не поддерживается — делаем вручную
            try {
                if (state != null) state.dispose();
                state = null;
                database.executeFast("UPDATE " + TABLE_LINKS + " SET weight = weight + 1, last_at = " + nowSec
                    + " WHERE uid = " + uid + " AND from_key = '" + fromKey.replace("'", "''")
                    + "' AND to_key = '" + toKey.replace("'", "''") + "' AND relation = '" + safe(relation).replace("'", "''") + "'").stepThis().dispose();
                database.executeFast("INSERT OR IGNORE INTO " + TABLE_LINKS
                    + "(uid, from_key, from_type, to_key, to_type, relation, weight, first_at, last_at) SELECT "
                    + uid + ", '" + fromKey.replace("'", "''") + "', '" + safe(fromType).replace("'", "''")
                    + "', '" + toKey.replace("'", "''") + "', '" + safe(toType).replace("'", "''")
                    + "', '" + safe(relation).replace("'", "''") + "', 1, " + nowSec + ", " + nowSec
                    + " WHERE (SELECT changes() = 0)").stepThis().dispose();
            } catch (Exception e2) {
                FileLog.e(e2);
            }
        } finally {
            if (state != null) state.dispose();
        }
    }

    /** Узлы person/topic/file/event и рёбра между ними (факт-узлы отфильтрованы). */
    public static MemoryGraph graphData(SQLiteDatabase database, long uidFilter, int maxNodes) {
        return graphData(database, uidFilter, maxNodes, null, 0);
    }

    /**
     * Граф знаний. Помимо сохранённых рёбер собирает узлы прямо из записей,
     * чтобы карта показывала людей, темы и события, а не только редкие topic-связи.
     *
     * @param nodeType    person | topic | file | event, или null
     * @param fromDateSec нижняя граница last_seen, 0 — без ограничения
     */
    public static MemoryGraph graphData(SQLiteDatabase database, long uidFilter, int maxNodes, String nodeType, long fromDateSec) {
        MemoryGraph graph = new MemoryGraph();
        if (database == null) {
            return graph;
        }
        ArrayList<MemoryLink> raw = new ArrayList<>();
        SQLiteCursor cursor = null;
        try {
            String sql = "SELECT from_key, from_type, to_key, to_type, relation, weight, last_at FROM " + TABLE_LINKS
                + " WHERE from_type != 'fact' AND to_type != 'fact'";
            if (uidFilter != 0) {
                sql += " AND uid = " + uidFilter;
            }
            cursor = database.queryFinalized(sql);
            while (cursor.next()) {
                MemoryLink l = new MemoryLink();
                l.fromKey = cursor.stringValue(0);
                l.fromType = cursor.stringValue(1);
                l.toKey = cursor.stringValue(2);
                l.toType = cursor.stringValue(3);
                l.relation = cursor.stringValue(4);
                l.weight = cursor.intValue(5);
                l.lastAt = cursor.longValue(6);
                if (fromDateSec > 0 && l.lastAt > 0 && l.lastAt < fromDateSec) {
                    continue;
                }
                raw.add(l);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        HashMap<String, MemoryGraph.Node> nodeByKey = new HashMap<>();
        HashMap<String, MemoryGraph.Edge> edgeByPair = new HashMap<>();
        for (MemoryLink l : raw) {
            addNode(nodeByKey, l.fromKey, l.fromType, l.weight, l.lastAt);
            addNode(nodeByKey, l.toKey, l.toType, l.weight, l.lastAt);
            putEdge(edgeByPair, l.fromKey, l.toKey, l.relation, l.weight);
        }
        absorbFactsIntoGraph(database, uidFilter, fromDateSec, nodeByKey, edgeByPair);
        if (!TextUtils.isEmpty(nodeType)) {
            HashSet<String> seeds = new HashSet<>();
            for (MemoryGraph.Node n : nodeByKey.values()) {
                if (nodeType.equals(n.type)) {
                    seeds.add(n.key);
                }
            }
            HashSet<String> keepTypes = new HashSet<>(seeds);
            for (MemoryGraph.Edge e : edgeByPair.values()) {
                if (seeds.contains(e.fromKey) || seeds.contains(e.toKey)) {
                    keepTypes.add(e.fromKey);
                    keepTypes.add(e.toKey);
                }
            }
            ArrayList<String> dropNodes = new ArrayList<>();
            for (String key : nodeByKey.keySet()) {
                if (!keepTypes.contains(key)) {
                    dropNodes.add(key);
                }
            }
            for (String key : dropNodes) {
                nodeByKey.remove(key);
            }
            ArrayList<String> dropEdges = new ArrayList<>();
            for (HashMap.Entry<String, MemoryGraph.Edge> en : edgeByPair.entrySet()) {
                MemoryGraph.Edge e = en.getValue();
                if (!keepTypes.contains(e.fromKey) || !keepTypes.contains(e.toKey)) {
                    dropEdges.add(en.getKey());
                }
            }
            for (String key : dropEdges) {
                edgeByPair.remove(key);
            }
        }
        ArrayList<MemoryGraph.Node> nodes = new ArrayList<>(nodeByKey.values());
        nodes.sort((x, y) -> Integer.compare(y.weight, x.weight));
        int cap = Math.max(8, maxNodes);
        if (nodes.size() > cap) {
            nodes = new ArrayList<>(nodes.subList(0, cap));
        }
        HashSet<String> keep = new HashSet<>();
        for (MemoryGraph.Node n : nodes) {
            keep.add(n.key);
            if (TextUtils.isEmpty(n.label)) {
                n.label = humanLabel(n.key);
            }
        }
        for (MemoryGraph.Edge e : edgeByPair.values()) {
            if (keep.contains(e.fromKey) && keep.contains(e.toKey) && !e.fromKey.equals(e.toKey)) {
                graph.edges.add(e);
            }
        }
        graph.nodes.addAll(nodes);
        return graph;
    }

    /** Строит узлы тем и событий из записей: тема — краткое обобщение, не цитата сообщения. */
    private static void absorbFactsIntoGraph(SQLiteDatabase database, long uidFilter, long fromDateSec,
                                             HashMap<String, MemoryGraph.Node> nodeByKey,
                                             HashMap<String, MemoryGraph.Edge> edgeByPair) {
        MemoryQuery query = new MemoryQuery();
        query.uid = uidFilter;
        query.limit = 160;
        query.usableOnly = true;
        if (fromDateSec > 0) {
            query.fromDateSec = fromDateSec;
        }
        HashMap<String, ArrayList<String>> keywordPeers = new HashMap<>();
        for (MemoryFact fact : searchFacts(database, query)) {
            if (STATUS_EXCLUDED.equals(fact.status) || TextUtils.isEmpty(fact.text)) {
                continue;
            }
            String subjectType = TYPE_EVENT.equals(fact.type) ? "event" : "topic";
            String topicTitle = topicTitleFrom(fact);
            String subjectKey = subjectType + ":" + normalizeKey(topicTitle);
            if (subjectKey.length() < subjectType.length() + 3) {
                continue;
            }
            addNode(nodeByKey, subjectKey, subjectType, 2 + Math.max(0, fact.confidence) / 40, fact.lastSeenAt);
            MemoryGraph.Node subject = nodeByKey.get(subjectKey);
            if (subject != null) {
                if (TextUtils.isEmpty(subject.label) || subject.label.length() > topicTitle.length()) {
                    subject.label = topicTitle;
                }
                if (TextUtils.isEmpty(subject.summary) || fact.text.length() > (subject.summary == null ? 0 : subject.summary.length())) {
                    subject.summary = summarizeFact(fact);
                }
                if (fact.uid != 0 && (subject.uid == 0 || fact.lastSeenAt >= subject.lastAt)) {
                    subject.uid = fact.uid;
                    int mid = firstSourceMid(fact.sourceMids);
                    if (mid != 0) {
                        subject.messageId = mid;
                    }
                }
            }
            String personKey = fact.fromSelf ? "person:self" : (fact.peerId != 0 ? "person:" + fact.peerId : null);
            if (personKey != null) {
                addNode(nodeByKey, personKey, "person", 1, fact.lastSeenAt);
                MemoryGraph.Node person = nodeByKey.get(personKey);
                if (person != null && person.uid == 0 && fact.uid != 0) {
                    person.uid = fact.uid;
                }
                putEdge(edgeByPair, personKey, subjectKey, relationFor(fact.type), 1);
            }
            if (TextUtils.isEmpty(fact.keywords)) {
                continue;
            }
            int taken = 0;
            for (String kw : fact.keywords.split(" ")) {
                if (kw.length() < 5) {
                    continue;
                }
                ArrayList<String> peers = keywordPeers.get(kw);
                if (peers == null) {
                    peers = new ArrayList<>();
                    keywordPeers.put(kw, peers);
                }
                if (!peers.contains(subjectKey)) {
                    peers.add(subjectKey);
                }
                if (++taken >= 4) {
                    break;
                }
            }
        }
        int relatedBudget = 36;
        for (ArrayList<String> peers : keywordPeers.values()) {
            if (peers.size() < 2 || relatedBudget <= 0) {
                continue;
            }
            String anchor = peers.get(0);
            for (int i = 1; i < peers.size() && relatedBudget > 0; i++) {
                putEdge(edgeByPair, anchor, peers.get(i), "related", 1);
                relatedBudget--;
            }
        }
    }

    /** Краткое название темы для узла графа — обобщение, а не сырая цитата. */
    public static String topicTitleFrom(MemoryFact fact) {
        if (fact == null) {
            return "";
        }
        // Приоритет: уже сохранённый чистый topic.
        if (TYPE_TOPIC.equals(fact.type) && !TextUtils.isEmpty(fact.text)) {
            String cleaned = cleanTopicPhrase(fact.text);
            if (cleaned.length() >= 4 && cleaned.length() <= 40 && !looksLikeRawMessage(cleaned)) {
                return capitalizePhrase(cleaned);
            }
        }
        if ((TYPE_EVENT.equals(fact.type) || TYPE_IDEA.equals(fact.type)) && !TextUtils.isEmpty(fact.text)) {
            String cleaned = cleanTopicPhrase(fact.text);
            if (cleaned.length() >= 4 && cleaned.length() <= 36 && !looksLikeRawMessage(cleaned)) {
                return capitalizePhrase(cleaned);
            }
        }
        String fromKeywords = titleFromKeywords(fact.keywords);
        if (!TextUtils.isEmpty(fromKeywords)) {
            return fromKeywords;
        }
        return capitalizePhrase(cleanTopicPhrase(fact.text));
    }

    private static String summarizeFact(MemoryFact fact) {
        if (fact == null || TextUtils.isEmpty(fact.text)) {
            return "";
        }
        String t = fact.text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (t.length() > 90) {
            t = t.substring(0, 89).trim() + "…";
        }
        return t;
    }

    /** Цитата/шум вместо названия темы. */
    public static boolean looksLikeRawMessage(String text) {
        if (text == null) {
            return true;
        }
        String s = text.toLowerCase(Locale.ROOT).trim();
        if (s.contains("http") || s.contains("www.") || s.endsWith("...")
            || s.contains(" лол") || s.contains(" хах")) {
            return true;
        }
        String[] words = s.split("\\s+");
        if (words.length >= 8) {
            return true;
        }
        // Длинные глагольные обещания — не title темы.
        if (words.length >= 4 && (s.contains("сделаю") || s.contains("пришлю") || s.contains("скину")
            || s.contains("отправлю") || s.contains("давай ") || s.contains("могу ")
            || s.startsWith("я ") || s.startsWith("а ") || s.startsWith("ну "))) {
            return true;
        }
        return false;
    }

    /** 2–3 значимых слова пробелом — фраза, не «каша» через ·. */
    public static String titleFromKeywords(String keywords) {
        if (TextUtils.isEmpty(keywords)) {
            return "";
        }
        ArrayList<String> parts = new ArrayList<>();
        for (String w : keywords.split(" ")) {
            if (w.length() < 4) {
                continue;
            }
            parts.add(capitalizeWord(w));
            if (parts.size() >= 3) {
                break;
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        String joined = TextUtils.join(" ", parts);
        if (joined.length() > 34) {
            joined = joined.substring(0, 33).trim() + "…";
        }
        return joined;
    }

    public static String cleanTopicPhrase(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[«\"']+|[»\"']+$", "").trim();
        // Обрезаем до первой точки/вопроса, если фраза длинная.
        if (t.length() > 40) {
            int cut = -1;
            for (int i = 18; i < Math.min(40, t.length()); i++) {
                char c = t.charAt(i);
                if (c == '.' || c == '!' || c == '?' || c == ',' || c == ';' || c == '—') {
                    cut = i;
                    break;
                }
            }
            if (cut > 12) {
                t = t.substring(0, cut).trim();
            } else {
                int space = t.lastIndexOf(' ', 34);
                t = (space > 12 ? t.substring(0, space) : t.substring(0, 34)).trim();
            }
        }
        return t;
    }

    public static String capitalizePhrase(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        if (text.length() == 1) {
            return text.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Открытые темы чата (для контекста LLM и merge). */
    public static ArrayList<String> openTopicTitles(SQLiteDatabase database, long uid, int limit) {
        ArrayList<String> out = new ArrayList<>();
        if (database == null || uid == 0) {
            return out;
        }
        MemoryQuery query = new MemoryQuery();
        query.uid = uid;
        query.types = new HashSet<>(Collections.singleton(TYPE_TOPIC));
        query.status = STATUS_OPEN;
        query.usableOnly = true;
        query.limit = Math.max(1, limit);
        for (MemoryFact fact : searchFacts(database, query)) {
            String title = topicTitleFrom(fact);
            if (TextUtils.isEmpty(title) || out.contains(title)) {
                continue;
            }
            out.add(title);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static String capitalizeWord(String w) {
        if (TextUtils.isEmpty(w)) {
            return "";
        }
        if (w.length() <= 2) {
            return w.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    private static int firstSourceMid(String mids) {
        if (TextUtils.isEmpty(mids)) {
            return 0;
        }
        try {
            return Integer.parseInt(mids.split(",")[0].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static void putEdge(HashMap<String, MemoryGraph.Edge> edgeByPair, String fromKey, String toKey, String relation, int weight) {
        if (TextUtils.isEmpty(fromKey) || TextUtils.isEmpty(toKey) || fromKey.equals(toKey)) {
            return;
        }
        String a = fromKey;
        String b = toKey;
        if (a.compareTo(b) > 0) {
            String t = a;
            a = b;
            b = t;
        }
        String pair = a + "→" + b;
        MemoryGraph.Edge e = edgeByPair.get(pair);
        if (e == null) {
            e = new MemoryGraph.Edge();
            e.fromKey = a;
            e.toKey = b;
            e.relation = relation;
            e.weight = weight;
            edgeByPair.put(pair, e);
        } else {
            e.weight += weight;
            if (TextUtils.isEmpty(e.relation)) {
                e.relation = relation;
            }
        }
    }

    private static String relationFor(String type) {
        if (TYPE_PROMISE.equals(type)) return "promised";
        if (TYPE_DECISION.equals(type)) return "decided";
        if (TYPE_PLAN.equals(type)) return "planned";
        if (TYPE_QUESTION.equals(type)) return "asked";
        if (TYPE_IDEA.equals(type)) return "about";
        if (TYPE_EVENT.equals(type)) return "about";
        return "said";
    }

    static String normalizeKey(String text) {
        if (text == null) {
            return "";
        }
        String t = text.toLowerCase(Locale.ROOT).replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (t.length() > 48) {
            t = t.substring(0, 48).trim();
        }
        return t;
    }

    private static void addNode(HashMap<String, MemoryGraph.Node> map, String key, String type, int weight, long lastAt) {
        if (key == null || "fact".equals(type)) return;
        MemoryGraph.Node n = map.get(key);
        if (n == null) {
            n = new MemoryGraph.Node();
            n.key = key;
            n.type = type;
            map.put(key, n);
        }
        n.weight += weight;
        if (lastAt > n.lastAt) n.lastAt = lastAt;
    }

    private static String humanLabel(String key) {
        if (key == null) {
            return "";
        }
        if ("person:self".equals(key)) {
            return "Я";
        }
        if (key.startsWith("person:")) {
            return "Человек";
        }
        int idx = key.indexOf(':');
        return idx > 0 && idx < key.length() - 1 ? key.substring(idx + 1) : key;
    }

    /** Последние факты, связанные с узлом графа. */
    public static ArrayList<MemoryFact> factsForNode(SQLiteDatabase database, String nodeKey, int limit) {
        ArrayList<MemoryFact> result = new ArrayList<>();
        if (database == null || TextUtils.isEmpty(nodeKey)) {
            return result;
        }
        int cap = Math.max(1, limit);
        SQLiteCursor cursor = null;
        try {
            if ("person:self".equals(nodeKey)) {
                cursor = database.queryFinalized(
                    "SELECT id, uid, peer_id, from_self, type, text, status, participants, keywords, source_mids, confidence, provider, created_at, updated_at, last_seen_at FROM "
                        + TABLE_FACTS + " WHERE from_self = 1 AND status != ? ORDER BY last_seen_at DESC LIMIT ?",
                    STATUS_EXCLUDED, cap);
            } else if (nodeKey.startsWith("person:")) {
                cursor = database.queryFinalized(
                    "SELECT id, uid, peer_id, from_self, type, text, status, participants, keywords, source_mids, confidence, provider, created_at, updated_at, last_seen_at FROM "
                        + TABLE_FACTS + " WHERE peer_id = ? AND status != ? ORDER BY last_seen_at DESC LIMIT ?",
                    Long.parseLong(nodeKey.substring("person:".length())), STATUS_EXCLUDED, cap);
            }
            if (cursor != null) {
                while (cursor.next()) {
                    result.add(readFact(cursor));
                }
                return result;
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        int colon = nodeKey.indexOf(':');
        String needle = colon >= 0 ? nodeKey.substring(colon + 1) : nodeKey;
        if (needle.length() < 2) {
            return result;
        }
        MemoryQuery query = new MemoryQuery();
        query.limit = 200;
        query.usableOnly = true;
        for (MemoryFact fact : searchFacts(database, query)) {
            if (TextUtils.isEmpty(fact.text)) {
                continue;
            }
            String titleKey = normalizeKey(topicTitleFrom(fact));
            String textKey = normalizeKey(fact.text);
            boolean match = titleKey.equals(needle) || textKey.equals(needle)
                || (!titleKey.isEmpty() && (titleKey.startsWith(needle) || needle.startsWith(titleKey)))
                || (!textKey.isEmpty() && (textKey.contains(needle) || needle.contains(textKey)));
            if (!match && !TextUtils.isEmpty(fact.keywords)) {
                for (String kw : fact.keywords.split(" ")) {
                    if (kw.length() >= 4 && needle.contains(kw)) {
                        match = true;
                        break;
                    }
                }
            }
            if (match) {
                result.add(fact);
                if (result.size() >= cap) {
                    break;
                }
            }
        }
        return result;
    }

    // endregion

    // region state

    public static long getStateLastMid(SQLiteDatabase database, long uid) {
        if (database == null) {
            return -1;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT last_mid FROM " + TABLE_STATE + " WHERE uid = " + uid);
            if (cursor.next()) {
                return cursor.longValue(0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        return -1;
    }

    /** @param initialized false — курсора ещё нет (нужен бэкфилл истории). */
    public static boolean hasState(SQLiteDatabase database, long uid) {
        return getStateLastMid(database, uid) >= 0;
    }

    public static void putState(SQLiteDatabase database, long uid, long lastMid) {
        if (database == null || uid == 0) {
            return;
        }
        SQLitePreparedStatement state = null;
        try {
            long now = ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime();
            state = database.executeFast(
                "INSERT INTO " + TABLE_STATE + "(uid, last_mid, last_run_at, total_facts) VALUES(?, ?, ?, 0)"
                    + " ON CONFLICT(uid) DO UPDATE SET last_mid = excluded.last_mid, last_run_at = excluded.last_run_at");
            state.requery();
            state.bindLong(1, uid);
            state.bindLong(2, lastMid);
            state.bindLong(3, now);
            state.step();
        } catch (Exception e) {
            try {
                if (state != null) state.dispose();
                state = null;
                database.executeFast("UPDATE " + TABLE_STATE + " SET last_mid = " + lastMid + ", last_run_at = "
                    + ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime()
                    + " WHERE uid = " + uid).stepThis().dispose();
                database.executeFast("INSERT OR IGNORE INTO " + TABLE_STATE + "(uid, last_mid, last_run_at, total_facts) VALUES("
                    + uid + ", " + lastMid + ", " + ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime() + ", 0)").stepThis().dispose();
            } catch (Exception e2) {
                FileLog.e(e2);
            }
        } finally {
            if (state != null) state.dispose();
        }
    }

    // endregion

    // region utils

    /** Нормализованные ключевые слова факта (нижний регистр, без стоп-слов, дедуп). */
    public static String keywordsOf(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-zа-яё0-9]+")) {
            if (w.length() > 3 && !MglaLocalAiEngine.STOPWORDS.contains(w)) {
                set.add(w);
                if (set.size() >= 24) break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String w : set) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static long lastInsertId(SQLiteDatabase database) {
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT last_insert_rowid()");
            if (cursor.next()) {
                return cursor.longValue(0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        return -1;
    }

    // endregion
}
