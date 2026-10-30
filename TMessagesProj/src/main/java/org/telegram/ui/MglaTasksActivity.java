package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.MglaTask;
import org.telegram.messenger.MglaTaskRepeat;
import org.telegram.messenger.MglaTasksIO;
import org.telegram.messenger.MglaTasksReminderScheduler;
import org.telegram.messenger.MglaTasksStorage;
import org.telegram.messenger.MglaTasksSync;
import org.telegram.messenger.MglaTasksWidgetProvider;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MglaGlobalMusicBar;
import org.telegram.ui.Components.MglaTaskCell;
import org.telegram.ui.Components.MglaTaskDetailsSheet;
import org.telegram.ui.Components.MglaTaskEditSheet;
import org.telegram.ui.Components.MglaTaskGroupsSheet;
import org.telegram.ui.Components.MglaTasksCalendarView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

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
    private static final int MENU_CLEAR_DONE = 3;
    private static final int MENU_EXPORT = 4;
    private static final int MENU_IMPORT = 5;
    private static final int MENU_SEARCH = 6;
    private static final int MENU_SYNC = 7;

    private static final int ROW_HEADER = 0;
    private static final int ROW_TASK = 1;
    private static final int ROW_DONE_HEADER = 2;

    private boolean hasMainTabs;
    private boolean showDone = true;
    private long selectedGroupId = -1; // -1 = все, 0 = без группы, >0 = группа
    private int selectedTab; // 0 = list, 1 = calendar
    private String searchQuery = "";

    private FrameLayout rootLayout;

    /* Liquid glass source for MainTabs bottom island + player */
    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @Nullable DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlassFrosted;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlass;
    private IBlur3Capture iBlur3CaptureList;
    private IBlur3Capture iBlur3CaptureDay;
    private boolean iBlur3Invalidated;
    private final ArrayList<RectF> iBlur3Positions = new ArrayList<>();
    private final RectF iBlur3PositionActionBar = new RectF();
    private final RectF iBlur3PositionMainTabs = new RectF();
    private SegmentedControl segmentedControl;
    private HorizontalScrollView tabsScroll;
    private LinearLayout tabsRow;
    private final ArrayList<TextView> tabViews = new ArrayList<>();
    private FrameLayout pagesLayout;
    private FrameLayout fab;
    private ValueAnimator fabMarginAnimator;

    private RecyclerListView listView;
    private TasksAdapter listAdapter;
    private LinearLayout emptyView;
    private TextView emptyTitle;

    private MglaTasksCalendarView calendarView;
    private TextView dayHeaderView;
    private RecyclerListView dayListView;
    private DayTasksAdapter dayAdapter;
    private TextView dayEmptyView;

    private TextView toggleDoneItem;
    private ActionBarMenuItem syncItem;
    private View syncSubItem;

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
    private long openTaskId;
    private static long pendingOpenTaskId;

    private static class Row {
        int type;
        String header;
        MglaTask task;
    }

    public static void requestOpenTask(long taskId) {
        pendingOpenTaskId = taskId;
    }

    public MglaTasksActivity(Bundle args) {
        super(args);
        if (args != null) {
            hasMainTabs = args.getBoolean("hasMainTabs", false);
            openTaskId = args.getLong("openTaskId", 0);
        }
        iBlur3SourceColor = new BlurredBackgroundSourceColor();
        iBlur3SourceColor.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlassFrosted = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(null);
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlassFrosted = null;
            iBlur3SourceGlass = null;
        }
        iBlur3Positions.add(iBlur3PositionActionBar);
        iBlur3Positions.add(iBlur3PositionMainTabs);
    }

    private int islandBottomInset() {
        int inset = AndroidUtilities.navigationBarHeight;
        if (hasMainTabs) {
            inset += dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 12);
            if (MglaGlobalMusicBar.isGloballyVisible()) {
                inset += dp(MglaGlobalMusicBar.BAR_HEIGHT + 8);
            }
        } else {
            inset += dp(16);
        }
        return inset;
    }

    private final MglaGlobalMusicBar.VisibilityListener musicBarVisibilityListener = visible -> updateInsets();

    private void updateInsets() {
        if (rootLayout == null || fab == null) {
            return;
        }
        int inset = islandBottomInset();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fab.getLayoutParams();
        if (lp.bottomMargin != inset) {
            if (fabMarginAnimator != null) {
                fabMarginAnimator.cancel();
            }
            fabMarginAnimator = ValueAnimator.ofInt(lp.bottomMargin, inset);
            fabMarginAnimator.setDuration(200);
            fabMarginAnimator.addUpdateListener(a -> {
                lp.bottomMargin = (int) a.getAnimatedValue();
                fab.setLayoutParams(lp);
            });
            fabMarginAnimator.start();
        }
        int listPadding = inset + dp(72);
        listView.setPadding(0, 0, 0, listPadding);
        dayListView.setPadding(0, 0, 0, listPadding);
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
                    MglaTasksWidgetProvider.updateAll();
                } else if (id == MENU_CLEAR_DONE) {
                    confirmClearDone();
                } else if (id == MENU_EXPORT) {
                    exportTasks();
                } else if (id == MENU_IMPORT) {
                    importTasks();
                } else if (id == MENU_SYNC) {
                    if (!MglaSubscription.canSyncTasks()) {
                        BulletinFactory.of(MglaTasksActivity.this)
                                .createErrorBulletin("Синхронизация доступна с Premium, Premium+ и Ultra")
                                .show();
                        return;
                    }
                    syncTasksNow();
                }
            }
        });

        showDone = getPreferences().getBoolean("tasks_show_done", true);

        // Always create; visibility follows selected-account plan (Plus/Ultra only).
        syncItem = actionBar.createMenu().addItem(MENU_SYNC, R.drawable.msg_retry);

        ActionBarMenuItem searchItem = actionBar.createMenu().addItem(MENU_SEARCH, R.drawable.outline_header_search);
        searchItem.setIsSearchField(true);
        searchItem.setSearchFieldHint("Поиск задач");
        searchItem.setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
            }

            @Override
            public void onSearchCollapse() {
                searchQuery = "";
                applyGroupFilter();
                rebuildRows();
                listAdapter.notifyDataSetChanged();
                updateEmptyState();
                updateDayTasks();
            }

            @Override
            public void onTextChanged(EditText editText) {
                searchQuery = editText.getText() != null ? editText.getText().toString().trim() : "";
                applyGroupFilter();
                rebuildRows();
                listAdapter.notifyDataSetChanged();
                updateEmptyState();
                calendarView.setTasks(filteredTasks);
                updateDayTasks();
            }
        });

        ActionBarMenuItem menu = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        menu.addSubItem(MENU_GROUPS, "Группы");
        toggleDoneItem = menu.addSubItem(MENU_TOGGLE_DONE, "");
        updateToggleDoneItem();
        menu.addSubItem(MENU_CLEAR_DONE, "Очистить выполненные");
        syncSubItem = menu.addSubItem(MENU_SYNC, R.drawable.msg_retry, "Синхронизировать");
        menu.addSubItem(MENU_EXPORT, "Экспорт JSON");
        menu.addSubItem(MENU_IMPORT, "Импорт из буфера");
        updateSyncUi();

        rootLayout = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    blur3_InvalidateBlur();
                    final int width = getMeasuredWidth();
                    final int height = getMeasuredHeight();
                    final int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
                    final boolean drawGlassContent = SharedConfig.chatBlurEnabled()
                            || LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
                    if (iBlur3SourceGlassFrosted != null && !iBlur3SourceGlassFrosted.inRecording()) {
                        final Canvas c = iBlur3SourceGlassFrosted.beginRecording(width, height);
                        c.drawColor(bg);
                        if (drawGlassContent) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                        }
                        iBlur3SourceGlassFrosted.endRecording();
                    }
                    if (iBlur3SourceGlass != null && !iBlur3SourceGlass.inRecording()) {
                        final Canvas c = iBlur3SourceGlass.beginRecording(width, height);
                        c.drawColor(bg);
                        if (drawGlassContent) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                        }
                        iBlur3SourceGlass.endRecording();
                    }
                    iBlur3Invalidated = false;
                    MainTabsActivity.notifyTabGlassFrom(MglaTasksActivity.this);
                }
                super.dispatchDraw(canvas);
            }
        };
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams contentLp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT);
        if (hasMainTabs) {
            contentLp.topMargin = ActionBar.getCurrentActionBarHeight() + (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0);
        }
        rootLayout.addView(contentLayout, contentLp);

        segmentedControl = new SegmentedControl(context);
        segmentedControl.setOnTabChangedListener(index -> {
            selectedTab = index;
            updatePagesVisibility();
        });
        contentLayout.addView(segmentedControl, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 16, 8, 16, 8));

        tabsScroll = new HorizontalScrollView(context);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsRow = new LinearLayout(context);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabsScroll.addView(tabsRow, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        contentLayout.addView(tabsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 8, 0, 8, 2));
        View tabsDivider = new View(context);
        tabsDivider.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        contentLayout.addView(tabsDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

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
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < rows.size()) {
                Row row = rows.get(position);
                if (row.type == ROW_TASK && row.task != null) {
                    openTaskDetails(row.task);
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= 0 && position < rows.size()) {
                Row row = rows.get(position);
                if (row.type == ROW_TASK && row.task != null) {
                    showTaskOptions(row.task);
                    return true;
                }
            }
            return false;
        });
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size()) {
                    return false;
                }
                Row fromRow = rows.get(from);
                Row toRow = rows.get(to);
                if (fromRow.type != ROW_TASK || toRow.type != ROW_TASK || fromRow.task == null || toRow.task == null) {
                    return false;
                }
                Collections.swap(rows, from, to);
                int tmp = fromRow.task.sortOrder;
                fromRow.task.sortOrder = toRow.task.sortOrder;
                toRow.task.sortOrder = tmp;
                listAdapter.notifyItemMoved(from, to);
                ArrayList<MglaTask> ordered = new ArrayList<>();
                for (Row row : rows) {
                    if (row.type == ROW_TASK && row.task != null && !row.task.done) {
                        ordered.add(row.task);
                    }
                }
                MglaTasksStorage.getInstance().updateTaskSortOrders(ordered);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                int pos = viewHolder.getAdapterPosition();
                if (pos < 0 || pos >= rows.size() || rows.get(pos).type != ROW_TASK) {
                    return makeMovementFlags(0, 0);
                }
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }
        });
        touchHelper.attachToRecyclerView(listView);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                    if (rootLayout != null) {
                        rootLayout.invalidate();
                    }
                }
            }
        });
        listPage.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyTitle = new TextView(context);
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
        dayListView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < dayTasks.size()) {
                openTaskDetails(dayTasks.get(position));
            }
        });
        dayListView.setOnItemLongClickListener((view, position) -> {
            if (position >= 0 && position < dayTasks.size()) {
                showTaskOptions(dayTasks.get(position));
                return true;
            }
            return false;
        });
        dayListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                    if (rootLayout != null) {
                        rootLayout.invalidate();
                    }
                }
            }
        });
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
        fab = new FrameLayout(context);
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
        MglaTasksStorage.getInstance().ensureLoaded(() -> {
            reloadData();
            openPendingTaskIfNeeded();
        });
        MglaGlobalMusicBar.addGlobalVisibilityListener(musicBarVisibilityListener);

        // Capture lists for the bottom tabs/player liquid glass.
        iBlur3CaptureList = new ViewGroupPartRenderer(listView, rootLayout, listView::drawChild);
        iBlur3CaptureDay = new ViewGroupPartRenderer(dayListView, rootLayout, dayListView::drawChild);

        fragmentView = rootLayout;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        openPendingTaskIfNeeded();
        updateSyncUi();
        if (MglaSubscription.canSyncTasks()) {
            MglaTasksSync.maybeAutoSync(false);
        }
    }

    /** Show sync controls only for the selected account on Premium+ / Ultra. */
    private void updateSyncUi() {
        boolean allowed = MglaSubscription.canSyncTasks();
        if (syncItem != null) {
            syncItem.setVisibility(allowed ? View.VISIBLE : View.GONE);
        }
        if (syncSubItem != null) {
            syncSubItem.setVisibility(allowed ? View.VISIBLE : View.GONE);
        }
    }

    private void syncTasksNow() {
        if (!MglaSubscription.canSyncTasks()) {
            BulletinFactory.of(this).createErrorBulletin("Синхронизация доступна с Premium, Premium+ и Ultra").show();
            return;
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, "Синхронизация…").show();
        MglaTasksSync.syncNow(true, new MglaTasksSync.Callback() {
            @Override
            public void onSuccess(String message) {
                reloadData();
                BulletinFactory.of(MglaTasksActivity.this)
                        .createSimpleBulletin(R.raw.chats_infotip, message != null ? message : "Готово")
                        .show();
            }

            @Override
            public void onError(String message) {
                BulletinFactory.of(MglaTasksActivity.this)
                        .createErrorBulletin(message != null ? message : "Ошибка синхронизации")
                        .show();
            }
        });
    }

    public void openPendingTaskIfNeeded() {
        long id = openTaskId != 0 ? openTaskId : pendingOpenTaskId;
        if (id == 0 || getContext() == null) {
            return;
        }
        openTaskId = 0;
        pendingOpenTaskId = 0;
        if (id == -1) {
            openTaskEditor(null);
            return;
        }
        MglaTasksStorage.getInstance().ensureLoaded(() -> {
            MglaTask task = MglaTasksStorage.getInstance().getTask(id);
            if (task != null) {
                openTaskDetails(task);
            }
        });
    }

    private void blur3_InvalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null || fragmentView == null) {
            return;
        }
        final IBlur3Capture capture = selectedTab == 1 && iBlur3CaptureDay != null
                ? iBlur3CaptureDay
                : iBlur3CaptureList;
        if (capture == null) {
            return;
        }
        final int additionalList = dp(48);
        final int nav = AndroidUtilities.navigationBarHeight;
        final int mainTabBottom = fragmentView.getMeasuredHeight() - nav - dp(DialogsActivity.MAIN_TABS_MARGIN);
        final int mainTabTop = mainTabBottom - dp(DialogsActivity.MAIN_TABS_HEIGHT)
                - dp(MglaGlobalMusicBar.blurExclusionExtraDp());

        final int actionBarBottom = ActionBar.getCurrentActionBarHeight()
                + (actionBar != null && actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0);
        iBlur3PositionActionBar.set(0, -additionalList, fragmentView.getMeasuredWidth(), actionBarBottom + additionalList);
        if (hasMainTabs) {
            iBlur3PositionMainTabs.set(0, mainTabTop, fragmentView.getMeasuredWidth(), mainTabBottom);
            iBlur3PositionMainTabs.inset(0, LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0 : -dp(48));
        } else {
            iBlur3PositionMainTabs.setEmpty();
        }
        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, hasMainTabs ? 2 : 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(
                capture, fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        return iBlur3SourceGlass;
    }

    @Override
    public void onFragmentDestroy() {
        MglaTasksStorage.getInstance().removeListener(this);
        MglaGlobalMusicBar.removeGlobalVisibilityListener(musicBarVisibilityListener);
        if (fabMarginAnimator != null) {
            fabMarginAnimator.cancel();
            fabMarginAnimator = null;
        }
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
        iBlur3Invalidated = true;
        if (rootLayout != null) {
            rootLayout.invalidate();
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

        rebuildGroupTabs();
        applyGroupFilter();
        rebuildRows();
        listAdapter.notifyDataSetChanged();
        updateEmptyState();
        calendarView.setTasks(filteredTasks);
        updateDayTasks();
    }

    private void applyGroupFilter() {
        filteredTasks.clear();
        String q = searchQuery != null ? searchQuery.toLowerCase(Locale.getDefault()) : "";
        for (MglaTask task : allTasks) {
            if (selectedGroupId != -1 && task.groupId != selectedGroupId) {
                continue;
            }
            if (!TextUtils.isEmpty(q)) {
                String title = task.title != null ? task.title.toLowerCase(Locale.getDefault()) : "";
                String note = task.note != null ? task.note.toLowerCase(Locale.getDefault()) : "";
                if (!title.contains(q) && !note.contains(q)) {
                    continue;
                }
            }
            filteredTasks.add(task);
        }
    }

    private void rebuildGroupTabs() {
        boolean groupExists = selectedGroupId <= 0;
        for (MglaTask.Group group : groups) {
            if (group.id == selectedGroupId) {
                groupExists = true;
                break;
            }
        }
        if (!groupExists) {
            selectedGroupId = -1;
        }

        tabsRow.removeAllViews();
        tabViews.clear();
        addGroupTab("Все", -1, 0);
        addGroupTab("Без группы", 0, 0);
        for (int a = 0; a < groups.size(); a++) {
            MglaTask.Group group = groups.get(a);
            addGroupTab(group.name, group.id, group.color);
        }
    }

    private void addGroupTab(String name, long groupId, int color) {
        Context context = tabsRow.getContext();
        TextView tab = new TextView(context);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tab.setSingleLine(true);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(16), 0, dp(16), 0);
        styleGroupTab(tab, name, color, selectedGroupId == groupId);
        tab.setOnClickListener(v -> {
            if (selectedGroupId != groupId) {
                selectedGroupId = groupId;
                rebuildGroupTabs();
                tabsRow.post(this::scrollSelectedTabIntoView);
                applyGroupFilter();
                rebuildRows();
                listAdapter.notifyDataSetChanged();
                updateEmptyState();
                calendarView.setTasks(filteredTasks);
                updateDayTasks();
            }
        });
        tabViews.add(tab);
        tabsRow.addView(tab, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, tabViews.size() > 1 ? 8 : 0, 0, 0, 0));
    }

    private void styleGroupTab(TextView tab, String name, int color, boolean selected) {
        int accent = Theme.getColor(Theme.key_chat_messagePanelSend);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        tab.setTypeface(selected ? AndroidUtilities.getTypeface("fonts/rmedium.ttf") : null);
        if (selected) {
            bg.setColor(accent);
            tab.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(ColorUtils.setAlphaComponent(accent, 26));
            tab.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
        tab.setBackground(bg);
        if (color != 0) {
            SpannableString ss = new SpannableString("●  " + name);
            ss.setSpan(new ForegroundColorSpan(selected ? 0xFFFFFFFF : color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tab.setText(ss);
        } else {
            tab.setText(name);
        }
    }

    private int selectedGroupTabIndex() {
        if (selectedGroupId == -1) {
            return 0;
        }
        if (selectedGroupId == 0) {
            return 1;
        }
        for (int a = 0; a < groups.size(); a++) {
            if (groups.get(a).id == selectedGroupId) {
                return a + 2;
            }
        }
        return 0;
    }

    private void scrollSelectedTabIntoView() {
        int index = selectedGroupTabIndex();
        if (index < tabViews.size()) {
            TextView tab = tabViews.get(index);
            tabsScroll.smoothScrollTo(Math.max(0, tab.getLeft() - dp(48)), 0);
        }
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
            long due = MglaTaskRepeat.effectiveDueAt(task);
            if (task.done) {
                done.add(task);
            } else if (due <= 0) {
                noDate.add(task);
            } else if (due < todayStart) {
                overdue.add(task);
            } else if (due < tomorrowStart) {
                today.add(task);
            } else if (due < dayAfterTomorrowStart) {
                tomorrow.add(task);
            } else if (due < weekEnd) {
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
            if (a.priority != b.priority) {
                return Integer.compare(b.priority, a.priority);
            }
            long da = MglaTaskRepeat.effectiveDueAt(a);
            long db = MglaTaskRepeat.effectiveDueAt(b);
            if (da <= 0) {
                da = Long.MAX_VALUE;
            }
            if (db <= 0) {
                db = Long.MAX_VALUE;
            }
            if (da != db) {
                return Long.compare(da, db);
            }
            if (a.sortOrder != b.sortOrder) {
                return Integer.compare(a.sortOrder, b.sortOrder);
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
        String emptyText;
        if (!TextUtils.isEmpty(searchQuery)) {
            emptyText = "Ничего не найдено";
        } else if (selectedGroupId > 0) {
            emptyText = "В этой группе задач нет";
        } else if (selectedGroupId == 0) {
            emptyText = "Задач без группы нет";
        } else {
            emptyText = "Задач пока нет";
        }
        emptyTitle.setText(emptyText);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void confirmClearDone() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        int count = 0;
        for (MglaTask task : allTasks) {
            if (task.done) {
                count++;
            }
        }
        if (count == 0) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, "Выполненных задач нет").show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Очистить выполненные?");
        builder.setMessage("Будет удалено задач: " + count);
        builder.setPositiveButton("Удалить", (d, w) -> {
            MglaTasksStorage.getInstance().deleteDoneTasks();
            MglaTasksWidgetProvider.updateAll();
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void exportTasks() {
        try {
            String json = MglaTasksIO.exportJson();
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, json);
            intent.putExtra(Intent.EXTRA_SUBJECT, "Mgla tasks");
            startActivityForResult(Intent.createChooser(intent, "Экспорт задач"), 0);
            ClipboardManager clipboard = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("mgla_tasks", json));
            }
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, "JSON скопирован и готов к отправке").show();
        } catch (Exception e) {
            BulletinFactory.of(this).createErrorBulletin("Не удалось экспортировать").show();
        }
    }

    private void importTasks() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence clip = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            clip = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
        }
        if (clip == null || TextUtils.isEmpty(clip)) {
            BulletinFactory.of(this).createErrorBulletin("В буфере нет JSON").show();
            return;
        }
        final String json = clip.toString();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Импорт задач?");
        builder.setMessage("Задачи из буфера обмена будут добавлены к текущим.");
        builder.setPositiveButton("Импорт", (d, w) -> {
            try {
                int count = MglaTasksIO.importJson(json);
                MglaTasksWidgetProvider.updateAll();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, "Импортировано: " + count).show();
            } catch (Exception e) {
                BulletinFactory.of(this).createErrorBulletin("Некорректный JSON").show();
            }
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void updateDayTasks() {
        dayTasks.clear();
        long dayStart = calendarView.getSelectedDay();
        for (MglaTask task : filteredTasks) {
            if (MglaTaskRepeat.occursOn(task, dayStart)) {
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
            long da = MglaTaskRepeat.effectiveDueAt(a);
            long db = MglaTaskRepeat.effectiveDueAt(b);
            if (da != db) {
                return Long.compare(da, db);
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

    private void onTaskCheckClicked(MglaTask task) {
        if (task == null) {
            return;
        }
        if (task.done) {
            MglaTasksStorage.getInstance().setTaskDone(task.id, false);
            MglaTask updated = MglaTasksStorage.getInstance().getTask(task.id);
            if (updated != null) {
                applyReminder(updated);
            }
        } else {
            MglaTasksReminderScheduler.completeTask(task.id);
        }
    }

    private void openTaskDetails(MglaTask task) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        MglaTaskDetailsSheet sheet = new MglaTaskDetailsSheet(context, task,
                () -> openTaskEditor(task),
                () -> {
                    MglaTasksStorage.getInstance().deleteTask(task.id);
                    MglaTasksReminderScheduler.cancel(task.id);
                });
        sheet.setOnOpenMessage(() -> openLinkedMessage(task));
        showDialog(sheet);
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
        items.add("Изменить");
        actions.add(1);
        items.add("Удалить");
        actions.add(2);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(task.title);
        builder.setItems(items.toArray(new CharSequence[0]), (d, which) -> {
            int action = actions.get(which);
            if (action == 0) {
                openLinkedMessage(task);
            } else if (action == 1) {
                openTaskEditor(task);
            } else if (action == 2) {
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
                cell.setOnOpenClickListener(v -> {
                    MglaTask task = cell.getTask();
                    if (task != null) {
                        openLinkedMessage(task);
                    }
                });
                cell.setOnCheckClickListener(v -> onTaskCheckClicked(cell.getTask()));
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
            cell.setOnOpenClickListener(v -> {
                MglaTask task = cell.getTask();
                if (task != null) {
                    openLinkedMessage(task);
                }
            });
            cell.setOnCheckClickListener(v -> onTaskCheckClicked(cell.getTask()));
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
            float target = segmentWidth * index;
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
                indicator.setTranslationX(segmentWidth * currentIndex);
                indicator.setTranslationY(0);
            }
        }
    }
}
