package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;

/**
 * Mgla: точка входа UI в «Живую память».
 *
 * Все методы статические, принимают колбэк и возвращают результат в UI-потоке.
 * Чтение/запись — на storageQueue соответствующего аккаунта; сетевые ответы —
 * в отдельном потоке.
 */
public class MglaMemoryEngine {

    public static class Answer {
        public String text;
        public boolean local;      // true — собран локально без LLM
        public int factsUsed;
        public boolean empty;      // true — по запросу ничего не нашлось
        public final ArrayList<MglaMemoryStorage.MemoryFact> sources = new ArrayList<>();
        /** Вопрос про связи — экран может открыть граф. */
        public boolean focusGraph;
        public String focusLabel;
    }

    public interface Callback<T> {
        void onDone(T result);
    }

    private MglaMemoryEngine() {
    }

    // region выборка фактов

    /** Факты по фильтру по всем активным аккаунтам (слияние по last_seen_at). */
    public static void queryFacts(int account, MglaMemoryStorage.MemoryQuery query, Callback<ArrayList<MglaMemoryStorage.MemoryFact>> callback) {
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()
                && MessagesStorage.getInstance(a).getDatabase() != null) {
                accounts.add(a);
            }
        }
        if (accounts.isEmpty()) {
            callback.onDone(new ArrayList<>());
            return;
        }
        final ArrayList<MglaMemoryStorage.MemoryFact> merged = new ArrayList<>();
        final int[] pending = {accounts.size()};
        for (int a : accounts) {
            MessagesStorage storage = MessagesStorage.getInstance(a);
            storage.getStorageQueue().postRunnable(() -> {
                ArrayList<MglaMemoryStorage.MemoryFact> part =
                    MglaMemoryStorage.searchFacts(storage.getDatabase(), query);
                synchronized (merged) {
                    merged.addAll(part);
                    pending[0]--;
                    if (pending[0] > 0) {
                        return;
                    }
                    merged.sort((x, y) -> Long.compare(y.lastSeenAt, x.lastSeenAt));
                    if (merged.size() > query.limit) {
                        merged.subList(query.limit, merged.size()).clear();
                    }
                }
                AndroidUtilities.runOnUIThread(() -> callback.onDone(merged));
            });
        }
    }

    public static void setFactStatus(int account, long factId, String status) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() ->
            MglaMemoryStorage.setFactStatus(storage.getDatabase(), factId, status));
    }

    public static void updateFactText(int account, long factId, String text) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() ->
            MglaMemoryStorage.updateFactText(storage.getDatabase(), factId, text));
    }

    public static void deleteFact(int account, long factId) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() ->
            MglaMemoryStorage.deleteFact(storage.getDatabase(), factId));
    }

    public static void factsForNode(int account, String nodeKey, Callback<ArrayList<MglaMemoryStorage.MemoryFact>> callback) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<MglaMemoryStorage.MemoryFact> facts =
                MglaMemoryStorage.factsForNode(storage.getDatabase(), nodeKey, 20);
            AndroidUtilities.runOnUIThread(() -> callback.onDone(facts));
        });
    }

    // endregion

    // region сводка и граф

    public static class Overview {
        public MglaMemoryStorage.MemoryCounts counts = new MglaMemoryStorage.MemoryCounts();
        public int watchedChats;
        public boolean enabled;
        /** Самая свежая незакрытая тема, вокруг которой собирается карточка «остановился». */
        public MglaMemoryStorage.MemoryFact resume;
        public String resumeChat;
        public String decisionLine;
        public String nextLine;
        public final ArrayList<MglaMemoryStorage.MemoryFact> important = new ArrayList<>();
        public final ArrayList<MglaMemoryStorage.MemoryFact> openTasks = new ArrayList<>();
        public final ArrayList<MglaMemoryStorage.MemoryFact> recent = new ArrayList<>();
    }

    public static void overview(int account, Callback<Overview> callback) {
        Overview result = new Overview();
        result.watchedChats = MglaMemoryConfig.watchedCount();
        result.enabled = MglaMemoryConfig.isEnabled();
        MessagesStorage storage = MessagesStorage.getInstance(account);
        if (!UserConfig.getInstance(account).isClientActivated() || storage.getDatabase() == null) {
            callback.onDone(result);
            return;
        }
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteDatabase db = storage.getDatabase();
            result.counts = MglaMemoryStorage.counts(db);
            MglaMemoryStorage.MemoryQuery query = new MglaMemoryStorage.MemoryQuery();
            query.limit = 80;
            query.usableOnly = true;
            ArrayList<MglaMemoryStorage.MemoryFact> facts = MglaMemoryStorage.searchFacts(db, query);
            fillOverview(result, facts);
            AndroidUtilities.runOnUIThread(() -> {
                if (result.resume != null) {
                    result.resumeChat = dialogTitle(account, result.resume.uid);
                }
                callback.onDone(result);
            });
        });
    }

    private static void fillOverview(Overview result, ArrayList<MglaMemoryStorage.MemoryFact> facts) {
        MglaMemoryStorage.MemoryFact resume = null;
        for (MglaMemoryStorage.MemoryFact fact : facts) {
            if (!MglaMemoryStorage.STATUS_OPEN.equals(fact.status)) {
                continue;
            }
            if (resume == null && isResumeType(fact.type)) {
                resume = fact;
            }
        }
        if (resume == null) {
            for (MglaMemoryStorage.MemoryFact fact : facts) {
                if (MglaMemoryStorage.STATUS_OPEN.equals(fact.status)) {
                    resume = fact;
                    break;
                }
            }
        }
        result.resume = resume;
        if (resume != null) {
            for (MglaMemoryStorage.MemoryFact fact : facts) {
                if (fact.uid != resume.uid || fact.id == resume.id) {
                    continue;
                }
                if (result.decisionLine == null && MglaMemoryStorage.TYPE_DECISION.equals(fact.type)) {
                    result.decisionLine = fact.text;
                } else if (result.nextLine == null && MglaMemoryStorage.STATUS_OPEN.equals(fact.status)
                    && (MglaMemoryStorage.TYPE_PLAN.equals(fact.type) || MglaMemoryStorage.TYPE_QUESTION.equals(fact.type) || MglaMemoryStorage.TYPE_PROMISE.equals(fact.type))) {
                    result.nextLine = fact.text;
                }
            }
            if (result.decisionLine == null && MglaMemoryStorage.TYPE_DECISION.equals(resume.type)) {
                result.decisionLine = resume.text;
            }
            if (result.nextLine == null && !MglaMemoryStorage.TYPE_DECISION.equals(resume.type)) {
                result.nextLine = resume.text;
            }
        }
        for (MglaMemoryStorage.MemoryFact fact : facts) {
            if (result.important.size() < 3 && isImportantType(fact.type)) {
                result.important.add(fact);
            }
            if (result.openTasks.size() < 3 && MglaMemoryStorage.STATUS_OPEN.equals(fact.status) && isTaskType(fact.type)) {
                result.openTasks.add(fact);
            }
            if (result.recent.size() < 3) {
                result.recent.add(fact);
            }
        }
    }

    private static boolean isResumeType(String type) {
        return MglaMemoryStorage.TYPE_DECISION.equals(type)
            || MglaMemoryStorage.TYPE_PLAN.equals(type)
            || MglaMemoryStorage.TYPE_TOPIC.equals(type)
            || MglaMemoryStorage.TYPE_IDEA.equals(type);
    }

    private static boolean isImportantType(String type) {
        return MglaMemoryStorage.TYPE_DECISION.equals(type)
            || MglaMemoryStorage.TYPE_EVENT.equals(type)
            || MglaMemoryStorage.TYPE_PROMISE.equals(type)
            || MglaMemoryStorage.TYPE_IDEA.equals(type);
    }

    private static boolean isTaskType(String type) {
        return MglaMemoryStorage.TYPE_PLAN.equals(type)
            || MglaMemoryStorage.TYPE_PROMISE.equals(type)
            || MglaMemoryStorage.TYPE_QUESTION.equals(type);
    }

    public static void graph(int account, long uidFilter, Callback<MglaMemoryStorage.MemoryGraph> callback) {
        graph(account, uidFilter, null, 0, callback);
    }

    public static void graph(int account, long uidFilter, String nodeType, long fromDateSec, Callback<MglaMemoryStorage.MemoryGraph> callback) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        if (!UserConfig.getInstance(account).isClientActivated() || storage.getDatabase() == null) {
            callback.onDone(new MglaMemoryStorage.MemoryGraph());
            return;
        }
        storage.getStorageQueue().postRunnable(() -> {
            MglaMemoryStorage.MemoryGraph g = MglaMemoryStorage.graphData(storage.getDatabase(), uidFilter, 72, nodeType, fromDateSec);
            AndroidUtilities.runOnUIThread(() -> {
                resolveGraphLabels(account, g);
                callback.onDone(g);
            });
        });
    }

    private static void resolveGraphLabels(int account, MglaMemoryStorage.MemoryGraph graph) {
        if (graph == null) {
            return;
        }
        for (MglaMemoryStorage.MemoryGraph.Node node : graph.nodes) {
            if (node.key == null || !node.key.startsWith("person:")) {
                continue;
            }
            if ("person:self".equals(node.key)) {
                node.label = "Я";
                continue;
            }
            try {
                long peerId = Long.parseLong(node.key.substring("person:".length()));
                String name = peerName(account, peerId);
                if (!TextUtils.isEmpty(name)) {
                    node.label = name;
                }
            } catch (Exception ignore) {
            }
        }
    }

    // endregion

    // region ответы на вопросы

    /** Отвечает на вопрос пользователя по памяти. */
    public static void answer(int account, String question, Callback<Answer> callback) {
        MemoryIntent intent = parseIntent(account, question);
        intent.query.usableOnly = true;
        queryFacts(account, intent.query, facts -> {
            Answer answer = new Answer();
            answer.focusGraph = intent.focusGraph;
            answer.focusLabel = intent.focusLabel;
            if (facts.isEmpty()) {
                answer.empty = true;
                answer.text = "Точного ответа в памяти нет. Добавьте чат в источники или переформулируйте вопрос — я не буду додумывать то, чего в переписке не было.";
                callback.onDone(answer);
                return;
            }
            int keep = Math.min(facts.size(), 5);
            for (int i = 0; i < keep; i++) {
                answer.sources.add(facts.get(i));
            }
            answer.factsUsed = facts.size();
            if (MglaMemoryConfig.canUseCloudAi()) {
                final String excerpt = buildExcerpt(account, facts);
                new Thread(() -> {
                    String ai = null;
                    try {
                        ai = MglaMemoryAi.answerQuestion(question, excerpt);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    final String aiResult = ai;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!TextUtils.isEmpty(aiResult)) {
                            answer.text = aiResult;
                            answer.local = false;
                        } else {
                            answer.text = buildLocalAnswer(account, facts, intent);
                            answer.local = true;
                        }
                        callback.onDone(answer);
                    });
                }, "MglaMemoryAnswer").start();
            } else {
                answer.text = buildLocalAnswer(account, facts, intent);
                answer.local = true;
                callback.onDone(answer);
            }
        });
    }

    private static class MemoryIntent {
        MglaMemoryStorage.MemoryQuery query = new MglaMemoryStorage.MemoryQuery();
        String chatTitle;   // чат, о котором спрашивают (null — все)
        boolean changes;
        boolean openPromises;
        boolean focusGraph;
        String focusLabel;
    }

    private static MemoryIntent parseIntent(int account, String question) {
        MemoryIntent intent = new MemoryIntent();
        MglaMemoryStorage.MemoryQuery q = intent.query;
        q.limit = 40;
        q.openFirst = true;
        String s = question == null ? "" : question.toLowerCase(Locale.ROOT).trim();

        if (s.contains("обещ") || s.contains("пообеща")) {
            q.types = new HashSet<>(Arrays.asList(MglaMemoryStorage.TYPE_PROMISE));
            intent.openPromises = true;
        }
        if (s.contains("план") || s.contains("задач") || s.contains("собирали") || s.contains("собираем")) {
            q.types = q.types == null
                ? new HashSet<>(Arrays.asList(MglaMemoryStorage.TYPE_PLAN))
                : q.types;
        }
        if (s.contains("вопрос")) {
            q.types = q.types == null
                ? new HashSet<>(Arrays.asList(MglaMemoryStorage.TYPE_QUESTION))
                : q.types;
        }
        if (s.contains("решени") || s.contains("договор") || s.contains("итог")) {
            q.types = q.types == null
                ? new HashSet<>(Arrays.asList(MglaMemoryStorage.TYPE_DECISION))
                : q.types;
        }
        if (s.contains("иде")) {
            q.types = q.types == null
                ? new HashSet<>(Arrays.asList(MglaMemoryStorage.TYPE_IDEA))
                : q.types;
        }
        if (hasWord(s, "факт") || s.contains("факты")) {
            q.types = q.types == null
                ? new HashSet<>(Arrays.asList(MglaMemoryStorage.TYPE_FACT))
                : q.types;
        }
        if (q.types == null && (s.contains("тем") || s.contains("о чём говорили") || s.contains("о чем говорили"))) {
            q.types = new HashSet<>(Arrays.asList(MglaMemoryStorage.TYPE_TOPIC));
        }

        boolean wantsOpen = s.contains("не выполн") || s.contains("невыполн") || s.contains("не сделал")
            || s.contains("долж") || s.contains("долг") || s.contains("забыл") || s.contains("незакрыт")
            || s.contains("открыт") || s.contains("остал") || s.contains("незаверш") || s.contains("не заверш");
        if (wantsOpen) {
            q.status = MglaMemoryStorage.STATUS_OPEN;
        }
        if (aboutSelf(s)) {
            q.fromSelf = 1;
        }
        if (s.contains("останов") || s.contains("на чём") || s.contains("на чем") || s.contains("продолж")) {
            if (q.types == null) {
                q.types = new HashSet<>(Arrays.asList(
                    MglaMemoryStorage.TYPE_DECISION, MglaMemoryStorage.TYPE_PLAN,
                    MglaMemoryStorage.TYPE_QUESTION, MglaMemoryStorage.TYPE_TOPIC));
            }
            q.status = MglaMemoryStorage.STATUS_OPEN;
            q.limit = 10;
        }
        if (s.contains("ново") || s.contains("произошл") || s.contains("изменил") || s.contains("за последн") || s.contains("за месяц")) {
            q.fromDateSec = ConnectionsManager.getInstance(account).getCurrentTime() - 30L * 24 * 3600;
            intent.changes = s.contains("изменил") || s.contains("за месяц") || s.contains("ново");
        }
        if (s.contains("связан") || s.contains("граф") || s.contains("покажи всё") || s.contains("покажи все")) {
            intent.focusGraph = true;
        }

        // Резолвим имя чата среди наблюдаемых
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) continue;
            for (long uid : MglaMemoryConfig.watchedForAccount(a)) {
                String title = dialogTitle(a, uid);
                if (!TextUtils.isEmpty(title) && title.length() >= 3
                    && s.contains(title.toLowerCase(Locale.ROOT))) {
                    intent.chatTitle = title;
                    q.uid = uid;
                    if (intent.focusGraph && intent.focusLabel == null) {
                        intent.focusLabel = title;
                    }
                    break;
                }
            }
            if (intent.chatTitle != null) break;
        }

        // Свободный поиск по словам, если фильтров не набралось
        if (q.types == null && q.uid == 0 && TextUtils.isEmpty(q.searchTokens) && !wantsOpen) {
            String tokens = MglaMemoryStorage.keywordsOf(question);
            if (!TextUtils.isEmpty(tokens)) {
                q.searchTokens = tokens;
                if (intent.focusGraph) {
                    int space = tokens.indexOf(' ');
                    intent.focusLabel = space > 0 ? tokens.substring(0, space) : tokens;
                }
            }
        }
        return intent;
    }

    private static boolean aboutSelf(String s) {
        return hasWord(s, "я") || hasWord(s, "мои") || hasWord(s, "моё") || hasWord(s, "мое")
            || hasWord(s, "мне") || hasWord(s, "мной");
    }

    private static boolean hasWord(String text, String word) {
        if (text == null || word == null || word.isEmpty()) {
            return false;
        }
        int from = 0;
        while (from <= text.length() - word.length()) {
            int i = text.indexOf(word, from);
            if (i < 0) {
                return false;
            }
            boolean left = i == 0 || !Character.isLetter(text.charAt(i - 1));
            int end = i + word.length();
            boolean right = end >= text.length() || !Character.isLetter(text.charAt(end));
            if (left && right) {
                return true;
            }
            from = i + word.length();
        }
        return false;
    }

    private static String buildExcerpt(int account, ArrayList<MglaMemoryStorage.MemoryFact> facts) {
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        int max = Math.min(facts.size(), 40);
        for (int i = 0; i < max; i++) {
            MglaMemoryStorage.MemoryFact f = facts.get(i);
            sb.append("- [").append(fmt.format(new Date(f.lastSeenAt * 1000L))).append("] ");
            if (f.fromSelf) {
                sb.append("я: ");
            } else if (f.peerId != 0) {
                sb.append(peerName(account, f.peerId)).append(": ");
            }
            sb.append('(').append(typeRu(f.type)).append(')').append(' ');
            sb.append(f.text.replace('\n', ' '));
            if (f.confidence > 0 && f.confidence < 75) {
                sb.append(" [предположение]");
            }
            if (!MglaMemoryStorage.STATUS_OPEN.equals(f.status)) {
                sb.append(" [").append(statusRu(f.status)).append(']');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String buildLocalAnswer(int account, ArrayList<MglaMemoryStorage.MemoryFact> facts, MemoryIntent intent) {
        if (intent.changes) {
            return buildChangeAnswer(account, facts);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        int max = Math.min(facts.size(), 8);
        for (int i = 0; i < max; i++) {
            MglaMemoryStorage.MemoryFact f = facts.get(i);
            sb.append("• ").append(f.text.replace('\n', ' '));
            sb.append("\n  ").append(fmt.format(new Date(f.lastSeenAt * 1000L)));
            sb.append(" · ").append(typeRu(f.type));
            if (f.confidence > 0 && f.confidence < 75) {
                sb.append(" · предположение");
            }
            String chat = dialogTitle(account, f.uid);
            if (!TextUtils.isEmpty(chat)) {
                sb.append(" · ").append(chat);
            }
            if (!MglaMemoryStorage.STATUS_OPEN.equals(f.status)) {
                sb.append(" · ").append(statusRu(f.status));
            }
            sb.append('\n');
        }
        if (facts.size() > max) {
            sb.append("…и ещё ").append(facts.size() - max).append(" записей.\n");
        }
        if (intent.openPromises) {
            sb.append("\nЭто только то, что осталось в переписке. Если там нет подтверждения, что обещание выполнено, я не считаю его невыполненным.");
        }
        return sb.toString().trim();
    }

    private static String buildChangeAnswer(int account, ArrayList<MglaMemoryStorage.MemoryFact> facts) {
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        StringBuilder fresh = new StringBuilder();
        StringBuilder dropped = new StringBuilder();
        for (MglaMemoryStorage.MemoryFact f : facts) {
            String line = "• " + f.text.replace('\n', ' ') + " (" + fmt.format(new Date(f.lastSeenAt * 1000L)) + ")\n";
            if (MglaMemoryStorage.STATUS_DROPPED.equals(f.status)) {
                dropped.append(line);
            } else {
                fresh.append(line);
            }
        }
        if (fresh.length() == 0 && dropped.length() == 0) {
            return "За этот срок память не видит смены решений.";
        }
        StringBuilder sb = new StringBuilder();
        if (fresh.length() > 0) {
            sb.append("Что появилось или осталось в силе\n").append(fresh);
        }
        if (dropped.length() > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Что было отменено\n").append(dropped);
        }
        sb.append("\nСравнение идёт по записям памяти, а не по догадке, чего в переписке не было.");
        return sb.toString().trim();
    }

    // endregion

    // region имена

    public static String dialogTitle(int account, long uid) {
        try {
            if (DialogObject.isUserDialog(uid)) {
                TLRPC.User user = MessagesController.getInstance(account).getUser(uid);
                if (user != null) {
                    return ContactsController.formatName(user.first_name, user.last_name);
                }
                return null;
            }
            if (DialogObject.isChatDialog(uid)) {
                TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-uid);
                if (chat != null) {
                    return chat.title;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    public static String peerName(int account, long peerId) {
        if (peerId == 0) {
            return "собеседник";
        }
        try {
            TLRPC.User user = MessagesController.getInstance(account).getUser(peerId);
            if (user != null) {
                String name = ContactsController.formatName(user.first_name, user.last_name);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
                if (!TextUtils.isEmpty(user.first_name)) {
                    return user.first_name;
                }
            }
        } catch (Throwable ignore) {
        }
        return "пользователь " + peerId;
    }

    public static String typeRu(String type) {
        if (type == null) return "факт";
        switch (type) {
            case MglaMemoryStorage.TYPE_DECISION: return "решение";
            case MglaMemoryStorage.TYPE_PROMISE: return "обещание";
            case MglaMemoryStorage.TYPE_PLAN: return "задача";
            case MglaMemoryStorage.TYPE_TOPIC: return "тема";
            case MglaMemoryStorage.TYPE_EVENT: return "событие";
            case MglaMemoryStorage.TYPE_QUESTION: return "вопрос";
            case MglaMemoryStorage.TYPE_IDEA: return "идея";
            case MglaMemoryStorage.TYPE_FACT: return "факт";
            default: return "факт";
        }
    }

    public static String statusRu(String status) {
        if (MglaMemoryStorage.STATUS_DONE.equals(status)) return "сделано";
        if (MglaMemoryStorage.STATUS_DROPPED.equals(status)) return "отменено";
        if (MglaMemoryStorage.STATUS_EXCLUDED.equals(status)) return "скрыто из ответов";
        return "актуально";
    }

    public static boolean isAssumption(MglaMemoryStorage.MemoryFact fact) {
        return fact != null && fact.confidence > 0 && fact.confidence < 75;
    }

    public static String certaintyRu(MglaMemoryStorage.MemoryFact fact) {
        return isAssumption(fact) ? "предположение" : "из переписки";
    }

    /** Почему запись попала в память — без нового запроса к модели. */
    public static String whyImportant(int account, MglaMemoryStorage.MemoryFact fact) {
        if (fact == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Это ").append(typeRu(fact.type));
        if (isAssumption(fact)) {
            sb.append(". В сообщении нет прямой формулировки, поэтому запись сохранена как предположение и не должна читаться как принятое решение.");
        } else {
            sb.append(". Формулировка прямо следует из переписки, а не из догадки модели.");
        }
        String chat = dialogTitle(account, fact.uid);
        if (!TextUtils.isEmpty(chat)) {
            sb.append(" Источник — чат «").append(chat).append("».");
        }
        if (!TextUtils.isEmpty(fact.sourceMids)) {
            sb.append(" Можно открыть исходное сообщение.");
        } else {
            sb.append(" Точное сообщение-источник не сохранилось, остался только текст записи.");
        }
        if (MglaMemoryStorage.STATUS_EXCLUDED.equals(fact.status)) {
            sb.append(" Запись скрыта из ответов.");
        }
        return sb.toString();
    }

    public static String relationRu(String relation) {
        if (relation == null) return "связано с";
        switch (relation) {
            case "promised": return "обещал";
            case "decided": return "решил";
            case "planned": return "запланировал";
            case "asked": return "спросил";
            case "about": return "относится к";
            case "mentioned": return "упомянул";
            case "said": return "сказал";
            case "related": return "связано с";
            default: return "связано с";
        }
    }

    // endregion
}
