package org.telegram.messenger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.RemoteViews;
import android.widget.Toast;

import org.telegram.ui.LaunchActivity;

public class MglaTasksWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_COMPLETE_TASK = "org.telegram.messenger.MGLA_TASKS_WIDGET_COMPLETE";
    public static final String EXTRA_TASK_ID = "extra_task_id";
    public static final String EXTRA_TASK_DONE = "extra_task_done";

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) {
            return;
        }
        if (ACTION_COMPLETE_TASK.equals(intent.getAction())) {
            long taskId = intent.getLongExtra(EXTRA_TASK_ID, 0);
            boolean alreadyDone = intent.getBooleanExtra(EXTRA_TASK_DONE, false);
            if (taskId == 0) {
                return;
            }
            if (alreadyDone) {
                // Open the task instead of completing again.
                try {
                    Intent open = new Intent(context, LaunchActivity.class);
                    open.setAction(MglaTasksReminderScheduler.ACTION_OPEN_TASK);
                    open.putExtra(MglaTasksReminderScheduler.EXTRA_TASK_ID, taskId);
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    context.startActivity(open);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                return;
            }
            final PendingResult pendingResult = goAsync();
            MglaTasksStorage.getInstance().ensureLoaded(() -> {
                try {
                    MglaTask task = MglaTasksStorage.getInstance().getTask(taskId);
                    MglaTasksReminderScheduler.completeTask(taskId);
                    if (task != null && context != null) {
                        try {
                            String msg = !TextUtils.isEmpty(task.title)
                                    ? ("Выполнено: " + task.title)
                                    : "Задача выполнена";
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignore) {
                        }
                    }
                    updateAll();
                } finally {
                    if (pendingResult != null) {
                        try {
                            pendingResult.finish();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            });
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        updateWidget(context, appWidgetManager, appWidgetId);
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
    }

    public static void updateAll() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, MglaTasksWidgetProvider.class));
        if (ids == null || ids.length == 0) {
            return;
        }
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    public static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.mgla_tasks_widget);

        Intent openTasksTab = new Intent(context, LaunchActivity.class);
        openTasksTab.setAction(MglaTasksReminderScheduler.ACTION_OPEN_TASK);
        openTasksTab.putExtra(MglaTasksReminderScheduler.EXTRA_TASK_ID, 0L);
        openTasksTab.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingOpenTab = PendingIntent.getActivity(
                context,
                appWidgetId,
                openTasksTab,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_header, pendingOpenTab);
        views.setOnClickPendingIntent(R.id.widget_empty, pendingOpenTab);

        Intent openAddTask = new Intent(context, LaunchActivity.class);
        openAddTask.setAction(MglaTasksReminderScheduler.ACTION_OPEN_TASK);
        openAddTask.putExtra(MglaTasksReminderScheduler.EXTRA_TASK_ID, -1L);
        openAddTask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingAddTask = PendingIntent.getActivity(
                context,
                appWidgetId * 100 + 99,
                openAddTask,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_add_button, pendingAddTask);

        views.setTextViewText(R.id.widget_title, "Задачи");
        views.setViewVisibility(R.id.widget_count_badge, android.view.View.GONE);

        Intent serviceIntent = new Intent(context, MglaTasksWidgetService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widget_list, serviceIntent);
        views.setEmptyView(R.id.widget_list, R.id.widget_empty);

        // Template for list item clicks (fill-in adds task id). Must be mutable.
        Intent template = new Intent(context, MglaTasksWidgetProvider.class);
        template.setAction(ACTION_COMPLETE_TASK);
        PendingIntent templatePi = PendingIntent.getBroadcast(
                context,
                appWidgetId * 31 + 7,
                template,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );
        views.setPendingIntentTemplate(R.id.widget_list, templatePi);

        manager.updateAppWidget(appWidgetId, views);
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list);
    }
}
