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
    public ArrayList<SubItem> subItems = new ArrayList<>();

    public boolean hasReminder() {
        return remindAt > 0;
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
