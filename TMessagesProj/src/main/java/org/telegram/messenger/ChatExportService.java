package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Keeps the process alive while chat export is running (foreground dataSync).
 */
public class ChatExportService extends Service implements NotificationCenter.NotificationCenterDelegate {

    public static final int NOTIFICATION_ID = ChatExportManager.EXPORT_NOTIFICATION_ID;
    private static final String CHANNEL_ID = ChatExportManager.EXPORT_CHANNEL_ID;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportCompleted);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportCanceled);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mglaExportFailed);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportCompleted);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportCanceled);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mglaExportFailed);
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ChatExportManager.isExportRunning()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureChannel();
        Notification notification = buildNotification("Экспорт чата...");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.mglaExportCompleted
                || id == NotificationCenter.mglaExportCanceled
                || id == NotificationCenter.mglaExportFailed) {
            stopSelf();
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(CHANNEL_ID, "Экспорт чата", NotificationManager.IMPORTANCE_LOW);
                channel.enableLights(false);
                channel.enableVibration(false);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private Notification buildNotification(String text) {
        Intent launchIntent = new Intent(this, org.telegram.ui.LaunchActivity.class);
        launchIntent.setAction("org.tmessages.openchat");
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(org.telegram.ui.MglaGlassConfig.getNotificationSmallIconRes())
                .setContentTitle(LocaleController.getString("ExportChatTitle", R.string.ExportChatTitle))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setColor(0xff11acfa)
                .setProgress(0, 0, true)
                .setContentIntent(pendingIntent)
                .build();
    }

    public static void start() {
        try {
            Context context = ApplicationLoader.applicationContext;
            Intent intent = new Intent(context, ChatExportService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void stop() {
        try {
            Context context = ApplicationLoader.applicationContext;
            context.stopService(new Intent(context, ChatExportService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
