package org.telegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class MglaTasksStorage {

    public interface Listener {
        void onTasksChanged();
    }

    private static volatile MglaTasksStorage instance;

    public static MglaTasksStorage getInstance() {
        MglaTasksStorage localInstance = instance;
        if (localInstance == null) {
            synchronized (MglaTasksStorage.class) {
                localInstance = instance;
                if (localInstance == null) {
                    instance = localInstance = new MglaTasksStorage();
                }
            }
        }
        return localInstance;
    }

    private final DispatchQueue storageQueue = new DispatchQueue("mglaTasksStorageQueue");
    private final ArrayList<Listener> listeners = new ArrayList<>();

    private SQLiteDatabase database;
    private boolean loaded;
    private boolean loading;
    private final ArrayList<MglaTask> tasks = new ArrayList<>();
    private final ArrayList<MglaTask.Group> groups = new ArrayList<>();
    private final ArrayList<Runnable> loadCallbacks = new ArrayList<>();

    public void addListener(Listener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public void ensureLoaded(Runnable onLoaded) {
        if (loaded) {
            if (onLoaded != null) {
                AndroidUtilities.runOnUIThread(onLoaded);
            }
            return;
        }
        storageQueue.postRunnable(() -> {
            if (!loaded) {
                if (onLoaded != null) {
                    synchronized (loadCallbacks) {
                        loadCallbacks.add(onLoaded);
                    }
                }
                if (loading) {
                    return;
                }
                loading = true;
                try {
                    openDatabase();
                    loadInternal();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                loaded = true;
                loading = false;
            }
            ArrayList<Runnable> callbacks;
            synchronized (loadCallbacks) {
                callbacks = new ArrayList<>(loadCallbacks);
                loadCallbacks.clear();
            }
            if (onLoaded != null && !callbacks.contains(onLoaded)) {
                callbacks.add(onLoaded);
            }
            for (Runnable callback : callbacks) {
                AndroidUtilities.runOnUIThread(callback);
            }
        });
    }

    public ArrayList<MglaTask> getTasks() {
        int account = UserConfig.selectedAccount;
        ArrayList<MglaTask> result = new ArrayList<>();
        synchronized (tasks) {
            for (MglaTask task : tasks) {
                if (task.account == account) {
                    result.add(task);
                }
            }
        }
        return result;
    }

    public ArrayList<MglaTask.Group> getGroups() {
        int account = UserConfig.selectedAccount;
        ArrayList<MglaTask.Group> result = new ArrayList<>();
        synchronized (tasks) {
            for (MglaTask.Group group : groups) {
                if (group.account == account) {
                    result.add(group);
                }
            }
        }
        return result;
    }

    public MglaTask getTask(long id) {
        synchronized (tasks) {
            for (MglaTask task : tasks) {
                if (task.id == id) {
                    return task;
                }
            }
        }
        return null;
    }

    public ArrayList<MglaTask> getAllTasksForScheduling() {
        synchronized (tasks) {
            return new ArrayList<>(tasks);
        }
    }

    public void saveTask(MglaTask task) {
        if (task == null) {
            return;
        }
        if (!loaded) {
            ensureLoaded(() -> saveTask(task));
            return;
        }
        long now = System.currentTimeMillis();
        if (task.createdAt == 0) {
            task.createdAt = now;
        }
        if (TextUtils.isEmpty(task.uuid)) {
            task.uuid = MglaTasksSync.newUuid();
        }
        if (!suppressingSync || task.updatedAt <= 0) {
            task.updatedAt = now;
        }
        // Reminder time alone is not enough for list/calendar — promote to due.
        if (task.dueAt <= 0 && task.remindAt > 0) {
            task.dueAt = task.remindAt;
        }
        if (task.subItems != null) {
            for (MglaTask.SubItem item : task.subItems) {
                if (item != null && TextUtils.isEmpty(item.uuid)) {
                    item.uuid = MglaTasksSync.newUuid();
                }
            }
        }
        if (task.id == 0) {
            synchronized (tasks) {
                long maxId = 0;
                for (MglaTask t : tasks) {
                    maxId = Math.max(maxId, t.id);
                }
                task.id = maxId + 1;
                tasks.add(task);
            }
        } else {
            MglaTask existing = getTask(task.id);
            if (existing != null) {
                existing.account = task.account;
                existing.uuid = task.uuid;
                existing.title = task.title;
                existing.note = task.note;
                existing.groupId = task.groupId;
                existing.dueAt = task.dueAt;
                existing.remindAt = task.remindAt;
                existing.done = task.done;
                existing.doneAt = task.doneAt;
                existing.createdAt = task.createdAt;
                existing.updatedAt = task.updatedAt;
                existing.sortOrder = task.sortOrder;
                existing.dialogId = task.dialogId;
                existing.messageId = task.messageId;
                existing.repeatInterval = task.repeatInterval;
                existing.repeatUnit = task.repeatUnit;
                existing.priority = task.priority;
                existing.subItems = task.subItems;
            } else {
                synchronized (tasks) {
                    tasks.add(task);
                }
            }
        }
        synchronized (tasks) {
            long maxSubId = 0;
            for (MglaTask t : tasks) {
                for (MglaTask.SubItem s : t.subItems) {
                    maxSubId = Math.max(maxSubId, s.id);
                }
            }
            for (MglaTask.SubItem item : task.subItems) {
                if (item.id == 0) {
                    item.id = ++maxSubId;
                }
                item.taskId = task.id;
            }
        }
        notifyChanged();
        final long dueAtSnap = task.dueAt;
        final int prioritySnap = task.priority;
        final long taskIdSnap = task.id;
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                // Prefer UPDATE so a partial bind cannot wipe due_at via REPLACE semantics.
                SQLiteCursor exists = database.queryFinalized("SELECT id FROM tasks WHERE id = " + taskIdSnap);
                boolean rowExists = exists.next();
                exists.dispose();
                if (rowExists) {
                    SQLitePreparedStatement state = database.executeFast(
                            "UPDATE tasks SET account = ?, title = ?, note = ?, group_id = ?, due_at = ?, remind_at = ?, "
                                    + "done = ?, done_at = ?, created_at = ?, sort_order = ?, dialog_id = ?, message_id = ?, "
                                    + "repeat_interval = ?, repeat_unit = ?, priority = ?, uuid = ?, updated_at = ? WHERE id = ?");
                    state.requery();
                    bindTask(state, task, true);
                    state.stepThis().dispose();
                } else {
                    SQLitePreparedStatement state = database.executeFast(
                            "INSERT INTO tasks(account, title, note, group_id, due_at, remind_at, done, done_at, created_at, "
                                    + "sort_order, dialog_id, message_id, repeat_interval, repeat_unit, priority, uuid, updated_at, id) "
                                    + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    state.requery();
                    bindTask(state, task, true);
                    state.stepThis().dispose();
                }
                // Sanity: never let priority save leave due_at as 0 if we still have a due.
                if (dueAtSnap > 0) {
                    database.executeFast("UPDATE tasks SET due_at = " + dueAtSnap + ", priority = " + prioritySnap
                            + " WHERE id = " + taskIdSnap).stepThis().dispose();
                }
                database.executeFast("DELETE FROM task_items WHERE task_id = " + task.id).stepThis().dispose();
                if (!task.subItems.isEmpty()) {
                    SQLitePreparedStatement itemState = database.executeFast(
                            "INSERT OR REPLACE INTO task_items(task_id, title, done, sort_order, uuid, id) VALUES(?, ?, ?, ?, ?, ?)");
                    for (MglaTask.SubItem item : task.subItems) {
                        itemState.requery();
                        itemState.bindLong(1, task.id);
                        itemState.bindString(2, item.title != null ? item.title : "");
                        itemState.bindInteger(3, item.done ? 1 : 0);
                        itemState.bindInteger(4, item.sortOrder);
                        itemState.bindString(5, item.uuid != null ? item.uuid : "");
                        itemState.bindLong(6, item.id);
                        itemState.stepThis();
                    }
                    itemState.dispose();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void deleteTask(long taskId) {
        String uuid = null;
        int account = UserConfig.selectedAccount;
        synchronized (tasks) {
            for (int a = 0; a < tasks.size(); a++) {
                if (tasks.get(a).id == taskId) {
                    uuid = tasks.get(a).uuid;
                    account = tasks.get(a).account;
                    tasks.remove(a);
                    break;
                }
            }
        }
        if (!TextUtils.isEmpty(uuid)) {
            addTombstone("task", account, uuid);
        }
        notifyChanged();
        final String uuidSnap = uuid;
        final int accountSnap = account;
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("DELETE FROM task_items WHERE task_id = " + taskId).stepThis().dispose();
                database.executeFast("DELETE FROM tasks WHERE id = " + taskId).stepThis().dispose();
                if (!TextUtils.isEmpty(uuidSnap)) {
                    insertTombstoneDb("task", accountSnap, uuidSnap, System.currentTimeMillis());
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void setTaskDone(long taskId, boolean done) {
        long now = System.currentTimeMillis();
        MglaTask task = getTask(taskId);
        if (task != null) {
            task.done = done;
            task.doneAt = done ? now : 0;
            task.updatedAt = now;
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("UPDATE tasks SET done = " + (done ? 1 : 0)
                        + ", done_at = " + (done ? now : 0)
                        + ", updated_at = " + now
                        + " WHERE id = " + taskId).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void saveGroup(MglaTask.Group group) {
        if (group == null) {
            return;
        }
        if (!loaded) {
            ensureLoaded(() -> saveGroup(group));
            return;
        }
        long now = System.currentTimeMillis();
        if (TextUtils.isEmpty(group.uuid)) {
            group.uuid = MglaTasksSync.newUuid();
        }
        if (!suppressingSync || group.updatedAt <= 0) {
            group.updatedAt = now;
        }
        if (group.id == 0) {
            synchronized (tasks) {
                long maxId = 0;
                for (MglaTask.Group g : groups) {
                    maxId = Math.max(maxId, g.id);
                }
                group.id = maxId + 1;
                groups.add(group);
            }
        } else {
            MglaTask.Group existing = null;
            synchronized (tasks) {
                for (MglaTask.Group g : groups) {
                    if (g.id == group.id) {
                        existing = g;
                        break;
                    }
                }
                if (existing != null) {
                    existing.account = group.account;
                    existing.uuid = group.uuid;
                    existing.name = group.name;
                    existing.color = group.color;
                    existing.sortOrder = group.sortOrder;
                    existing.updatedAt = group.updatedAt;
                } else {
                    groups.add(group);
                }
            }
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                SQLitePreparedStatement state = database.executeFast(
                        "INSERT OR REPLACE INTO task_groups(account, name, color, sort_order, uuid, updated_at, id) VALUES(?, ?, ?, ?, ?, ?, ?)");
                state.requery();
                state.bindInteger(1, group.account);
                state.bindString(2, group.name != null ? group.name : "");
                state.bindInteger(3, group.color);
                state.bindInteger(4, group.sortOrder);
                state.bindString(5, group.uuid != null ? group.uuid : "");
                state.bindLong(6, group.updatedAt);
                state.bindLong(7, group.id);
                state.stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void deleteGroup(long groupId) {
        String uuid = null;
        int account = UserConfig.selectedAccount;
        synchronized (tasks) {
            for (int a = 0; a < groups.size(); a++) {
                if (groups.get(a).id == groupId) {
                    uuid = groups.get(a).uuid;
                    account = groups.get(a).account;
                    groups.remove(a);
                    break;
                }
            }
            for (MglaTask task : tasks) {
                if (task.groupId == groupId) {
                    task.groupId = 0;
                    task.updatedAt = System.currentTimeMillis();
                }
            }
        }
        if (!TextUtils.isEmpty(uuid)) {
            addTombstone("group", account, uuid);
        }
        notifyChanged();
        final String uuidSnap = uuid;
        final int accountSnap = account;
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("UPDATE tasks SET group_id = 0, updated_at = " + System.currentTimeMillis()
                        + " WHERE group_id = " + groupId).stepThis().dispose();
                database.executeFast("DELETE FROM task_groups WHERE id = " + groupId).stepThis().dispose();
                if (!TextUtils.isEmpty(uuidSnap)) {
                    insertTombstoneDb("group", accountSnap, uuidSnap, System.currentTimeMillis());
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void saveSubItem(long taskId, MglaTask.SubItem item) {
        if (item == null) {
            return;
        }
        if (!loaded) {
            ensureLoaded(() -> saveSubItem(taskId, item));
            return;
        }
        MglaTask task = getTask(taskId);
        if (item.id == 0) {
            synchronized (tasks) {
                long maxId = 0;
                for (MglaTask t : tasks) {
                    for (MglaTask.SubItem s : t.subItems) {
                        maxId = Math.max(maxId, s.id);
                    }
                }
                item.id = maxId + 1;
            }
            item.taskId = taskId;
            if (task != null) {
                task.subItems.add(item);
            }
        } else if (task != null) {
            MglaTask.SubItem existing = null;
            for (MglaTask.SubItem subItem : task.subItems) {
                if (subItem.id == item.id) {
                    existing = subItem;
                    break;
                }
            }
            if (existing != null) {
                existing.taskId = taskId;
                existing.title = item.title;
                existing.done = item.done;
                existing.sortOrder = item.sortOrder;
            } else {
                item.taskId = taskId;
                task.subItems.add(item);
            }
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                SQLitePreparedStatement state = database.executeFast("INSERT OR REPLACE INTO task_items(task_id, title, done, sort_order, id) VALUES(?, ?, ?, ?, ?)");
                state.requery();
                state.bindLong(1, taskId);
                state.bindString(2, item.title != null ? item.title : "");
                state.bindInteger(3, item.done ? 1 : 0);
                state.bindInteger(4, item.sortOrder);
                state.bindLong(5, item.id);
                state.stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void deleteSubItem(long taskId, long subItemId) {
        MglaTask task = getTask(taskId);
        if (task != null) {
            for (int a = 0; a < task.subItems.size(); a++) {
                if (task.subItems.get(a).id == subItemId) {
                    task.subItems.remove(a);
                    break;
                }
            }
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("DELETE FROM task_items WHERE id = " + subItemId).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void setSubItemDone(long taskId, long subItemId, boolean done) {
        long now = System.currentTimeMillis();
        MglaTask task = getTask(taskId);
        if (task != null) {
            task.updatedAt = now;
            for (MglaTask.SubItem subItem : task.subItems) {
                if (subItem.id == subItemId) {
                    subItem.done = done;
                    break;
                }
            }
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("UPDATE task_items SET done = " + (done ? 1 : 0) + " WHERE id = " + subItemId).stepThis().dispose();
                database.executeFast("UPDATE tasks SET updated_at = " + now + " WHERE id = " + taskId).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void notifyChanged() {
        ArrayList<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        for (Listener listener : copy) {
            AndroidUtilities.runOnUIThread(listener::onTasksChanged);
        }
        AndroidUtilities.runOnUIThread(MglaTasksWidgetProvider::updateAll);
        if (!suppressingSync) {
            try {
                MglaTasksSync.scheduleAfterLocalChange();
            } catch (Throwable ignore) {
            }
        }
    }

    private final ArrayList<Tombstone> pendingTombstones = new ArrayList<>();

    private static final class Tombstone {
        final String kind;
        final int account;
        final String uuid;
        final long deletedAt;

        Tombstone(String kind, int account, String uuid, long deletedAt) {
            this.kind = kind;
            this.account = account;
            this.uuid = uuid;
            this.deletedAt = deletedAt;
        }
    }

    private void addTombstone(String kind, int account, String uuid) {
        if (TextUtils.isEmpty(uuid)) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (pendingTombstones) {
            for (int i = 0; i < pendingTombstones.size(); i++) {
                Tombstone t = pendingTombstones.get(i);
                if (t.kind.equals(kind) && t.account == account && t.uuid.equals(uuid)) {
                    pendingTombstones.set(i, new Tombstone(kind, account, uuid, Math.max(t.deletedAt, now)));
                    return;
                }
            }
            pendingTombstones.add(new Tombstone(kind, account, uuid, now));
        }
    }

    private void insertTombstoneDb(String kind, int account, String uuid, long deletedAt) throws Exception {
        SQLitePreparedStatement state = database.executeFast(
                "INSERT OR REPLACE INTO task_tombstones(kind, account, uuid, deleted_at) VALUES(?, ?, ?, ?)");
        state.requery();
        state.bindString(1, kind);
        state.bindInteger(2, account);
        state.bindString(3, uuid);
        state.bindLong(4, deletedAt);
        state.stepThis().dispose();
    }

    private void ensureOpen() throws Exception {
        if (database == null) {
            openDatabase();
        }
    }

    private void openDatabase() throws Exception {
        if (database != null) {
            return;
        }
        File dbFile = new File(ApplicationLoader.getFilesDirFixed(), "mgla_tasks.db");
        database = new SQLiteDatabase(dbFile.getPath());
        database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
        database.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();
        database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
        database.executeFast("CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, account INTEGER, title TEXT, note TEXT, group_id INTEGER, due_at INTEGER, remind_at INTEGER, done INTEGER, done_at INTEGER, created_at INTEGER, sort_order INTEGER, dialog_id INTEGER, message_id INTEGER)").stepThis().dispose();
        database.executeFast("CREATE TABLE IF NOT EXISTS task_groups(id INTEGER PRIMARY KEY AUTOINCREMENT, account INTEGER, name TEXT, color INTEGER, sort_order INTEGER)").stepThis().dispose();
        database.executeFast("CREATE TABLE IF NOT EXISTS task_items(id INTEGER PRIMARY KEY AUTOINCREMENT, task_id INTEGER, title TEXT, done INTEGER, sort_order INTEGER)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS tasks_remind_at_idx ON tasks(remind_at)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS tasks_due_at_idx ON tasks(due_at)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS task_items_task_id_idx ON task_items(task_id)").stepThis().dispose();
        try {
            database.executeFast("ALTER TABLE tasks ADD COLUMN repeat_interval INTEGER DEFAULT 0").stepThis().dispose();
        } catch (Exception ignore) {
        }
        try {
            database.executeFast("ALTER TABLE tasks ADD COLUMN repeat_unit INTEGER DEFAULT 0").stepThis().dispose();
        } catch (Exception ignore) {
        }
        try {
            database.executeFast("ALTER TABLE tasks ADD COLUMN priority INTEGER DEFAULT 0").stepThis().dispose();
        } catch (Exception ignore) {
        }
        try {
            database.executeFast("ALTER TABLE tasks ADD COLUMN uuid TEXT DEFAULT ''").stepThis().dispose();
        } catch (Exception ignore) {
        }
        try {
            database.executeFast("ALTER TABLE tasks ADD COLUMN updated_at INTEGER DEFAULT 0").stepThis().dispose();
        } catch (Exception ignore) {
        }
        try {
            database.executeFast("ALTER TABLE task_groups ADD COLUMN uuid TEXT DEFAULT ''").stepThis().dispose();
        } catch (Exception ignore) {
        }
        try {
            database.executeFast("ALTER TABLE task_groups ADD COLUMN updated_at INTEGER DEFAULT 0").stepThis().dispose();
        } catch (Exception ignore) {
        }
        try {
            database.executeFast("ALTER TABLE task_items ADD COLUMN uuid TEXT DEFAULT ''").stepThis().dispose();
        } catch (Exception ignore) {
        }
        database.executeFast(
                "CREATE TABLE IF NOT EXISTS task_tombstones(kind TEXT NOT NULL, account INTEGER NOT NULL, uuid TEXT NOT NULL, deleted_at INTEGER NOT NULL, PRIMARY KEY(kind, account, uuid))"
        ).stepThis().dispose();
        // One-shot heal: rows that only have remind_at (clock in UI, missing from calendar).
        try {
            database.executeFast(
                    "UPDATE tasks SET due_at = remind_at WHERE (due_at IS NULL OR due_at <= 0) AND remind_at > 0"
            ).stepThis().dispose();
        } catch (Exception ignore) {
        }
    }

    public void deleteDoneTasks() {
        if (!loaded) {
            ensureLoaded(this::deleteDoneTasks);
            return;
        }
        ArrayList<Long> ids = new ArrayList<>();
        int account = UserConfig.selectedAccount;
        synchronized (tasks) {
            for (int a = tasks.size() - 1; a >= 0; a--) {
                MglaTask task = tasks.get(a);
                if (task.account == account && task.done) {
                    if (!TextUtils.isEmpty(task.uuid)) {
                        addTombstone("task", account, task.uuid);
                    }
                    ids.add(task.id);
                    tasks.remove(a);
                }
            }
        }
        for (long id : ids) {
            MglaTasksReminderScheduler.cancel(id);
        }
        // Persist tombstones for cleared done tasks.
        ArrayList<Tombstone> tombs;
        synchronized (pendingTombstones) {
            tombs = new ArrayList<>(pendingTombstones);
        }
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                for (Tombstone t : tombs) {
                    if (t.account == account && "task".equals(t.kind)) {
                        insertTombstoneDb(t.kind, t.account, t.uuid, t.deletedAt);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                for (long id : ids) {
                    database.executeFast("DELETE FROM task_items WHERE task_id = " + id).stepThis().dispose();
                    database.executeFast("DELETE FROM tasks WHERE id = " + id).stepThis().dispose();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void updateTaskSortOrders(ArrayList<MglaTask> ordered) {
        if (ordered == null || ordered.isEmpty()) {
            return;
        }
        if (!loaded) {
            ensureLoaded(() -> updateTaskSortOrders(ordered));
            return;
        }
        long now = System.currentTimeMillis();
        for (int a = 0; a < ordered.size(); a++) {
            MglaTask task = ordered.get(a);
            if (task != null) {
                task.sortOrder = a;
                task.updatedAt = now;
                MglaTask existing = getTask(task.id);
                if (existing != null) {
                    existing.sortOrder = a;
                    existing.updatedAt = now;
                }
            }
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                for (int a = 0; a < ordered.size(); a++) {
                    MglaTask task = ordered.get(a);
                    if (task == null || task.id == 0) {
                        continue;
                    }
                    database.executeFast("UPDATE tasks SET sort_order = " + a + ", updated_at = " + now
                            + " WHERE id = " + task.id).stepThis().dispose();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void loadInternal() throws Exception {
        ArrayList<MglaTask> newTasks = new ArrayList<>();
        ArrayList<MglaTask.Group> newGroups = new ArrayList<>();
        SQLiteCursor cursor = database.queryFinalized(
                "SELECT id, account, name, color, sort_order, uuid, updated_at FROM task_groups ORDER BY sort_order, id");
        try {
            while (cursor.next()) {
                MglaTask.Group group = new MglaTask.Group();
                group.id = cursor.longValue(0);
                group.account = cursor.intValue(1);
                group.name = cursor.stringValue(2);
                group.color = cursor.intValue(3);
                group.sortOrder = cursor.intValue(4);
                group.uuid = cursor.stringValue(5);
                group.updatedAt = cursor.longValue(6);
                newGroups.add(group);
            }
        } finally {
            cursor.dispose();
        }
        cursor = database.queryFinalized(
                "SELECT id, account, title, note, group_id, due_at, remind_at, done, done_at, created_at, sort_order, "
                        + "dialog_id, message_id, repeat_interval, repeat_unit, priority, uuid, updated_at "
                        + "FROM tasks ORDER BY sort_order, id");
        try {
            while (cursor.next()) {
                MglaTask task = new MglaTask();
                task.id = cursor.longValue(0);
                task.account = cursor.intValue(1);
                task.title = cursor.stringValue(2);
                task.note = cursor.stringValue(3);
                task.groupId = cursor.longValue(4);
                task.dueAt = cursor.longValue(5);
                task.remindAt = cursor.longValue(6);
                task.done = cursor.intValue(7) != 0;
                task.doneAt = cursor.longValue(8);
                task.createdAt = cursor.longValue(9);
                task.sortOrder = cursor.intValue(10);
                task.dialogId = cursor.longValue(11);
                task.messageId = cursor.intValue(12);
                task.repeatInterval = cursor.intValue(13);
                task.repeatUnit = cursor.intValue(14);
                task.priority = cursor.intValue(15);
                task.uuid = cursor.stringValue(16);
                task.updatedAt = cursor.longValue(17);
                // Heal: reminder-without-due left clock icon but no list/calendar date.
                if (task.dueAt <= 0 && task.remindAt > 0) {
                    task.dueAt = task.remindAt;
                }
                newTasks.add(task);
            }
        } finally {
            cursor.dispose();
        }
        for (MglaTask task : newTasks) {
            cursor = database.queryFinalized(
                    "SELECT id, title, done, sort_order, uuid FROM task_items WHERE task_id = "
                            + task.id + " ORDER BY sort_order, id");
            try {
                while (cursor.next()) {
                    MglaTask.SubItem item = new MglaTask.SubItem();
                    item.id = cursor.longValue(0);
                    item.taskId = task.id;
                    item.title = cursor.stringValue(1);
                    item.done = cursor.intValue(2) != 0;
                    item.sortOrder = cursor.intValue(3);
                    item.uuid = cursor.stringValue(4);
                    task.subItems.add(item);
                }
            } finally {
                cursor.dispose();
            }
        }
        ArrayList<Tombstone> tombs = new ArrayList<>();
        try {
            SQLiteCursor tc = database.queryFinalized(
                    "SELECT kind, account, uuid, deleted_at FROM task_tombstones");
            try {
                while (tc.next()) {
                    tombs.add(new Tombstone(tc.stringValue(0), tc.intValue(1), tc.stringValue(2), tc.longValue(3)));
                }
            } finally {
                tc.dispose();
            }
        } catch (Exception ignore) {
        }
        synchronized (tasks) {
            tasks.clear();
            tasks.addAll(newTasks);
            groups.clear();
            groups.addAll(newGroups);
        }
        synchronized (pendingTombstones) {
            pendingTombstones.clear();
            pendingTombstones.addAll(tombs);
        }
    }

    private void bindTask(SQLitePreparedStatement state, MglaTask task, boolean withId) throws Exception {
        state.bindInteger(1, task.account);
        state.bindString(2, task.title != null ? task.title : "");
        state.bindString(3, task.note != null ? task.note : "");
        state.bindLong(4, task.groupId);
        state.bindLong(5, task.dueAt);
        state.bindLong(6, task.remindAt);
        state.bindInteger(7, task.done ? 1 : 0);
        state.bindLong(8, task.doneAt);
        state.bindLong(9, task.createdAt);
        state.bindInteger(10, task.sortOrder);
        state.bindLong(11, task.dialogId);
        state.bindInteger(12, task.messageId);
        state.bindInteger(13, task.repeatInterval);
        state.bindInteger(14, task.repeatUnit);
        state.bindInteger(15, task.priority);
        state.bindString(16, task.uuid != null ? task.uuid : "");
        state.bindLong(17, task.updatedAt);
        if (withId) {
            state.bindLong(18, task.id);
        }
    }

    /** Assign UUIDs to any legacy rows for the account (persisted asynchronously). */
    public void ensureSyncIdsForAccount(int account) {
        ArrayList<MglaTask> touchTasks = new ArrayList<>();
        ArrayList<MglaTask.Group> touchGroups = new ArrayList<>();
        synchronized (tasks) {
            for (MglaTask.Group g : groups) {
                if (g.account != account) {
                    continue;
                }
                if (TextUtils.isEmpty(g.uuid)) {
                    g.uuid = MglaTasksSync.newUuid();
                    if (g.updatedAt <= 0) {
                        g.updatedAt = Math.max(1L, System.currentTimeMillis());
                    }
                    touchGroups.add(g);
                }
            }
            for (MglaTask t : tasks) {
                if (t.account != account) {
                    continue;
                }
                boolean dirty = false;
                if (TextUtils.isEmpty(t.uuid)) {
                    t.uuid = MglaTasksSync.newUuid();
                    dirty = true;
                }
                if (t.updatedAt <= 0) {
                    t.updatedAt = t.createdAt > 0 ? t.createdAt : System.currentTimeMillis();
                    dirty = true;
                }
                if (t.subItems != null) {
                    for (MglaTask.SubItem s : t.subItems) {
                        if (s != null && TextUtils.isEmpty(s.uuid)) {
                            s.uuid = MglaTasksSync.newUuid();
                            dirty = true;
                        }
                    }
                }
                if (dirty) {
                    touchTasks.add(t);
                }
            }
        }
        for (MglaTask.Group g : touchGroups) {
            persistGroupSyncFields(g);
        }
        for (MglaTask t : touchTasks) {
            persistTaskSyncFields(t);
        }
    }

    private void persistGroupSyncFields(MglaTask.Group group) {
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("UPDATE task_groups SET uuid = '" + escapeSql(group.uuid)
                        + "', updated_at = " + group.updatedAt + " WHERE id = " + group.id).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void persistTaskSyncFields(MglaTask task) {
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("UPDATE tasks SET uuid = '" + escapeSql(task.uuid)
                        + "', updated_at = " + task.updatedAt + " WHERE id = " + task.id).stepThis().dispose();
                if (task.subItems != null) {
                    for (MglaTask.SubItem s : task.subItems) {
                        if (s == null || s.id == 0) {
                            continue;
                        }
                        database.executeFast("UPDATE task_items SET uuid = '" + escapeSql(s.uuid)
                                + "' WHERE id = " + s.id).stepThis().dispose();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static String escapeSql(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("'", "''");
    }

    public JSONObject buildSyncPayload(int account, long tgId) throws Exception {
        JSONObject root = new JSONObject();
        root.put("tg_id", tgId);
        JSONArray groupsJson = new JSONArray();
        JSONArray tasksJson = new JSONArray();
        HashMap<Long, String> groupUuidById = new HashMap<>();
        synchronized (tasks) {
            for (MglaTask.Group g : groups) {
                if (g.account != account || TextUtils.isEmpty(g.uuid)) {
                    continue;
                }
                groupUuidById.put(g.id, g.uuid);
                JSONObject o = new JSONObject();
                o.put("uuid", g.uuid);
                o.put("name", g.name != null ? g.name : "");
                o.put("color", g.color);
                o.put("sortOrder", g.sortOrder);
                o.put("updatedAt", g.updatedAt);
                groupsJson.put(o);
            }
            for (MglaTask t : tasks) {
                if (t.account != account || TextUtils.isEmpty(t.uuid)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("uuid", t.uuid);
                o.put("title", t.title != null ? t.title : "");
                o.put("note", t.note != null ? t.note : "");
                String gUuid = groupUuidById.get(t.groupId);
                o.put("groupUuid", gUuid != null ? gUuid : "");
                o.put("dueAt", t.dueAt);
                o.put("remindAt", t.remindAt);
                o.put("done", t.done);
                o.put("doneAt", t.doneAt);
                o.put("createdAt", t.createdAt);
                o.put("sortOrder", t.sortOrder);
                o.put("dialogId", t.dialogId);
                o.put("messageId", t.messageId);
                o.put("repeatInterval", t.repeatInterval);
                o.put("repeatUnit", t.repeatUnit);
                o.put("priority", t.priority);
                o.put("updatedAt", t.updatedAt);
                JSONArray subs = new JSONArray();
                if (t.subItems != null) {
                    for (MglaTask.SubItem s : t.subItems) {
                        if (s == null || TextUtils.isEmpty(s.title)) {
                            continue;
                        }
                        JSONObject so = new JSONObject();
                        so.put("uuid", !TextUtils.isEmpty(s.uuid) ? s.uuid : MglaTasksSync.newUuid());
                        so.put("title", s.title);
                        so.put("done", s.done);
                        so.put("sortOrder", s.sortOrder);
                        subs.put(so);
                    }
                }
                o.put("subItems", subs);
                tasksJson.put(o);
            }
        }
        root.put("groups", groupsJson);
        root.put("tasks", tasksJson);

        JSONArray delGroups = new JSONArray();
        JSONArray delTasks = new JSONArray();
        synchronized (pendingTombstones) {
            for (Tombstone t : pendingTombstones) {
                if (t.account != account) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("uuid", t.uuid);
                o.put("deletedAt", t.deletedAt);
                if ("group".equals(t.kind)) {
                    delGroups.put(o);
                } else {
                    delTasks.put(o);
                }
            }
        }
        root.put("deleted_groups", delGroups);
        root.put("deleted_tasks", delTasks);
        return root;
    }

    /**
     * Apply merged server snapshot for this account. Returns number of tasks after apply.
     */
    public int applySyncResponse(int account, JSONObject json) throws Exception {
        if (json == null) {
            return 0;
        }
        JSONArray groupsJson = json.optJSONArray("groups");
        JSONArray tasksJson = json.optJSONArray("tasks");
        JSONArray delGroups = json.optJSONArray("deleted_groups");
        JSONArray delTasks = json.optJSONArray("deleted_tasks");

        HashSet<String> deleteGroupUuids = new HashSet<>();
        HashSet<String> deleteTaskUuids = new HashSet<>();
        if (delGroups != null) {
            for (int i = 0; i < delGroups.length(); i++) {
                Object item = delGroups.opt(i);
                if (item instanceof JSONObject) {
                    String u = ((JSONObject) item).optString("uuid", "");
                    if (!TextUtils.isEmpty(u)) {
                        deleteGroupUuids.add(u);
                    }
                } else if (item instanceof String) {
                    deleteGroupUuids.add((String) item);
                }
            }
        }
        if (delTasks != null) {
            for (int i = 0; i < delTasks.length(); i++) {
                Object item = delTasks.opt(i);
                if (item instanceof JSONObject) {
                    String u = ((JSONObject) item).optString("uuid", "");
                    if (!TextUtils.isEmpty(u)) {
                        deleteTaskUuids.add(u);
                    }
                } else if (item instanceof String) {
                    deleteTaskUuids.add((String) item);
                }
            }
        }

        HashMap<String, MglaTask.Group> localGroups = new HashMap<>();
        HashMap<String, MglaTask> localTasks = new HashMap<>();
        synchronized (tasks) {
            for (MglaTask.Group g : groups) {
                if (g.account == account && !TextUtils.isEmpty(g.uuid)) {
                    localGroups.put(g.uuid, g);
                }
            }
            for (MglaTask t : tasks) {
                if (t.account == account && !TextUtils.isEmpty(t.uuid)) {
                    localTasks.put(t.uuid, t);
                }
            }
        }

        // Apply group deletes.
        for (String uuid : deleteGroupUuids) {
            MglaTask.Group g = localGroups.remove(uuid);
            if (g != null) {
                deleteGroupLocalOnly(g.id);
            }
        }
        // Upsert groups.
        HashMap<String, Long> groupIdByUuid = new HashMap<>();
        if (groupsJson != null) {
            for (int i = 0; i < groupsJson.length(); i++) {
                JSONObject o = groupsJson.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String uuid = o.optString("uuid", "");
                if (TextUtils.isEmpty(uuid) || deleteGroupUuids.contains(uuid)) {
                    continue;
                }
                long updatedAt = o.optLong("updatedAt", 0);
                MglaTask.Group existing = localGroups.get(uuid);
                if (existing != null && existing.updatedAt > updatedAt) {
                    groupIdByUuid.put(uuid, existing.id);
                    continue;
                }
                MglaTask.Group g = existing != null ? existing : new MglaTask.Group();
                g.account = account;
                g.uuid = uuid;
                g.name = o.optString("name", "Группа");
                g.color = o.optInt("color", 0);
                g.sortOrder = o.optInt("sortOrder", 0);
                g.updatedAt = updatedAt;
                saveGroupSilent(g);
                groupIdByUuid.put(uuid, g.id);
                localGroups.put(uuid, g);
            }
        }
        synchronized (tasks) {
            for (MglaTask.Group g : groups) {
                if (g.account == account && !TextUtils.isEmpty(g.uuid)) {
                    groupIdByUuid.put(g.uuid, g.id);
                }
            }
        }

        for (String uuid : deleteTaskUuids) {
            MglaTask t = localTasks.remove(uuid);
            if (t != null) {
                deleteTaskLocalOnly(t.id);
            }
        }

        if (tasksJson != null) {
            for (int i = 0; i < tasksJson.length(); i++) {
                JSONObject o = tasksJson.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String uuid = o.optString("uuid", "");
                if (TextUtils.isEmpty(uuid) || deleteTaskUuids.contains(uuid)) {
                    continue;
                }
                long updatedAt = o.optLong("updatedAt", 0);
                MglaTask existing = localTasks.get(uuid);
                if (existing != null && existing.updatedAt > updatedAt) {
                    continue;
                }
                MglaTask t = existing != null ? existing : new MglaTask();
                t.account = account;
                t.uuid = uuid;
                t.title = o.optString("title", "");
                t.note = o.optString("note", "");
                String gUuid = o.optString("groupUuid", "");
                Long gid = !TextUtils.isEmpty(gUuid) ? groupIdByUuid.get(gUuid) : null;
                t.groupId = gid != null ? gid : 0;
                t.dueAt = o.optLong("dueAt", 0);
                t.remindAt = o.optLong("remindAt", 0);
                t.done = o.optBoolean("done", false);
                t.doneAt = o.optLong("doneAt", 0);
                t.createdAt = o.optLong("createdAt", updatedAt);
                t.sortOrder = o.optInt("sortOrder", 0);
                t.dialogId = o.optLong("dialogId", 0);
                t.messageId = o.optInt("messageId", 0);
                t.repeatInterval = o.optInt("repeatInterval", 0);
                t.repeatUnit = o.optInt("repeatUnit", 0);
                t.priority = o.optInt("priority", MglaTask.PRIORITY_NONE);
                t.updatedAt = updatedAt;
                t.subItems = new ArrayList<>();
                JSONArray subs = o.optJSONArray("subItems");
                if (subs != null) {
                    for (int b = 0; b < subs.length(); b++) {
                        JSONObject so = subs.optJSONObject(b);
                        if (so == null) {
                            continue;
                        }
                        MglaTask.SubItem s = new MglaTask.SubItem();
                        s.uuid = so.optString("uuid", MglaTasksSync.newUuid());
                        s.title = so.optString("title", "");
                        s.done = so.optBoolean("done", false);
                        s.sortOrder = so.optInt("sortOrder", b);
                        if (!TextUtils.isEmpty(s.title)) {
                            t.subItems.add(s);
                        }
                    }
                }
                saveTaskSilent(t);
                if (t.remindAt > 0 && !t.done) {
                    MglaTasksReminderScheduler.schedule(t);
                } else {
                    MglaTasksReminderScheduler.cancel(t.id);
                }
            }
        }

        // Clear acknowledged tombstones for this account.
        synchronized (pendingTombstones) {
            for (int i = pendingTombstones.size() - 1; i >= 0; i--) {
                if (pendingTombstones.get(i).account == account) {
                    pendingTombstones.remove(i);
                }
            }
        }
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("DELETE FROM task_tombstones WHERE account = " + account).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });

        notifyChangedQuiet();
        int count = 0;
        synchronized (tasks) {
            for (MglaTask t : tasks) {
                if (t.account == account) {
                    count++;
                }
            }
        }
        return count;
    }

    /** saveTask without scheduling another cloud sync (used while applying remote). */
    private void saveTaskSilent(MglaTask task) {
        // Temporarily avoid debounce storm: mutate via normal save but suppress schedule.
        suppressingSync = true;
        try {
            saveTask(task);
        } finally {
            suppressingSync = false;
        }
    }

    private void saveGroupSilent(MglaTask.Group group) {
        suppressingSync = true;
        try {
            saveGroup(group);
        } finally {
            suppressingSync = false;
        }
    }

    private void deleteTaskLocalOnly(long taskId) {
        suppressingSync = true;
        try {
            // Soft local delete without new tombstone (already on server).
            synchronized (tasks) {
                for (int a = 0; a < tasks.size(); a++) {
                    if (tasks.get(a).id == taskId) {
                        tasks.remove(a);
                        break;
                    }
                }
            }
            storageQueue.postRunnable(() -> {
                try {
                    ensureOpen();
                    database.executeFast("DELETE FROM task_items WHERE task_id = " + taskId).stepThis().dispose();
                    database.executeFast("DELETE FROM tasks WHERE id = " + taskId).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
            MglaTasksReminderScheduler.cancel(taskId);
        } finally {
            suppressingSync = false;
        }
    }

    private void deleteGroupLocalOnly(long groupId) {
        suppressingSync = true;
        try {
            synchronized (tasks) {
                for (int a = 0; a < groups.size(); a++) {
                    if (groups.get(a).id == groupId) {
                        groups.remove(a);
                        break;
                    }
                }
                for (MglaTask task : tasks) {
                    if (task.groupId == groupId) {
                        task.groupId = 0;
                    }
                }
            }
            storageQueue.postRunnable(() -> {
                try {
                    ensureOpen();
                    database.executeFast("UPDATE tasks SET group_id = 0 WHERE group_id = " + groupId).stepThis().dispose();
                    database.executeFast("DELETE FROM task_groups WHERE id = " + groupId).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        } finally {
            suppressingSync = false;
        }
    }

    private volatile boolean suppressingSync;

    private void notifyChangedQuiet() {
        ArrayList<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        for (Listener listener : copy) {
            AndroidUtilities.runOnUIThread(listener::onTasksChanged);
        }
        AndroidUtilities.runOnUIThread(MglaTasksWidgetProvider::updateAll);
    }
}
