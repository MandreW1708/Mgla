package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;

public class MglaSideMenuElementsActivity extends BaseFragment {

    private RecyclerListView visibleList;
    private RecyclerListView hiddenList;
    private VisibleAdapter visibleAdapter;
    private HiddenAdapter hiddenAdapter;
    private ItemTouchHelper visibleTouchHelper;

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

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);

        // Блок видимых элементов
        LinearLayout visibleBlock = createBlock(context, "Видимые");

        visibleList = new RecyclerListView(context);
        visibleList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        visibleList.setNestedScrollingEnabled(false);
        visibleList.setAdapter(visibleAdapter = new VisibleAdapter(context));
        visibleList.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= visibleItems.size()) return;
            int item = visibleItems.get(position);
            if (MglaSideMenuController.isDivider(item)) {
                visibleItems.remove(position);
                MglaSideMenuController.removeDivider(getContext(), item);
                saveFullOrder();
                refreshLists();
            } else {
                visibleItems.remove(position);
                hiddenItems.add(item);
                MglaSideMenuController.setEnabled(getContext(), item, false);
                saveFullOrder();
                refreshLists();
            }
        });

        visibleTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from < 0 || to < 0 || from >= visibleItems.size() || to >= visibleItems.size() || from == to) {
                    return false;
                }
                Collections.swap(visibleItems, from, to);
                visibleAdapter.notifyItemMoved(from, to);
                visibleAdapter.notifyItemChanged(Math.min(from, to));
                visibleAdapter.notifyItemChanged(Math.max(from, to));
                saveFullOrder();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false; // Отключаем автоматический drag по long press
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                if (visibleAdapter != null) {
                    visibleAdapter.notifyDataSetChanged();
                }
                viewHolder.itemView.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            }
        });
        visibleTouchHelper.attachToRecyclerView(visibleList);

        // Ручной запуск drag по long press
        visibleList.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= visibleItems.size()) {
                return false;
            }
            RecyclerView.ViewHolder holder = visibleList.findViewHolderForAdapterPosition(position);
            if (holder == null) {
                holder = visibleList.findContainingViewHolder(view);
            }
            if (holder != null && visibleTouchHelper != null) {
                visibleTouchHelper.startDrag(holder);
                return true;
            }
            return false;
        });

        visibleBlock.addView(visibleList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Кнопка добавления разделителя
        TextView addDividerBtn = new TextView(context);
        addDividerBtn.setText("+ Добавить разделитель");
        addDividerBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        addDividerBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        addDividerBtn.setGravity(Gravity.CENTER);
        addDividerBtn.setPadding(dp(16), dp(14), dp(16), dp(14));
        addDividerBtn.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        addDividerBtn.setOnClickListener(v -> {
            int newDividerId = MglaSideMenuController.addDivider(getContext());
            visibleItems.add(newDividerId);
            saveFullOrder();
            refreshLists();
        });
        visibleBlock.addView(addDividerBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(visibleBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        // Блок скрытых элементов
        LinearLayout hiddenBlock = createBlock(context, "Скрытые");

        hiddenList = new RecyclerListView(context);
        hiddenList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        hiddenList.setNestedScrollingEnabled(false);
        hiddenList.setAdapter(hiddenAdapter = new HiddenAdapter(context));
        hiddenList.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= hiddenItems.size()) return;
            int item = hiddenItems.remove(position);
            visibleItems.add(item);
            MglaSideMenuController.setEnabled(getContext(), item, true);
            saveFullOrder();
            refreshLists();
        });

        hiddenBlock.addView(hiddenList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(hiddenBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        scrollView.setFillViewport(true);

        fragmentView = scrollView;
        return fragmentView;
    }

    private void loadData(Context context) {
        ArrayList<Integer> allItems = MglaSideMenuController.getOrder(context);
        visibleItems = new ArrayList<>();
        hiddenItems = new ArrayList<>();
        for (int item : allItems) {
            if (MglaSideMenuController.isEnabled(context, item)) {
                visibleItems.add(item);
            } else {
                hiddenItems.add(item);
            }
        }
    }

    private void saveFullOrder() {
        ArrayList<Integer> fullOrder = new ArrayList<>();
        fullOrder.addAll(visibleItems);
        fullOrder.addAll(hiddenItems);
        MglaSideMenuController.saveOrder(getContext(), fullOrder);
    }

    private void refreshLists() {
        if (visibleAdapter != null) visibleAdapter.notifyDataSetChanged();
        if (hiddenAdapter != null) hiddenAdapter.notifyDataSetChanged();
    }

    private LinearLayout createBlock(Context context, String title) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        block.setBackground(bg);
        block.setClipToOutline(true);
        block.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        HeaderCell header = new HeaderCell(context, 22);
        header.setBackground(null);
        header.setText(title);
        block.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return block;
    }

    private void setupItemView(FrameLayout container, int item) {
        container.removeAllViews();
        container.setPadding(dp(21), dp(12), dp(18), dp(12));
        container.setMinimumHeight(dp(50));

        if (MglaSideMenuController.isDivider(item)) {
            ImageView icon = new ImageView(container.getContext());
            icon.setImageResource(R.drawable.msg_divider_icon);
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
            container.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            TextView label = new TextView(container.getContext());
            label.setText("Разделитель");
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            container.addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 40, 0, 0, 0));
        } else {
            ImageView icon = new ImageView(container.getContext());
            icon.setImageResource(MglaSideMenuController.getIcon(item));
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            container.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            TextView title = new TextView(container.getContext());
            title.setText(MglaSideMenuController.getTitle(item));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            container.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 40, 0, 0, 0));
        }
    }

    private class VisibleAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        VisibleAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return visibleItems == null ? 0 : visibleItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout container = new FrameLayout(context);
            container.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(container);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            FrameLayout container = (FrameLayout) holder.itemView;
            container.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            int item = visibleItems.get(position);
            setupItemView(container, item);
        }
    }

    private class HiddenAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        HiddenAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return hiddenItems == null ? 0 : hiddenItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout container = new FrameLayout(context);
            container.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(container);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            FrameLayout container = (FrameLayout) holder.itemView;
            container.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            int item = hiddenItems.get(position);
            setupItemView(container, item);
        }
    }
}