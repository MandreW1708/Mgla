package org.telegram.messenger;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;

import java.io.File;
import java.util.ArrayList;

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
        if (task.createdAt == 0) {
            task.createdAt = System.currentTimeMillis();
        }
        MglaTask existing = getTask(task.id);
        if (existing != null) {
            existing.account = task.account;
            existing.title = task.title;
            existing.note = task.note;
            existing.groupId = task.groupId;
            existing.dueAt = task.dueAt;
            existing.remindAt = task.remindAt;
            existing.done = task.done;
            existing.doneAt = task.doneAt;
            existing.createdAt = task.createdAt;
            existing.sortOrder = task.sortOrder;
            existing.dialogId = task.dialogId;
            existing.messageId = task.messageId;
            existing.subItems = task.subItems;
        } else if (task.id != 0) {
            synchronized (tasks) {
                tasks.add(task);
            }
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                if (task.id == 0) {
                    SQLitePreparedStatement state = database.executeFast("INSERT INTO tasks(account, title, note, group_id, due_at, remind_at, done, done_at, created_at, sort_order, dialog_id, message_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    state.requery();
                    bindTask(state, task, false);
                    state.stepThis().dispose();
                    task.id = database.executeInt("SELECT last_insert_rowid()");
                    synchronized (tasks) {
                        tasks.add(task);
                    }
                    AndroidUtilities.runOnUIThread(this::notifyChanged);
                } else {
                    SQLitePreparedStatement state = database.executeFast("UPDATE tasks SET account = ?, title = ?, note = ?, group_id = ?, due_at = ?, remind_at = ?, done = ?, done_at = ?, created_at = ?, sort_order = ?, dialog_id = ?, message_id = ? WHERE id = ?");
                    state.requery();
                    bindTask(state, task, true);
                    state.stepThis().dispose();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void deleteTask(long taskId) {
        synchronized (tasks) {
            for (int a = 0; a < tasks.size(); a++) {
                if (tasks.get(a).id == taskId) {
                    tasks.remove(a);
                    break;
                }
            }
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("DELETE FROM task_items WHERE task_id = " + taskId).stepThis().dispose();
                database.executeFast("DELETE FROM tasks WHERE id = " + taskId).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void setTaskDone(long taskId, boolean done) {
        MglaTask task = getTask(taskId);
        if (task != null) {
            task.done = done;
            task.doneAt = done ? System.currentTimeMillis() : 0;
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("UPDATE tasks SET done = " + (done ? 1 : 0) + ", done_at = " + (done ? System.currentTimeMillis() : 0) + " WHERE id = " + taskId).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void saveGroup(MglaTask.Group group) {
        if (group == null) {
            return;
        }
        MglaTask.Group existing = null;
        synchronized (tasks) {
            for (MglaTask.Group g : groups) {
                if (g.id == group.id) {
                    existing = g;
                    break;
                }
            }
        }
        if (existing != null) {
            existing.account = group.account;
            existing.name = group.name;
            existing.color = group.color;
            existing.sortOrder = group.sortOrder;
        } else if (group.id != 0) {
            synchronized (tasks) {
                groups.add(group);
            }
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                if (group.id == 0) {
                    SQLitePreparedStatement state = database.executeFast("INSERT INTO task_groups(account, name, color, sort_order) VALUES(?, ?, ?, ?)");
                    state.requery();
                    state.bindInteger(1, group.account);
                    state.bindString(2, group.name != null ? group.name : "");
                    state.bindInteger(3, group.color);
                    state.bindInteger(4, group.sortOrder);
                    state.stepThis().dispose();
                    group.id = database.executeInt("SELECT last_insert_rowid()");
                    synchronized (tasks) {
                        groups.add(group);
                    }
                    AndroidUtilities.runOnUIThread(this::notifyChanged);
                } else {
                    SQLitePreparedStatement state = database.executeFast("UPDATE task_groups SET account = ?, name = ?, color = ?, sort_order = ? WHERE id = ?");
                    state.requery();
                    state.bindInteger(1, group.account);
                    state.bindString(2, group.name != null ? group.name : "");
                    state.bindInteger(3, group.color);
                    state.bindInteger(4, group.sortOrder);
                    state.bindLong(5, group.id);
                    state.stepThis().dispose();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void deleteGroup(long groupId) {
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
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                database.executeFast("UPDATE tasks SET group_id = 0 WHERE group_id = " + groupId).stepThis().dispose();
                database.executeFast("DELETE FROM task_groups WHERE id = " + groupId).stepThis().dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void saveSubItem(long taskId, MglaTask.SubItem item) {
        if (item == null) {
            return;
        }
        MglaTask task = getTask(taskId);
        MglaTask.SubItem existing = null;
        if (task != null) {
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
            } else if (item.id != 0) {
                item.taskId = taskId;
                task.subItems.add(item);
            }
        }
        notifyChanged();
        storageQueue.postRunnable(() -> {
            try {
                ensureOpen();
                if (item.id == 0) {
                    SQLitePreparedStatement state = database.executeFast("INSERT INTO task_items(task_id, title, done, sort_order) VALUES(?, ?, ?, ?)");
                    state.requery();
                    state.bindLong(1, taskId);
                    state.bindString(2, item.title != null ? item.title : "");
                    state.bindInteger(3, item.done ? 1 : 0);
                    state.bindInteger(4, item.sortOrder);
                    state.stepThis().dispose();
                    item.id = database.executeInt("SELECT last_insert_rowid()");
                    item.taskId = taskId;
                    if (task != null) {
                        task.subItems.add(item);
                    }
                    AndroidUtilities.runOnUIThread(this::notifyChanged);
                } else {
                    SQLitePreparedStatement state = database.executeFast("UPDATE task_items SET task_id = ?, title = ?, done = ?, sort_order = ? WHERE id = ?");
                    state.requery();
                    state.bindLong(1, taskId);
                    state.bindString(2, item.title != null ? item.title : "");
                    state.bindInteger(3, item.done ? 1 : 0);
                    state.bindInteger(4, item.sortOrder);
                    state.bindLong(5, item.id);
                    state.stepThis().dispose();
                }
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
        MglaTask task = getTask(taskId);
        if (task != null) {
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
    }

    private void loadInternal() throws Exception {
        ArrayList<MglaTask> newTasks = new ArrayList<>();
        ArrayList<MglaTask.Group> newGroups = new ArrayList<>();
        SQLiteCursor cursor = database.queryFinalized("SELECT id, account, name, color, sort_order FROM task_groups ORDER BY sort_order, id");
        try {
            while (cursor.next()) {
                MglaTask.Group group = new MglaTask.Group();
                group.id = cursor.longValue(0);
                group.account = cursor.intValue(1);
                group.name = cursor.stringValue(2);
                group.color = cursor.intValue(3);
                group.sortOrder = cursor.intValue(4);
                newGroups.add(group);
            }
        } finally {
            cursor.dispose();
        }
        cursor = database.queryFinalized("SELECT id, account, title, note, group_id, due_at, remind_at, done, done_at, created_at, sort_order, dialog_id, message_id FROM tasks ORDER BY sort_order, id");
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
                newTasks.add(task);
            }
        } finally {
            cursor.dispose();
        }
        for (MglaTask task : newTasks) {
            cursor = database.queryFinalized("SELECT id, title, done, sort_order FROM task_items WHERE task_id = " + task.id + " ORDER BY sort_order, id");
            try {
                while (cursor.next()) {
                    MglaTask.SubItem item = new MglaTask.SubItem();
                    item.id = cursor.longValue(0);
                    item.taskId = task.id;
                    item.title = cursor.stringValue(1);
                    item.done = cursor.intValue(2) != 0;
                    item.sortOrder = cursor.intValue(3);
                    task.subItems.add(item);
                }
            } finally {
                cursor.dispose();
            }
        }
        synchronized (tasks) {
            tasks.clear();
            tasks.addAll(newTasks);
            groups.clear();
            groups.addAll(newGroups);
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
        if (withId) {
            state.bindLong(13, task.id);
        }
    }
}
