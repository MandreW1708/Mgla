package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public class MglaEditHistoryStorage {

    private static final String TABLE = "mgla_edit_history";

    public static class Entry {
        public final int replacedByEditDate;
        public final TLRPC.Message message;

        public Entry(int replacedByEditDate, TLRPC.Message message) {
            this.replacedByEditDate = replacedByEditDate;
            this.message = message;
        }
    }

    public static void ensureTable(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.executeFast(
                "CREATE TABLE IF NOT EXISTS " + TABLE + "(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "mid INTEGER, " +
                    "uid INTEGER, " +
                    "replaced_by_edit_date INTEGER, " +
                    "saved_at INTEGER, " +
                    "data BLOB" +
                ")"
            ).stepThis().dispose();
            database.executeFast(
                "CREATE INDEX IF NOT EXISTS mgla_edit_history_uid_mid_edit_date ON " + TABLE + "(uid, mid, replaced_by_edit_date DESC)"
            ).stepThis().dispose();
        } catch (SQLiteException e) {
            FileLog.e(e);
        }
    }

    public static void savePreviousVersionIfIncomingEdit(SQLiteDatabase database, int currentAccount, TLRPC.Message newMessage) {
        if (database == null || newMessage == null || newMessage.id <= 0 || newMessage.edit_date == 0) {
            return;
        }
        if (newMessage.out || DialogObject.isEncryptedDialog(newMessage.dialog_id)) {
            return;
        }
        long clientUserId = UserConfig.getInstance(currentAccount).getClientUserId();
        long fromId = newMessage.from_id != null ? DialogObject.getPeerDialogId(newMessage.from_id) : 0;
        if (fromId == clientUserId) {
            return;
        }

        TLRPC.Message oldMessage = loadCurrentMessage(database, currentAccount, newMessage.dialog_id, newMessage.id);
        if (oldMessage == null) {
            return;
        }
        if (oldMessage.edit_date == newMessage.edit_date) {
            return;
        }
        if (TextUtils.equals(oldMessage.message, newMessage.message) && entitiesEqual(oldMessage.entities, newMessage.entities)) {
            return;
        }

        SQLiteCursor cursor = null;
        SQLitePreparedStatement state = null;
        NativeByteBuffer data = null;
        try {
            cursor = database.queryFinalized(
                "SELECT 1 FROM " + TABLE + " WHERE uid = ? AND mid = ? AND replaced_by_edit_date = ? LIMIT 1",
                newMessage.dialog_id, newMessage.id, newMessage.edit_date
            );
            if (cursor.next()) {
                return;
            }

            data = new NativeByteBuffer(oldMessage.getObjectSize());
            oldMessage.serializeToStream(data);

            state = database.executeFast(
                "INSERT INTO " + TABLE + "(mid, uid, replaced_by_edit_date, saved_at, data) VALUES(?, ?, ?, ?, ?)"
            );
            state.requery();
            state.bindInteger(1, newMessage.id);
            state.bindLong(2, newMessage.dialog_id);
            state.bindInteger(3, newMessage.edit_date);
            state.bindInteger(4, ConnectionsManager.getInstance(currentAccount).getCurrentTime());
            state.bindByteBuffer(5, data);
            state.step();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
            if (state != null) {
                state.dispose();
            }
            if (data != null) {
                data.reuse();
            }
        }
    }

    public static ArrayList<Entry> loadEditHistory(SQLiteDatabase database, int currentAccount, long dialogId, int messageId, int limit) {
        ArrayList<Entry> result = new ArrayList<>();
        if (database == null || dialogId == 0 || messageId <= 0) {
            return result;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(
                "SELECT replaced_by_edit_date, data FROM " + TABLE + " WHERE uid = ? AND mid = ? ORDER BY replaced_by_edit_date DESC LIMIT ?",
                dialogId, messageId, Math.max(1, limit)
            );
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                message.readAttachPath(data, UserConfig.getInstance(currentAccount).getClientUserId());
                data.reuse();
                result.add(new Entry(cursor.intValue(0), message));
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

    private static TLRPC.Message loadCurrentMessage(SQLiteDatabase database, int currentAccount, long dialogId, int messageId) {
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT data FROM messages_v2 WHERE uid = ? AND mid = ? LIMIT 1", dialogId, messageId);
            if (!cursor.next()) {
                cursor.dispose();
                cursor = database.queryFinalized("SELECT data FROM messages_topics WHERE uid = ? AND mid = ? LIMIT 1", dialogId, messageId);
                if (!cursor.next()) {
                    return null;
                }
            }
            NativeByteBuffer data = cursor.byteBufferValue(0);
            if (data == null) {
                return null;
            }
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            message.readAttachPath(data, UserConfig.getInstance(currentAccount).getClientUserId());
            data.reuse();
            return message;
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return null;
    }

    private static boolean entitiesEqual(ArrayList<TLRPC.MessageEntity> a, ArrayList<TLRPC.MessageEntity> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            TLRPC.MessageEntity ea = a.get(i);
            TLRPC.MessageEntity eb = b.get(i);
            if (ea == null || eb == null) {
                if (ea != eb) {
                    return false;
                }
                continue;
            }
            if (ea.getClass() != eb.getClass() || ea.offset != eb.offset || ea.length != eb.length) {
                return false;
            }
        }
        return true;
    }
}
