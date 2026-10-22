package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MglaTasksReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        long taskId = intent.getLongExtra(MglaTasksReminderScheduler.EXTRA_TASK_ID, 0);
        if (taskId == 0) {
            return;
        }
        String action = intent.getAction();
        PendingResult pendingResult = goAsync();
        MglaTasksStorage.getInstance().ensureLoaded(() -> {
            if (MglaTasksReminderScheduler.ACTION_REMINDER.equals(action)) {
                MglaTasksReminderScheduler.onAlarmFired(taskId);
            } else if (MglaTasksReminderScheduler.ACTION_DONE.equals(action)) {
                MglaTasksReminderScheduler.completeTask(taskId);
            } else if (MglaTasksReminderScheduler.ACTION_SNOOZE.equals(action)) {
                MglaTasksReminderScheduler.snoozeTask(taskId, MglaTasksReminderScheduler.SNOOZE_DELAY_MS);
            }
            pendingResult.finish();
        });
    }
}
