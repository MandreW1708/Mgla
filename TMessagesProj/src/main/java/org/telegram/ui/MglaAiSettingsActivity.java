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
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

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
    private static final int ROW_PROVIDER_HEADER = 9;
    private static final int ROW_PROVIDER_BASIC = 10;
    private static final int ROW_PROVIDER_GEMINI = 11;
    private static final int ROW_SHADOW_4 = 12;
    private static final int ROW_COUNT = 13;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_TEXT = 1;
    private static final int VIEW_TYPE_SHADOW = 2;
    private static final int VIEW_TYPE_HEADER = 3;
    private static final int VIEW_TYPE_RADIO = 4;

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
            } else if (position == ROW_PROVIDER_BASIC) {
                if (!"openrouter".equals(prefs.getString("ai_provider", "openrouter"))) {
                    prefs.edit().putString("ai_provider", "openrouter").apply();
                    if (listView.getAdapter() != null) {
                        listView.getAdapter().notifyDataSetChanged();
                    }
                }
            } else if (position == ROW_PROVIDER_GEMINI) {
                if (!"gemini".equals(prefs.getString("ai_provider", "openrouter"))) {
                    prefs.edit().putString("ai_provider", "gemini").apply();
                    if (listView.getAdapter() != null) {
                        listView.getAdapter().notifyDataSetChanged();
                    }
                }
            } else {
                String key = getSwitchKey(position);
                if (key != null) {
                    boolean enabled = !prefs.getBoolean(key, true);
                    prefs.edit().putBoolean(key, enabled).apply();
                    if ("ai_summary".equals(key)) {
                        MglaMessageMenuController.setEnabled(context, ChatActivity.OPTION_AI_SUMMARY, enabled);
                    }
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

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyItemChanged(ROW_AI_EDITOR_LIMIT);
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
            return position == ROW_AI_ENABLED || position == ROW_AI_SUMMARY || position == ROW_AI_RETELL || position == ROW_AI_EDITOR || position == ROW_AI_TRANSCRIBE || position == ROW_PROVIDER_BASIC || position == ROW_PROVIDER_GEMINI;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_SHADOW_1 || position == ROW_SHADOW_2 || position == ROW_SHADOW_3 || position == ROW_SHADOW_4) {
                return VIEW_TYPE_SHADOW;
            } else if (position == ROW_AI_EDITOR_LIMIT || position == ROW_AI_TRANSCRIBE) {
                return VIEW_TYPE_TEXT;
            } else if (position == ROW_PROVIDER_HEADER) {
                return VIEW_TYPE_HEADER;
            } else if (position == ROW_PROVIDER_BASIC || position == ROW_PROVIDER_GEMINI) {
                return VIEW_TYPE_RADIO;
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
            } else if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(context, 22);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_RADIO) {
                view = new RadioCell(context);
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
                cell.setTextAndValue("Лимит запросов к AI", AiAssistant.getUsageLabel(), false);
                cell.setCanDisable(false);
            } else if (position == ROW_AI_TRANSCRIBE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setText("ИИ-расшифровка", false);
                cell.setCanDisable(false);
            } else if (holder.itemView instanceof HeaderCell) {
                ((HeaderCell) holder.itemView).setText("Провайдер AI");
            } else if (holder.itemView instanceof RadioCell) {
                RadioCell cell = (RadioCell) holder.itemView;
                String provider = prefs.getString("ai_provider", "openrouter");
                if (position == ROW_PROVIDER_BASIC) {
                    cell.setText("Базовый", "openrouter".equals(provider), true);
                } else if (position == ROW_PROVIDER_GEMINI) {
                    cell.setText("Gemini", "gemini".equals(provider), false);
                }
            } else if (holder.itemView instanceof TextCheckCell) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                switch (position) {
                    case ROW_AI_ENABLED:
                        cell.setTextAndCheck("Включение AI", prefs.getBoolean("ai_enabled", true), false);
                        break;
                    case ROW_AI_SUMMARY:
                        cell.setTextAndCheck("Краткая Сводка", prefs.getBoolean("ai_summary", true), true);
                        break;
                    case ROW_AI_RETELL:
                        cell.setTextAndCheck("Пересказ сообщений", prefs.getBoolean("ai_retell", true), true);
                        break;
                    case ROW_AI_EDITOR:
                        cell.setTextAndCheck("AI-редактор", prefs.getBoolean("ai_editor", true), true);
                        break;
                }
            }
        }
    }
}
