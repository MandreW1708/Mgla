package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MglaTasksBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        PendingResult pendingResult = goAsync();
        MglaTasksStorage.getInstance().ensureLoaded(() -> {
            MglaTasksReminderScheduler.rescheduleAll();
            MglaTasksWidgetProvider.updateAll();
            pendingResult.finish();
        });
    }
}
