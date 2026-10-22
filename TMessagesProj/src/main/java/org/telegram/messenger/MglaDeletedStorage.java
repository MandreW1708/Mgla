package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Deleted messages archive, stored in the regular Telegram cache database.
 *
 * Rows are written from {@link MessagesStorage#markMessagesAsDeletedInternal} while the original
 * message is still readable, and read back in two places: the chat history loader (so deleted
 * messages stay inline in their original chronological place) and the per-chat deleted section.
 */
public class MglaDeletedStorage {

    private static final String TABLE = "mgla_deleted_messages";

    /** Soft cap when archiving a whole dialog so a huge history cannot hang the storage queue. */
    public static final int MAX_DIALOG_ARCHIVE_MESSAGES = 2_000;

    /**
     * Message ids the user opted not to archive on the next local delete
     * ("Сохранить у себя" unchecked). Checked from the UI thread (keep-visible)
     * and consumed on the storage thread when archiving.
     */
    private static final Object skipArchiveLock = new Object();
    private static final LongSparseArray<HashSet<Integer>> skipArchiveByDialog = new LongSparseArray<>();

    /** Whole-dialog skip/preserve for the next {@code deleteDialog} call. */
    private static final Object dialogDeleteLock = new Object();
    private static final HashSet<Long> skipArchiveDialogs = new HashSet<>();
    private static final HashSet<Long> preserveDialogsOnce = new HashSet<>();

    /** Dialogs kept in the chat list after local/server history wipe. */
    private static final Object preservedLock = new Object();
    private static final HashSet<Long>[] preservedDialogs = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];
    private static boolean preservedLoaded;

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
        // Prefer explicit once/ttl flags: emptied stubs keep voice/round but lose document attrs.
        if ((message.media != null && message.media.voice) || MessageObject.isVoiceMessage(message)) {
            return MglaSpyConfig.MSG_TYPE_VOICE;
        }
        if ((message.media != null && message.media.round) || MessageObject.isRoundVideoMessage(message)) {
            return MglaSpyConfig.MSG_TYPE_ROUND;
        }
        if (MessageObject.isVideoMessage(message)
                || (message.media instanceof TLRPC.TL_messageMediaDocument && message.media.ttl_seconds != 0)) {
            return MglaSpyConfig.MSG_TYPE_VIDEO;
        }
        if (MessageObject.isPhoto(message) || message.media instanceof TLRPC.TL_messageMediaPhoto) {
            return MglaSpyConfig.MSG_TYPE_PHOTO;
        }
        return MglaSpyConfig.MSG_TYPE_TEXT;
    }

    /** Whether the delete dialog should offer the "Сохранить у себя" checkbox. */
    public static boolean shouldOfferSaveOnDelete(int currentAccount, long dialogId) {
        if (!MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return false;
        }
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        return MglaSpyConfig.isSaveDeletedForCategoryEnabled(resolveChatType(currentAccount, dialogId));
    }

    /**
     * Mark message ids so the upcoming local delete does not write them into the archive.
     * Call on the UI thread before {@code deleteMessages}.
     */
    public static void skipArchiveForDeletion(long dialogId, Collection<Integer> messageIds) {
        if (dialogId == 0 || messageIds == null || messageIds.isEmpty()) {
            return;
        }
        synchronized (skipArchiveLock) {
            HashSet<Integer> set = skipArchiveByDialog.get(dialogId);
            if (set == null) {
                set = new HashSet<>();
                skipArchiveByDialog.put(dialogId, set);
            }
            set.addAll(messageIds);
        }
    }

    /** UI-thread peek: whether this mid is marked to skip archiving (not consumed). */
    public static boolean isSkipArchivePending(long dialogId, int messageId) {
        synchronized (skipArchiveLock) {
            HashSet<Integer> set = skipArchiveByDialog.get(dialogId);
            return set != null && set.contains(messageId);
        }
    }

    /** Storage-thread: consume a skip mark for this mid. Returns true if archiving must be skipped. */
    public static boolean consumeSkipArchive(long dialogId, int messageId) {
        synchronized (skipArchiveLock) {
            HashSet<Integer> set = skipArchiveByDialog.get(dialogId);
            if (set == null) {
                return false;
            }
            boolean removed = set.remove(messageId);
            if (set.isEmpty()) {
                skipArchiveByDialog.remove(dialogId);
            }
            return removed;
        }
    }

    /** UI: next chat delete must wipe locally without archiving. */
    public static void skipArchiveDialogOnce(long dialogId) {
        if (dialogId == 0) {
            return;
        }
        synchronized (dialogDeleteLock) {
            skipArchiveDialogs.add(dialogId);
            preserveDialogsOnce.remove(dialogId);
        }
    }

    /** UI: next chat delete must archive messages and keep the dialog in the list. */
    public static void preserveDialogOnce(long dialogId) {
        if (dialogId == 0) {
            return;
        }
        synchronized (dialogDeleteLock) {
            preserveDialogsOnce.add(dialogId);
            skipArchiveDialogs.remove(dialogId);
        }
    }

    public static boolean consumeSkipArchiveDialog(long dialogId) {
        synchronized (dialogDeleteLock) {
            return skipArchiveDialogs.remove(dialogId);
        }
    }

    public static boolean consumePreserveDialog(long dialogId) {
        synchronized (dialogDeleteLock) {
            return preserveDialogsOnce.remove(dialogId);
        }
    }

    public static boolean peekPreserveDialog(long dialogId) {
        synchronized (dialogDeleteLock) {
            return preserveDialogsOnce.contains(dialogId);
        }
    }

    private static SharedPreferences preservedPrefs() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences("mgla_preserved_dialogs", Context.MODE_PRIVATE);
    }

    private static void ensurePreservedLoaded() {
        synchronized (preservedLock) {
            if (preservedLoaded) {
                return;
            }
            SharedPreferences p = preservedPrefs();
            for (int a = 0; a < preservedDialogs.length; a++) {
                HashSet<Long> set = new HashSet<>();
                if (p != null) {
                    Set<String> raw = p.getStringSet("ids_" + a, null);
                    if (raw != null) {
                        for (String s : raw) {
                            try {
                                set.add(Long.parseLong(s));
                            } catch (NumberFormatException ignore) {
                            }
                        }
                    }
                }
                preservedDialogs[a] = set;
            }
            preservedLoaded = true;
        }
    }

    private static void savePreservedIds(int account) {
        SharedPreferences p = preservedPrefs();
        if (p == null || account < 0 || account >= preservedDialogs.length) {
            return;
        }
        HashSet<String> raw = new HashSet<>();
        for (Long id : preservedDialogs[account]) {
            raw.add(String.valueOf(id));
        }
        p.edit().putStringSet("ids_" + account, raw).apply();
    }

    public static void markDialogPreserved(int account, long dialogId) {
        if (dialogId == 0 || account < 0 || account >= preservedDialogs.length) {
            return;
        }
        if (!shouldOfferSaveOnDelete(account, dialogId)) {
            return;
        }
        ensurePreservedLoaded();
        synchronized (preservedLock) {
            if (preservedDialogs[account].add(dialogId)) {
                savePreservedIds(account);
            }
        }
    }

    public static void unmarkDialogPreserved(int account, long dialogId) {
        if (dialogId == 0 || account < 0 || account >= preservedDialogs.length) {
            return;
        }
        ensurePreservedLoaded();
        synchronized (preservedLock) {
            if (preservedDialogs[account].remove(dialogId)) {
                savePreservedIds(account);
            }
        }
    }

    public static boolean isDialogPreserved(int account, long dialogId) {
        if (dialogId == 0 || account < 0 || account >= preservedDialogs.length) {
            return false;
        }
        if (!MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return false;
        }
        ensurePreservedLoaded();
        synchronized (preservedLock) {
            return preservedDialogs[account].contains(dialogId);
        }
    }

    public static ArrayList<Long> getPreservedDialogIds(int account) {
        ArrayList<Long> out = new ArrayList<>();
        if (account < 0 || account >= preservedDialogs.length || !MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return out;
        }
        ensurePreservedLoaded();
        synchronized (preservedLock) {
            out.addAll(preservedDialogs[account]);
        }
        return out;
    }

    /**
     * Copy up to {@link #MAX_DIALOG_ARCHIVE_MESSAGES} messages from {@code messages_v2} into the
     * archive. Safe to call on the storage thread; never throws into the caller.
     *
     * @return number of messages archived
     */
    public static int archiveDialogMessages(SQLiteDatabase database, int currentAccount, long dialogId) {
        if (database == null || dialogId == 0 || !shouldOfferSaveOnDelete(currentAccount, dialogId)) {
            return 0;
        }
        ensureTable(database);
        int archived = 0;
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(String.format(Locale.US,
                "SELECT data, mid FROM messages_v2 WHERE uid = %d ORDER BY date DESC, mid DESC LIMIT %d",
                dialogId, MAX_DIALOG_ARCHIVE_MESSAGES));
            long clientUserId = UserConfig.getInstance(currentAccount).getClientUserId();
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, clientUserId);
                        if (message.dialog_id == 0) {
                            message.dialog_id = dialogId;
                        }
                        if (shouldSave(currentAccount, dialogId, message)) {
                            save(
                                database, currentAccount, dialogId,
                                MessageObject.getTopicId(currentAccount, message, 0),
                                message
                            );
                            archived++;
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    data.reuse();
                }
            }
            if (archived > 0) {
                markDialogPreserved(currentAccount, dialogId);
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("mgla-deleted archiveDialog did=" + dialogId + " count=" + archived);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return archived;
    }

    /** Newest archived message for dialog-list preview after a preserved wipe. */
    public static TLRPC.Message loadNewestArchived(SQLiteDatabase database, int currentAccount, long dialogId) {
        if (database == null || dialogId == 0) {
            return null;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(
                "SELECT data FROM " + TABLE + " WHERE uid = ? ORDER BY date DESC, mid DESC LIMIT 1",
                dialogId
            );
            if (!cursor.next()) {
                return null;
            }
            NativeByteBuffer data = cursor.byteBufferValue(0);
            if (data == null) {
                return null;
            }
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            if (message != null) {
                message.readAttachPath(data, UserConfig.getInstance(currentAccount).getClientUserId());
                if (message.dialog_id == 0) {
                    message.dialog_id = dialogId;
                }
                message.mglaDeleted = true;
            }
            data.reuse();
            return message;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
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
        return MglaSpyConfig.isSaveDeletedMsgTypeEnabled(dialogId, resolveMessageType(message));
    }

    /**
     * Once / TTL media that Telegram already wiped from the regular messages table.
     * <p>
     * Photos deserialize a missing FLAG_0 as {@link TLRPC.TL_photoEmpty}; documents leave
     * {@code document == null} after the same empty+{@ round-trip. Treat both as emptied
     * so archived once-videos/rounds can be swapped back into the chat bubble.
     */
    public static boolean isEmptiedDisappearingMedia(TLRPC.Message message) {
        if (message == null || message.media == null || message.media.ttl_seconds == 0) {
            return false;
        }
        if (message.media instanceof TLRPC.TL_messageMediaPhoto) {
            return message.media.photo == null || message.media.photo instanceof TLRPC.TL_photoEmpty;
        }
        if (message.media instanceof TLRPC.TL_messageMediaDocument) {
            return message.media.document == null || message.media.document instanceof TLRPC.TL_documentEmpty;
        }
        return message.media.photo instanceof TLRPC.TL_photoEmpty
            || message.media.document instanceof TLRPC.TL_documentEmpty;
    }

    /** View-once / self-destruct media that still has real content. */
    public static boolean isDisappearingMedia(TLRPC.Message message) {
        if (message == null || message.media == null || message.media.ttl_seconds == 0) {
            return false;
        }
        if (isEmptiedDisappearingMedia(message)) {
            return false;
        }
        return message.media.photo != null || message.media.document != null;
    }

    /** View-once media (ttl == 0x7FFFFFFF on message and/or media). */
    public static boolean isViewOnceMedia(TLRPC.Message message) {
        if (message == null) {
            return false;
        }
        if (message.ttl == 0x7FFFFFFF) {
            return true;
        }
        return message.media != null && message.media.ttl_seconds == 0x7FFFFFFF;
    }

    /** Deep-copy a message (used to keep a full UI copy while emptying the DB row). */
    public static TLRPC.Message cloneMessage(TLRPC.Message message, long clientUserId) {
        if (message == null) {
            return null;
        }
        NativeByteBuffer data = null;
        try {
            data = new NativeByteBuffer(message.getObjectSize());
            message.serializeToStream(data);
            data.position(0);
            TLRPC.Message copy = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            if (copy != null) {
                copy.readAttachPath(data, clientUserId);
                if (copy.dialog_id == 0) {
                    copy.dialog_id = message.dialog_id;
                }
                copy.id = message.id;
                copy.date = message.date;
                copy.mglaDeleted = true;
            }
            return copy;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (data != null) {
                data.reuse();
            }
        }
    }

    /** Load one archived message by id (for restoring a chat bubble after once-media expiry). */
    public static TLRPC.Message loadMessage(SQLiteDatabase database, int currentAccount, long dialogId, int messageId) {
        if (database == null || dialogId == 0 || messageId <= 0) {
            return null;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized(
                "SELECT data FROM " + TABLE + " WHERE uid = ? AND mid = ? LIMIT 1",
                dialogId, messageId
            );
            if (!cursor.next()) {
                return null;
            }
            NativeByteBuffer data = cursor.byteBufferValue(0);
            if (data == null) {
                return null;
            }
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            if (message != null) {
                message.readAttachPath(data, UserConfig.getInstance(currentAccount).getClientUserId());
                if (message.dialog_id == 0) {
                    message.dialog_id = dialogId;
                }
                message.mglaDeleted = true;
            }
            data.reuse();
            return message;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    // endregion

    // region write

    public static void save(SQLiteDatabase database, int currentAccount, long dialogId, long topicId, TLRPC.Message message) {
        if (database == null || message == null) {
            return;
        }
        // Never archive emptied once-stubs — they would REPLACE a good full copy and show up as
        // "Photo/Video has expired" junk in «Удалённые».
        if (isEmptiedDisappearingMedia(message)) {
            return;
        }
        ensureTable(database);

        SQLitePreparedStatement state = null;
        NativeByteBuffer data = null;
        try {
            if (message.dialog_id == 0) {
                message.dialog_id = dialogId;
            }
            // Keep an existing richer archive if this write is somehow worse.
            TLRPC.Message existing = loadMessage(database, currentAccount, dialogId, message.id);
            if (existing != null && !isEmptiedDisappearingMedia(existing) && isEmptiedDisappearingMedia(message)) {
                return;
            }
            if (existing != null && isDisappearingMedia(existing) && !isDisappearingMedia(message)
                    && message.media != null && existing.media != null) {
                return;
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
     * <p>
     * Prefer {@link #loadForChatWindow} when the caller already has the loaded mid set: a single
     * {@code ORDER BY date DESC LIMIT N} over a wide mid span starves older archived rows when the
     * dialog has many recent deletions, which leaves visible holes in the timeline.
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
        return loadWhere(
            database, currentAccount, dialogId, topicId,
            buildInRangeWhere(topicId, minId, maxId, includeOlder, includeNewer),
            limit
        );
    }

    /**
     * Fill archived deletions into a chat page without starving mid-range gaps: load each
     * consecutive mid gap separately (plus optional older/newer edges), ordered by mid.
     */
    public static ArrayList<TLRPC.Message> loadForChatWindow(
        SQLiteDatabase database,
        int currentAccount,
        long dialogId,
        long topicId,
        Collection<Integer> loadedIds,
        boolean includeOlder,
        boolean includeNewer,
        int perGapLimit
    ) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (database == null || dialogId == 0 || !MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return result;
        }
        HashSet<Integer> seen = new HashSet<>();
        int limit = Math.max(1, perGapLimit);
        if (loadedIds == null || loadedIds.isEmpty()) {
            if (!includeOlder && !includeNewer) {
                return result;
            }
            appendUnique(result, seen, loadWhere(
                database, currentAccount, dialogId, topicId,
                buildInRangeWhere(topicId, Integer.MAX_VALUE, Integer.MIN_VALUE, includeOlder, includeNewer),
                limit
            ));
            return result;
        }
        ArrayList<Integer> sorted = new ArrayList<>(loadedIds);
        Collections.sort(sorted);
        int minLoadedId = sorted.get(0);
        int maxLoadedId = sorted.get(sorted.size() - 1);
        for (int i = 0, N = sorted.size() - 1; i < N; i++) {
            int lo = sorted.get(i);
            int hi = sorted.get(i + 1);
            if (hi - lo <= 1) {
                continue;
            }
            StringBuilder where = new StringBuilder("uid = ?");
            if (topicId != 0) {
                where.append(" AND topic_id = ?");
            }
            where.append(" AND mid > ").append(lo).append(" AND mid < ").append(hi);
            appendUnique(result, seen, loadWhere(
                database, currentAccount, dialogId, topicId, where.toString(), limit
            ));
        }
        if (includeOlder) {
            StringBuilder where = new StringBuilder("uid = ?");
            if (topicId != 0) {
                where.append(" AND topic_id = ?");
            }
            where.append(" AND mid < ").append(minLoadedId);
            appendUnique(result, seen, loadWhere(
                database, currentAccount, dialogId, topicId, where.toString(), limit
            ));
        }
        if (includeNewer) {
            StringBuilder where = new StringBuilder("uid = ?");
            if (topicId != 0) {
                where.append(" AND topic_id = ?");
            }
            where.append(" AND mid > ").append(maxLoadedId);
            appendUnique(result, seen, loadWhere(
                database, currentAccount, dialogId, topicId, where.toString(), limit
            ));
        }
        return result;
    }

    private static String buildInRangeWhere(
        long topicId, int minId, int maxId, boolean includeOlder, boolean includeNewer
    ) {
        StringBuilder where = new StringBuilder("uid = ?");
        if (topicId != 0) {
            where.append(" AND topic_id = ?");
        }
        if (minId == Integer.MAX_VALUE || maxId == Integer.MIN_VALUE) {
            if (!includeNewer && !includeOlder) {
                return null;
            }
            return where.toString();
        }
        where.append(" AND (mid BETWEEN ").append(minId).append(" AND ").append(maxId);
        if (includeNewer) {
            where.append(" OR mid > ").append(maxId);
        }
        if (includeOlder) {
            where.append(" OR mid < ").append(minId);
        }
        where.append(')');
        return where.toString();
    }

    private static void appendUnique(
        ArrayList<TLRPC.Message> out, HashSet<Integer> seen, ArrayList<TLRPC.Message> batch
    ) {
        for (int a = 0, N = batch.size(); a < N; a++) {
            TLRPC.Message message = batch.get(a);
            if (message == null || !seen.add(message.id)) {
                continue;
            }
            out.add(message);
        }
    }

    private static ArrayList<TLRPC.Message> loadWhere(
        SQLiteDatabase database,
        int currentAccount,
        long dialogId,
        long topicId,
        String where,
        int limit
    ) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (database == null || where == null) {
            return result;
        }
        SQLiteCursor cursor = null;
        try {
            // mid order keeps gap fills contiguous; date DESC starved older archived rows in wide windows.
            String sql = "SELECT data FROM " + TABLE + " WHERE " + where + " ORDER BY mid ASC LIMIT ?";
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
                // Skip emptied once-stubs that leaked into the archive (no real media left).
                if (isEmptiedDisappearingMedia(message)) {
                    data.reuse();
                    continue;
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
            final int account = a;
            MessagesStorage storage = MessagesStorage.getInstance(a);
            storage.getStorageQueue().postRunnable(() -> clearAll(storage.getDatabase()));
            ensurePreservedLoaded();
            synchronized (preservedLock) {
                if (preservedDialogs[account] != null) {
                    preservedDialogs[account].clear();
                    savePreservedIds(account);
                }
            }
        }
    }

    // endregion
}
