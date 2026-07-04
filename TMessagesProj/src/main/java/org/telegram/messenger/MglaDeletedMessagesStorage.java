package org.telegram.messenger;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import android.util.SparseBooleanArray;

import androidx.collection.LongSparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public class MglaDeletedMessagesStorage {

    private static final String TABLE = "mgla_deleted_messages";

    public static void ensureTable(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.executeFast(
                "CREATE TABLE IF NOT EXISTS " + TABLE + "(" +
                    "mid INTEGER, " +
                    "uid INTEGER, " +
                    "topic_id INTEGER, " +
                    "date INTEGER, " +
                    "deleted_date INTEGER, " +
                    "data BLOB, " +
                    "PRIMARY KEY(mid, uid)" +
                ")"
            ).stepThis().dispose();
            database.executeFast(
                "CREATE INDEX IF NOT EXISTS mgla_deleted_messages_uid_date ON " + TABLE + "(uid, date DESC)"
            ).stepThis().dispose();
        } catch (SQLiteException e) {
            FileLog.e(e);
        }
    }

    public static void saveMessageIfEnabled(SQLiteDatabase database, int currentAccount, long dialogId, TLRPC.Message message, long topicId) {
        if (!MglaSpyConfig.isSaveDeletedMessagesEnabled() || database == null || message == null) {
            return;
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }

        NativeByteBuffer data = null;
        SQLitePreparedStatement state = null;
        try {
            data = new NativeByteBuffer(message.getObjectSize());
            message.serializeToStream(data);

            state = database.executeFast(
                "REPLACE INTO " + TABLE + "(mid, uid, topic_id, date, deleted_date, data) VALUES(?, ?, ?, ?, ?, ?)"
            );
            state.requery();
            state.bindInteger(1, message.id);
            state.bindLong(2, dialogId);
            state.bindLong(3, topicId);
            state.bindInteger(4, message.date);
            state.bindInteger(5, ConnectionsManager.getInstance(currentAccount).getCurrentTime());
            state.bindByteBuffer(6, data);
            state.step();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (state != null) {
                state.dispose();
            }
            if (data != null) {
                data.reuse();
            }
        }
    }

    public static void markMessageAsSavedDeleted(MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        messageObject.mglaSavedDeleted = true;
        messageObject.deleted = false;
        messageObject.deletedByThanos = false;
    }

    public static void saveMessageObjectIfEnabled(int currentAccount, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        long dialogId = messageObject.getDialogId();
        long topicId = MessageObject.getTopicId(currentAccount, messageObject.messageOwner, true);
        storage.getStorageQueue().postRunnable(() -> {
            MglaDeletedMessagesStorage.saveMessageIfEnabled(storage.getDatabase(), currentAccount, dialogId, messageObject.messageOwner, topicId);
        });
    }

    public static TLRPC.Message loadDeletedMessageById(SQLiteDatabase database, long dialogId, int messageId) {
        if (database == null || messageId <= 0) {
            return null;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(
                "SELECT data FROM " + TABLE + " WHERE uid = " + dialogId + " AND mid = " + messageId + " LIMIT 1"
            );
            if (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data != null) {
                    long currentUserId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    message.readAttachPath(data, currentUserId);
                    data.reuse();
                    return message;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return null;
    }

    public static void mergeIntoLoadedMessages(
        int currentAccount,
        SQLiteDatabase database,
        long dialogId,
        long topicId,
        ArrayList<MessageObject> objects,
        LongSparseArray<TLRPC.User> usersDict,
        LongSparseArray<TLRPC.Chat> chatsDict
    ) {
        if (!MglaSpyConfig.isSaveDeletedMessagesEnabled() || database == null || objects == null || objects.isEmpty()) {
            return;
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }

        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        SparseBooleanArray existingIds = new SparseBooleanArray();
        for (int i = 0, size = objects.size(); i < size; i++) {
            MessageObject obj = objects.get(i);
            int id = obj.getId();
            if (id > 0) {
                minId = Math.min(minId, id);
                maxId = Math.max(maxId, id);
                existingIds.put(id, true);
            }
        }
        if (minId == Integer.MAX_VALUE) {
            return;
        }

        ArrayList<TLRPC.Message> deleted = loadDeletedMessages(database, dialogId, topicId, 500);
        if (deleted.isEmpty()) {
            return;
        }

        deleted.sort((a, b) -> {
            if (a.date != b.date) {
                return Integer.compare(a.date, b.date);
            }
            return Integer.compare(a.id, b.id);
        });

        for (int i = 0, size = deleted.size(); i < size; i++) {
            TLRPC.Message tlMsg = deleted.get(i);
            int id = tlMsg.id;
            if (id < minId || id > maxId) {
                continue;
            }
            if (topicId != 0) {
                long msgTopic = MessageObject.getTopicId(currentAccount, tlMsg, true);
                if (msgTopic != topicId) {
                    continue;
                }
            }
            if (existingIds.get(id)) {
                for (int j = 0, objSize = objects.size(); j < objSize; j++) {
                    MessageObject existing = objects.get(j);
                    if (existing.getId() == id) {
                        existing.mglaSavedDeleted = true;
                        existing.deleted = false;
                        existing.deletedByThanos = false;
                        break;
                    }
                }
                continue;
            }
            MessageObject obj = new MessageObject(currentAccount, tlMsg, usersDict, chatsDict, true, false, false);
            obj.mglaSavedDeleted = true;
            obj.deleted = false;
            objects.add(findInsertIndex(objects, obj), obj);
            existingIds.put(id, true);
        }
    }

    private static int compareMessageOrder(MessageObject a, MessageObject b) {
        if (a.messageOwner.date != b.messageOwner.date) {
            return Integer.compare(a.messageOwner.date, b.messageOwner.date);
        }
        return Integer.compare(a.getId(), b.getId());
    }

    private static boolean isAscendingOrder(ArrayList<MessageObject> objects) {
        for (int i = 0, size = objects.size() - 1; i < size; i++) {
            MessageObject a = objects.get(i);
            MessageObject b = objects.get(i + 1);
            if (a.getId() <= 0 || b.getId() <= 0) {
                continue;
            }
            int cmp = compareMessageOrder(a, b);
            if (cmp != 0) {
                return cmp < 0;
            }
        }
        return true;
    }

    private static int findInsertIndex(ArrayList<MessageObject> objects, MessageObject toInsert) {
        boolean ascending = isAscendingOrder(objects);
        for (int i = 0, size = objects.size(); i < size; i++) {
            MessageObject m = objects.get(i);
            if (m.getId() <= 0) {
                continue;
            }
            int cmp = compareMessageOrder(m, toInsert);
            if (ascending ? cmp > 0 : cmp < 0) {
                return i;
            }
        }
        return objects.size();
    }

    public static ArrayList<TLRPC.Message> loadDeletedMessages(SQLiteDatabase database, long dialogId, long topicId, int limit) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (database == null || !MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return result;
        }

        SQLiteCursor cursor = null;
        try {
            String query = topicId != 0
                ? "SELECT data FROM " + TABLE + " WHERE uid = " + dialogId + " AND topic_id = " + topicId + " ORDER BY date DESC LIMIT " + Math.max(1, limit)
                : "SELECT data FROM " + TABLE + " WHERE uid = " + dialogId + " ORDER BY date DESC LIMIT " + Math.max(1, limit);
            cursor = database.queryFinalized(query);
            long currentUserId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                message.readAttachPath(data, currentUserId);
                data.reuse();
                if (message != null) {
                    result.add(message);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    public static int getDeletedMessagesCount(SQLiteDatabase database, long dialogId) {
        if (database == null) {
            return 0;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT COUNT(*) FROM " + TABLE + " WHERE uid = " + dialogId);
            if (cursor.next()) {
                return cursor.intValue(0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return 0;
    }
}
