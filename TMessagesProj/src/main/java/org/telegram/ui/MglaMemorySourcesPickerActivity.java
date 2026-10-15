package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MglaMemoryConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GroupCreateUserCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Лёгкий выбор чатов для источников Живой памяти.
 * Без DialogsActivity (stories/blur/двойные страницы) — стабильнее и быстрее на слабых устройствах.
 */
public class MglaMemorySourcesPickerActivity extends BaseFragment {

    public interface Delegate {
        void onChatsSelected(ArrayList<Long> dialogIds);
    }

    private static final int DONE_ID = 1;
    private static final int FILTER_ALL = -1;

    private Delegate delegate;
    private int maxCount = MglaMemoryConfig.MAX_WATCHED;

    private final HashSet<Long> selected = new HashSet<>();
    private final ArrayList<Long> dialogIds = new ArrayList<>();
    private final ArrayList<TLObject> objects = new ArrayList<>();
    private final ArrayList<CharSequence> titles = new ArrayList<>();
    private final ArrayList<Integer> filterIds = new ArrayList<>();

    private LinearLayout filtersRow;
    private RecyclerListView listView;
    private TextView emptyView;
    private int selectedFilterId = FILTER_ALL;

    public MglaMemorySourcesPickerActivity(Bundle args) {
        super(args);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean onFragmentCreate() {
        if (getArguments() != null) {
            maxCount = Math.max(1, getArguments().getInt("maxCount", MglaMemoryConfig.MAX_WATCHED));
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Источники памяти");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == DONE_ID) {
                    finishWithSelection();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(DONE_ID, "Готово");

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        TextView hint = new TextView(context);
        hint.setText("Выберите до " + maxCount + " чатов");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(dp(16), dp(10), dp(16), dp(6));
        root.addView(hint);

        HorizontalScrollView filtersScroll = new HorizontalScrollView(context);
        filtersScroll.setHorizontalScrollBarEnabled(false);
        filtersRow = new LinearLayout(context);
        filtersRow.setOrientation(LinearLayout.HORIZONTAL);
        filtersRow.setPadding(dp(12), 0, dp(12), dp(8));
        filtersScroll.addView(filtersRow);
        root.addView(filtersScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout listBox = new FrameLayout(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(new Adapter());
        listView.setOnItemClickListener((view, position) -> toggleAt(position, view));
        listBox.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText("Нет чатов в этой папке");
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        listBox.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(listBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        fragmentView = root;
        buildFilterChips();
        rebuildList();
        updateTitle();
        return fragmentView;
    }

    private void buildFilterChips() {
        filtersRow.removeAllViews();
        filterIds.clear();
        addFilterChip("Все", FILTER_ALL);
        ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        if (filters == null) {
            return;
        }
        for (int i = 0; i < filters.size(); i++) {
            MessagesController.DialogFilter filter = filters.get(i);
            if (filter == null || TextUtils.isEmpty(filter.name) || filter.id == 0) {
                continue;
            }
            addFilterChip(filter.name, filter.id);
        }
    }

    private void addFilterChip(String title, int filterId) {
        filterIds.add(filterId);
        TextView chip = new TextView(getContext());
        chip.setText(title);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        final int id = filterId;
        chip.setOnClickListener(v -> {
            if (selectedFilterId == id) {
                return;
            }
            selectedFilterId = id;
            styleFilters();
            rebuildList();
        });
        filtersRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        styleFilters();
    }

    private void styleFilters() {
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        for (int i = 0; i < filtersRow.getChildCount(); i++) {
            View child = filtersRow.getChildAt(i);
            if (!(child instanceof TextView) || i >= filterIds.size()) {
                continue;
            }
            TextView chip = (TextView) child;
            boolean on = selectedFilterId == filterIds.get(i);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(on ? (accent & 0x00FFFFFF) | 0x22000000 : Theme.getColor(Theme.key_windowBackgroundGray));
            chip.setBackground(bg);
            chip.setTextColor(on ? accent : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
    }

    private void rebuildList() {
        dialogIds.clear();
        objects.clear();
        titles.clear();

        MessagesController mc = getMessagesController();
        AccountInstance account = getAccountInstance();
        MessagesController.DialogFilter filter = null;
        if (selectedFilterId != FILTER_ALL) {
            ArrayList<MessagesController.DialogFilter> filters = mc.getDialogFilters();
            for (int i = 0; i < filters.size(); i++) {
                if (filters.get(i).id == selectedFilterId) {
                    filter = filters.get(i);
                    break;
                }
            }
        }

        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog dialog = dialogs.get(i);
            long did = dialog.id;
            if (DialogObject.isEncryptedDialog(did) || DialogObject.isFolderDialogId(did)) {
                continue;
            }
            if (MglaMemoryConfig.isWatched(currentAccount, did)) {
                continue;
            }
            if (filter != null && !filter.includesDialog(account, did, dialog)) {
                continue;
            }
            TLObject obj;
            CharSequence title;
            if (DialogObject.isUserDialog(did)) {
                TLRPC.User user = mc.getUser(did);
                if (user == null) {
                    continue;
                }
                obj = user;
                title = UserObject.getUserName(user);
            } else {
                TLRPC.Chat chat = mc.getChat(-did);
                if (chat == null) {
                    continue;
                }
                obj = chat;
                title = chat.title;
            }
            dialogIds.add(did);
            objects.add(obj);
            titles.add(title);
        }

        boolean empty = dialogIds.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
    }

    private void toggleAt(int position, View view) {
        if (position < 0 || position >= dialogIds.size()) {
            return;
        }
        long did = dialogIds.get(position);
        if (selected.contains(did)) {
            selected.remove(did);
        } else {
            if (selected.size() >= maxCount) {
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), "Можно выбрать до " + maxCount + " чатов", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            selected.add(did);
        }
        if (view instanceof GroupCreateUserCell) {
            ((GroupCreateUserCell) view).setChecked(selected.contains(did), true);
        }
        updateTitle();
    }

    private void updateTitle() {
        if (selected.isEmpty()) {
            actionBar.setTitle("Источники памяти");
        } else {
            actionBar.setTitle("Выбрано: " + selected.size());
        }
    }

    private void finishWithSelection() {
        if (delegate != null && !selected.isEmpty()) {
            delegate.onChatsSelected(new ArrayList<>(selected));
        }
        finishFragment();
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return dialogIds.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            GroupCreateUserCell cell = new GroupCreateUserCell(getContext(), 1, 0, false);
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            GroupCreateUserCell cell = (GroupCreateUserCell) holder.itemView;
            cell.setObject(objects.get(position), titles.get(position), statusFor(objects.get(position)));
            cell.setChecked(selected.contains(dialogIds.get(position)), false);
        }

        private String statusFor(TLObject obj) {
            if (obj instanceof TLRPC.User) {
                TLRPC.User user = (TLRPC.User) obj;
                if (UserObject.isUserSelf(user)) {
                    return "Избранное";
                }
                if (user.bot) {
                    return "бот";
                }
                return "личный чат";
            }
            if (obj instanceof TLRPC.Chat) {
                TLRPC.Chat chat = (TLRPC.Chat) obj;
                if (ChatObject.isChannel(chat) && !chat.megagroup) {
                    return "канал";
                }
                return "группа";
            }
            return "";
        }
    }
}
