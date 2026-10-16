package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaMainTabsConfig;
import org.telegram.messenger.MglaMainTabsConfig.TabId;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;

/**
 * Interactive preview of the bottom main-tabs island: long-press drag to reorder,
 * tap to remove (except locked {@link MglaMainTabsConfig#isLocked}), chips below to add.
 */
@SuppressLint("ViewConstructor")
public class MglaMainTabsIslandEditor extends LinearLayout {

    public interface Listener {
        void onTabsChanged(ArrayList<TabId> order);
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final int currentAccount;
    private final Listener listener;

    private final ArrayList<TabId> order = new ArrayList<>();
    private final LinearLayout chipsRow;
    private final RecyclerListView dragList;
    private final TabsAdapter adapter;
    private final LinearLayout previewRow;
    private final FrameLayout islandWrap;
    private final FrameLayout searchButtonPreview;
    private final ItemTouchHelper touchHelper;
    private int previewSelected = 0;
    private boolean dragging;
    private boolean searchButtonEnabled;

    public MglaMainTabsIslandEditor(Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, Listener listener) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.currentAccount = currentAccount;
        this.listener = listener;
        setOrientation(VERTICAL);

        boolean showCalls = UserConfig.getInstance(currentAccount).showCallsTab;
        order.addAll(MglaMainTabsConfig.getEnabledTabs(showCalls));
        ensureRequiredTabs();
        ensurePreviewIndex();

        TextView hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        hintView.setText("Удерживайте и перетаскивайте, чтобы менять порядок. Нажмите, чтобы убрать. Максимум 7 вкладок.");
        hintView.setPadding(dp(4), 0, dp(4), dp(10));
        addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        previewRow = new LinearLayout(context);
        previewRow.setOrientation(HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.setClipChildren(false);

        islandWrap = new FrameLayout(context);
        GradientDrawable islandBg = createIslandBackground();
        islandWrap.setBackground(islandBg);
        islandWrap.setPadding(dp(4), dp(4), dp(4), dp(4));

        searchButtonPreview = new FrameLayout(context);
        searchButtonPreview.setBackground(createIslandBackground());
        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.outline_header_search);
        searchIcon.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider), PorterDuff.Mode.SRC_IN));
        searchIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        searchButtonPreview.addView(searchIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        searchButtonEnabled = MglaMainTabsConfig.isSearchButtonEnabled();

        dragList = new RecyclerListView(context) {
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                if (w != oldw && adapter != null && !dragging) {
                    adapter.notifyDataSetChanged();
                }
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent e) {
                if (dragging) {
                    ViewParent parent = getParent();
                    while (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                        parent = parent.getParent();
                    }
                }
                return super.onInterceptTouchEvent(e);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (dragging) {
                    ViewParent parent = getParent();
                    while (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                        parent = parent.getParent();
                    }
                }
                return super.onTouchEvent(e);
            }
        };
        dragList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        dragList.setClipToPadding(false);
        dragList.setOverScrollMode(OVER_SCROLL_NEVER);
        dragList.setNestedScrollingEnabled(false);
        dragList.setHasFixedSize(true);
        adapter = new TabsAdapter();
        adapter.setHasStableIds(true);
        dragList.setAdapter(adapter);

        touchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return makeMovementFlags(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from < 0 || to < 0 || from >= order.size() || to >= order.size() || from == to) {
                    return false;
                }
                TabId moved = order.remove(from);
                order.add(to, moved);
                if (previewSelected == from) {
                    previewSelected = to;
                } else if (from < previewSelected && to >= previewSelected) {
                    previewSelected--;
                } else if (from > previewSelected && to <= previewSelected) {
                    previewSelected++;
                }
                // Only notifyItemMoved — notifyItemChanged / notifyDataSetChanged break multi-step drag.
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // Manual startDrag via long-click — RecyclerListView fights auto long-press drag.
                return false;
            }

            @Override
            public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current, @NonNull RecyclerView.ViewHolder target) {
                return true;
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                dragging = actionState == ItemTouchHelper.ACTION_STATE_DRAG;
                if (dragging && viewHolder != null) {
                    viewHolder.itemView.setAlpha(0.85f);
                    viewHolder.itemView.setScaleX(1.06f);
                    viewHolder.itemView.setScaleY(1.06f);
                    ViewParent parent = dragList.getParent();
                    while (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                        parent = parent.getParent();
                    }
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                viewHolder.itemView.setAlpha(1f);
                viewHolder.itemView.setScaleX(1f);
                viewHolder.itemView.setScaleY(1f);
                dragging = false;
                // Refresh selection ovals once drag finished; avoid mid-drag notifyDataSetChanged.
                adapter.notifyDataSetChanged();
                persist();
            }

            @Override
            public int interpolateOutOfBoundsScroll(@NonNull RecyclerView recyclerView, int viewSize, int viewSizeOutOfBounds, int totalSize, long msSinceStartScroll) {
                // Stronger horizontal auto-scroll so you can drag across several positions.
                int direction = (int) Math.signum(viewSizeOutOfBounds);
                int abs = Math.min(dp(28), Math.abs(viewSizeOutOfBounds));
                return direction * Math.max(dp(8), abs);
            }
        });
        touchHelper.attachToRecyclerView(dragList);

        dragList.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= order.size()) {
                return false;
            }
            RecyclerView.ViewHolder holder = dragList.findContainingViewHolder(view);
            if (holder == null) {
                holder = dragList.findViewHolderForAdapterPosition(position);
            }
            if (holder != null) {
                touchHelper.startDrag(holder);
                dragList.cancelClickRunnables(true);
                return true;
            }
            return false;
        });

        dragList.setOnItemClickListener((view, position) -> {
            if (dragging || position < 0 || position >= order.size()) {
                return;
            }
            TabId id = order.get(position);
            if (MglaMainTabsConfig.isLocked(id)) {
                previewSelected = position;
                adapter.notifyDataSetChanged();
                if (id == TabId.CHATS) {
                    showHint("А как ты тогда попадешь в чаты?");
                } else if (id == TabId.SETTINGS) {
                    showHint("А как ты тогда попадешь в настройки?");
                }
                return;
            }
            order.remove(position);
            ensureRequiredTabs();
            ensurePreviewIndex();
            adapter.notifyDataSetChanged();
            rebuildChips();
            persist();
        });

        islandWrap.addView(dragList, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, DialogsActivity.MAIN_TABS_HEIGHT));
        previewRow.setGravity(Gravity.CENTER);
        previewRow.addView(islandWrap, LayoutHelper.createLinear(0, DialogsActivity.MAIN_TABS_HEIGHT + 8, 1f, Gravity.CENTER_VERTICAL));
        previewRow.addView(searchButtonPreview, LayoutHelper.createLinear(
                MglaMainTabsConfig.SEARCH_BUTTON_SIZE,
                MglaMainTabsConfig.SEARCH_BUTTON_SIZE,
                Gravity.CENTER_VERTICAL,
                MglaMainTabsConfig.SEARCH_BUTTON_GAP, 0, 0, 0));
        applySearchButtonPreview();
        addView(previewRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, DialogsActivity.MAIN_TABS_HEIGHT + 8, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        TextView availableTitle = new TextView(context);
        availableTitle.setText("Добавить");
        availableTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        availableTitle.setTypeface(AndroidUtilities.bold());
        availableTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        availableTitle.setPadding(dp(4), 0, dp(4), dp(8));
        addView(availableTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsScroll.addView(chipsRow, LayoutHelper.createScroll(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rebuildChips();
    }

    private GradientDrawable createIslandBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f));
        bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), 0.96f));
        bg.setStroke(Math.max(1, dp(1)), Theme.getColor(Theme.key_divider, resourcesProvider));
        return bg;
    }

    private void applySearchButtonPreview() {
        searchButtonPreview.setVisibility(searchButtonEnabled ? VISIBLE : GONE);
        if (adapter != null && !dragging) {
            adapter.notifyDataSetChanged();
        }
        previewRow.requestLayout();
    }

    public void setSearchButtonEnabled(boolean enabled) {
        if (searchButtonEnabled == enabled) {
            return;
        }
        searchButtonEnabled = enabled;
        applySearchButtonPreview();
    }

    private void ensureRequiredTabs() {
        if (!order.contains(TabId.CHATS)) {
            order.add(0, TabId.CHATS);
        }
        if (!order.contains(TabId.SETTINGS)) {
            order.add(TabId.SETTINGS);
        }
    }

    private void ensurePreviewIndex() {
        if (order.isEmpty()) {
            previewSelected = 0;
            return;
        }
        if (previewSelected < 0 || previewSelected >= order.size()) {
            int chats = order.indexOf(TabId.CHATS);
            previewSelected = chats >= 0 ? chats : 0;
        }
    }

    private void rebuildChips() {
        chipsRow.removeAllViews();
        ArrayList<TabId> hidden = new ArrayList<>();
        for (TabId id : TabId.values()) {
            if (!order.contains(id)) {
                hidden.add(id);
            }
        }
        if (hidden.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("Все вкладки добавлены");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            empty.setPadding(dp(4), dp(6), dp(4), dp(6));
            chipsRow.addView(empty);
            return;
        }
        for (TabId id : hidden) {
            TextView chip = new TextView(getContext());
            chip.setText("+ " + MglaMainTabsConfig.titleFor(id));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), 0.12f));
            chip.setBackground(bg);
            final TabId addId = id;
            chip.setOnClickListener(v -> {
                if (order.size() >= MglaMainTabsConfig.MAX_TABS) {
                    showHint("Можно добавить не больше " + MglaMainTabsConfig.MAX_TABS + " вкладок");
                    return;
                }
                order.add(addId);
                adapter.notifyDataSetChanged();
                rebuildChips();
                persist();
            });
            chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        }
    }

    private void persist() {
        if (listener != null) {
            listener.onTabsChanged(new ArrayList<>(order));
        }
    }

    private void showHint(String text) {
        Context ctx = getContext();
        Activity activity = ctx instanceof Activity ? (Activity) ctx : AndroidUtilities.findActivity(ctx);
        if (activity != null) {
            View content = activity.findViewById(android.R.id.content);
            if (content instanceof FrameLayout) {
                BulletinFactory.of((FrameLayout) content, resourcesProvider)
                        .createSimpleBulletin(R.raw.error, text)
                        .show();
                return;
            }
        }
        android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    public static GlassTabView createTabView(Context context, Theme.ResourcesProvider rp, int account, TabId id) {
        switch (id) {
            case CHATS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CHATS, MglaMainTabsConfig.titleFor(id));
            case CONTACTS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CONTACTS, MglaMainTabsConfig.titleFor(id));
            case SETTINGS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.SETTINGS, MglaMainTabsConfig.titleFor(id));
            case CALLS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CALLS, MglaMainTabsConfig.titleFor(id));
            case PROFILE:
                return GlassTabView.createAvatar(context, rp, account, R.string.MainTabsProfile);
            case TASKS:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CHECKLIST, MglaMainTabsConfig.titleFor(id));
            case AI:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.MODELS, MglaMainTabsConfig.titleFor(id));
            case MUSIC:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.MUSIC, MglaMainTabsConfig.titleFor(id));
            default:
                return GlassTabView.createMainTab(context, rp, GlassTabView.TabAnimation.CHATS, MglaMainTabsConfig.titleFor(id));
        }
    }

    private int tabCellWidth() {
        int avail = dragList.getWidth();
        if (avail <= 0 && islandWrap.getWidth() > 0) {
            avail = islandWrap.getWidth() - islandWrap.getPaddingLeft() - islandWrap.getPaddingRight();
        }
        if (avail <= 0) {
            avail = AndroidUtilities.displaySize.x - dp(64);
            if (searchButtonEnabled) {
                avail -= dp(MglaMainTabsConfig.SEARCH_BUTTON_SIZE + MglaMainTabsConfig.SEARCH_BUTTON_GAP);
            }
        }
        return Math.max(dp(40), avail / Math.max(1, order.size()));
    }

    private class TabsAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            EditorTabCell cell = new EditorTabCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(tabCellWidth(), dp(DialogsActivity.MAIN_TABS_HEIGHT)));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            if (lp != null) {
                lp.width = tabCellWidth();
                holder.itemView.setLayoutParams(lp);
            }
            ((EditorTabCell) holder.itemView).bind(order.get(position), position == previewSelected, order.size());
        }

        @Override
        public int getItemCount() {
            return order.size();
        }

        @Override
        public long getItemId(int position) {
            return order.get(position).ordinal();
        }
    }

    private class EditorTabCell extends FrameLayout {
        public EditorTabCell(Context context) {
            super(context);
        }

        void bind(TabId id, boolean selected, int total) {
            removeAllViews();
            GlassTabView view = createTabView(getContext(), resourcesProvider, currentAccount, id);
            view.setSelected(selected, false);
            if (total >= 6) {
                view.setTextSizeDp(0);
            } else if (total >= 5) {
                view.setTextSizeDp(10);
            } else if (total >= 4) {
                view.setTextSizeDp(11);
            } else {
                view.setTextSizeDp(12);
            }
            addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
    }
}
