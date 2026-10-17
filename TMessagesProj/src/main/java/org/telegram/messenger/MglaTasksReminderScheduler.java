package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

public class MglaTasksReminderScheduler {

    public static final String ACTION_REMINDER = "org.telegram.messenger.mgla.task.reminder";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String CHANNEL_ID = "mgla_tasks_reminders";

    private static final int NOTIFICATION_ID_BASE = 0x4D474C00;

    public static void schedule(MglaTask task) {
        if (task == null || task.id == 0) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        long now = System.currentTimeMillis();
        if (task.done || task.remindAt <= now) {
            cancel(task.id);
            return;
        }
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pendingIntent = buildPendingIntent(context, task.id);
            if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.remindAt, pendingIntent);
            } else {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, task.remindAt - 5 * 60 * 1000, 10 * 60 * 1000, pendingIntent);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void cancel(long taskId) {
        try {
            Context context = ApplicationLoader.applicationContext;
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmManager.cancel(buildPendingIntent(context, taskId));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void rescheduleAll() {
        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        storage.ensureLoaded(() -> {
            long now = System.currentTimeMillis();
            ArrayList<MglaTask> allTasks = storage.getAllTasksForScheduling();
            for (MglaTask task : allTasks) {
                if (!task.done && task.remindAt > now) {
                    schedule(task);
                }
            }
        });
    }

    public static void onAlarmFired(long taskId) {
        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        storage.ensureLoaded(() -> {
            MglaTask task = storage.getTask(taskId);
            if (task == null || task.done) {
                return;
            }
            showNotification(task);
        });
    }

    private static PendingIntent buildPendingIntent(Context context, long taskId) {
        Intent intent = new Intent(context, MglaTasksReminderReceiver.class)
                .setAction(ACTION_REMINDER)
                .putExtra(EXTRA_TASK_ID, taskId);
        return PendingIntent.getBroadcast(context, (int) (taskId & 0x7fffffff), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void showNotification(MglaTask task) {
        Context context = ApplicationLoader.applicationContext;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Задачи и напоминания", NotificationManager.IMPORTANCE_HIGH));
            }
            Intent intent;
            if (task.dialogId != 0) {
                intent = new Intent(context, LaunchActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("tg://openmessage?" + (task.dialogId > 0 ? "user_id=" + task.dialogId : "chat_id=" + (-task.dialogId)) + "&message_id=" + task.messageId));
            } else {
                intent = new Intent(context, LaunchActivity.class);
                intent.setAction("mgla_task://" + task.id);
            }
            PendingIntent contentIntent = PendingIntent.getActivity(context, (int) (task.id & 0x7fffffff), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String text;
            if (!android.text.TextUtils.isEmpty(task.note)) {
                text = task.note;
            } else if (task.isLinkedToMessage()) {
                text = "Нажмите, чтобы открыть сообщение";
            } else {
                text = "";
            }
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setColor(Theme.getColor(Theme.key_chat_messagePanelSend))
                    .setContentTitle(task.title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setWhen(task.remindAt)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent);
            notificationManager.notify(NOTIFICATION_ID_BASE + (int) (task.id % 10000), builder.build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
