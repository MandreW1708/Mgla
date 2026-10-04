package org.telegram.messenger;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Deleted messages archive, stored in the regular Telegram cache database.
 *
 * Rows are written from {@link MessagesStorage#markMessagesAsDeletedInternal} while the original
 * message is still readable, and read back in two places: the chat history loader (so deleted
 * messages stay inline in their original chronological place) and the per-chat deleted section.
 */
public class MglaDeletedStorage {

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

    // region filters

    public static int resolveChatType(int currentAccount, long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            return MglaSpyConfig.CHAT_TYPE_PRIVATE;
        }
        if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            if (chat == null) {
                return MglaSpyConfig.CHAT_TYPE_GROUP_SMALL;
            }
            if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                return MglaSpyConfig.CHAT_TYPE_CHANNEL;
            }
            return chat.participants_count > 100
                ? MglaSpyConfig.CHAT_TYPE_GROUP_LARGE
                : MglaSpyConfig.CHAT_TYPE_GROUP_SMALL;
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
        if (MessageObject.isVideoMessage(message)) {
            return MglaSpyConfig.MSG_TYPE_VIDEO;
        }
        if (MessageObject.isPhoto(message)) {
            return MglaSpyConfig.MSG_TYPE_PHOTO;
        }
        return MglaSpyConfig.MSG_TYPE_TEXT;
    }

    /**
     * Whether this message should be archived when deleted. Safe to call from any thread:
     * it only reads preferences and the in-memory chat cache.
     */
    public static boolean shouldSave(int currentAccount, long dialogId, TLRPC.Message message) {
        if (!MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return false;
        }
        if (message == null || message.id <= 0 || dialogId == 0) {
            return false;
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        if (message instanceof TLRPC.TL_messageEmpty || message.action != null) {
            return false;
        }
        if (MessageObject.isEphemeralMessageId(message.id)) {
            return false;
        }
        if (!MglaSpyConfig.isSaveDeletedForCategoryEnabled(resolveChatType(currentAccount, dialogId))) {
            return false;
        }
        return MglaSpyConfig.isSaveDeletedMsgTypeEnabled(resolveMessageType(message));
    }

    // endregion

    // region write

    public static void save(SQLiteDatabase database, int currentAccount, long dialogId, long topicId, TLRPC.Message message) {
        if (database == null || message == null) {
            return;
        }
        ensureTable(database);

        SQLitePreparedStatement state = null;
        NativeByteBuffer data = null;
        try {
            if (message.dialog_id == 0) {
                message.dialog_id = dialogId;
            }
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
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("mgla-deleted archived mid=" + message.id + " uid=" + dialogId + " topic=" + topicId);
            }
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

    // endregion

    // region read

    /** Newest first, for the per-chat deleted section. */
    public static ArrayList<TLRPC.Message> load(SQLiteDatabase database, int currentAccount, long dialogId, long topicId, int limit, int offset) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (database == null || dialogId == 0 || !MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return result;
        }
        SQLiteCursor cursor = null;
        try {
            if (topicId != 0) {
                cursor = database.queryFinalized(
                    "SELECT data FROM " + TABLE + " WHERE uid = ? AND topic_id = ? ORDER BY date DESC, mid DESC LIMIT ? OFFSET ?",
                    dialogId, topicId, Math.max(1, limit), Math.max(0, offset)
                );
            } else {
                cursor = database.queryFinalized(
                    "SELECT data FROM " + TABLE + " WHERE uid = ? ORDER BY date DESC, mid DESC LIMIT ? OFFSET ?",
                    dialogId, Math.max(1, limit), Math.max(0, offset)
                );
            }
            readCursor(cursor, currentAccount, dialogId, result);
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    /**
     * Messages for the open chat window. {@code includeNewer}/{@code includeOlder} widen the window
     * past the loaded ids, which is what makes a deleted newest message reappear at the bottom.
     */
    public static ArrayList<TLRPC.Message> loadInRange(
        SQLiteDatabase database,
        int currentAccount,
        long dialogId,
        long topicId,
        int minId,
        int maxId,
        boolean includeOlder,
        boolean includeNewer,
        int limit
    ) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (database == null || dialogId == 0 || !MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return result;
        }
        StringBuilder where = new StringBuilder("uid = ?");
        if (topicId != 0) {
            where.append(" AND topic_id = ?");
        }
        if (minId == Integer.MAX_VALUE || maxId == Integer.MIN_VALUE) {
            // Nothing loaded yet: only meaningful when the window is unbounded on either side.
            if (!includeNewer && !includeOlder) {
                return result;
            }
        } else {
            where.append(" AND (mid BETWEEN ").append(minId).append(" AND ").append(maxId);
            if (includeNewer) {
                where.append(" OR mid > ").append(maxId);
            }
            if (includeOlder) {
                where.append(" OR mid < ").append(minId);
            }
            where.append(')');
        }

        SQLiteCursor cursor = null;
        try {
            String sql = "SELECT data FROM " + TABLE + " WHERE " + where + " ORDER BY date DESC, mid DESC LIMIT ?";
            if (topicId != 0) {
                cursor = database.queryFinalized(sql, dialogId, topicId, Math.max(1, limit));
            } else {
                cursor = database.queryFinalized(sql, dialogId, Math.max(1, limit));
            }
            readCursor(cursor, currentAccount, dialogId, result);
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    public static int count(SQLiteDatabase database, long dialogId, long topicId) {
        if (database == null || dialogId == 0) {
            return 0;
        }
        SQLiteCursor cursor = null;
        try {
            if (topicId != 0) {
                cursor = database.queryFinalized("SELECT COUNT(*) FROM " + TABLE + " WHERE uid = ? AND topic_id = ?", dialogId, topicId);
            } else {
                cursor = database.queryFinalized("SELECT COUNT(*) FROM " + TABLE + " WHERE uid = ?", dialogId);
            }
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

    private static void readCursor(SQLiteCursor cursor, int currentAccount, long dialogId, ArrayList<TLRPC.Message> out) throws Exception {
        long clientUserId = UserConfig.getInstance(currentAccount).getClientUserId();
        while (cursor.next()) {
            NativeByteBuffer data = cursor.byteBufferValue(0);
            if (data == null) {
                continue;
            }
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            if (message != null) {
                message.readAttachPath(data, clientUserId);
                if (message.dialog_id == 0) {
                    message.dialog_id = dialogId;
                }
                message.mglaDeleted = true;
                out.add(message);
            }
            data.reuse();
        }
    }

    // endregion

    // region delete

    public static void deleteMessage(SQLiteDatabase database, long dialogId, int messageId) {
        if (database == null) {
            return;
        }
        try {
            database.executeFast("DELETE FROM " + TABLE + " WHERE uid = " + dialogId + " AND mid = " + messageId).stepThis().dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void clearChat(SQLiteDatabase database, long dialogId, long topicId) {
        if (database == null) {
            return;
        }
        try {
            if (topicId != 0) {
                database.executeFast("DELETE FROM " + TABLE + " WHERE uid = " + dialogId + " AND topic_id = " + topicId).stepThis().dispose();
            } else {
                database.executeFast("DELETE FROM " + TABLE + " WHERE uid = " + dialogId).stepThis().dispose();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void clearAll(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.executeFast("DELETE FROM " + TABLE + " WHERE 1").stepThis().dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void clearAllAccounts() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            MessagesStorage storage = MessagesStorage.getInstance(a);
            storage.getStorageQueue().postRunnable(() -> clearAll(storage.getDatabase()));
        }
    }

    // endregion
}
