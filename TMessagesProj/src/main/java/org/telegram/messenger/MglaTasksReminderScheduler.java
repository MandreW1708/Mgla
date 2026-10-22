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
    public static final String ACTION_DONE = "org.telegram.messenger.mgla.task.done";
    public static final String ACTION_SNOOZE = "org.telegram.messenger.mgla.task.snooze";
    public static final String ACTION_OPEN_TASK = "org.telegram.messenger.OPEN_MGLA_TASK";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String CHANNEL_ID = "mgla_tasks_reminders";

    public static final long SNOOZE_DELAY_MS = 60 * 60 * 1000L;

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
            PendingIntent pendingIntent = buildAlarmPendingIntent(context, task.id);
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
            alarmManager.cancel(buildAlarmPendingIntent(context, taskId));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void cancelNotification(long taskId) {
        try {
            NotificationManager notificationManager = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(notificationId(taskId));
            }
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
                if (!task.done && task.remindAt > 0) {
                    if (task.remindAt <= now && task.hasRepeat()) {
                        advanceToNextOccurrence(storage, task, now);
                    }
                    if (task.remindAt > now) {
                        schedule(task);
                    }
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
            // Do not advance repeats here — wait until the user completes the occurrence.
        });
    }

    /**
     * Marks a task done, or advances a repeating task to the next occurrence.
     */
    public static void completeTask(long taskId) {
        if (taskId == 0) {
            return;
        }
        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        storage.ensureLoaded(() -> {
            MglaTask task = storage.getTask(taskId);
            if (task == null) {
                return;
            }
            cancelNotification(taskId);
            if (task.hasRepeat() && !task.done) {
                // Advance from current due, so early completion still moves to the next occurrence.
                advanceToNextOccurrence(storage, task, task.dueAt);
                cancel(taskId);
                if (task.remindAt > System.currentTimeMillis()) {
                    schedule(task);
                }
            } else {
                storage.setTaskDone(taskId, true);
                cancel(taskId);
            }
        });
    }

    public static void snoozeTask(long taskId, long delayMs) {
        if (taskId == 0) {
            return;
        }
        if (delayMs <= 0) {
            delayMs = SNOOZE_DELAY_MS;
        }
        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        final long delay = delayMs;
        storage.ensureLoaded(() -> {
            MglaTask task = storage.getTask(taskId);
            if (task == null || task.done) {
                return;
            }
            cancelNotification(taskId);
            task.remindAt = System.currentTimeMillis() + delay;
            storage.saveTask(task);
            schedule(task);
        });
    }

    public static void advanceToNextOccurrence(MglaTasksStorage storage, MglaTask task, long now) {
        long next = MglaTaskRepeat.nextOccurrenceAfter(task, now);
        if (next <= 0) {
            return;
        }
        long offset = task.remindAt > 0 ? task.dueAt - task.remindAt : 0;
        if (offset < 0) {
            offset = 0;
        }
        task.dueAt = next;
        task.remindAt = task.hasReminder() || offset > 0 ? next - offset : 0;
        if (task.remindAt < 0) {
            task.remindAt = 0;
        }
        storage.saveTask(task);
    }

    private static PendingIntent buildAlarmPendingIntent(Context context, long taskId) {
        Intent intent = new Intent(context, MglaTasksReminderReceiver.class)
                .setAction(ACTION_REMINDER)
                .putExtra(EXTRA_TASK_ID, taskId);
        return PendingIntent.getBroadcast(context, requestCode(taskId, 0), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent buildActionPendingIntent(Context context, String action, long taskId, int salt) {
        Intent intent = new Intent(context, MglaTasksReminderReceiver.class)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId);
        return PendingIntent.getBroadcast(context, requestCode(taskId, salt), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int requestCode(long taskId, int salt) {
        return (int) (taskId & 0x0fffffff) ^ (salt << 28);
    }

    private static int notificationId(long taskId) {
        return NOTIFICATION_ID_BASE + (int) (taskId % 10000);
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
            Intent contentIntentAction = new Intent(context, LaunchActivity.class);
            if (task.dialogId != 0 && task.messageId != 0) {
                contentIntentAction.setAction(Intent.ACTION_VIEW);
                contentIntentAction.setData(Uri.parse("tg://openmessage?" + (task.dialogId > 0 ? "user_id=" + task.dialogId : "chat_id=" + (-task.dialogId)) + "&message_id=" + task.messageId));
            } else {
                contentIntentAction.setAction(ACTION_OPEN_TASK);
                contentIntentAction.putExtra(EXTRA_TASK_ID, task.id);
            }
            contentIntentAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(context, requestCode(task.id, 1), contentIntentAction, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String text;
            if (!android.text.TextUtils.isEmpty(task.note)) {
                text = task.note;
            } else if (task.isLinkedToMessage()) {
                text = "Нажмите, чтобы открыть сообщение";
            } else {
                text = "Нажмите, чтобы открыть задачу";
            }
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setColor(Theme.getColor(Theme.key_chat_messagePanelSend))
                    .setContentTitle(task.title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setWhen(task.remindAt > 0 ? task.remindAt : System.currentTimeMillis())
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .addAction(0, "Готово", buildActionPendingIntent(context, ACTION_DONE, task.id, 2))
                    .addAction(0, "Отложить на 1 час", buildActionPendingIntent(context, ACTION_SNOOZE, task.id, 3));
            notificationManager.notify(notificationId(task.id), builder.build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
