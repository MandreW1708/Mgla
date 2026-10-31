package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Scrollable task list for {@link MglaTasksWidgetProvider}.
 */
public class MglaTasksWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static final class Factory implements RemoteViewsFactory {

        private final Context context;
        private final ArrayList<MglaTask> tasks = new ArrayList<>();
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat dayFormat = new SimpleDateFormat("d MMM", Locale.getDefault());
        private final Calendar dueCalendar = Calendar.getInstance();
        private Calendar todayCalendar;
        private Calendar tomorrowCalendar;

        Factory(Context context) {
            this.context = context;
        }

        @Override
        public void onCreate() {
            ApplicationLoader.postInitApplication();
        }

        @Override
        public void onDestroy() {
            tasks.clear();
        }

        @Override
        public int getCount() {
            return tasks.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.mgla_tasks_widget_item);
            if (position < 0 || position >= tasks.size()) {
                return rv;
            }
            MglaTask task = tasks.get(position);
            String title = TextUtils.isEmpty(task.title) ? "Без названия" : task.title;
            rv.setTextViewText(R.id.widget_item_title, title);
            if (task.done) {
                rv.setTextColor(R.id.widget_item_title,
                        context.getResources().getColor(R.color.mgla_widget_text_secondary));
                rv.setImageViewResource(R.id.widget_item_check, R.drawable.mgla_widget_checkbox_done);
            } else if (task.priority == MglaTask.PRIORITY_HIGH) {
                rv.setTextColor(R.id.widget_item_title,
                        context.getResources().getColor(R.color.mgla_widget_text_primary));
                rv.setImageViewResource(R.id.widget_item_check, R.drawable.mgla_widget_checkbox_high);
            } else {
                rv.setTextColor(R.id.widget_item_title,
                        context.getResources().getColor(R.color.mgla_widget_text_primary));
                rv.setImageViewResource(R.id.widget_item_check, R.drawable.mgla_widget_checkbox);
            }

            String meta = formatMeta(task);
            if (!TextUtils.isEmpty(meta)) {
                rv.setTextViewText(R.id.widget_item_meta, meta);
                rv.setViewVisibility(R.id.widget_item_meta, View.VISIBLE);
            } else {
                rv.setViewVisibility(R.id.widget_item_meta, View.GONE);
            }

            if (!task.done && task.priority == MglaTask.PRIORITY_HIGH) {
                rv.setViewVisibility(R.id.widget_item_priority, View.VISIBLE);
            } else {
                rv.setViewVisibility(R.id.widget_item_priority, View.GONE);
            }

            Intent fillIn = new Intent();
            fillIn.putExtra(MglaTasksWidgetProvider.EXTRA_TASK_ID, task.id);
            fillIn.putExtra(MglaTasksWidgetProvider.EXTRA_TASK_DONE, task.done);
            fillIn.setData(Uri.parse("mgla_widget_task://" + task.id));
            rv.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
            return new RemoteViews(context.getPackageName(), R.layout.mgla_tasks_widget_item);
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= tasks.size()) {
                return position;
            }
            return tasks.get(position).id;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public void onDataSetChanged() {
            refreshDayAnchors();
            final ArrayList<MglaTask> loaded = new ArrayList<>();
            final CountDownLatch latch = new CountDownLatch(1);
            MglaTasksStorage storage = MglaTasksStorage.getInstance();
            Runnable fill = () -> {
                try {
                    loaded.addAll(buildList());
                } catch (Throwable ignore) {
                } finally {
                    latch.countDown();
                }
            };
            if (storage.isLoaded()) {
                fill.run();
            } else {
                storage.ensureLoaded(fill);
                try {
                    if (!latch.await(4, TimeUnit.SECONDS)) {
                        FileLog.d("MglaTasksWidgetService: load timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            tasks.clear();
            tasks.addAll(loaded);
        }

        /** Compute today/tomorrow once per dataset refresh — not once per scrolled row. */
        private void refreshDayAnchors() {
            todayCalendar = Calendar.getInstance();
            tomorrowCalendar = (Calendar) todayCalendar.clone();
            tomorrowCalendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        private ArrayList<MglaTask> buildList() {
            ArrayList<MglaTask> open = new ArrayList<>();
            ArrayList<MglaTask> done = new ArrayList<>();
            boolean showDone = true;
            try {
                SharedPreferences prefs = context.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
                showDone = prefs.getBoolean("tasks_show_done", true);
            } catch (Throwable ignore) {
            }
            try {
                for (MglaTask task : MglaTasksStorage.getInstance().getTasks()) {
                    if (task == null) {
                        continue;
                    }
                    if (task.done) {
                        if (showDone) {
                            done.add(task);
                        }
                    } else {
                        open.add(task);
                    }
                }
            } catch (Throwable ignore) {
            }
            Collections.sort(open, Factory::compareOpen);
            Collections.sort(done, (a, b) -> Long.compare(b.doneAt, a.doneAt));
            ArrayList<MglaTask> out = new ArrayList<>(open.size() + done.size());
            out.addAll(open);
            out.addAll(done);
            return out;
        }

        private static int compareOpen(MglaTask a, MglaTask b) {
            if (a.priority != b.priority) {
                return Integer.compare(b.priority, a.priority);
            }
            long da = a.dueAt > 0 ? a.dueAt : Long.MAX_VALUE;
            long db = b.dueAt > 0 ? b.dueAt : Long.MAX_VALUE;
            if (da != db) {
                return Long.compare(da, db);
            }
            return Integer.compare(a.sortOrder, b.sortOrder);
        }

        private String formatMeta(MglaTask task) {
            if (task.done) {
                return "Выполнено";
            }
            if (task.dueAt <= 0) {
                return "";
            }
            if (todayCalendar == null || tomorrowCalendar == null) {
                refreshDayAnchors();
            }
            dueCalendar.setTimeInMillis(task.dueAt);
            String timeStr = timeFormat.format(dueCalendar.getTime());
            if (isSameDay(dueCalendar, todayCalendar)) {
                return timeStr;
            }
            if (isSameDay(dueCalendar, tomorrowCalendar)) {
                return "Завтра " + timeStr;
            }
            return dayFormat.format(dueCalendar.getTime());
        }

        private static boolean isSameDay(Calendar a, Calendar b) {
            return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                    && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        }
    }
}
