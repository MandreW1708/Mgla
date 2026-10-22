package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** JSON export / import for local tasks. */
public final class MglaTasksIO {

    private MglaTasksIO() {
    }

    public static String exportJson() throws Exception {
        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("account", UserConfig.selectedAccount);

        JSONArray groupsJson = new JSONArray();
        for (MglaTask.Group group : storage.getGroups()) {
            JSONObject g = new JSONObject();
            g.put("id", group.id);
            g.put("name", group.name != null ? group.name : "");
            g.put("color", group.color);
            g.put("sortOrder", group.sortOrder);
            groupsJson.put(g);
        }
        root.put("groups", groupsJson);

        JSONArray tasksJson = new JSONArray();
        for (MglaTask task : storage.getTasks()) {
            JSONObject t = new JSONObject();
            t.put("id", task.id);
            t.put("title", task.title != null ? task.title : "");
            t.put("note", task.note != null ? task.note : "");
            t.put("groupId", task.groupId);
            t.put("dueAt", task.dueAt);
            t.put("remindAt", task.remindAt);
            t.put("done", task.done);
            t.put("doneAt", task.doneAt);
            t.put("createdAt", task.createdAt);
            t.put("sortOrder", task.sortOrder);
            t.put("dialogId", task.dialogId);
            t.put("messageId", task.messageId);
            t.put("repeatInterval", task.repeatInterval);
            t.put("repeatUnit", task.repeatUnit);
            t.put("priority", task.priority);
            JSONArray subs = new JSONArray();
            if (task.subItems != null) {
                for (MglaTask.SubItem item : task.subItems) {
                    JSONObject s = new JSONObject();
                    s.put("title", item.title != null ? item.title : "");
                    s.put("done", item.done);
                    s.put("sortOrder", item.sortOrder);
                    subs.put(s);
                }
            }
            t.put("subItems", subs);
            tasksJson.put(t);
        }
        root.put("tasks", tasksJson);
        return root.toString(2);
    }

    public static int importJson(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            return 0;
        }
        JSONObject root = new JSONObject(json);
        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        int account = UserConfig.selectedAccount;

        java.util.HashMap<Long, Long> groupIdMap = new java.util.HashMap<>();
        JSONArray groupsJson = root.optJSONArray("groups");
        if (groupsJson != null) {
            for (int a = 0; a < groupsJson.length(); a++) {
                JSONObject g = groupsJson.getJSONObject(a);
                long oldId = g.optLong("id", 0);
                MglaTask.Group group = new MglaTask.Group();
                group.account = account;
                group.name = g.optString("name", "Группа");
                group.color = g.optInt("color", 0);
                group.sortOrder = g.optInt("sortOrder", storage.getGroups().size() + a);
                storage.saveGroup(group);
                if (oldId != 0) {
                    groupIdMap.put(oldId, group.id);
                }
            }
        }

        int imported = 0;
        JSONArray tasksJson = root.optJSONArray("tasks");
        if (tasksJson == null) {
            return 0;
        }
        for (int a = 0; a < tasksJson.length(); a++) {
            JSONObject t = tasksJson.getJSONObject(a);
            MglaTask task = new MglaTask();
            task.account = account;
            task.title = t.optString("title", "");
            if (task.title.trim().isEmpty()) {
                continue;
            }
            task.note = t.optString("note", "");
            long oldGroupId = t.optLong("groupId", 0);
            if (oldGroupId != 0 && groupIdMap.containsKey(oldGroupId)) {
                task.groupId = groupIdMap.get(oldGroupId);
            } else {
                task.groupId = 0;
            }
            task.dueAt = t.optLong("dueAt", 0);
            task.remindAt = t.optLong("remindAt", 0);
            task.done = t.optBoolean("done", false);
            task.doneAt = t.optLong("doneAt", 0);
            task.createdAt = t.optLong("createdAt", System.currentTimeMillis());
            task.sortOrder = t.optInt("sortOrder", 0);
            task.dialogId = t.optLong("dialogId", 0);
            task.messageId = t.optInt("messageId", 0);
            task.repeatInterval = t.optInt("repeatInterval", 0);
            task.repeatUnit = t.optInt("repeatUnit", 0);
            task.priority = t.optInt("priority", MglaTask.PRIORITY_NONE);
            task.subItems = new ArrayList<>();
            JSONArray subs = t.optJSONArray("subItems");
            if (subs != null) {
                for (int b = 0; b < subs.length(); b++) {
                    JSONObject s = subs.getJSONObject(b);
                    MglaTask.SubItem item = new MglaTask.SubItem();
                    item.title = s.optString("title", "");
                    item.done = s.optBoolean("done", false);
                    item.sortOrder = s.optInt("sortOrder", b);
                    if (!item.title.trim().isEmpty()) {
                        task.subItems.add(item);
                    }
                }
            }
            storage.saveTask(task);
            if (task.remindAt > 0 && !task.done) {
                MglaTasksReminderScheduler.schedule(task);
            }
            imported++;
        }
        return imported;
    }
}
