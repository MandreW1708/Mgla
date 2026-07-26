package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ChatExportManager {

    private static final String TAG = "ChatExportManager";

    public interface ExportCallback {
        void onProgress(int progress, int total);
        void onComplete(String path);
        void onError(String error);
    }

    // Export state accessible from UI
    public static class ExportState {
        public volatile boolean running = false;
        public volatile boolean canceled = false;
        public volatile int progress = 0;
        public volatile int total = 0;
        public volatile int percent = 0;
        public volatile int mediaTotal = 0;
        public volatile int mediaProcessed = 0;
        public volatile String currentMediaInfo = "";
        public volatile String folderName = "";
        public volatile String outputFilePath = "";
        public volatile String error = null;
        public volatile boolean completed = false;
    }

    private static volatile ExportState currentState = null;

    public static ExportState getCurrentState() {
        return currentState;
    }

    public static boolean isExportRunning() {
        ExportState s = currentState;
        return s != null && s.running && !s.canceled && !s.completed;
    }

    public static void cancelExport() {
        ExportState s = currentState;
        if (s != null && s.running && !s.canceled && !s.completed) {
            s.canceled = true;
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaExportCanceled);
                cancelExportNotification();
            });
        }
    }

    private static void checkCanceled() throws InterruptedException {
        ExportState s = currentState;
        if (s != null && s.canceled) {
            throw new InterruptedException("Export canceled by user");
        }
    }

    private static void cancelExportNotification() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(EXPORT_NOTIFICATION_ID);
            } catch (Throwable ignore) {
            }
        });
    }

    private static final int EXPORT_NOTIFICATION_ID = 77777;
    private static final String EXPORT_CHANNEL_ID = "chat_export_channel";
    private static final String EXPORT_CHANNEL_NAME = "Экспорт чата";
    private static final int HISTORY_BATCH_SIZE = 100;
    private static final int DB_EXPORT_BATCH_SIZE = 1000;
    private static final long MEDIA_DOWNLOAD_RETRY_DELAY_MS = 5000L;
    private static volatile long lastProgressUpdate = 0;

    private static void ensureExportChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel channel = nm.getNotificationChannel(EXPORT_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(EXPORT_CHANNEL_ID, EXPORT_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
                channel.enableLights(true);
                channel.enableVibration(true);
                channel.setShowBadge(true);
                nm.createNotificationChannel(channel);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static String getExportTitle() {
        String title = LocaleController.getString("ExportChatTitle", R.string.ExportChatTitle);
        return title != null ? title : "Экспорт чата";
    }

    private static void showProgressNotification(int percent, String text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ensureExportChannel();
                Intent launchIntent = new Intent(ApplicationLoader.applicationContext, org.telegram.ui.LaunchActivity.class);
                launchIntent.setAction("org.tmessages.openchat");
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                    ApplicationLoader.applicationContext,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );

                NotificationCompat.Builder builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, EXPORT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.notific_icon)
                        .setContentTitle(getExportTitle())
                        .setContentText(text)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setColor(0xff11acfa)
                        .setProgress(100, percent, false)
                        .setContentIntent(pendingIntent);

                NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(EXPORT_NOTIFICATION_ID, builder.build());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static void showIndeterminateNotification(String text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ensureExportChannel();
                Intent launchIntent = new Intent(ApplicationLoader.applicationContext, org.telegram.ui.LaunchActivity.class);
                launchIntent.setAction("org.tmessages.openchat");
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                    ApplicationLoader.applicationContext,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                );

                NotificationCompat.Builder builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, EXPORT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.notific_icon)
                        .setContentTitle(getExportTitle())
                        .setContentText(text)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setColor(0xff11acfa)
                        .setProgress(0, 0, true)
                        .setContentIntent(pendingIntent);

                NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(EXPORT_NOTIFICATION_ID, builder.build());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static void showCompletionNotification(String filePath) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ensureExportChannel();
                File file = new File(filePath);
                PendingIntent openPendingIntent = buildOpenFileIntent(file);

                NotificationCompat.Builder builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, EXPORT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.notific_icon)
                        .setContentTitle(getExportTitle())
                        .setContentText("Экспорт завершён: " + file.getName())
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setColor(0xff11acfa)
                        .setProgress(0, 0, false)
                        .setContentIntent(openPendingIntent != null ? openPendingIntent : buildLaunchPendingIntent());

                if (openPendingIntent != null) {
                    builder.addAction(0, "Открыть", openPendingIntent);
                }

                NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(EXPORT_NOTIFICATION_ID, builder.build());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static void showErrorNotification(String error) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ensureExportChannel();
                NotificationCompat.Builder builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, EXPORT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.notific_icon)
                        .setContentTitle(getExportTitle())
                        .setContentText("Ошибка экспорта: " + (error != null ? error : "неизвестная ошибка"))
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setColor(0xff11acfa)
                        .setProgress(0, 0, false)
                        .setContentIntent(buildLaunchPendingIntent());

                NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(EXPORT_NOTIFICATION_ID, builder.build());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static PendingIntent buildLaunchPendingIntent() {
        Intent launchIntent = new Intent(ApplicationLoader.applicationContext, org.telegram.ui.LaunchActivity.class);
        launchIntent.setAction("org.tmessages.openchat");
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        return PendingIntent.getActivity(
            ApplicationLoader.applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    private static PendingIntent buildOpenFileIntent(File file) {
        try {
            if (file == null || !file.exists()) return null;
            String extension = MimeTypeMap.getFileExtensionFromUrl(file.getAbsolutePath());
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mimeType == null) {
                mimeType = "*/*";
            }
            Intent openIntent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", file);
            openIntent.setDataAndType(uri, mimeType);
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(
                ApplicationLoader.applicationContext,
                1,
                openIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void awaitLatchWithoutTimeout(CountDownLatch latch) throws InterruptedException {
        while (!latch.await(1, TimeUnit.SECONDS)) {
            checkCanceled();
        }
    }

    private static ArrayList<TLRPC.Message> fetchFullHistoryFromServer(int account, long dialogId, ExportState state) throws InterruptedException {
        ArrayList<TLRPC.Message> allMessages = new ArrayList<>();
        TLRPC.InputPeer inputPeer = MessagesController.getInstance(account).getInputPeer(dialogId);
        if (inputPeer == null) {
            return allMessages;
        }

        int offsetId = 0;
        int downloadedCount = 0;

        showIndeterminateNotification("Синхронизация истории с сервера...");

        while (true) {
            checkCanceled();

            CountDownLatch latch = new CountDownLatch(1);
            final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = inputPeer;
            req.offset_id = offsetId;
            req.limit = HISTORY_BATCH_SIZE;

            final boolean[] finished = new boolean[1];
            final int[] retryDelay = new int[1];
            final int[] lastMsgId = new int[1];

            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (error != null) {
                    if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                        retryDelay[0] = Utilities.parseInt(error.text);
                        if (retryDelay[0] <= 0) retryDelay[0] = 5;
                    } else {
                        retryDelay[0] = 5;
                    }
                } else if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    if (res.messages == null || res.messages.isEmpty()) {
                        finished[0] = true;
                    } else {
                        MessagesController.getInstance(account).putUsers(res.users, false);
                        MessagesController.getInstance(account).putChats(res.chats, false);
                        MessagesStorage.getInstance(account).putMessages(res, dialogId, -2, 0, false, 0, 0);

                        int minId = Integer.MAX_VALUE;
                        for (int i = 0; i < res.messages.size(); i++) {
                            TLRPC.Message msg = res.messages.get(i);
                            if (msg != null) {
                                msg.dialog_id = dialogId;
                                allMessages.add(msg);
                                if (msg.id < minId) {
                                    minId = msg.id;
                                }
                            }
                        }
                        lastMsgId[0] = minId;
                        if (res.messages.size() < HISTORY_BATCH_SIZE) {
                            finished[0] = true;
                        }
                    }
                } else {
                    finished[0] = true;
                }
                latch.countDown();
            });

            awaitLatchWithoutTimeout(latch);

            if (retryDelay[0] > 0) {
                Thread.sleep(retryDelay[0] * 1000L);
                continue;
            }

            if (finished[0]) {
                break;
            }

            if (lastMsgId[0] > 0 && lastMsgId[0] != offsetId) {
                downloadedCount += HISTORY_BATCH_SIZE;
                showIndeterminateNotification("Синхронизация истории... (" + downloadedCount + " сообщений)");
                offsetId = lastMsgId[0];
            } else {
                break;
            }

            Thread.sleep(100);
        }
        Collections.sort(allMessages, (a, b) -> {
            if (a.date != b.date) {
                return Integer.compare(a.date, b.date);
            }
            return Integer.compare(a.id, b.id);
        });
        return allMessages;
    }

    public static void startExport(int account, long dialogId, boolean photos, boolean videos, boolean voice, boolean stickers, boolean files, boolean htmlFormat, String folderName, String saveLocation, long maxFileSize, ExportCallback callback) {
        if (isExportRunning()) {
            AndroidUtilities.runOnUIThread(() -> callback.onError("Экспорт уже выполняется"));
            return;
        }
        final ExportState state = new ExportState();
        state.running = true;
        state.folderName = folderName;
        currentState = state;
        lastProgressUpdate = 0;
        showIndeterminateNotification("Подготовка...");

        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaExportStarted, state);
        });

        new Thread(() -> {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            FileOutputStream fos = null;
            File exportDir = null;
            try {
                checkCanceled();
                File baseDir;
                if (saveLocation != null && saveLocation.startsWith("/")) {
                    // Full path from folder picker
                    baseDir = new File(saveLocation);
                } else if (saveLocation != null && saveLocation.equals("Telegram")) {
                    baseDir = new File(Environment.getExternalStorageDirectory(), "Telegram");
                } else if (saveLocation != null && !saveLocation.equals(Environment.DIRECTORY_DOWNLOADS)) {
                    baseDir = Environment.getExternalStoragePublicDirectory(saveLocation);
                } else {
                    baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                }
                if (!baseDir.exists() && !baseDir.mkdirs()) {
                    baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                }
                exportDir = new File(baseDir, folderName);
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    state.running = false;
                    state.error = "Не удалось создать папку экспорта";
                    showErrorNotification(state.error);
                    AndroidUtilities.runOnUIThread(() -> {
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaExportFailed, state);
                        callback.onError("Failed to create export directory");
                    });
                    return;
                }

                File mediaDir = new File(exportDir, "media");
                if ((photos || videos || voice || stickers || files) && !mediaDir.exists()) {
                    mediaDir.mkdirs();
                }
                File photosDir = new File(mediaDir, "photos");
                File videosDir = new File(mediaDir, "videos");
                File voiceDir = new File(mediaDir, "voice");
                File stickersDir = new File(mediaDir, "stickers");
                File filesDir = new File(mediaDir, "files");
                if (photos && !photosDir.exists()) photosDir.mkdirs();
                if ((videos || stickers) && !videosDir.exists()) videosDir.mkdirs();
                if (voice && !voiceDir.exists()) voiceDir.mkdirs();
                if (stickers && !stickersDir.exists()) stickersDir.mkdirs();
                if (files && !filesDir.exists()) filesDir.mkdirs();

                File outputFile = new File(exportDir, htmlFormat ? "export_1.html" : "export.json");
                fos = new FileOutputStream(outputFile);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"), 8192);

                final int MESSAGES_PER_FILE = 10000;
                int fileIndex = 1;
                int messagesInCurrentFile = 0;

                if (htmlFormat) {
                    writer.write("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>Chat Export — Часть 1</title>\n");
                    writer.write("<style>\n");
                    writer.write("*{box-sizing:border-box; margin:0; padding:0;}\n");
                    writer.write("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; background:#e7ebf0; color:#000; padding:16px;}\n");
                    writer.write(".header{background:#3390ec; color:#fff; padding:20px; border-radius:16px; margin-bottom:16px; text-align:center;}\n");
                    writer.write(".header h1{font-size:20px; font-weight:600;}\n");
                    writer.write(".header .info{font-size:13px; opacity:0.85; margin-top:4px;}\n");
                    writer.write(".nav{text-align:center; margin:12px 0; padding:8px;}\n");
                    writer.write(".nav a{display:inline-block; background:#3390ec; color:#fff; padding:8px 16px; border-radius:8px; text-decoration:none; margin:0 4px; font-size:14px;}\n");
                    writer.write(".msg{background:#fff; padding:12px 16px; margin:4px 0; border-radius:14px; max-width:85%; word-wrap:break-word; box-shadow:0 1px 2px rgba(0,0,0,0.08);}\n");
                    writer.write(".msg.out{background:#efffde; margin-left:auto; border-bottom-right-radius:4px;}\n");
                    writer.write(".msg.in{margin-right:auto; border-bottom-left-radius:4px;}\n");
                    writer.write(".msg .sender{font-weight:600; font-size:14px; color:#3390ec; margin-bottom:2px;}\n");
                    writer.write(".msg.out .sender{color:#4caf50;}\n");
                    writer.write(".msg .text{font-size:15px; line-height:1.4;}\n");
                    writer.write(".msg .date{font-size:11px; color:#999; margin-top:4px; text-align:right;}\n");
                    writer.write(".msg a{color:#3390ec; text-decoration:none;}\n");
                    writer.write(".msg a:hover{text-decoration:underline;}\n");
                    writer.write(".media-badge{display:inline-block; background:#3390ec1a; color:#3390ec; padding:4px 10px; border-radius:8px; font-size:13px; margin-top:4px;}\n");
                    writer.write("</style>\n");
                    writer.write("</head>\n<body>\n");
                    writer.write("<div class=\"header\"><h1>Экспорт чата — Часть 1</h1><div class=\"info\">" + dateFormat.format(new Date()) + "</div></div>\n");
                } else {
                    writer.write("{\n\"messages\": [\n");
                }

                MessagesStorage storage = MessagesStorage.getInstance(account);
                int count = 0;
                int mediaProcessed = 0;
                int totalMessages = 0;
                ArrayList<TLRPC.Message> serverMessages = null;
                
                // Synchronize full chat history from Telegram servers first
                try {
                    serverMessages = fetchFullHistoryFromServer(account, dialogId, state);
                } catch (Exception e) {
                    FileLog.e(e);
                }

                // Get total messages for progress
                if (serverMessages != null && !serverMessages.isEmpty()) {
                    totalMessages = serverMessages.size();
                } else {
                    CountDownLatch countLatch = new CountDownLatch(1);
                    final int[] total = {0};
                    storage.getStorageQueue().postRunnable(() -> {
                        try {
                            SQLiteCursor cursor = storage.getDatabase().queryFinalized("SELECT COUNT(*) FROM messages_v2 WHERE uid = " + dialogId);
                            if (cursor.next()) {
                                total[0] = cursor.intValue(0);
                            }
                            cursor.dispose();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                        countLatch.countDown();
                    });
                    awaitLatchWithoutTimeout(countLatch);
                    totalMessages = total[0];
                }
                state.total = totalMessages;

                checkCanceled();

                // Count total media items
                int totalMedia = 0;
                if (photos || videos || voice || stickers || files) {
                    if (serverMessages != null && !serverMessages.isEmpty()) {
                        for (int i = 0; i < serverMessages.size(); i++) {
                            if (hasSelectedMedia(serverMessages.get(i), photos, videos, voice, stickers, files)) {
                                totalMedia++;
                            }
                        }
                    } else {
                        CountDownLatch mediaCountLatch = new CountDownLatch(1);
                        final int[] mediaTotal = {0};
                        storage.getStorageQueue().postRunnable(() -> {
                            try {
                                SQLiteCursor cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + dialogId);
                                while (cursor.next()) {
                                    NativeByteBuffer data = cursor.byteBufferValue(0);
                                    if (data != null) {
                                        try {
                                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                            if (hasSelectedMedia(message, photos, videos, voice, stickers, files)) {
                                                mediaTotal[0]++;
                                            }
                                        } catch (Exception e) {
                                            FileLog.e(e);
                                        }
                                        data.reuse();
                                    }
                                }
                                cursor.dispose();
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                            mediaCountLatch.countDown();
                        });
                        awaitLatchWithoutTimeout(mediaCountLatch);
                        totalMedia = mediaTotal[0];
                    }
                }
                state.mediaTotal = totalMedia;

                checkCanceled();

                boolean isFirstJson = true;
                final int batchSize = DB_EXPORT_BATCH_SIZE;
                long lastMid = Long.MIN_VALUE;
                int serverMessageIndex = 0;

                while (true) {
                    final ArrayList<TLRPC.Message> messages = new ArrayList<>();
                    final ArrayList<Long> messageMids = new ArrayList<>();
                    if (serverMessages != null && !serverMessages.isEmpty()) {
                        int endIndex = Math.min(serverMessageIndex + batchSize, serverMessages.size());
                        for (int i = serverMessageIndex; i < endIndex; i++) {
                            messages.add(serverMessages.get(i));
                        }
                        serverMessageIndex = endIndex;
                    } else {
                        CountDownLatch latch = new CountDownLatch(1);
                        final long currentLastMid = lastMid;

                        storage.getStorageQueue().postRunnable(() -> {
                            try {
                                // Load all messages in batches using mid-based cursor
                                // mid can be negative (channels) so we use > comparison
                                String query;
                                if (currentLastMid == Long.MIN_VALUE) {
                                    query = "SELECT data, mid FROM messages_v2 WHERE uid = " + dialogId + " ORDER BY mid ASC LIMIT " + batchSize;
                                } else {
                                    query = "SELECT data, mid FROM messages_v2 WHERE uid = " + dialogId + " AND mid > " + currentLastMid + " ORDER BY mid ASC LIMIT " + batchSize;
                                }
                                SQLiteCursor cursor = storage.getDatabase().queryFinalized(query);
                                while (cursor.next()) {
                                    NativeByteBuffer data = cursor.byteBufferValue(0);
                                    if (data != null) {
                                        try {
                                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                            if (message != null) {
                                                message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                                                messages.add(message);
                                                messageMids.add(cursor.longValue(1));
                                            }
                                        } catch (Exception e) {
                                            FileLog.e(e);
                                        }
                                        data.reuse();
                                    }
                                }
                                cursor.dispose();
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                            latch.countDown();
                        });

                        awaitLatchWithoutTimeout(latch);
                    }
                    
                    if (messages.isEmpty()) {
                        break;
                    }

                    // Update lastMid to the last message's mid for next batch
                    if (serverMessages == null || serverMessages.isEmpty()) {
                        lastMid = messageMids.get(messageMids.size() - 1);
                    }

                    for (TLRPC.Message msg : messages) {
                        checkCanceled();
                        count++;
                        messagesInCurrentFile++;

                        // Split HTML files every MESSAGES_PER_FILE messages
                        if (htmlFormat && messagesInCurrentFile >= MESSAGES_PER_FILE) {
                            writer.write("<div class=\"nav\"><a href=\"export_" + (fileIndex + 1) + ".html\">Вперёд →</a></div>\n");
                            writer.write("</body>\n</html>");
                            writer.flush();
                            writer.close();

                            fileIndex++;
                            messagesInCurrentFile = 0;
                            File nextFile = new File(exportDir, "export_" + fileIndex + ".html");
                            fos = new FileOutputStream(nextFile);
                            writer = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"), 8192);
                            writer.write("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>Chat Export — Часть " + fileIndex + "</title>\n");
                            writer.write("<style>\n");
                            writer.write("*{box-sizing:border-box; margin:0; padding:0;}\n");
                            writer.write("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; background:#e7ebf0; color:#000; padding:16px;}\n");
                            writer.write(".header{background:#3390ec; color:#fff; padding:20px; border-radius:16px; margin-bottom:16px; text-align:center;}\n");
                            writer.write(".header h1{font-size:20px; font-weight:600;}\n");
                            writer.write(".header .info{font-size:13px; opacity:0.85; margin-top:4px;}\n");
                            writer.write(".nav{text-align:center; margin:12px 0; padding:8px;}\n");
                            writer.write(".nav a{display:inline-block; background:#3390ec; color:#fff; padding:8px 16px; border-radius:8px; text-decoration:none; margin:0 4px; font-size:14px;}\n");
                            writer.write(".msg{background:#fff; padding:12px 16px; margin:4px 0; border-radius:14px; max-width:85%; word-wrap:break-word; box-shadow:0 1px 2px rgba(0,0,0,0.08);}\n");
                            writer.write(".msg.out{background:#efffde; margin-left:auto; border-bottom-right-radius:4px;}\n");
                            writer.write(".msg.in{margin-right:auto; border-bottom-left-radius:4px;}\n");
                            writer.write(".msg .sender{font-weight:600; font-size:14px; color:#3390ec; margin-bottom:2px;}\n");
                            writer.write(".msg.out .sender{color:#4caf50;}\n");
                            writer.write(".msg .text{font-size:15px; line-height:1.4;}\n");
                            writer.write(".msg .date{font-size:11px; color:#999; margin-top:4px; text-align:right;}\n");
                            writer.write(".msg a{color:#3390ec; text-decoration:none;}\n");
                            writer.write(".msg a:hover{text-decoration:underline;}\n");
                            writer.write(".media-badge{display:inline-block; background:#3390ec1a; color:#3390ec; padding:4px 10px; border-radius:8px; font-size:13px; margin-top:4px;}\n");
                            writer.write("</style>\n");
                            writer.write("</head>\n<body>\n");
                            writer.write("<div class=\"header\"><h1>Экспорт чата — Часть " + fileIndex + "</h1><div class=\"info\">" + dateFormat.format(new Date()) + "</div></div>\n");
                            writer.write("<div class=\"nav\"><a href=\"export_" + (fileIndex - 1) + ".html\">← Назад</a></div>\n");
                        }
                        
                        // Process message text
                        String text = msg.message != null ? msg.message : "";
                        String date = dateFormat.format(new Date(msg.date * 1000L));
                        String sender = getSenderName(account, msg);
                        String safeSender = TextUtils.htmlEncode(sender);
                        
                        // Handle Media
                        String mediaPath = "";
                        boolean hasMediaToDownload = false;
                        String mediaTypeLabel = "";
                        String mediaSubfolder = "";
                        if (msg.media != null) {
                            TLRPC.Document doc = MessageObject.getDocument(msg);
                            if (photos && msg.media instanceof TLRPC.TL_messageMediaPhoto && msg.media.photo != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "фото";
                                mediaSubfolder = "photos";
                                mediaPath = checkAndCopyMedia(account, msg.media.photo, photosDir, maxFileSize);
                            } else if (photos && msg.media instanceof TLRPC.TL_messageMediaWebPage && msg.media.webpage != null && msg.media.webpage.photo != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "фото";
                                mediaSubfolder = "photos";
                                mediaPath = checkAndCopyMedia(account, msg.media.webpage.photo, photosDir, maxFileSize);
                            } else if (videos && MessageObject.isVideoMessage(msg) && doc != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "видео";
                                mediaSubfolder = "videos";
                                mediaPath = checkAndCopyMedia(account, doc, videosDir, maxFileSize);
                            } else if (videos && MessageObject.isRoundVideoMessage(msg) && doc != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "видео";
                                mediaSubfolder = "videos";
                                mediaPath = checkAndCopyMedia(account, doc, videosDir, maxFileSize);
                            } else if (voice && MessageObject.isVoiceMessage(msg) && doc != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "голосовое";
                                mediaSubfolder = "voice";
                                mediaPath = checkAndCopyMedia(account, doc, voiceDir, maxFileSize);
                            } else if (videos && MessageObject.isGifMessage(msg) && doc != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "gif";
                                mediaSubfolder = "videos";
                                mediaPath = checkAndCopyMedia(account, doc, videosDir, maxFileSize);
                            } else if ((videos || stickers) && MessageObject.isAnimatedStickerMessage(msg) && doc != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "animated_sticker";
                                mediaSubfolder = "videos";
                                mediaPath = checkAndCopyMedia(account, doc, videosDir, maxFileSize);
                            } else if (voice && MessageObject.isMusicMessage(msg) && doc != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "music";
                                mediaSubfolder = "voice";
                                mediaPath = checkAndCopyMedia(account, doc, voiceDir, maxFileSize);
                            } else if (stickers && MessageObject.isStickerMessage(msg) && doc != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "стикер";
                                mediaSubfolder = "stickers";
                                mediaPath = checkAndCopyMedia(account, doc, stickersDir, maxFileSize);
                            } else if (files && doc != null && !MessageObject.isStickerMessage(msg) && !MessageObject.isVoiceMessage(msg) && !MessageObject.isVideoMessage(msg) && !MessageObject.isRoundVideoMessage(msg) && !MessageObject.isAnimatedStickerMessage(msg) && !MessageObject.isMusicMessage(msg) && !MessageObject.isGifMessage(msg)) {
                                // Regular files/documents
                                hasMediaToDownload = true;
                                mediaTypeLabel = "файл";
                                mediaSubfolder = "files";
                                mediaPath = checkAndCopyMedia(account, doc, filesDir, maxFileSize);
                            }
                        }
                        if (hasMediaToDownload) {
                            mediaProcessed++;
                            state.mediaProcessed = mediaProcessed;
                            state.currentMediaInfo = mediaTypeLabel;
                        }

                        if (htmlFormat) {
                            String msgClass = msg.out ? "out" : "in";
                            writer.write("<div class=\"msg " + msgClass + "\">");
                            writer.write("<div class=\"sender\">" + safeSender + "</div>");
                            if (!TextUtils.isEmpty(text)) {
                                writer.write("<div class=\"text\">" + TextUtils.htmlEncode(text).replace("\n", "<br>") + "</div>");
                            }
                            if (!TextUtils.isEmpty(mediaPath)) {
                                writer.write("<a class=\"media-badge\" href=\"media/" + mediaSubfolder + "/" + new File(mediaPath).getName() + "\">📎 " + mediaTypeLabel + "</a>");
                            }
                            writer.write("<div class=\"date\">" + date + "</div>");
                            writer.write("</div>\n");
                        } else {
                            if (!isFirstJson) writer.write(",\n");
                            isFirstJson = false;
                            String jsonText = escapeJson(text);
                            String jsonSender = escapeJson(sender);
                            writer.write("{\"id\": " + msg.id + ", \"date\": \"" + date + "\", \"sender\": \"" + jsonSender + "\", \"text\": \"" + jsonText + "\"");
                            if (!TextUtils.isEmpty(mediaPath)) {
                                writer.write(", \"media\": \"media/" + mediaSubfolder + "/" + new File(mediaPath).getName() + "\", \"media_type\": \"" + mediaTypeLabel + "\"");
                            }
                            writer.write("}");
                        }
                        
                        final int currentCount = count;
                        final int cTotal = totalMessages;
                        final int cMediaProcessed = mediaProcessed;
                        final int cMediaTotal = totalMedia;
                        if (cTotal > 0 && (currentCount % 50 == 0 || currentCount == cTotal || hasMediaToDownload)) {
                            long now = System.currentTimeMillis();
                            if (currentCount == cTotal || now - lastProgressUpdate > 500) {
                                lastProgressUpdate = now;
                                // Weight: messages + media (media counts as extra work)
                                int totalUnits = cTotal + cMediaTotal;
                                int doneUnits = currentCount + cMediaProcessed;
                                int percent = totalUnits > 0 ? (int) ((doneUnits / (float) totalUnits) * 100) : 0;
                                state.progress = currentCount;
                                state.percent = percent;
                                state.mediaProcessed = cMediaProcessed;
                                String progressText = "Экспорт: " + percent + "% (" + currentCount + "/" + cTotal + " сообщений";
                                if (cMediaTotal > 0) {
                                    progressText += ", " + cMediaProcessed + "/" + cMediaTotal + " медиа";
                                }
                                progressText += ")";
                                showProgressNotification(percent, progressText);
                                AndroidUtilities.runOnUIThread(() -> {
                                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaExportProgressChanged, state);
                                });
                            }
                        }
                    }
                }

                if (htmlFormat) {
                    writer.write("</body>\n</html>");
                } else {
                    writer.write("\n]\n}");
                }

                writer.flush();
                writer.close();

                File finalOutputFile = htmlFormat ? new File(exportDir, "export_" + fileIndex + ".html") : new File(exportDir, "export.json");
                state.completed = true;
                state.running = false;
                state.outputFilePath = finalOutputFile.getAbsolutePath();
                showCompletionNotification(finalOutputFile.getAbsolutePath());
                
                final File finalExportDir = exportDir;
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaExportCompleted, state);
                    callback.onComplete(finalExportDir.getAbsolutePath());
                });

            } catch (InterruptedException e) {
                // Export was canceled
                state.running = false;
                cancelExportNotification();
                try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                // Delete partial file
                try {
                    if (exportDir != null) {
                        File partialFile = new File(exportDir, htmlFormat ? "export.html" : "export.json");
                        if (partialFile.exists()) partialFile.delete();
                    }
                } catch (Exception ignored) {}
                AndroidUtilities.runOnUIThread(() -> callback.onError("Экспорт отменён"));
            } catch (Exception e) {
                FileLog.e(e);
                state.running = false;
                state.error = e.getMessage();
                showErrorNotification(e.getMessage());
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mglaExportFailed, state);
                    callback.onError(e.getMessage());
                });
            }
        }).start();
    }

    private static String getSenderName(int account, TLRPC.Message msg) {
        try {
            if (msg.out) {
                TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
                if (self != null) {
                    String username = UserObject.getPublicUsername(self);
                    if (username != null && !username.isEmpty()) {
                        return username;
                    }
                    return UserObject.getUserName(self);
                }
                return "You";
            }
            MessagesController mc = MessagesController.getInstance(account);
            if (msg.from_id != null) {
                if (msg.from_id instanceof TLRPC.TL_peerUser) {
                    TLRPC.User user = mc.getUser(msg.from_id.user_id);
                    if (user != null) {
                        String username = UserObject.getPublicUsername(user);
                        if (username != null && !username.isEmpty()) {
                            return username;
                        }
                        return UserObject.getUserName(user);
                    }
                } else if (msg.from_id instanceof TLRPC.TL_peerChannel || msg.from_id instanceof TLRPC.TL_peerChat) {
                    long chatId = msg.from_id instanceof TLRPC.TL_peerChannel ? msg.from_id.channel_id : msg.from_id.chat_id;
                    TLRPC.Chat chat = mc.getChat(chatId);
                    if (chat != null && chat.title != null && !chat.title.isEmpty()) {
                        return chat.title;
                    }
                }
            }
            // Fallback: try dialog peer
            if (msg.dialog_id > 0) {
                TLRPC.User user = mc.getUser(msg.dialog_id);
                if (user != null) {
                    String username = UserObject.getPublicUsername(user);
                    if (username != null && !username.isEmpty()) {
                        return username;
                    }
                    return UserObject.getUserName(user);
                }
            } else if (msg.dialog_id < 0) {
                TLRPC.Chat chat = mc.getChat(-msg.dialog_id);
                if (chat != null && chat.title != null && !chat.title.isEmpty()) {
                    return chat.title;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "Unknown";
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static boolean hasSelectedMedia(TLRPC.Message message, boolean photos, boolean videos, boolean voice, boolean stickers, boolean files) {
        if (message == null || message.media == null) {
            return false;
        }
        TLRPC.Document doc = MessageObject.getDocument(message);
        if (photos && message.media instanceof TLRPC.TL_messageMediaPhoto && message.media.photo != null) return true;
        if (photos && message.media instanceof TLRPC.TL_messageMediaWebPage && message.media.webpage != null && message.media.webpage.photo != null) return true;
        if (videos && doc != null && MessageObject.isVideoMessage(message)) return true;
        if (videos && doc != null && MessageObject.isRoundVideoMessage(message)) return true;
        if (voice && doc != null && MessageObject.isVoiceMessage(message)) return true;
        if (videos && doc != null && MessageObject.isGifMessage(message)) return true;
        if ((videos || stickers) && doc != null && MessageObject.isAnimatedStickerMessage(message)) return true;
        if (voice && doc != null && MessageObject.isMusicMessage(message)) return true;
        if (stickers && doc != null && MessageObject.isStickerMessage(message)) return true;
        return files && doc != null && !MessageObject.isStickerMessage(message) && !MessageObject.isVoiceMessage(message) && !MessageObject.isVideoMessage(message) && !MessageObject.isRoundVideoMessage(message) && !MessageObject.isAnimatedStickerMessage(message) && !MessageObject.isMusicMessage(message) && !MessageObject.isGifMessage(message);
    }

    private static String checkAndCopyMedia(int account, Object mediaObject, File mediaDir, long maxFileSize) {
        try {
            File localPath = null;
            long mediaSize = 0;
            if (mediaObject instanceof TLRPC.Photo) {
                TLRPC.Photo photo = (TLRPC.Photo) mediaObject;
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true));
                if (size == null) {
                    size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1000);
                }
                if (size != null) {
                    mediaSize = size.size;
                    localPath = FileLoader.getInstance(account).getPathToAttach(size, false);
                    if (localPath == null || !localPath.exists()) {
                        localPath = FileLoader.getInstance(account).getPathToAttach(size, true);
                    }
                    if (localPath == null || !localPath.exists()) {
                        localPath = downloadMediaSync(account, size, null, photo);
                    }
                }
            } else if (mediaObject instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) mediaObject;
                mediaSize = document.size;
                localPath = FileLoader.getInstance(account).getPathToAttach(document, false);
                if (localPath == null || !localPath.exists()) {
                    localPath = FileLoader.getInstance(account).getPathToAttach(document, true);
                }
                if (localPath == null || !localPath.exists()) {
                    localPath = downloadMediaSync(account, null, document, null);
                }
            }

            // Check file size limit
            if (maxFileSize > 0) {
                long actualSize = mediaSize;
                if (actualSize <= 0 && localPath != null && localPath.exists()) {
                    actualSize = localPath.length();
                }
                if (actualSize > maxFileSize) {
                    return ""; // Skip this file — too large
                }
            }

            if (localPath != null && localPath.exists()) {
                File destFile = new File(mediaDir, localPath.getName());
                // Handle name collision — append number if file already exists with different content
                if (destFile.exists() && destFile.length() != localPath.length()) {
                    String baseName = localPath.getName();
                    int dotIdx = baseName.lastIndexOf('.');
                    String name = dotIdx > 0 ? baseName.substring(0, dotIdx) : baseName;
                    String ext = dotIdx > 0 ? baseName.substring(dotIdx) : "";
                    int suffix = 1;
                    while (destFile.exists() && destFile.length() != localPath.length()) {
                        destFile = new File(mediaDir, name + "_" + suffix + ext);
                        suffix++;
                    }
                }
                AndroidUtilities.copyFile(localPath, destFile);
                return destFile.getAbsolutePath();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "";
    }

    private static File downloadMediaSync(final int account, final TLRPC.PhotoSize photoSize, final TLRPC.Document document, final TLRPC.Photo photo) {
        try {
            while (true) {
                checkCanceled();
                final CountDownLatch latch = new CountDownLatch(1);
                final File[] result = new File[1];

                final NotificationCenter.NotificationCenterDelegate delegate = new NotificationCenter.NotificationCenterDelegate() {
                    @Override
                    public void didReceivedNotification(int id, int accountId, Object... args) {
                        if (id == NotificationCenter.fileLoaded) {
                            String fileName = (String) args[0];
                            String expectedName = "";
                            if (photoSize != null) expectedName = FileLoader.getAttachFileName(photoSize);
                            else if (document != null) expectedName = FileLoader.getAttachFileName(document);

                            if (expectedName.equals(fileName)) {
                                if (args[1] instanceof File) {
                                    result[0] = (File) args[1];
                                } else if (args[1] instanceof String) {
                                    result[0] = new File((String) args[1]);
                                }
                                latch.countDown();
                            }
                        } else if (id == NotificationCenter.fileLoadFailed) {
                            String fileName = (String) args[0];
                            String expectedName = "";
                            if (photoSize != null) expectedName = FileLoader.getAttachFileName(photoSize);
                            else if (document != null) expectedName = FileLoader.getAttachFileName(document);

                            if (expectedName.equals(fileName)) {
                                latch.countDown();
                            }
                        }
                    }
                };

                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(account).addObserver(delegate, NotificationCenter.fileLoaded);
                    NotificationCenter.getInstance(account).addObserver(delegate, NotificationCenter.fileLoadFailed);

                    if (photoSize != null) {
                        FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(photoSize, photo), photo != null ? photo : photoSize, null, FileLoader.PRIORITY_HIGH, 1);
                    } else if (document != null) {
                        FileLoader.getInstance(account).loadFile(document, document, FileLoader.PRIORITY_HIGH, 1);
                    }
                });

                try {
                    awaitLatchWithoutTimeout(latch);
                } finally {
                    AndroidUtilities.runOnUIThread(() -> {
                        NotificationCenter.getInstance(account).removeObserver(delegate, NotificationCenter.fileLoaded);
                        NotificationCenter.getInstance(account).removeObserver(delegate, NotificationCenter.fileLoadFailed);
                    });
                }

                if (result[0] != null && result[0].exists()) {
                    return result[0];
                }
                Thread.sleep(MEDIA_DOWNLOAD_RETRY_DELAY_MS);
            }
        } catch (InterruptedException e) {
            FileLog.e(e);
        }

        return null;
    }
}
