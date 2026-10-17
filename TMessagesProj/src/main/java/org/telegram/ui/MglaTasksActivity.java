package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.animation.ValueAnimator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MglaTask;
import org.telegram.messenger.MglaTasksReminderScheduler;
import org.telegram.messenger.MglaTasksStorage;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaGlobalMusicBar;
import org.telegram.ui.Components.MglaTaskCell;
import org.telegram.ui.Components.MglaTaskEditSheet;
import org.telegram.ui.Components.MglaTaskGroupsSheet;
import org.telegram.ui.Components.MglaTasksCalendarView;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class MglaTasksActivity extends BaseFragment implements MglaTasksStorage.Listener, MainTabsActivity.TabFragmentDelegate {

    private static final int MENU_GROUPS = 1;
    private static final int MENU_TOGGLE_DONE = 2;

    private static final int ROW_HEADER = 0;
    private static final int ROW_TASK = 1;
    private static final int ROW_DONE_HEADER = 2;

    private boolean hasMainTabs;
    private boolean showDone = true;
    private long selectedGroupId;
    private int selectedTab; // 0 = list, 1 = calendar

    private FrameLayout rootLayout;
    private SegmentedControl segmentedControl;
    private LinearLayout chipsLayout;
    private FrameLayout pagesLayout;

    private RecyclerListView listView;
    private TasksAdapter listAdapter;
    private LinearLayout emptyView;

    private MglaTasksCalendarView calendarView;
    private TextView dayHeaderView;
    private RecyclerListView dayListView;
    private DayTasksAdapter dayAdapter;
    private TextView dayEmptyView;

    private TextView toggleDoneItem;

    private final ArrayList<Row> rows = new ArrayList<>();
    private final ArrayList<MglaTask> allTasks = new ArrayList<>();
    private final ArrayList<MglaTask> filteredTasks = new ArrayList<>();
    private final ArrayList<MglaTask.Group> groups = new ArrayList<>();
    private final HashMap<Long, MglaTask.Group> groupsById = new HashMap<>();
    private final ArrayList<MglaTask> dayTasks = new ArrayList<>();

    private final Calendar calendar = Calendar.getInstance();
    private final SimpleDateFormat dayTitleFormat = new SimpleDateFormat("d MMM", Locale.getDefault());
    private final Date dateInstance = new Date();

    private MglaTask pendingReminderTask;

    private static class Row {
        int type;
        String header;
        MglaTask task;
    }

    public MglaTasksActivity(Bundle args) {
        super(args);
        if (args != null) {
            hasMainTabs = args.getBoolean("hasMainTabs", false);
        }
    }

    private int islandBottomInset() {
        return AndroidUtilities.navigationBarHeight
                + (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + MglaGlobalMusicBar.BAR_HEIGHT + 12) : dp(16));
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle("Задачи");
        actionBar.setAllowOverlayTitle(true);
        if (hasMainTabs) {
            actionBar.setBackButtonImage(0);
        } else {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_GROUPS) {
                    showDialog(new MglaTaskGroupsSheet(context, MglaTasksActivity.this::reloadData));
                } else if (id == MENU_TOGGLE_DONE) {
                    showDone = !showDone;
                    getPreferences().edit().putBoolean("tasks_show_done", showDone).apply();
                    updateToggleDoneItem();
                    rebuildRows();
                    listAdapter.notifyDataSetChanged();
                    updateEmptyState();
                    updateDayTasks();
                }
            }
        });

        showDone = getPreferences().getBoolean("tasks_show_done", true);

        ActionBarMenuItem menu = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        menu.addSubItem(MENU_GROUPS, "Группы");
        toggleDoneItem = menu.addSubItem(MENU_TOGGLE_DONE, "");
        updateToggleDoneItem();

        rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.addView(contentLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        segmentedControl = new SegmentedControl(context);
        segmentedControl.setOnTabChangedListener(index -> {
            selectedTab = index;
            updatePagesVisibility();
        });
        contentLayout.addView(segmentedControl, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 16, 6, 16, 6));

        HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsLayout = new LinearLayout(context);
        chipsLayout.setOrientation(LinearLayout.HORIZONTAL);
        chipsLayout.setGravity(Gravity.CENTER_VERTICAL);
        chipsScroll.addView(chipsLayout, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        contentLayout.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 16, 2, 16, 4));

        pagesLayout = new FrameLayout(context);
        contentLayout.addView(pagesLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // List page
        FrameLayout listPage = new FrameLayout(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, islandBottomInset() + dp(72));
        listAdapter = new TasksAdapter();
        listView.setAdapter(listAdapter);
        listPage.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        TextView emptyTitle = new TextView(context);
        emptyTitle.setText("Задач пока нет");
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        emptyTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyTitle.setGravity(Gravity.CENTER);
        emptyView.addView(emptyTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView emptySubtitle = new TextView(context);
        emptySubtitle.setText("Нажмите + чтобы добавить");
        emptySubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptySubtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptySubtitle.setGravity(Gravity.CENTER);
        emptyView.addView(emptySubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        emptyView.setVisibility(View.GONE);
        listPage.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listPage.setTag("list");
        pagesLayout.addView(listPage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Calendar page
        LinearLayout calendarPage = new LinearLayout(context);
        calendarPage.setOrientation(LinearLayout.VERTICAL);
        calendarView = new MglaTasksCalendarView(context);
        calendarView.setDelegate(day -> updateDayTasks());
        calendarPage.addView(calendarView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        dayHeaderView = new TextView(context);
        dayHeaderView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        dayHeaderView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        dayHeaderView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        calendarPage.addView(dayHeaderView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 4));

        FrameLayout dayListContainer = new FrameLayout(context);
        dayListView = new RecyclerListView(context);
        dayListView.setLayoutManager(new LinearLayoutManager(context));
        dayListView.setClipToPadding(false);
        dayListView.setPadding(0, 0, 0, islandBottomInset() + dp(72));
        dayAdapter = new DayTasksAdapter();
        dayListView.setAdapter(dayAdapter);
        dayListContainer.addView(dayListView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        dayEmptyView = new TextView(context);
        dayEmptyView.setText("Нет задач на этот день");
        dayEmptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        dayEmptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        dayEmptyView.setGravity(Gravity.CENTER);
        dayEmptyView.setVisibility(View.GONE);
        dayListContainer.addView(dayEmptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        calendarPage.addView(dayListContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        calendarPage.setVisibility(View.GONE);
        calendarPage.setTag("calendar");
        pagesLayout.addView(calendarPage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // FAB
        FrameLayout fab = new FrameLayout(context);
        fab.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16),
                Theme.getColor(Theme.key_chat_messagePanelSend),
                ColorUtils.blendARGB(Theme.getColor(Theme.key_chat_messagePanelSend), 0xFF000000, 0.12f)));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            fab.setElevation(dp(6));
        }
        PlusView plusView = new PlusView(context);
        fab.addView(plusView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        fab.setOnClickListener(v -> openTaskEditor(null));
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.RIGHT);
        fabParams.rightMargin = dp(16);
        fabParams.bottomMargin = islandBottomInset();
        rootLayout.addView(fab, fabParams);

        MglaTasksStorage.getInstance().addListener(this);
        MglaTasksStorage.getInstance().ensureLoaded(this::reloadData);

        fragmentView = rootLayout;
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        MglaTasksStorage.getInstance().removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onTasksChanged() {
        reloadData();
    }

    @Override
    public void onParentScrollToTop() {
        if (selectedTab == 0 && listView != null) {
            listView.smoothScrollToPosition(0);
        } else if (dayListView != null) {
            dayListView.smoothScrollToPosition(0);
        }
    }

    private SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
    }

    private void updateToggleDoneItem() {
        if (toggleDoneItem != null) {
            toggleDoneItem.setText(showDone ? "Скрыть выполненные" : "Показать выполненные");
        }
    }

    private void updatePagesVisibility() {
        for (int a = 0; a < pagesLayout.getChildCount(); a++) {
            View child = pagesLayout.getChildAt(a);
            boolean list = "list".equals(child.getTag());
            child.setVisibility((selectedTab == 0) == list ? View.VISIBLE : View.GONE);
        }
    }

    private void reloadData() {
        MglaTasksStorage storage = MglaTasksStorage.getInstance();
        allTasks.clear();
        allTasks.addAll(storage.getTasks());
        groups.clear();
        groups.addAll(storage.getGroups());
        groupsById.clear();
        for (MglaTask.Group group : groups) {
            groupsById.put(group.id, group);
        }

        if (pendingReminderTask != null && pendingReminderTask.id != 0) {
            applyReminder(pendingReminderTask);
            pendingReminderTask = null;
        }

        rebuildChips();
        applyGroupFilter();
        rebuildRows();
        listAdapter.notifyDataSetChanged();
        updateEmptyState();
        calendarView.setTasks(filteredTasks);
        updateDayTasks();
    }

    private void applyGroupFilter() {
        filteredTasks.clear();
        for (MglaTask task : allTasks) {
            if (selectedGroupId == 0 || task.groupId == selectedGroupId) {
                filteredTasks.add(task);
            }
        }
    }

    private void rebuildChips() {
        boolean groupExists = selectedGroupId == 0;
        for (MglaTask.Group group : groups) {
            if (group.id == selectedGroupId) {
                groupExists = true;
                break;
            }
        }
        if (!groupExists) {
            selectedGroupId = 0;
        }

        chipsLayout.removeAllViews();
        addChip("Все", 0, 0, true);
        for (int a = 0; a < groups.size(); a++) {
            MglaTask.Group group = groups.get(a);
            addChip(group.name, group.id, group.color, false);
        }
    }

    private void addChip(String name, long groupId, int color, boolean isAll) {
        Context context = chipsLayout.getContext();
        TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        boolean selected = selectedGroupId == groupId;
        styleChip(chip, name, color, selected);
        chip.setOnClickListener(v -> {
            if (selectedGroupId != groupId) {
                selectedGroupId = groupId;
                rebuildChips();
                applyGroupFilter();
                rebuildRows();
                listAdapter.notifyDataSetChanged();
                updateEmptyState();
                calendarView.setTasks(filteredTasks);
                updateDayTasks();
            }
        });
        chipsLayout.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, isAll ? 0 : 8, 0, 0, 0));
    }

    private void styleChip(TextView chip, String name, int color, boolean selected) {
        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (selected) {
            bg.setColor(accent);
            chip.setTextColor(0xFFFFFFFF);
            chip.setText(name);
        } else {
            bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            bg.setStroke(dp(1), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            chip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            if (color != 0) {
                SpannableString ss = new SpannableString("●  " + name);
                ss.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                chip.setText(ss);
            } else {
                chip.setText(name);
            }
        }
        chip.setBackground(bg);
    }

    private void rebuildRows() {
        rows.clear();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long todayStart = calendar.getTimeInMillis();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        long tomorrowStart = calendar.getTimeInMillis();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        long dayAfterTomorrowStart = calendar.getTimeInMillis();
        calendar.setTimeInMillis(todayStart);
        calendar.add(Calendar.DAY_OF_MONTH, 7);
        long weekEnd = calendar.getTimeInMillis();

        ArrayList<MglaTask> overdue = new ArrayList<>();
        ArrayList<MglaTask> today = new ArrayList<>();
        ArrayList<MglaTask> tomorrow = new ArrayList<>();
        ArrayList<MglaTask> week = new ArrayList<>();
        ArrayList<MglaTask> later = new ArrayList<>();
        ArrayList<MglaTask> noDate = new ArrayList<>();
        ArrayList<MglaTask> done = new ArrayList<>();

        for (MglaTask task : filteredTasks) {
            if (task.done) {
                done.add(task);
            } else if (task.dueAt <= 0) {
                noDate.add(task);
            } else if (task.dueAt < todayStart) {
                overdue.add(task);
            } else if (task.dueAt < tomorrowStart) {
                today.add(task);
            } else if (task.dueAt < dayAfterTomorrowStart) {
                tomorrow.add(task);
            } else if (task.dueAt < weekEnd) {
                week.add(task);
            } else {
                later.add(task);
            }
        }

        addSection("Просрочено", overdue, ROW_HEADER);
        addSection("Сегодня", today, ROW_HEADER);
        addSection("Завтра", tomorrow, ROW_HEADER);
        addSection("На этой неделе", week, ROW_HEADER);
        addSection("Позже", later, ROW_HEADER);
        addSection("Без даты", noDate, ROW_HEADER);

        if (showDone && !done.isEmpty()) {
            Collections.sort(done, (a, b) -> Long.compare(b.doneAt, a.doneAt));
            Row header = new Row();
            header.type = ROW_DONE_HEADER;
            header.header = "Выполненные";
            rows.add(header);
            for (MglaTask task : done) {
                Row row = new Row();
                row.type = ROW_TASK;
                row.task = task;
                rows.add(row);
            }
        }
    }

    private void addSection(String title, ArrayList<MglaTask> section, int headerType) {
        if (section.isEmpty()) {
            return;
        }
        Collections.sort(section, (a, b) -> {
            long da = a.dueAt > 0 ? a.dueAt : Long.MAX_VALUE;
            long db = b.dueAt > 0 ? b.dueAt : Long.MAX_VALUE;
            if (da != db) {
                return Long.compare(da, db);
            }
            return Long.compare(b.createdAt, a.createdAt);
        });
        Row header = new Row();
        header.type = headerType;
        header.header = title;
        rows.add(header);
        for (MglaTask task : section) {
            Row row = new Row();
            row.type = ROW_TASK;
            row.task = task;
            rows.add(row);
        }
    }

    private void updateEmptyState() {
        boolean empty = rows.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updateDayTasks() {
        dayTasks.clear();
        long dayStart = calendarView.getSelectedDay();
        calendar.setTimeInMillis(dayStart);
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        long nextDay = calendar.getTimeInMillis();
        for (MglaTask task : filteredTasks) {
            if (task.dueAt >= dayStart && task.dueAt < nextDay) {
                if (task.done && !showDone) {
                    continue;
                }
                dayTasks.add(task);
            }
        }
        Collections.sort(dayTasks, (a, b) -> {
            if (a.done != b.done) {
                return a.done ? 1 : -1;
            }
            if (a.dueAt != b.dueAt) {
                return Long.compare(a.dueAt, b.dueAt);
            }
            return Long.compare(b.createdAt, a.createdAt);
        });
        dateInstance.setTime(dayStart);
        dayHeaderView.setText("Задачи на " + dayTitleFormat.format(dateInstance));
        dayAdapter.notifyDataSetChanged();
        dayEmptyView.setVisibility(dayTasks.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void applyReminder(MglaTask task) {
        if (task.remindAt > 0 && !task.done) {
            MglaTasksReminderScheduler.schedule(task);
        } else {
            MglaTasksReminderScheduler.cancel(task.id);
        }
    }

    private void openTaskEditor(MglaTask task) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        showDialog(new MglaTaskEditSheet(context, task, saved -> {
            MglaTasksStorage.getInstance().saveTask(saved);
            if (saved.id != 0) {
                applyReminder(saved);
            } else {
                // New task: id is assigned asynchronously by storage; apply reminder on the next change tick.
                pendingReminderTask = saved;
            }
        }, task == null ? null : () -> {
            MglaTasksStorage.getInstance().deleteTask(task.id);
            MglaTasksReminderScheduler.cancel(task.id);
        }));
    }

    private void showTaskOptions(MglaTask task) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        ArrayList<String> items = new ArrayList<>();
        ArrayList<Integer> actions = new ArrayList<>();
        if (task.dialogId != 0) {
            items.add("Открыть сообщение");
            actions.add(0);
        }
        items.add("Удалить");
        actions.add(1);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(task.title);
        builder.setItems(items.toArray(new CharSequence[0]), (d, which) -> {
            int action = actions.get(which);
            if (action == 0) {
                openLinkedMessage(task);
            } else if (action == 1) {
                MglaTasksStorage.getInstance().deleteTask(task.id);
                MglaTasksReminderScheduler.cancel(task.id);
            }
        });
        showDialog(builder.create());
    }

    private void openLinkedMessage(MglaTask task) {
        Bundle args = new Bundle();
        if (task.dialogId > 0) {
            args.putLong("user_id", task.dialogId);
        } else {
            args.putLong("chat_id", -task.dialogId);
        }
        args.putInt("message_id", task.messageId);
        presentFragment(new ChatActivity(args));
    }

    private class TasksAdapter extends RecyclerView.Adapter<TasksAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            if (viewType == ROW_TASK) {
                MglaTaskCell cell = new MglaTaskCell(context);
                cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.setOnClickListener(v -> {
                    MglaTask task = cell.getTask();
                    if (task != null) {
                        openTaskEditor(task);
                    }
                });
                cell.setOnLongClickListener(v -> {
                    MglaTask task = cell.getTask();
                    if (task != null) {
                        showTaskOptions(task);
                    }
                    return true;
                });
                cell.setOnCheckClickListener(v -> {
                    MglaTask task = cell.getTask();
                    if (task != null) {
                        boolean done = !task.done;
                        MglaTasksStorage.getInstance().setTaskDone(task.id, done);
                        if (done) {
                            MglaTasksReminderScheduler.cancel(task.id);
                        }
                    }
                });
                view = cell;
            } else if (viewType == ROW_DONE_HEADER) {
                LinearLayout container = new LinearLayout(context);
                container.setOrientation(LinearLayout.VERTICAL);
                View divider = new View(context);
                divider.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
                container.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
                container.addView(makeHeaderView(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                view = container;
            } else {
                view = makeHeaderView(context);
            }
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = rows.get(position);
            if (row.type == ROW_TASK) {
                MglaTaskCell cell = (MglaTaskCell) holder.itemView;
                cell.setTask(row.task, groupsById.get(row.task.groupId));
            } else {
                TextView header = row.type == ROW_DONE_HEADER
                        ? (TextView) ((ViewGroup) holder.itemView).getChildAt(1)
                        : (TextView) holder.itemView;
                header.setText(row.header);
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }

    private class DayTasksAdapter extends RecyclerView.Adapter<DayTasksAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MglaTaskCell cell = new MglaTaskCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.setOnClickListener(v -> {
                MglaTask task = cell.getTask();
                if (task != null) {
                    openTaskEditor(task);
                }
            });
            cell.setOnLongClickListener(v -> {
                MglaTask task = cell.getTask();
                if (task != null) {
                    showTaskOptions(task);
                }
                return true;
            });
            cell.setOnCheckClickListener(v -> {
                MglaTask task = cell.getTask();
                if (task != null) {
                    boolean done = !task.done;
                    MglaTasksStorage.getInstance().setTaskDone(task.id, done);
                    if (done) {
                        MglaTasksReminderScheduler.cancel(task.id);
                    }
                }
            });
            return new Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            MglaTask task = dayTasks.get(position);
            ((MglaTaskCell) holder.itemView).setTask(task, groupsById.get(task.groupId));
        }

        @Override
        public int getItemCount() {
            return dayTasks.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }

    private TextView makeHeaderView(Context context) {
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        header.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        header.setPadding(dp(16), dp(14), dp(16), dp(6));
        return header;
    }

    private static class PlusView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PlusView(Context context) {
            super(context);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = dp(7);
            canvas.drawLine(cx - r, cy, cx + r, cy, paint);
            canvas.drawLine(cx, cy - r, cx, cy + r, paint);
        }
    }

    private interface OnTabChanged {
        void onChanged(int index);
    }

    private class SegmentedControl extends FrameLayout {

        private final View indicator;
        private final TextView[] tabs = new TextView[2];
        private int currentIndex;
        private ValueAnimator slideAnimator;

        SegmentedControl(Context context) {
            super(context);
            setPadding(dp(3), dp(3), dp(3), dp(3));
            int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(ColorUtils.setAlphaComponent(accent, 31));
            setBackground(bg);

            GradientDrawable indicatorBg = new GradientDrawable();
            indicatorBg.setCornerRadius(dp(17));
            indicatorBg.setColor(accent);
            indicator = new View(context);
            indicator.setBackground(indicatorBg);
            addView(indicator);

            LinearLayout tabsLayout = new LinearLayout(context);
            tabsLayout.setOrientation(LinearLayout.HORIZONTAL);
            String[] titles = { "Список", "Календарь" };
            for (int a = 0; a < 2; a++) {
                final int index = a;
                TextView tab = new TextView(context);
                tab.setText(titles[a]);
                tab.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                tab.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                tab.setGravity(Gravity.CENTER);
                tab.setSingleLine(true);
                tab.setOnClickListener(v -> setSelectedIndex(index, true));
                tabs[a] = tab;
                tabsLayout.addView(tab, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
            }
            addView(tabsLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            updateTabColors();
        }

        private OnTabChanged tabChangedListener;

        void setOnTabChangedListener(OnTabChanged listener) {
            tabChangedListener = listener;
        }

        void setSelectedIndex(int index, boolean animate) {
            if (index == currentIndex) {
                return;
            }
            currentIndex = index;
            updateTabColors();
            int segmentWidth = (getWidth() - getPaddingLeft() - getPaddingRight()) / 2;
            float target = getPaddingLeft() + segmentWidth * index;
            if (slideAnimator != null) {
                slideAnimator.cancel();
            }
            if (animate && segmentWidth > 0) {
                slideAnimator = ValueAnimator.ofFloat(indicator.getTranslationX(), target);
                slideAnimator.setDuration(200);
                slideAnimator.addUpdateListener(a -> indicator.setTranslationX((float) a.getAnimatedValue()));
                slideAnimator.start();
            } else {
                indicator.setTranslationX(target);
            }
            if (tabChangedListener != null) {
                tabChangedListener.onChanged(index);
            }
        }

        private void updateTabColors() {
            int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
            for (int a = 0; a < 2; a++) {
                tabs[a].setTextColor(a == currentIndex ? 0xFFFFFFFF : accent);
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            int segmentWidth = (getWidth() - getPaddingLeft() - getPaddingRight()) / 2;
            if (segmentWidth > 0) {
                ViewGroup.LayoutParams lp = indicator.getLayoutParams();
                if (lp.width != segmentWidth || lp.height != getHeight() - getPaddingTop() - getPaddingBottom()) {
                    lp.width = segmentWidth;
                    lp.height = getHeight() - getPaddingTop() - getPaddingBottom();
                    indicator.setLayoutParams(lp);
                }
                indicator.setTranslationX(getPaddingLeft() + segmentWidth * currentIndex);
                indicator.setTranslationY(getPaddingTop());
            }
        }
    }
}
