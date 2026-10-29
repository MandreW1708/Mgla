/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.ui.LaunchActivity;

/**
 * Sticky FGS «Фоновая работа» while Mgla «Висеть в фоне» is enabled.
 * Independent of save-deleted. When hang is off, this service is stopped and FCM is used.
 */
public class NotificationsService extends Service {

    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ApplicationLoader.isNotificationsKeepAliveWanted()) {
            tearDownForeground();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNotification();
        return START_STICKY;
    }

    private void tearDownForeground() {
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        } catch (Throwable ignore) {
        }
    }

    private void startForegroundNotification() {
        if (ApplicationLoader.applicationContext == null) {
            return;
        }

        NotificationsController.checkMglaBackgroundChannel();

        Intent launchIntent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        launchIntent.setAction("org.tmessages.openchat");
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent contentIntent = PendingIntent.getActivity(
            ApplicationLoader.applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.MGLA_BACKGROUND_CHANNEL);
        builder.setWhen(System.currentTimeMillis());
        builder.setSmallIcon(R.drawable.mgla_notification_service);
        builder.setContentTitle("Mgla");
        builder.setOngoing(true);
        builder.setPriority(NotificationCompat.PRIORITY_MIN);
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
        builder.setContentIntent(contentIntent);
        builder.setSilent(true);
        builder.setShowWhen(false);
        builder.setVisibility(NotificationCompat.VISIBILITY_SECRET);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, builder.build(), 1073741824 /* ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE */);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        super.onTimeout(startId, fgsType);
        try {
            stopForeground(true);
            stopSelfResult(startId);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (!ApplicationLoader.isNotificationsKeepAliveWanted()) {
            tearDownForeground();
            return;
        }
        try {
            Intent intent = new Intent(this, NotificationsService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            try {
                Intent intent = new Intent("org.telegram.start");
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
    }
}
