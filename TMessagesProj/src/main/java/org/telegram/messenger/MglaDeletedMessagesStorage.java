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

    public static void saveMessageObjectsBatchIfEnabled(int currentAccount, ArrayList<MessageObject> messageObjects) {
        if (messageObjects == null || messageObjects.isEmpty()) {
            return;
        }
        if (!MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return;
        }
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        final ArrayList<MessageObject> batch = new ArrayList<>(messageObjects);
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteDatabase database = storage.getDatabase();
            if (database == null) return;
            saveMessagesBatch(database, currentAccount, batch);
        });
    }

    private static void saveMessagesBatch(SQLiteDatabase database, int currentAccount, ArrayList<MessageObject> batch) {
        if (batch == null || batch.isEmpty() || database == null) {
            return;
        }
        try {
            database.executeFast("BEGIN TRANSACTION").stepThis().dispose();
        } catch (SQLiteException e) {
            FileLog.e(e);
            return;
        }
        SQLitePreparedStatement state = null;
        try {
            state = database.executeFast(
                "REPLACE INTO " + TABLE + "(mid, uid, topic_id, date, deleted_date, data) VALUES(?, ?, ?, ?, ?, ?)"
            );
            int currentTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            for (int i = 0, size = batch.size(); i < size; i++) {
                MessageObject obj = batch.get(i);
                if (obj == null || obj.messageOwner == null) continue;
                TLRPC.Message message = obj.messageOwner;
                long dialogId = obj.getDialogId();
                if (DialogObject.isEncryptedDialog(dialogId)) {
                    continue;
                }
                long topicId = MessageObject.getTopicId(currentAccount, message, true);
                NativeByteBuffer data = null;
                try {
                    data = new NativeByteBuffer(message.getObjectSize());
                    message.serializeToStream(data);
                    state.requery();
                    state.bindInteger(1, message.id);
                    state.bindLong(2, dialogId);
                    state.bindLong(3, topicId);
                    state.bindInteger(4, message.date);
                    state.bindInteger(5, currentTime);
                    state.bindByteBuffer(6, data);
                    state.step();
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (data != null) {
                        data.reuse();
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (state != null) {
                state.dispose();
            }
            try {
                database.executeFast("COMMIT").stepThis().dispose();
            } catch (SQLiteException e) {
                FileLog.e(e);
                try {
                    database.executeFast("ROLLBACK").stepThis().dispose();
                } catch (SQLiteException ex) {
                    FileLog.e(ex);
                }
            }
        }
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

        // Use range-filtered SQL query instead of loading all and filtering in memory
        ArrayList<TLRPC.Message> deleted = loadDeletedMessagesInRange(database, dialogId, topicId, minId, maxId, 500);
        if (deleted.isEmpty()) {
            return;
        }

        deleted.sort((a, b) -> {
            if (a.date != b.date) {
                return Integer.compare(a.date, b.date);
            }
            return Integer.compare(a.id, b.id);
        });

        // Build a fast lookup for existing objects by id
        SparseBooleanArray existingIdLookup = new SparseBooleanArray(existingIds.size());
        for (int i = 0; i < existingIds.size(); i++) {
            existingIdLookup.put(existingIds.keyAt(i), true);
        }

        boolean ascending = isAscendingOrder(objects);
        int size = objects.size();
        for (int i = 0, dSize = deleted.size(); i < dSize; i++) {
            TLRPC.Message tlMsg = deleted.get(i);
            int id = tlMsg.id;
            if (topicId != 0) {
                long msgTopic = MessageObject.getTopicId(currentAccount, tlMsg, true);
                if (msgTopic != topicId) {
                    continue;
                }
            }
            if (existingIdLookup.get(id)) {
                for (int j = 0; j < size; j++) {
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
            int insertIdx = findInsertIndexBinary(objects, obj, ascending, size);
            objects.add(insertIdx, obj);
            existingIdLookup.put(id, true);
            size++;
        }
    }

    public static int compareMessageOrder(MessageObject a, MessageObject b) {
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

    private static int findInsertIndexBinary(ArrayList<MessageObject> objects, MessageObject toInsert, boolean ascending, int size) {
        int lo = 0;
        int hi = size - 1;
        int insertDate = toInsert.messageOwner.date;
        int insertId = toInsert.getId();

        // Binary search for insertion point
        int result = size;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            MessageObject m = objects.get(mid);
            if (m.getId() <= 0) {
                // Skip invalid entries — fall back to linear from here
                lo = mid + 1;
                continue;
            }
            int cmp;
            if (m.messageOwner.date != insertDate) {
                cmp = Integer.compare(m.messageOwner.date, insertDate);
            } else {
                cmp = Integer.compare(m.getId(), insertId);
            }
            boolean goLeft = ascending ? cmp > 0 : cmp < 0;
            if (goLeft) {
                result = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return result;
    }

    private static int findInsertIndex(ArrayList<MessageObject> objects, MessageObject toInsert) {
        boolean ascending = isAscendingOrder(objects);
        int size = objects.size();
        return findInsertIndexBinary(objects, toInsert, ascending, size);
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

    public static ArrayList<TLRPC.Message> loadDeletedMessagesInRange(SQLiteDatabase database, long dialogId, long topicId, int minId, int maxId, int limit) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (database == null || !MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return result;
        }

        SQLiteCursor cursor = null;
        try {
            String query;
            if (topicId != 0) {
                query = "SELECT data FROM " + TABLE + " WHERE uid = " + dialogId + " AND topic_id = " + topicId + 
                        " AND mid >= " + minId + " AND mid <= " + maxId + " ORDER BY date ASC LIMIT " + Math.max(1, limit);
            } else {
                query = "SELECT data FROM " + TABLE + " WHERE uid = " + dialogId + 
                        " AND mid >= " + minId + " AND mid <= " + maxId + " ORDER BY date ASC LIMIT " + Math.max(1, limit);
            }
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

    public static void clearAllDeletedMessages(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.executeFast("DELETE FROM " + TABLE).stepThis().dispose();
        } catch (SQLiteException e) {
            FileLog.e(e);
        }
    }

    public static void clearAllDeletedMessagesForAllAccounts() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                final int account = a;
                MessagesStorage storage = MessagesStorage.getInstance(account);
                storage.getStorageQueue().postRunnable(() -> {
                    clearAllDeletedMessages(storage.getDatabase());
                });
            }
        }
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
