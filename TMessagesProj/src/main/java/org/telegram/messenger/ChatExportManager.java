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

    private static final int EXPORT_NOTIFICATION_ID = 77777;
    private static final String EXPORT_CHANNEL_ID = "chat_export_channel";
    private static final String EXPORT_CHANNEL_NAME = "Экспорт чата";
    private static volatile long lastProgressUpdate = 0;
    private static volatile boolean exportRunning = false;

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

    public static void startExport(int account, long dialogId, boolean photos, boolean videos, boolean voice, boolean stickers, boolean htmlFormat, String folderName, ExportCallback callback) {
        if (exportRunning) {
            AndroidUtilities.runOnUIThread(() -> callback.onError("Экспорт уже выполняется"));
            return;
        }
        exportRunning = true;
        lastProgressUpdate = 0;
        showIndeterminateNotification("Подготовка...");

        new Thread(() -> {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            try {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File exportDir = new File(downloadsDir, folderName);
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    showErrorNotification("Не удалось создать папку экспорта");
                    AndroidUtilities.runOnUIThread(() -> callback.onError("Failed to create export directory"));
                    return;
                }

                File mediaDir = new File(exportDir, "media");
                if ((photos || videos || voice || stickers) && !mediaDir.exists()) {
                    mediaDir.mkdirs();
                }

                File outputFile = new File(exportDir, htmlFormat ? "export.html" : "export.json");
                FileOutputStream fos = new FileOutputStream(outputFile);
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
                        lastMid = msg.id;
                        count++;
                        
                        // Process message text
                        String text = msg.message != null ? msg.message : "";
                        String date = dateFormat.format(new Date(msg.date * 1000L));
                        String sender = msg.out ? "You" : "Peer";
                        
                        // Handle Media
                        String mediaPath = "";
                        if (msg.media != null) {
                            if (photos && msg.media.photo != null) {
                                mediaPath = checkAndCopyMedia(account, msg.media.photo, mediaDir);
                            } else if (videos && msg.media.document != null && MessageObject.isVideoMessage(msg)) {
                                mediaPath = checkAndCopyMedia(account, msg.media.document, mediaDir);
                            } else if (voice && msg.media.document != null && MessageObject.isVoiceMessage(msg)) {
                                mediaPath = checkAndCopyMedia(account, msg.media.document, mediaDir);
                            } else if (stickers && msg.media.document != null && MessageObject.isStickerMessage(msg)) {
                                mediaPath = checkAndCopyMedia(account, msg.media.document, mediaDir);
                            }
                        }

                        if (htmlFormat) {
                            writer.write("<div class=\"msg\"><b>" + sender + "</b> [" + date + "]<br>");
                            if (!TextUtils.isEmpty(text)) {
                                writer.write(TextUtils.htmlEncode(text).replace("\n", "<br>") + "<br>");
                            }
                            if (!TextUtils.isEmpty(mediaPath)) {
                                writer.write("<a href=\"media/" + new File(mediaPath).getName() + "\">[Media]</a><br>");
                            }
                            writer.write("</div>\n");
                        } else {
                            if (!isFirstJson) writer.write(",\n");
                            isFirstJson = false;
                            writer.write("{\"id\": " + msg.id + ", \"date\": \"" + date + "\", \"sender\": \"" + sender + "\", \"text\": \"" + text.replace("\"", "\\\"").replace("\n", "\\n") + "\"");
                            if (!TextUtils.isEmpty(mediaPath)) {
                                writer.write(", \"media\": \"media/" + new File(mediaPath).getName() + "\"");
                            }
                            writer.write("}");
                        }
                        
                        final int currentCount = count;
                        final int cTotal = totalMessages;
                        if (cTotal > 0 && (currentCount % 100 == 0 || currentCount == cTotal)) {
                            long now = System.currentTimeMillis();
                            if (currentCount == cTotal || now - lastProgressUpdate > 1000) {
                                lastProgressUpdate = now;
                                int percent = (int) ((currentCount / (float) cTotal) * 100);
                                showProgressNotification(percent, "Экспорт: " + percent + "% (" + currentCount + "/" + cTotal + ")");
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

                showCompletionNotification(outputFile.getAbsolutePath());
                
                AndroidUtilities.runOnUIThread(() -> {
                    callback.onComplete(exportDir.getAbsolutePath());
                });

            } catch (Exception e) {
                FileLog.e(e);
                showErrorNotification(e.getMessage());
                AndroidUtilities.runOnUIThread(() -> callback.onError(e.getMessage()));
            } finally {
                exportRunning = false;
            }
        }).start();
    }

    private static String checkAndCopyMedia(int account, Object mediaObject, File mediaDir) {
        try {
            File localPath = null;
            if (mediaObject instanceof TLRPC.Photo) {
                TLRPC.Photo photo = (TLRPC.Photo) mediaObject;
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1000);
                if (size != null) {
                    localPath = FileLoader.getInstance(account).getPathToAttach(size, true);
                    if (localPath != null && !localPath.exists()) {
                        localPath = downloadMediaSync(account, size, null);
                    }
                }
            } else if (mediaObject instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) mediaObject;
                localPath = FileLoader.getInstance(account).getPathToAttach(document, true);
                if (localPath != null && !localPath.exists()) {
                    localPath = downloadMediaSync(account, null, document);
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

    private static File downloadMediaSync(final int account, final TLRPC.PhotoSize photoSize, final TLRPC.Document document) {
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
                        result[0] = new File((String) args[1]);
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
                FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(photoSize, (TLRPC.Photo)null), photoSize, null, 1, 1);
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
