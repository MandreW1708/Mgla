package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class MglaChatsSettingsActivity extends BaseFragment {

    private static final int ROW_MESSAGE_MENU = 0;
    private static final int ROW_TIME_SECONDS = 1;
    private static final int ROW_SHADOW = 2;
    private static final int ROW_COUNT = 3;

    private static final int VIEW_TYPE_TEXT = 0;
    private static final int VIEW_TYPE_CHECK = 1;
    private static final int VIEW_TYPE_SHADOW = 2;

    private SharedPreferences prefs;
    private RecyclerListView listView;

    public MglaChatsSettingsActivity() {
        this(null);
    }

    public MglaChatsSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        prefs = context.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Чаты");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setPadding(0, dp(8), 0, AndroidUtilities.navigationBarHeight);
        listView.setClipToPadding(false);
        listView.setSections();
        listView.setAdapter(new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position == ROW_MESSAGE_MENU) {
                presentFragment(new MglaMessageMenuSettingsActivity());
            } else if (position == ROW_TIME_SECONDS) {
                boolean enabled = !prefs.getBoolean("chat_time_seconds", false);
                prefs.edit().putBoolean("chat_time_seconds", enabled).apply();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                notifyTimeFormatChanged();
            }
        });

        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void notifyTimeFormatChanged() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_MESSAGE_TEXT);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == ROW_MESSAGE_MENU || position == ROW_TIME_SECONDS;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_SHADOW) {
                return VIEW_TYPE_SHADOW;
            } else if (position == ROW_TIME_SECONDS) {
                return VIEW_TYPE_CHECK;
            }
            return VIEW_TYPE_TEXT;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_SHADOW) {
                view = new ShadowSectionCell(context);
            } else if (viewType == VIEW_TYPE_CHECK) {
                view = new TextCheckCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == ROW_MESSAGE_MENU) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setText("Настроить меню сообщения", true);
                cell.setCanDisable(false);
            } else if (position == ROW_TIME_SECONDS) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck("Время с секундами", prefs.getBoolean("chat_time_seconds", false), false);
            }
        }
    }
}
