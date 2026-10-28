package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaSideMenuConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Side-menu item editor.
 * Pattern: FiltersSetupActivity — one full-screen RecyclerListView + setSections() + reorder handle.
 * No ScrollView around the list (that broke ItemTouchHelper).
 */
public class MglaSideMenuElementsActivity extends MglaBaseSettingsActivity {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_VISIBLE = 1;
    private static final int TYPE_ADD_DIVIDER = 2;
    private static final int TYPE_GAP = 3;
    private static final int TYPE_HIDDEN = 4;
    private static final int TYPE_HINT = 5;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private ArrayList<Integer> visibleItems;
    private ArrayList<Integer> hiddenItems;

    public MglaSideMenuElementsActivity() {
        this(null);
    }

    public MglaSideMenuElementsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Элементы бокового меню");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        loadData(context);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setSections(dp(16), dp(MglaUi.BLOCK_CORNER_RADIUS_DP), true);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);
        listView.setClipToPadding(false);
        listView.setAdapter(adapter = new ListAdapter(context));

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= adapter.getItemCount()) {
                return;
            }
            int type = adapter.getItemViewType(position);
            if (type == TYPE_ADD_DIVIDER) {
                int newId = MglaSideMenuController.addDivider(getContext());
                visibleItems.add(newId);
                saveFullOrder();
                adapter.notifyDataSetChanged();
                return;
            }
            Object tag = view.getTag(R.id.object_tag);
            if (!(tag instanceof Integer)) {
                return;
            }
            int itemId = (Integer) tag;
            if (type == TYPE_VISIBLE) {
                hideById(itemId);
            } else if (type == TYPE_HIDDEN) {
                showById(itemId);
            }
        });

        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                if (viewHolder.getItemViewType() != TYPE_VISIBLE) {
                    return makeMovementFlags(0, 0);
                }
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean canDropOver(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder current, @NonNull RecyclerView.ViewHolder target) {
                return current.getItemViewType() == TYPE_VISIBLE && target.getItemViewType() == TYPE_VISIBLE;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                if (viewHolder.getItemViewType() != TYPE_VISIBLE || target.getItemViewType() != TYPE_VISIBLE) {
                    return false;
                }
                Object fromTag = viewHolder.itemView.getTag(R.id.object_tag);
                Object toTag = target.itemView.getTag(R.id.object_tag);
                if (!(fromTag instanceof Integer) || !(toTag instanceof Integer)) {
                    return false;
                }
                int from = indexOfId(visibleItems, (Integer) fromTag);
                int to = indexOfId(visibleItems, (Integer) toTag);
                if (from < 0 || to < 0 || from == to) {
                    return false;
                }
                int item = visibleItems.remove(from);
                visibleItems.add(to, item);
                int fromPos = viewHolder.getAdapterPosition();
                int toPos = target.getAdapterPosition();
                if (fromPos < 0 || toPos < 0) {
                    return false;
                }
                adapter.notifyItemMoved(fromPos, toPos);
                saveFullOrder();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                    listView.cancelClickRunnables(false);
                    if (viewHolder != null) {
                        viewHolder.itemView.setPressed(true);
                    }
                }
                super.onSelectedChanged(viewHolder, actionState);
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                viewHolder.itemView.setPressed(false);
                // Retag children so clicks stay bound to item ids after reorder.
                retagVisibleRows(recyclerView);
            }
        });
        itemTouchHelper.attachToRecyclerView(listView);

        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return fragmentView;
    }

    private void hideById(int itemId) {
        int index = indexOfId(visibleItems, itemId);
        if (index < 0) {
            return;
        }
        visibleItems.remove(index);
        if (MglaSideMenuController.isDivider(itemId)) {
            MglaSideMenuController.removeDivider(getContext(), itemId);
        } else {
            hiddenItems.add(itemId);
            MglaSideMenuController.setEnabled(getContext(), itemId, false);
        }
        saveFullOrder();
        adapter.notifyDataSetChanged();
    }

    private void showById(int itemId) {
        int index = indexOfId(hiddenItems, itemId);
        if (index < 0) {
            return;
        }
        hiddenItems.remove(index);
        visibleItems.add(itemId);
        MglaSideMenuController.setEnabled(getContext(), itemId, true);
        saveFullOrder();
        adapter.notifyDataSetChanged();
    }

    private static int indexOfId(ArrayList<Integer> list, int id) {
        if (list == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) != null && list.get(i) == id) {
                return i;
            }
        }
        return -1;
    }

    private void retagVisibleRows(RecyclerView recyclerView) {
        if (recyclerView == null || visibleItems == null) {
            return;
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            int pos = recyclerView.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION || adapter.getItemViewType(pos) != TYPE_VISIBLE) {
                continue;
            }
            int index = pos - adapter.visibleStart();
            if (index >= 0 && index < visibleItems.size()) {
                child.setTag(R.id.object_tag, visibleItems.get(index));
            }
        }
    }

    private void loadData(Context context) {
        ArrayList<Integer> all = MglaSideMenuController.getOrder(context);
        visibleItems = new ArrayList<>();
        hiddenItems = new ArrayList<>();
        for (int item : all) {
            if (MglaSideMenuController.isEnabled(context, item)) {
                visibleItems.add(item);
            } else {
                hiddenItems.add(item);
            }
        }
    }

    private void saveFullOrder() {
        ArrayList<Integer> full = new ArrayList<>();
        full.addAll(visibleItems);
        full.addAll(hiddenItems);
        MglaSideMenuController.saveOrder(getContext(), full);
    }

    private void bindRow(ItemCell cell, int itemId, boolean showHandle) {
        cell.bind(itemId, showHandle);
        cell.setTag(R.id.object_tag, itemId);
        if (showHandle) {
            cell.setReorderTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    RecyclerView.ViewHolder holder = listView.getChildViewHolder(cell);
                    if (holder != null && itemTouchHelper != null && holder.getAdapterPosition() != RecyclerView.NO_POSITION) {
                        itemTouchHelper.startDrag(holder);
                    }
                }
                return false;
            });
        } else {
            cell.setReorderTouchListener(null);
        }
    }

    private class ItemCell extends FrameLayout {
        private final ImageView iconView;
        private final TextView titleView;
        private final ImageView reorderView;
        private final LayoutParams titleLp;

        ItemCell(Context context) {
            super(context);
            setPadding(dp(21), 0, dp(6), 0);

            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            titleView.setIncludeFontPadding(false);
            // WRAP_CONTENT height — MATCH_PARENT inside WRAP_CONTENT row measures to 0 and clips text.
            titleLp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL, 40, 0, 48, 0);
            addView(titleView, titleLp);

            reorderView = new ImageView(context);
            reorderView.setImageResource(R.drawable.list_reorder);
            reorderView.setScaleType(ImageView.ScaleType.CENTER);
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_pinnedIcon), PorterDuff.Mode.MULTIPLY));
            reorderView.setContentDescription("Перетащить");
            addView(reorderView, LayoutHelper.createFrame(40, LayoutHelper.MATCH_PARENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY)
            );
        }

        void bind(int itemId, boolean showHandle) {
            titleView.setText(MglaSideMenuController.getTitle(itemId));
            iconView.setImageResource(MglaSideMenuController.getIcon(itemId));
            final boolean colored = !MglaSideMenuController.isDivider(itemId)
                    && MglaSideMenuConfig.isColoredIconsEnabled();
            ViewGroup.LayoutParams iconLp = iconView.getLayoutParams();
            if (iconLp != null) {
                int size = colored ? dp(28) : dp(24);
                iconLp.width = size;
                iconLp.height = size;
                iconView.setLayoutParams(iconLp);
            }
            titleLp.leftMargin = colored ? dp(44) : dp(40);
            if (MglaSideMenuController.isDivider(itemId)) {
                iconView.setBackground(null);
                iconView.setPadding(0, 0, 0, 0);
                iconView.setColorFilter(new PorterDuffColorFilter(
                        Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
            } else if (colored) {
                IconBackgroundColors colors = MglaSideMenuController.getIconColors(itemId);
                iconView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
                iconView.setBackground(Theme.createCircleDrawable(dp(28), colors.top, colors.bottom));
                iconView.setPadding(dp(5), dp(5), dp(5), dp(5));
            } else {
                iconView.setBackground(null);
                iconView.setPadding(0, 0, 0, 0);
                iconView.setColorFilter(new PorterDuffColorFilter(
                        Theme.getColor(Theme.key_chats_menuItemIcon), PorterDuff.Mode.SRC_IN));
            }
            reorderView.setVisibility(showHandle ? VISIBLE : GONE);
            titleLp.rightMargin = showHandle ? dp(44) : dp(12);
            titleView.setLayoutParams(titleLp);
        }

        void setReorderTouchListener(OnTouchListener listener) {
            reorderView.setOnTouchListener(listener);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        int visibleStart() {
            return 1;
        }

        int addDividerPos() {
            return visibleStart() + size(visibleItems);
        }

        int gapPos() {
            return addDividerPos() + 1;
        }

        int hiddenHeaderPos() {
            return gapPos() + 1;
        }

        int hiddenStart() {
            return hiddenHeaderPos() + 1;
        }

        int hintPos() {
            return hiddenStart() + size(hiddenItems);
        }

        private int size(ArrayList<?> list) {
            return list == null ? 0 : list.size();
        }

        @Override
        public int getItemCount() {
            return hintPos() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0 || position == hiddenHeaderPos()) {
                return TYPE_HEADER;
            }
            if (position == addDividerPos()) {
                return TYPE_ADD_DIVIDER;
            }
            if (position == gapPos()) {
                return TYPE_GAP;
            }
            if (position == hintPos()) {
                return TYPE_HINT;
            }
            if (position >= visibleStart() && position < addDividerPos()) {
                return TYPE_VISIBLE;
            }
            return TYPE_HIDDEN;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_VISIBLE || type == TYPE_HIDDEN || type == TYPE_ADD_DIVIDER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER: {
                    HeaderCell header = new HeaderCell(context, 22);
                    header.setBackground(null);
                    view = header;
                    break;
                }
                case TYPE_GAP: {
                    GraySectionCell gap = new GraySectionCell(context);
                    gap.setText("");
                    view = gap;
                    break;
                }
                case TYPE_ADD_DIVIDER: {
                    TextView btn = new TextView(context);
                    btn.setText("+ Добавить разделитель");
                    btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                    btn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                    btn.setGravity(Gravity.CENTER_VERTICAL);
                    btn.setPadding(dp(21), dp(14), dp(21), dp(14));
                    btn.setMinHeight(dp(48));
                    btn.setBackground(null);
                    view = btn;
                    break;
                }
                case TYPE_HINT: {
                    TextInfoPrivacyCell hint = new TextInfoPrivacyCell(context);
                    hint.setText("Зажмите иконку справа, чтобы переставить. Нажмите на пункт — скрыть или показать.\nШапка профиля, режим призрака и аккаунты всегда на панели.");
                    hint.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    view = hint;
                    break;
                }
                case TYPE_VISIBLE:
                case TYPE_HIDDEN:
                default: {
                    ItemCell cell = new ItemCell(context);
                    cell.setLayoutParams(new RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT, dp(50)));
                    view = cell;
                    break;
                }
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText(position == 0 ? "Видимые" : "Скрытые");
                holder.itemView.setTag(R.id.object_tag, null);
            } else if (type == TYPE_VISIBLE) {
                int index = position - visibleStart();
                bindRow((ItemCell) holder.itemView, visibleItems.get(index), true);
            } else if (type == TYPE_HIDDEN) {
                int index = position - hiddenStart();
                bindRow((ItemCell) holder.itemView, hiddenItems.get(index), false);
            } else {
                holder.itemView.setTag(R.id.object_tag, null);
            }
        }
    }
}
