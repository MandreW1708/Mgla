package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

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
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class MglaMessageMenuSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ItemTouchHelper itemTouchHelper;
    private ArrayList<Integer> order;

    public MglaMessageMenuSettingsActivity() {
        this(null);
    }

    public MglaMessageMenuSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Меню сообщения");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        order = MglaMessageMenuController.getOrder(context);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);

        // Создаём красивый блок с закруглёнными углами
        LinearLayout menuBlock = createBlock(context, "Элементы меню");

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setNestedScrollingEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= order.size()) return;
            int option = order.get(position);
            boolean enabled = !MglaMessageMenuController.isEnabled(getContext(), option);
            MglaMessageMenuController.setEnabled(getContext(), option, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            RecyclerView.ViewHolder holder = listView.findContainingViewHolder(view);
            if (holder != null && itemTouchHelper != null) {
                itemTouchHelper.startDrag(holder);
                return true;
            }
            return false;
        });

        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from < 0 || to < 0 || from >= order.size() || to >= order.size()) {
                    return false;
                }
                int option = order.remove(from);
                order.add(to, option);
                adapter.notifyItemMoved(from, to);
                MglaMessageMenuController.saveOrder(getContext(), order);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }
        });
        itemTouchHelper.attachToRecyclerView(listView);

        // Добавляем listView в блок
        menuBlock.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Добавляем блок в rootLayout с отступами
        rootLayout.addView(menuBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        fragmentView = rootLayout;
        return fragmentView;
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

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return order == null ? 0 : order.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextCheckCell cell = new TextCheckCell(context);
            cell.setBackground(null);
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TextCheckCell cell = (TextCheckCell) holder.itemView;
            int option = order.get(position);
            cell.setTextAndCheck(MglaMessageMenuController.getTitle(option), MglaMessageMenuController.isEnabled(getContext(), option), position != order.size() - 1);
        }
    }
}