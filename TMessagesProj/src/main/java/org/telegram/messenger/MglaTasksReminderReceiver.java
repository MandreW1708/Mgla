package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MglaTasksReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !MglaTasksReminderScheduler.ACTION_REMINDER.equals(intent.getAction())) {
            return;
        }
        long taskId = intent.getLongExtra(MglaTasksReminderScheduler.EXTRA_TASK_ID, 0);
        if (taskId == 0) {
            return;
        }
        PendingResult pendingResult = goAsync();
        MglaTasksStorage.getInstance().ensureLoaded(() -> {
            MglaTasksReminderScheduler.onAlarmFired(taskId);
            pendingResult.finish();
        });
    }
}
