package org.telegram.messenger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import org.telegram.ui.LaunchActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;

public class MglaTasksWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_COMPLETE_TASK = "org.telegram.messenger.MGLA_TASKS_WIDGET_COMPLETE";
    public static final String EXTRA_TASK_ID = "extra_task_id";

    private static final int[] ITEM_IDS = new int[] {
            R.id.widget_item_1,
            R.id.widget_item_2,
            R.id.widget_item_3,
            R.id.widget_item_4,
            R.id.widget_item_5
    };

    private static final int[] LINE_IDS = new int[] {
            R.id.widget_line1,
            R.id.widget_line2,
            R.id.widget_line3,
            R.id.widget_line4,
            R.id.widget_line5
    };

    private static final int[] META_IDS = new int[] {
            R.id.widget_meta_1,
            R.id.widget_meta_2,
            R.id.widget_meta_3,
            R.id.widget_meta_4,
            R.id.widget_meta_5
    };

    private static final int[] PRIORITY_IDS = new int[] {
            R.id.widget_priority_1,
            R.id.widget_priority_2,
            R.id.widget_priority_3,
            R.id.widget_priority_4,
            R.id.widget_priority_5
    };

    private static final int[] CHECK_IDS = new int[] {
            R.id.widget_check_1,
            R.id.widget_check_2,
            R.id.widget_check_3,
            R.id.widget_check_4,
            R.id.widget_check_5
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) {
            return;
        }
        if (ACTION_COMPLETE_TASK.equals(intent.getAction())) {
            long taskId = intent.getLongExtra(EXTRA_TASK_ID, 0);
            if (taskId != 0) {
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
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        if (!storage.isLoaded()) {
            storage.ensureLoaded(() -> {
                for (int id : appWidgetIds) {
                    updateWidget(context, appWidgetManager, id);
                }
            });
            return;
        }
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
        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        if (!storage.isLoaded()) {
            storage.ensureLoaded(() -> {
                for (int id : ids) {
                    updateWidget(context, manager, id);
                }
            });
            return;
        }
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    public static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.mgla_tasks_widget);

        // Click on widget root / empty area opens tasks tab
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
        views.setOnClickPendingIntent(R.id.widget_root, pendingOpenTab);
        views.setOnClickPendingIntent(R.id.widget_header, pendingOpenTab);
        views.setOnClickPendingIntent(R.id.widget_empty, pendingOpenTab);

        // Click on add button ("+") directly opens new task dialog
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

        views.setTextViewText(R.id.widget_title, "Задачи");

        if (openTasks.isEmpty()) {
            views.setViewVisibility(R.id.widget_count_badge, View.GONE);
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE);
            views.setViewVisibility(R.id.widget_items_container, View.GONE);
            for (int itemId : ITEM_IDS) {
                views.setViewVisibility(itemId, View.GONE);
            }
        } else {
            views.setTextViewText(R.id.widget_count_badge, String.valueOf(openTasks.size()));
            views.setViewVisibility(R.id.widget_count_badge, View.VISIBLE);
            views.setViewVisibility(R.id.widget_empty, View.GONE);
            views.setViewVisibility(R.id.widget_items_container, View.VISIBLE);

            for (int a = 0; a < ITEM_IDS.length; a++) {
                if (a < openTasks.size()) {
                    MglaTask task = openTasks.get(a);
                    views.setViewVisibility(ITEM_IDS[a], View.VISIBLE);
                    views.setTextViewText(LINE_IDS[a], task.title);

                    // Formatted due date / subtask count
                    String meta = formatMeta(task);
                    if (meta != null && !meta.isEmpty()) {
                        views.setTextViewText(META_IDS[a], meta);
                        views.setViewVisibility(META_IDS[a], View.VISIBLE);
                    } else {
                        views.setViewVisibility(META_IDS[a], View.GONE);
                    }

                    // Priority chip & checkbox
                    if (task.priority == MglaTask.PRIORITY_HIGH) {
                        views.setViewVisibility(PRIORITY_IDS[a], View.VISIBLE);
                        views.setImageViewResource(CHECK_IDS[a], R.drawable.mgla_widget_checkbox_high);
                    } else {
                        views.setViewVisibility(PRIORITY_IDS[a], View.GONE);
                        views.setImageViewResource(CHECK_IDS[a], R.drawable.mgla_widget_checkbox);
                    }

                    // Complete task directly from widget without opening the client
                    Intent completeIntent = new Intent(context, MglaTasksWidgetProvider.class);
                    completeIntent.setAction(ACTION_COMPLETE_TASK);
                    completeIntent.setData(Uri.parse("mgla_task_complete://" + task.id));
                    completeIntent.putExtra(EXTRA_TASK_ID, task.id);
                    PendingIntent completePending = PendingIntent.getBroadcast(
                            context,
                            (int) (task.id ^ (appWidgetId * 100 + a + 1)),
                            completeIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    );
                    views.setOnClickPendingIntent(CHECK_IDS[a], completePending);
                    views.setOnClickPendingIntent(ITEM_IDS[a], completePending);
                } else {
                    views.setViewVisibility(ITEM_IDS[a], View.GONE);
                }
            }
        }

        try {
            views.apply(context, new android.widget.FrameLayout(context));
        } catch (Throwable t) {
            FileLog.e("MglaTasksWidgetProvider: test apply failed", t);
        }

        manager.updateAppWidget(appWidgetId, views);
    }

    private static String formatMeta(MglaTask task) {
        StringBuilder sb = new StringBuilder();
        if (task.dueAt > 0) {
            Calendar due = Calendar.getInstance();
            due.setTimeInMillis(task.dueAt);
            Calendar today = Calendar.getInstance();
            Calendar tomorrow = Calendar.getInstance();
            tomorrow.add(Calendar.DAY_OF_YEAR, 1);

            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String timeStr = timeFormat.format(due.getTime());

            if (isSameDay(due, today)) {
                sb.append("Сегодня, ").append(timeStr);
            } else if (isSameDay(due, tomorrow)) {
                sb.append("Завтра, ").append(timeStr);
            } else {
                SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
                sb.append(dateFormat.format(due.getTime()));
            }
        }
        if (task.subItems != null && !task.subItems.isEmpty()) {
            int doneCount = 0;
            for (MglaTask.SubItem sub : task.subItems) {
                if (sub.done) {
                    doneCount++;
                }
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(doneCount).append("/").append(task.subItems.size()).append(" подзадач");
        }
        return sb.toString();
    }

    private static boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }
}
