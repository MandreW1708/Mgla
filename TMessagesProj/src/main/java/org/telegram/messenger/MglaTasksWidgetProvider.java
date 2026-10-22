package org.telegram.messenger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Collections;

public class MglaTasksWidgetProvider extends AppWidgetProvider {

    private static final int[] LINE_IDS = new int[] {
            R.id.widget_line1,
            R.id.widget_line2,
            R.id.widget_line3,
            R.id.widget_line4,
            R.id.widget_line5
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
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
        Intent open = new Intent(context, LaunchActivity.class);
        open.setAction(MglaTasksReminderScheduler.ACTION_OPEN_TASK);
        open.putExtra(MglaTasksReminderScheduler.EXTRA_TASK_ID, 0L);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, appWidgetId, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pending);

        ArrayList<MglaTask> openTasks = new ArrayList<>();
        try {
            MglaTasksStorage storage = MglaTasksStorage.getInstance();
            if (storage.isLoaded()) {
                for (MglaTask task : storage.getTasks()) {
                    if (!task.done) {
                        openTasks.add(task);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        Collections.sort(openTasks, (a, b) -> {
            if (a.priority != b.priority) {
                return Integer.compare(b.priority, a.priority);
            }
            long da = a.dueAt > 0 ? a.dueAt : Long.MAX_VALUE;
            long db = b.dueAt > 0 ? b.dueAt : Long.MAX_VALUE;
            return Long.compare(da, db);
        });

        views.setTextViewText(R.id.widget_title, "Задачи" + (openTasks.isEmpty() ? "" : " · " + openTasks.size()));
        if (openTasks.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE);
            for (int lineId : LINE_IDS) {
                views.setViewVisibility(lineId, View.GONE);
            }
        } else {
            views.setViewVisibility(R.id.widget_empty, View.GONE);
            for (int a = 0; a < LINE_IDS.length; a++) {
                if (a < openTasks.size()) {
                    MglaTask task = openTasks.get(a);
                    String prefix = task.priority == MglaTask.PRIORITY_HIGH ? "⚠ " : "• ";
                    views.setTextViewText(LINE_IDS[a], prefix + task.title);
                    views.setViewVisibility(LINE_IDS[a], View.VISIBLE);
                } else {
                    views.setViewVisibility(LINE_IDS[a], View.GONE);
                }
            }
        }
        manager.updateAppWidget(appWidgetId, views);
    }
}
