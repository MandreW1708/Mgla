package org.telegram.messenger;

import java.util.ArrayList;

public class MglaTask {

    public long id;
    public int account;
    public String title;
    public String note;
    public long groupId;
    public long dueAt;
    public long remindAt;
    public boolean done;
    public long doneAt;
    public long createdAt;
    public int sortOrder;
    public long dialogId;
    public int messageId;
    public int repeatInterval; // 0 = нет повтора; иначе каждые N единиц
    public int repeatUnit;     // MglaTaskRepeat.UNIT_DAY / UNIT_WEEK / UNIT_MONTH
    public ArrayList<SubItem> subItems = new ArrayList<>();

    public boolean hasReminder() {
        return remindAt > 0;
    }

    public boolean hasRepeat() {
        return repeatInterval > 0 && repeatUnit >= MglaTaskRepeat.UNIT_DAY && repeatUnit <= MglaTaskRepeat.UNIT_MONTH && dueAt > 0;
    }

    public boolean isLinkedToMessage() {
        return dialogId != 0 && messageId != 0;
    }

    public static class Group {
        public long id;
        public int account;
        public String name;
        public int color;
        public int sortOrder;
    }

    public static class SubItem {
        public long id;
        public long taskId;
        public String title;
        public boolean done;
        public int sortOrder;
    }
}
