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
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

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

    public static void startExport(int account, long dialogId, boolean photos, boolean videos, boolean voice, boolean stickers, boolean htmlFormat, String folderName, String saveLocation, long maxFileSize, ExportCallback callback) {
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
                if ((photos || videos || voice || stickers) && !mediaDir.exists()) {
                    mediaDir.mkdirs();
                }
                File photosDir = new File(mediaDir, "photos");
                File videosDir = new File(mediaDir, "videos");
                File voiceDir = new File(mediaDir, "voice");
                File stickersDir = new File(mediaDir, "stickers");
                File filesDir = new File(mediaDir, "files");
                if (photos && !photosDir.exists()) photosDir.mkdirs();
                if (videos && !videosDir.exists()) videosDir.mkdirs();
                if (voice && !voiceDir.exists()) voiceDir.mkdirs();
                if (stickers && !stickersDir.exists()) stickersDir.mkdirs();
                if (photos && !filesDir.exists()) filesDir.mkdirs();

                File outputFile = new File(exportDir, htmlFormat ? "export.html" : "export.json");
                fos = new FileOutputStream(outputFile);
                OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");

                if (htmlFormat) {
                    writer.write("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>Chat Export</title>\n");
                    writer.write("<style>body{font-family:sans-serif; background:#f0f0f0;} .msg{background:#fff; padding:10px; margin:10px; border-radius:10px;}</style>\n");
                    writer.write("</head>\n<body>\n");
                } else {
                    writer.write("{\n\"messages\": [\n");
                }

                MessagesStorage storage = MessagesStorage.getInstance(account);
                int count = 0;
                int mediaProcessed = 0;
                int totalMessages = 0;
                
                // Get total messages for progress
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
                countLatch.await();
                totalMessages = total[0];
                state.total = totalMessages;

                checkCanceled();

                // Count total media items to download
                int totalMedia = 0;
                if (photos || videos || voice || stickers) {
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
                                        if (message != null && message.media != null) {
                                            boolean hasMedia = false;
                                            TLRPC.Document doc = MessageObject.getDocument(message);
                                            if (photos && MessageObject.isPhoto(message) && message.media.photo != null) hasMedia = true;
                                            else if (videos && doc != null && MessageObject.isVideoMessage(message)) hasMedia = true;
                                            else if (videos && doc != null && MessageObject.isRoundVideoMessage(message)) hasMedia = true;
                                            else if (voice && doc != null && MessageObject.isVoiceMessage(message)) hasMedia = true;
                                            else if (stickers && doc != null && MessageObject.isStickerMessage(message)) hasMedia = true;
                                            else if (photos && doc != null && !MessageObject.isStickerMessage(message) && !MessageObject.isVoiceMessage(message) && !MessageObject.isVideoMessage(message) && !MessageObject.isRoundVideoMessage(message) && !MessageObject.isAnimatedStickerMessage(message) && !MessageObject.isMusicMessage(message) && !MessageObject.isGifMessage(message)) hasMedia = true;
                                            if (hasMedia) mediaTotal[0]++;
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
                    mediaCountLatch.await();
                    totalMedia = mediaTotal[0];
                }
                state.mediaTotal = totalMedia;

                checkCanceled();

                int lastMid = 0;
                boolean isFirstJson = true;

                while (true) {
                    final ArrayList<TLRPC.Message> messages = new ArrayList<>();
                    CountDownLatch latch = new CountDownLatch(1);
                    final int currentLastMid = lastMid;
                    
                    storage.getStorageQueue().postRunnable(() -> {
                        try {
                            SQLiteCursor cursor = storage.getDatabase().queryFinalized(
                                    "SELECT data, mid FROM messages_v2 WHERE uid = " + dialogId + " AND mid > " + currentLastMid + " ORDER BY mid ASC LIMIT 1000");
                            while (cursor.next()) {
                                NativeByteBuffer data = cursor.byteBufferValue(0);
                                if (data != null) {
                                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                    if (message != null) {
                                        message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                                        messages.add(message);
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
                    
                    latch.await();
                    
                    if (messages.isEmpty()) {
                        break;
                    }

                    for (TLRPC.Message msg : messages) {
                        checkCanceled();
                        lastMid = msg.id;
                        count++;
                        
                        // Process message text
                        String text = msg.message != null ? msg.message : "";
                        String date = dateFormat.format(new Date(msg.date * 1000L));
                        String sender = msg.out ? "You" : "Peer";
                        
                        // Handle Media
                        String mediaPath = "";
                        boolean hasMediaToDownload = false;
                        String mediaTypeLabel = "";
                        String mediaSubfolder = "";
                        if (msg.media != null) {
                            TLRPC.Document doc = MessageObject.getDocument(msg);
                            if (photos && MessageObject.isPhoto(msg) && msg.media.photo != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "фото";
                                mediaSubfolder = "photos";
                                mediaPath = checkAndCopyMedia(account, msg.media.photo, photosDir, maxFileSize);
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
                            } else if (stickers && MessageObject.isStickerMessage(msg) && doc != null) {
                                hasMediaToDownload = true;
                                mediaTypeLabel = "стикер";
                                mediaSubfolder = "stickers";
                                mediaPath = checkAndCopyMedia(account, doc, stickersDir, maxFileSize);
                            } else if (photos && doc != null && !MessageObject.isStickerMessage(msg) && !MessageObject.isVoiceMessage(msg) && !MessageObject.isVideoMessage(msg) && !MessageObject.isRoundVideoMessage(msg) && !MessageObject.isAnimatedStickerMessage(msg) && !MessageObject.isMusicMessage(msg) && !MessageObject.isGifMessage(msg)) {
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
                            writer.write("<div class=\"msg\"><b>" + sender + "</b> [" + date + "]<br>");
                            if (!TextUtils.isEmpty(text)) {
                                writer.write(TextUtils.htmlEncode(text).replace("\n", "<br>") + "<br>");
                            }
                            if (!TextUtils.isEmpty(mediaPath)) {
                                writer.write("<a href=\"media/" + mediaSubfolder + "/" + new File(mediaPath).getName() + "\">[" + mediaTypeLabel + "]</a><br>");
                            }
                            writer.write("</div>\n");
                        } else {
                            if (!isFirstJson) writer.write(",\n");
                            isFirstJson = false;
                            writer.write("{\"id\": " + msg.id + ", \"date\": \"" + date + "\", \"sender\": \"" + sender + "\", \"text\": \"" + text.replace("\"", "\\\"").replace("\n", "\\n") + "\"");
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
                fos.close();

                state.completed = true;
                state.running = false;
                state.outputFilePath = outputFile.getAbsolutePath();
                showCompletionNotification(outputFile.getAbsolutePath());
                
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
                AndroidUtilities.copyFile(localPath, destFile);
                return destFile.getAbsolutePath();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "";
    }

    private static File downloadMediaSync(final int account, final TLRPC.PhotoSize photoSize, final TLRPC.Document document, final TLRPC.Photo photo) {
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
                FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(photoSize, photo), photoSize, null, 1, 1);
            } else if (document != null) {
                FileLoader.getInstance(account).loadFile(document, document, 1, 1);
            }
        });
        
        try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            FileLog.e(e);
        }
        
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).removeObserver(delegate, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(account).removeObserver(delegate, NotificationCenter.fileLoadFailed);
        });
        
        return result[0];
    }
}
