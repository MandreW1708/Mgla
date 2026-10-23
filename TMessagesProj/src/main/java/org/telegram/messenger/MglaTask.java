package org.telegram.messenger;

import java.util.ArrayList;

public class MglaTask {

    public static final int PRIORITY_NONE = 0;
    public static final int PRIORITY_LOW = 1;
    public static final int PRIORITY_NORMAL = 2;
    public static final int PRIORITY_HIGH = 3;

    public long id;
    public int account;
    /** Stable id for cloud sync across devices (UUID string). */
    public String uuid;
    public String title;
    public String note;
    public long groupId;
    public long dueAt;
    public long remindAt;
    public boolean done;
    public long doneAt;
    public long createdAt;
    /** Last local/cloud mutation time (ms) — LWW sync. */
    public long updatedAt;
    public int sortOrder;
    public long dialogId;
    public int messageId;
    public int repeatInterval; // 0 = нет повтора; иначе каждые N единиц
    public int repeatUnit;     // MglaTaskRepeat.UNIT_*
    public int priority = PRIORITY_NONE;
    public ArrayList<SubItem> subItems = new ArrayList<>();

    public boolean hasReminder() {
        return remindAt > 0;
    }

    public boolean hasRepeat() {
        return repeatInterval > 0
                && repeatUnit >= MglaTaskRepeat.UNIT_DAY
                && repeatUnit <= MglaTaskRepeat.UNIT_YEAR
                && dueAt > 0;
    }

    public boolean isLinkedToMessage() {
        return dialogId != 0 && messageId != 0;
    }

    public static String priorityLabel(int priority) {
        switch (priority) {
            case PRIORITY_LOW:
                return "Низкий";
            case PRIORITY_NORMAL:
                return "Обычный";
            case PRIORITY_HIGH:
                return "Высокий";
            default:
                return "Нет";
        }
    }

    public static class Group {
        public long id;
        public int account;
        public String uuid;
        public String name;
        public int color;
        public int sortOrder;
        public long updatedAt;
    }

    public static class SubItem {
        public long id;
        public long taskId;
        public String uuid;
        public String title;
        public boolean done;
        public int sortOrder;
    }
}
