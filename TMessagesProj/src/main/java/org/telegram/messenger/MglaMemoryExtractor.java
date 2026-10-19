package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Mgla: фоновый экстрактор «Живой памяти».
 *
 * Периодически (тикер на {@code globalQueue}, интервал — {@link MglaMemoryConfig})
 * для каждого чата в наблюдении дочитывает новые сообщения из {@code messages_v2}
 * начиная с сохранённого курсора, склеивает их в батч и отправляет в облачный LLM
 * ({@link MglaMemoryAi}) или, если тот недоступен, разбирает локальными эвристиками.
 * Результат — факты и рёбра графа в {@link MglaMemoryStorage}.
 *
 * Первый заход по чату делает бэкфилл: обрабатывает последние
 * {@value #BACKFILL_MESSAGES} сообщений истории, дальше — только новые.
 * При сбое анализа курсор не двигается: батч будет повторён на следующем тике.
 * Чтение/запись БД — только на storageQueue; сетевой вызов LLM — вне её.
 */
public class MglaMemoryExtractor {

    private static final long TICK_DELAY_MS = 60_000;
    private static final int BACKFILL_MESSAGES = 300;
    private static final long DB_WAIT_MS = 30_000;

    private static final Object lock = new Object();
    private static boolean started;
    private static volatile boolean running;

    private static final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            runTick();
            Utilities.globalQueue.postRunnable(this, Math.max(60_000, MglaMemoryConfig.getIntervalMin() * 60_000L));
        }
    };

    private MglaMemoryExtractor() {
    }

    public static void start() {
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
        }
        Utilities.globalQueue.postRunnable(ticker, TICK_DELAY_MS);
    }

    /** Перезапускает цикл с короткой задержкой (после добавления чата в память). */
    public static void kick() {
        Utilities.globalQueue.cancelRunnable(ticker);
        Utilities.globalQueue.postRunnable(ticker, 5_000);
    }

    private static void runTick() {
        if (!MglaMemoryConfig.isActive() || !MglaMemoryConfig.isToggleOn()
                || MglaMemoryConfig.watchedCount() == 0 || running) {
            return;
        }
        running = true;
        try {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (!UserConfig.getInstance(account).isClientActivated()) {
                    continue;
                }
                if (!MglaMemoryConfig.isEnabledForAccount(account)) {
                    continue;
                }
                ArrayList<Long> watched = MglaMemoryConfig.watchedForAccount(account);
                for (long uid : watched) {
                    if (!MglaMemoryConfig.isEnabledForAccount(account)) {
                        break;
                    }
                    try {
                        processChat(account, uid);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            running = false;
        }
    }

    // ——— батч сообщений ———

    private static class BatchMessage {
        int mid;
        long date;
        long peerId;      // автор (user id), 0 = сервисное/моё
        boolean fromSelf;
        String text;
        String fileName;  // вложение, если есть
    }

    /** Коробки для передачи результата чтения БД обратно в поток тикера. */
    private static class ReadResult {
        ArrayList<BatchMessage> batch = new ArrayList<>();
        long maxMid;
        boolean dbReady;
    }

    private static void processChat(int account, long uid) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        if (storage.getDatabase() == null) {
            return;
        }
        final ReadResult read = new ReadResult();
        CountDownLatch latch = new CountDownLatch(1);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                SQLiteDatabase db = storage.getDatabase();
                if (db == null) {
                    return;
                }
                long lastMid = MglaMemoryStorage.getStateLastMid(db, uid);
                if (lastMid < 0) {
                    // Первый заход: бэкфилл последних BACKFILL_MESSAGES сообщений
                    long startMid = backfillCursor(db, uid);
                    if (startMid < 0) {
                        return; // чат пуст или ещё не в БД — повторим на следующем тике
                    }
                    MglaMemoryStorage.putState(db, uid, startMid);
                    lastMid = startMid;
                }
                read.maxMid = readBatch(db, uid, lastMid, MglaMemoryConfig.getBatchSize(), read.batch);
                read.dbReady = true;
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(DB_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            return;
        }
        if (!read.dbReady || read.batch.isEmpty()) {
            return;
        }

        String batchText = buildBatchText(account, uid, read.batch);
        if (TextUtils.isEmpty(batchText)) {
            writeCursor(storage, uid, read.maxMid);
            return;
        }

        // Сетевой вызов — вне storageQueue
        ArrayList<MglaMemoryStorage.MemoryFact> facts = null;
        try {
            String raw = MglaMemoryAi.extractFacts(batchText);
            if (!TextUtils.isEmpty(raw)) {
                facts = parseAiFacts(raw, account, read.batch);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        final String provider;
        if (facts != null) {
            provider = MglaMemoryConfig.PROVIDER_GEMINI.equals(MglaMemoryConfig.getProvider()) ? "gemini" : "openrouter";
        } else {
            facts = extractHeuristic(account, uid, read.batch);
            provider = "local";
        }
        final ArrayList<MglaMemoryStorage.MemoryFact> toSave = facts;
        final long now = ConnectionsManager.getInstance(account).getCurrentTime();
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteDatabase db = storage.getDatabase();
            if (db == null) {
                return;
            }
            for (MglaMemoryStorage.MemoryFact fact : toSave) {
                fact.uid = uid;
                fact.provider = provider;
                long id = MglaMemoryStorage.saveFact(db, fact);
                if (id > 0 && !TextUtils.isEmpty(fact.fileName)) {
                    MglaMemoryStorage.upsertLink(db, uid,
                        fact.fromSelf ? "person:self" : (fact.peerId != 0 ? "person:" + fact.peerId : "person:unknown"),
                        "person", "file:" + fact.fileName, "file", "mentioned", now);
                }
            }
            // GC статусов: сообщения с маркерами выполнения закрывают открытые факты
            for (BatchMessage m : read.batch) {
                if (m.text == null) continue;
                String lower = m.text.toLowerCase(Locale.ROOT);
                if (RE_DONE.matcher(lower).find()) {
                    MglaMemoryStorage.markDoneByText(db, uid, m.text);
                }
            }
            MglaMemoryStorage.putState(db, uid, read.maxMid);
        });
    }

    private static void writeCursor(MessagesStorage storage, long uid, long maxMid) {
        CountDownLatch latch = new CountDownLatch(1);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                SQLiteDatabase db = storage.getDatabase();
                if (db != null) {
                    MglaMemoryStorage.putState(db, uid, maxMid);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(DB_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
        }
    }

    /** Порог бэкфилла: mid такой, что следующие батчи накроют последние BACKFILL_MESSAGES сообщений. */
    private static long backfillCursor(SQLiteDatabase db, long uid) {
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized(
                "SELECT mid FROM messages_v2 WHERE uid = " + uid + " ORDER BY mid DESC LIMIT " + BACKFILL_MESSAGES);
            long last = -1;
            long count = 0;
            while (cursor.next()) {
                last = cursor.longValue(0);
                count++;
            }
            if (count == 0) {
                return -1;
            }
            if (count < BACKFILL_MESSAGES) {
                return 0; // истории меньше бэкфилла — обрабатываем всё
            }
            return Math.max(0, last - 1);
        } catch (Exception e) {
            FileLog.e(e);
            return -1;
        } finally {
            if (cursor != null) cursor.dispose();
        }
    }

    /** @return максимальный mid батча (новый курсор). */
    private static long readBatch(SQLiteDatabase db, long uid, long afterMid, int limit, ArrayList<BatchMessage> out) {
        SQLiteCursor cursor = null;
        long maxMid = afterMid;
        try {
            cursor = db.queryFinalized(
                "SELECT m.mid, m.data FROM messages_v2 as m WHERE m.uid = " + uid
                    + " AND m.mid > " + afterMid + " ORDER BY m.mid ASC LIMIT " + limit);
            while (cursor.next()) {
                int mid = cursor.intValue(0);
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) continue;
                TLRPC.Message message = null;
                try {
                    message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                } catch (Throwable ignore) {
                }
                data.reuse();
                if (message == null) continue;
                BatchMessage bm = new BatchMessage();
                bm.mid = mid;
                if (mid > maxMid) maxMid = mid;
                bm.date = message.date;
                bm.fromSelf = message.out;
                bm.peerId = bm.fromSelf ? 0 : MessageObject.getPeerId(message.from_id);
                bm.text = message.message;
                if (message.media instanceof TLRPC.TL_messageMediaDocument
                    && ((TLRPC.TL_messageMediaDocument) message.media).document != null) {
                    bm.fileName = ((TLRPC.TL_messageMediaDocument) message.media).document.file_name;
                }
                if (!TextUtils.isEmpty(bm.text) || !TextUtils.isEmpty(bm.fileName)) {
                    out.add(bm);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        return maxMid;
    }

    private static String buildBatchText(int account, long uid, ArrayList<BatchMessage> batch) {
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        int maxChars = MglaMemoryConfig.getMaxChars();
        HashMap<Long, String> nameCache = new HashMap<>();
        for (BatchMessage m : batch) {
            String who = m.fromSelf ? "я" : nameFor(account, m.peerId, nameCache);
            String text = m.text != null ? m.text.replace('\n', ' ').trim() : "";
            if (m.fileName != null && !m.fileName.isEmpty()) {
                text = (text.isEmpty() ? "" : text + " ") + "[файл: " + m.fileName + "]";
            }
            if (text.isEmpty()) continue;
            String line = "[" + who + ", " + fmt.format(new Date(m.date * 1000L)) + "]: " + text + "\n";
            if (sb.length() + line.length() > maxChars) break;
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private static String nameFor(int account, long peerId, HashMap<Long, String> cache) {
        if (peerId == 0) {
            return "собеседник";
        }
        String cached = cache.get(peerId);
        if (cached != null) {
            return cached;
        }
        String name = "собеседник";
        try {
            TLRPC.User user = MessagesController.getInstance(account).getUser(peerId);
            if (user != null) {
                name = ContactsController.formatName(user.first_name, user.last_name);
                if (TextUtils.isEmpty(name)) {
                    name = user.first_name;
                }
                if (TextUtils.isEmpty(name)) {
                    name = "пользователь " + peerId;
                }
            }
        } catch (Throwable ignore) {
        }
        cache.put(peerId, name);
        return name;
    }

    // ——— разбор ответа LLM ———

    private static ArrayList<MglaMemoryStorage.MemoryFact> parseAiFacts(
            String raw, int account, ArrayList<BatchMessage> batch) {
        ArrayList<MglaMemoryStorage.MemoryFact> result = new ArrayList<>();
        HashMap<String, Long> peerByName = new HashMap<>();
        HashMap<Long, String> nameCache = new HashMap<>();
        for (BatchMessage m : batch) {
            if (!m.fromSelf && m.peerId != 0) {
                String name = nameFor(account, m.peerId, nameCache);
                peerByName.put(name.toLowerCase(Locale.ROOT), m.peerId);
            }
        }
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|", 4);
            if (parts.length < 4) continue;
            String type = mapType(parts[0]);
            if (type == null) continue;
            String who = parts[1].trim();
            String status = mapStatus(parts[2]);
            String text = parts[3].trim();
            if (text.length() < 4 || text.length() > 500) continue;
            MglaMemoryStorage.MemoryFact fact = new MglaMemoryStorage.MemoryFact();
            fact.type = type;
            fact.text = text;
            fact.status = status;
            fact.confidence = 90;
            softenAssumption(fact);
            String whoLower = who.toLowerCase(Locale.ROOT);
            if (whoLower.equals("я") || whoLower.equals("self") || who.startsWith("я (")) {
                fact.fromSelf = true;
            } else {
                Long peer = peerByName.get(whoLower);
                if (peer != null) {
                    fact.peerId = peer;
                    fact.participants = String.valueOf(peer);
                }
            }
            // источники: сообщения, пересекающиеся по словам
            String[] tokens = MglaMemoryStorage.keywordsOf(text).split(" ");
            ArrayList<String> mids = new ArrayList<>();
            for (BatchMessage m : batch) {
                if (m.text == null) continue;
                String lower = m.text.toLowerCase(Locale.ROOT);
                int hits = 0;
                for (String t : tokens) {
                    if (t.length() >= 4 && lower.contains(t)) hits++;
                    if (hits >= 2) break;
                }
                if (hits >= 2) {
                    mids.add(String.valueOf(m.mid));
                    if (mids.size() >= 3) break;
                }
            }
            fact.sourceMids = TextUtils.join(",", mids);
            result.add(fact);
            if (result.size() >= 15) break;
        }
        return result;
    }

    private static String mapType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "decision": case "решение": case "решили": return MglaMemoryStorage.TYPE_DECISION;
            case "promise": case "обещание": case "обещаю": return MglaMemoryStorage.TYPE_PROMISE;
            case "plan": case "план": return MglaMemoryStorage.TYPE_PLAN;
            case "topic": case "тема": return MglaMemoryStorage.TYPE_TOPIC;
            case "event": case "событие": return MglaMemoryStorage.TYPE_EVENT;
            case "question": case "вопрос": return MglaMemoryStorage.TYPE_QUESTION;
            case "idea": case "идея": return MglaMemoryStorage.TYPE_IDEA;
            case "fact": case "факт": return MglaMemoryStorage.TYPE_FACT;
            default: return null;
        }
    }

    private static String mapStatus(String raw) {
        if (raw == null) {
            return MglaMemoryStorage.STATUS_OPEN;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("done") || s.equals("выполнено") || s.equals("сделано")) {
            return MglaMemoryStorage.STATUS_DONE;
        }
        if (s.equals("dropped") || s.equals("отменено")) {
            return MglaMemoryStorage.STATUS_DROPPED;
        }
        return MglaMemoryStorage.STATUS_OPEN;
    }

    // ——— локальные эвристики (полностью офлайн) ———

    private static final Pattern RE_PROMISE = Pattern.compile(
        "(обещаю|пришлю|скину|отправлю|сделаю|переведу|переведём|дам|верну|позвоню|напишу|подготовлю|привезу|закажу)");
    private static final Pattern RE_DECISION = Pattern.compile(
        "(договорились|решили|итог\\s*:|решаем так|давайте так|остановились на|так и поступим)");
    private static final Pattern RE_PLAN = Pattern.compile(
        "(план|давайте|встретимся|завтра в|в субботу|в воскресенье|в понедельник|во вторник|в среду|в четверг|в пятницу|надо сделать|нужно сделать|задача|созвонимся)");
    private static final Pattern RE_QUESTION = Pattern.compile(
        ".*(как|когда|где|кто|почему|зачем|что|можешь|сможешь|получится ли).*[?？]\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_IDEA = Pattern.compile(
        "(идея|а что если|можно было бы|было бы|давай добавим|предлагаю)");
    private static final Pattern RE_HEDGE = Pattern.compile(
        "(может|возможно|наверное|кажется|думаю|не уверен|если получится)");
    private static final Pattern RE_DONE = Pattern.compile(
        "(готово|сделал|сделала|отправил|отправила|перев[её]л|перевела|загрузил|выложил|прислал|встретились)");

    private static ArrayList<MglaMemoryStorage.MemoryFact> extractHeuristic(
            int account, long uid, ArrayList<BatchMessage> batch) {
        ArrayList<MglaMemoryStorage.MemoryFact> result = new ArrayList<>();
        for (BatchMessage m : batch) {
            if (m.text == null) continue;
            String lower = m.text.toLowerCase(Locale.ROOT);
            String type = null;
            if (RE_PROMISE.matcher(lower).find() && (m.fromSelf || lower.contains("я "))) {
                type = MglaMemoryStorage.TYPE_PROMISE;
            } else if (RE_DECISION.matcher(lower).find()) {
                type = MglaMemoryStorage.TYPE_DECISION;
            } else if (RE_IDEA.matcher(lower).find()) {
                type = MglaMemoryStorage.TYPE_IDEA;
            } else if (RE_QUESTION.matcher(lower).matches()) {
                type = MglaMemoryStorage.TYPE_QUESTION;
            } else if (RE_PLAN.matcher(lower).find()) {
                type = MglaMemoryStorage.TYPE_PLAN;
            }
            if (type == null) continue;
            MglaMemoryStorage.MemoryFact fact = new MglaMemoryStorage.MemoryFact();
            fact.type = type;
            fact.text = shorten(m.text.trim(), 240);
            fact.status = MglaMemoryStorage.STATUS_OPEN;
            fact.fromSelf = m.fromSelf;
            fact.peerId = m.fromSelf ? 0 : m.peerId;
            fact.confidence = 70;
            softenAssumption(fact);
            fact.lastSeenAt = m.date;
            fact.createdAt = m.date;
            fact.sourceMids = String.valueOf(m.mid);
            if (!m.fromSelf && m.peerId != 0) {
                fact.participants = String.valueOf(m.peerId);
            }
            result.add(fact);
            if (result.size() >= 10) break;
        }
        return result;
    }

    private static void softenAssumption(MglaMemoryStorage.MemoryFact fact) {
        if (fact == null || fact.text == null) {
            return;
        }
        if (!RE_HEDGE.matcher(fact.text.toLowerCase(Locale.ROOT)).find()) {
            return;
        }
        if (MglaMemoryStorage.TYPE_DECISION.equals(fact.type) || MglaMemoryStorage.TYPE_PLAN.equals(fact.type)) {
            fact.type = MglaMemoryStorage.TYPE_IDEA;
        }
        fact.confidence = Math.min(fact.confidence <= 0 ? 40 : fact.confidence, 40);
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }
}
