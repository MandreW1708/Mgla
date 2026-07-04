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
import android.content.SharedPreferences;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

public class NotificationsService extends Service {

    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (shouldKeepRunning()) {
                startForegroundNotification();
            } else {
                stopSelf();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return START_STICKY;
    }

    private boolean shouldKeepRunning() {
        if (MglaSpyConfig.isSaveDeletedMessagesEnabled()) {
            return true;
        }
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.contains("pushService")) {
            return preferences.getBoolean("pushService", true);
        }
        return MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false);
    }

    private void startForegroundNotification() {
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
        builder.setSmallIcon(R.drawable.mgla_notification_blank);
        builder.setContentTitle("Mgla");
        builder.setOngoing(true);
        builder.setPriority(NotificationCompat.PRIORITY_MIN);
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
        builder.setContentIntent(contentIntent);
        builder.setSilent(true);
        builder.setShowWhen(false);
        builder.setVisibility(NotificationCompat.VISIBILITY_SECRET);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        if (shouldKeepRunning()) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
