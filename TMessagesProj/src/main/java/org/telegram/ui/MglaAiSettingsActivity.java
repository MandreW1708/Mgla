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
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MglaAiSettingsActivity extends BaseFragment {

    private static final int ROW_AI_ENABLED = 0;
    private static final int ROW_SHADOW_1 = 1;
    private static final int ROW_AI_SUMMARY = 2;
    private static final int ROW_AI_RETELL = 3;
    private static final int ROW_AI_EDITOR = 4;
    private static final int ROW_AI_EDITOR_LIMIT = 5;
    private static final int ROW_SHADOW_2 = 6;
    private static final int ROW_AI_TRANSCRIBE = 7;
    private static final int ROW_SHADOW_3 = 8;
    private static final int ROW_COUNT = 9;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_TEXT = 1;
    private static final int VIEW_TYPE_SHADOW = 2;

    private SharedPreferences prefs;
    private RecyclerListView listView;

    public MglaAiSettingsActivity() {
        this(null);
    }

    public MglaAiSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        prefs = context.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Искусственный интеллект");
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
            if (position == ROW_AI_TRANSCRIBE) {
                presentFragment(new MglaAiTranscribeActivity());
            } else {
                String key = getSwitchKey(position);
                if (key != null) {
                    boolean enabled = !prefs.getBoolean(key, false);
                    prefs.edit().putBoolean(key, enabled).apply();
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(enabled);
                    }
                }
            }
        });

        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private String getSwitchKey(int position) {
        switch (position) {
            case ROW_AI_ENABLED:
                return "ai_enabled";
            case ROW_AI_SUMMARY:
                return "ai_summary";
            case ROW_AI_RETELL:
                return "ai_retell";
            case ROW_AI_EDITOR:
                return "ai_editor";
        }
        return null;
    }

    private String getEditorLimitValue() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String savedDate = prefs.getString("ai_editor_date", "");
        int used = today.equals(savedDate) ? prefs.getInt("ai_editor_count", 0) : 0;
        return used + " / 50";
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == ROW_AI_ENABLED || position == ROW_AI_SUMMARY || position == ROW_AI_RETELL || position == ROW_AI_EDITOR || position == ROW_AI_TRANSCRIBE;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_SHADOW_1 || position == ROW_SHADOW_2 || position == ROW_SHADOW_3) {
                return VIEW_TYPE_SHADOW;
            } else if (position == ROW_AI_EDITOR_LIMIT || position == ROW_AI_TRANSCRIBE) {
                return VIEW_TYPE_TEXT;
            }
            return VIEW_TYPE_CHECK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_SHADOW) {
                view = new ShadowSectionCell(context);
            } else if (viewType == VIEW_TYPE_TEXT) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextCheckCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == ROW_AI_EDITOR_LIMIT) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Лимит AI-редактора", getEditorLimitValue(), false);
                cell.setCanDisable(false);
            } else if (position == ROW_AI_TRANSCRIBE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setText("ИИ-расшифровка", false);
                cell.setCanDisable(false);
            } else if (holder.itemView instanceof TextCheckCell) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                switch (position) {
                    case ROW_AI_ENABLED:
                        cell.setTextAndCheck("Включение AI", prefs.getBoolean("ai_enabled", false), false);
                        break;
                    case ROW_AI_SUMMARY:
                        cell.setTextAndCheck("Краткая Сводка", prefs.getBoolean("ai_summary", false), true);
                        break;
                    case ROW_AI_RETELL:
                        cell.setTextAndCheck("Пересказ сообщений", prefs.getBoolean("ai_retell", false), true);
                        break;
                    case ROW_AI_EDITOR:
                        cell.setTextAndCheck("AI-редактор", prefs.getBoolean("ai_editor", false), true);
                        break;
                }
            }
        }
    }
}
