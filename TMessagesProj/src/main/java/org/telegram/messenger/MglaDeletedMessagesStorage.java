package org.telegram.messenger;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;

import androidx.collection.LongSparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

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
        if (!shouldSaveForDialog(currentAccount, dialogId, message)) {
            return;
        }
        if (!shouldSaveForMessageType(message)) {
            return;
        }

        NativeByteBuffer data = null;
        SQLitePreparedStatement state = null;
        try {
            preserveLocalMediaCopyIfPresent(currentAccount, message);
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
                if (!shouldSaveForDialog(currentAccount, dialogId, message)) {
                    continue;
                }
                if (!shouldSaveForMessageType(message)) {
                    continue;
                }
                long topicId = MessageObject.getTopicId(currentAccount, message, true);
                NativeByteBuffer data = null;
                try {
                    preserveLocalMediaCopyIfPresent(currentAccount, message);
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

    public static TLRPC.Message loadDeletedMessageById(SQLiteDatabase database, int currentAccount, long dialogId, int messageId) {
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
                    restoreLocalMediaPath(currentAccount, message);
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
        if (!shouldSaveForDialog(currentAccount, dialogId, null)) {
            return;
        }

        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        HashSet<Integer> existingIds = new HashSet<>(objects.size());
        for (int i = 0, size = objects.size(); i < size; i++) {
            MessageObject obj = objects.get(i);
            int id = obj.getId();
            if (id > 0) {
                minId = Math.min(minId, id);
                maxId = Math.max(maxId, id);
                existingIds.add(id);
            }
        }
        if (minId == Integer.MAX_VALUE) {
            return;
        }

        // Use range-filtered SQL query instead of loading all and filtering in memory
        ArrayList<TLRPC.Message> deleted = loadDeletedMessagesInRange(database, currentAccount, dialogId, topicId, minId, maxId, 500);
        if (deleted.isEmpty()) {
            return;
        }

        deleted.sort((a, b) -> {
            if (a.date != b.date) {
                return Integer.compare(a.date, b.date);
            }
            return Integer.compare(a.id, b.id);
        });

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
            if (existingIds.contains(id)) {
                for (int j = 0; j < size; j++) {
                    MessageObject existing = objects.get(j);
                    if (existing.getId() == id) {
                        existing.mglaSavedDeleted = true;
                        existing.deleted = false;
                        existing.deletedByThanos = false;
                        if (tlMsg.attachPath != null && tlMsg.attachPath.length() > 0) {
                            existing.messageOwner.attachPath = tlMsg.attachPath;
                            existing.attachPathExists = new File(tlMsg.attachPath).exists();
                        }
                        existing.checkMediaExistance(false);
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
            existingIds.add(id);
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

    public static ArrayList<TLRPC.Message> loadDeletedMessages(SQLiteDatabase database, int currentAccount, long dialogId, long topicId, int limit) {
        return loadDeletedMessages(database, currentAccount, dialogId, topicId, limit, 0);
    }

    public static ArrayList<TLRPC.Message> loadDeletedMessages(SQLiteDatabase database, int currentAccount, long dialogId, long topicId, int limit, int offset) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (database == null || !MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return result;
        }

        SQLiteCursor cursor = null;
        try {
            int safeLimit = Math.max(1, limit);
            int safeOffset = Math.max(0, offset);
            String query = topicId != 0
                ? "SELECT data FROM " + TABLE + " WHERE uid = " + dialogId + " AND topic_id = " + topicId + " ORDER BY date DESC LIMIT " + safeLimit + " OFFSET " + safeOffset
                : "SELECT data FROM " + TABLE + " WHERE uid = " + dialogId + " ORDER BY date DESC LIMIT " + safeLimit + " OFFSET " + safeOffset;
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
                    restoreLocalMediaPath(currentAccount, message);
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

    public static ArrayList<TLRPC.Message> searchDeletedMessages(SQLiteDatabase database, int currentAccount, long dialogId, long topicId, String query, int limit, int offset) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (database == null || !MglaSpyConfig.isSaveDeletedMessagesEnabled() || query == null || query.isEmpty()) {
            return result;
        }

        SQLiteCursor cursor = null;
        try {
            int safeLimit = Math.max(1, limit);
            int safeOffset = Math.max(0, offset);
            String escapedQuery = query.replace("'", "''");
            String likeQuery = "'%" + escapedQuery + "%'";
            String sql = topicId != 0
                ? "SELECT data FROM " + TABLE + " WHERE uid = " + dialogId + " AND topic_id = " + topicId + " AND data LIKE " + likeQuery + " ORDER BY date DESC LIMIT " + safeLimit + " OFFSET " + safeOffset
                : "SELECT data FROM " + TABLE + " WHERE uid = " + dialogId + " AND data LIKE " + likeQuery + " ORDER BY date DESC LIMIT " + safeLimit + " OFFSET " + safeOffset;
            cursor = database.queryFinalized(sql);
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
                    restoreLocalMediaPath(currentAccount, message);
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

    public static ArrayList<TLRPC.Message> loadDeletedMessagesInRange(SQLiteDatabase database, int currentAccount, long dialogId, long topicId, int minId, int maxId, int limit) {
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
                    restoreLocalMediaPath(currentAccount, message);
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

    private static void preserveLocalMediaCopyIfPresent(int currentAccount, TLRPC.Message message) {
        if (message == null) {
            return;
        }
        File sourceFile = null;
        if (message.attachPath != null && message.attachPath.length() != 0) {
            File attachFile = new File(message.attachPath);
            if (attachFile.exists() && attachFile.isFile()) {
                sourceFile = attachFile;
            }
        }
        if (sourceFile == null) {
            File pathToMessage = FileLoader.getInstance(currentAccount).getPathToMessage(message, false, true);
            if (pathToMessage != null && pathToMessage.exists() && pathToMessage.isFile()) {
                sourceFile = pathToMessage;
            }
        }
        if (sourceFile == null) {
            return;
        }

        File destination = new File(getDeletedMediaDir(currentAccount), buildDeletedMediaFileName(message, sourceFile));
        if (!destination.exists()) {
            try {
                AndroidUtilities.copyFile(sourceFile, destination);
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
        }
        message.attachPath = destination.getAbsolutePath();
        restoreLocalMediaPath(currentAccount, message);
    }

    private static void restoreLocalMediaPath(int currentAccount, TLRPC.Message message) {
        if (message == null || message.attachPath == null || message.attachPath.length() == 0) {
            return;
        }
        File file = new File(message.attachPath);
        if (!file.exists()) {
            return;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            FileLoader.getInstance(currentAccount).setLocalPathTo(media.document, file.getAbsolutePath());
        } else if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null && !media.photo.sizes.isEmpty()) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
            if (size != null) {
                FileLoader.getInstance(currentAccount).setLocalPathTo(size, file.getAbsolutePath());
            }
        }
    }

    private static File getDeletedMediaDir(int currentAccount) {
        File baseDir = new File(ApplicationLoader.applicationContext.getFilesDir(), "mgla_deleted_media");
        File accountDir = new File(baseDir, "account_" + currentAccount);
        if (!accountDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            accountDir.mkdirs();
        }
        return accountDir;
    }

    private static String buildDeletedMediaFileName(TLRPC.Message message, File sourceFile) {
        String originalName = null;
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            originalName = FileLoader.getAttachFileName(media.document);
        } else if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null && !media.photo.sizes.isEmpty()) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
            if (size != null) {
                originalName = FileLoader.getAttachFileName(size);
            }
        }
        if (originalName == null || originalName.isEmpty()) {
            originalName = sourceFile.getName();
        }
        return Math.abs(message.dialog_id) + "_" + message.id + "_" + originalName;
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

    public static int getMaxDeletedDate(SQLiteDatabase database, long dialogId, long topicId) {
        if (database == null) {
            return 0;
        }
        SQLiteCursor cursor = null;
        try {
            String query = topicId != 0
                ? "SELECT MAX(deleted_date) FROM " + TABLE + " WHERE uid = " + dialogId + " AND topic_id = " + topicId
                : "SELECT MAX(deleted_date) FROM " + TABLE + " WHERE uid = " + dialogId;
            cursor = database.queryFinalized(query);
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

    public static int countDeletedSince(SQLiteDatabase database, long dialogId, long topicId, int sinceDeletedDate) {
        if (database == null || sinceDeletedDate <= 0) {
            return 0;
        }
        SQLiteCursor cursor = null;
        try {
            String query = topicId != 0
                ? "SELECT COUNT(*) FROM " + TABLE + " WHERE uid = " + dialogId + " AND topic_id = " + topicId + " AND deleted_date > " + sinceDeletedDate
                : "SELECT COUNT(*) FROM " + TABLE + " WHERE uid = " + dialogId + " AND deleted_date > " + sinceDeletedDate;
            cursor = database.queryFinalized(query);
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

    public static void deleteDeletedMessage(SQLiteDatabase database, long dialogId, int messageId) {
        if (database == null || messageId <= 0) {
            return;
        }
        try {
            database.executeFast("DELETE FROM " + TABLE + " WHERE mid = " + messageId + " AND uid = " + dialogId).stepThis().dispose();
        } catch (SQLiteException e) {
            FileLog.e(e);
        }
    }

    public static int deleteDeletedMessagesByType(SQLiteDatabase database, int currentAccount, long dialogId, long topicId, int msgType) {
        if (database == null) {
            return 0;
        }
        ArrayList<Integer> idsToDelete = new ArrayList<>();
        SQLiteCursor cursor = null;
        try {
            String query = topicId != 0
                ? "SELECT mid, data FROM " + TABLE + " WHERE uid = " + dialogId + " AND topic_id = " + topicId
                : "SELECT mid, data FROM " + TABLE + " WHERE uid = " + dialogId;
            cursor = database.queryFinalized(query);
            long currentUserId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
            while (cursor.next()) {
                int mid = cursor.intValue(0);
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) {
                    continue;
                }
                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null && resolveMessageType(message) == msgType) {
                        idsToDelete.add(mid);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    data.reuse();
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        if (idsToDelete.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        try {
            database.executeFast("BEGIN TRANSACTION").stepThis().dispose();
            for (int i = 0; i < idsToDelete.size(); i++) {
                database.executeFast("DELETE FROM " + TABLE + " WHERE mid = " + idsToDelete.get(i) + " AND uid = " + dialogId).stepThis().dispose();
                deleted++;
            }
            database.executeFast("COMMIT").stepThis().dispose();
        } catch (SQLiteException e) {
            FileLog.e(e);
            try {
                database.executeFast("ROLLBACK").stepThis().dispose();
            } catch (SQLiteException ex) {
                FileLog.e(ex);
            }
        }
        return deleted;
    }

    public static void deleteDeletedMessageAsync(int currentAccount, long dialogId, int messageId) {
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            deleteDeletedMessage(storage.getDatabase(), dialogId, messageId);
        });
    }

    public static boolean shouldSaveForDialog(int currentAccount, long dialogId, TLRPC.Message message) {
        int chatType = resolveChatType(currentAccount, dialogId, message);
        return MglaSpyConfig.isSaveDeletedForCategoryEnabled(chatType);
    }

    public static int resolveChatType(int currentAccount, long dialogId, TLRPC.Message message) {
        if (DialogObject.isUserDialog(dialogId)) {
            return MglaSpyConfig.CHAT_TYPE_PRIVATE;
        }
        if (DialogObject.isChatDialog(dialogId)) {
            long chatId = -dialogId;
            MessagesController controller = MessagesController.getInstance(currentAccount);
            TLRPC.Chat chat = controller.getChat(chatId);
            if (chat == null) {
                return MglaSpyConfig.CHAT_TYPE_GROUP_SMALL;
            }
            if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                return MglaSpyConfig.CHAT_TYPE_CHANNEL;
            }
            // Megagroup or regular group - check if it's small or large
            if (chat.participants_count <= 100) {
                return MglaSpyConfig.CHAT_TYPE_GROUP_SMALL;
            } else {
                return MglaSpyConfig.CHAT_TYPE_GROUP_LARGE;
            }
        }
        return MglaSpyConfig.CHAT_TYPE_GROUP_SMALL;
    }

    public static int resolveMessageType(TLRPC.Message message) {
        if (message == null) {
            return MglaSpyConfig.MSG_TYPE_TEXT;
        }
        if (MessageObject.isVoiceMessage(message)) {
            return MglaSpyConfig.MSG_TYPE_VOICE;
        }
        if (MessageObject.isRoundVideoMessage(message)) {
            return MglaSpyConfig.MSG_TYPE_ROUND;
        }
        if (MessageObject.isPhoto(message)) {
            return MglaSpyConfig.MSG_TYPE_PHOTO;
        }
        if (MessageObject.isVideoMessage(message)) {
            return MglaSpyConfig.MSG_TYPE_VIDEO;
        }
        return MglaSpyConfig.MSG_TYPE_TEXT;
    }

    public static boolean shouldSaveForMessageType(TLRPC.Message message) {
        int msgType = resolveMessageType(message);
        return MglaSpyConfig.isSaveDeletedMsgTypeEnabled(msgType);
    }
}
