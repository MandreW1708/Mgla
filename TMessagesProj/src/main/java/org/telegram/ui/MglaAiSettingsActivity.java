package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaChatDna;
import org.telegram.messenger.MglaLocalModelsManager;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
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
    private static final int ROW_DNA_HEADER = 13;
    private static final int ROW_DNA_TOPICS = 14;
    private static final int ROW_DNA_MODELS = 15;
    private static final int ROW_DNA_ENGINE = 16;
    private static final int ROW_COUNT = 17;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_TEXT = 1;
    private static final int VIEW_TYPE_SHADOW = 2;
    private static final int VIEW_TYPE_HEADER = 3;
    private static final int VIEW_TYPE_RADIO = 4;
    private static final int VIEW_TYPE_DNA_ENGINE = 5;

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
            } else if (position == ROW_DNA_TOPICS) {
                showDnaTopicsProviderDialog(context);
            } else if (position == ROW_DNA_MODELS) {
                presentFragment(new MglaLocalModelsActivity());
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

    /** Выбор нейросети для генерации главных тем Chat DNA. */
    private void showDnaTopicsProviderDialog(Context context) {
        if (getParentActivity() == null) {
            return;
        }
        String current = MglaChatDna.getTopicsProvider();
        String[] names = {"Gemini (Ваш API)", "Локальная модель"};
        String[] values = {MglaChatDna.PROVIDER_GEMINI, MglaChatDna.PROVIDER_LOCAL};
        int checked = MglaChatDna.PROVIDER_GEMINI.equals(current) ? 0 : 1;

        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Главные темы Chat DNA");
        dlg.setItems(names, (dialog, which) -> {
            MglaChatDna.setTopicsProvider(values[which]);
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyDataSetChanged();
            }
        });
        showDialog(dlg.create());
    }

    private String modelSummary() {
        String id = MglaLocalModelsManager.getSelectedModelId();
        return id.isEmpty() ? "Не выбрана" : id;
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
            return position == ROW_AI_ENABLED || position == ROW_AI_SUMMARY || position == ROW_AI_RETELL || position == ROW_AI_EDITOR || position == ROW_AI_TRANSCRIBE || position == ROW_PROVIDER_BASIC || position == ROW_PROVIDER_GEMINI || position == ROW_DNA_TOPICS || position == ROW_DNA_MODELS;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_SHADOW_1 || position == ROW_SHADOW_2 || position == ROW_SHADOW_3 || position == ROW_SHADOW_4) {
                return VIEW_TYPE_SHADOW;
            } else if (position == ROW_AI_EDITOR_LIMIT || position == ROW_AI_TRANSCRIBE || position == ROW_DNA_TOPICS || position == ROW_DNA_MODELS) {
                return VIEW_TYPE_TEXT;
            } else if (position == ROW_PROVIDER_HEADER || position == ROW_DNA_HEADER) {
                return VIEW_TYPE_HEADER;
            } else if (position == ROW_PROVIDER_BASIC || position == ROW_PROVIDER_GEMINI) {
                return VIEW_TYPE_RADIO;
            } else if (position == ROW_DNA_ENGINE) {
                return VIEW_TYPE_DNA_ENGINE;
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
            } else if (viewType == VIEW_TYPE_DNA_ENGINE) {
                view = new DnaEngineCell(context);
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
            } else if (position == ROW_DNA_TOPICS) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Главные темы Chat DNA", MglaChatDna.getTopicsProviderTitle(), true);
                cell.setCanDisable(false);
            } else if (position == ROW_DNA_MODELS) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Локальные модели", modelSummary(), false);
                cell.setCanDisable(false);
            } else if (position == ROW_DNA_ENGINE) {
                ((DnaEngineCell) holder.itemView).bind();
            } else if (holder.itemView instanceof HeaderCell) {
                ((HeaderCell) holder.itemView).setText(position == ROW_DNA_HEADER ? "Chat DNA" : "Провайдер AI");
            } else if (holder.itemView instanceof RadioCell) {
                RadioCell cell = (RadioCell) holder.itemView;
                String provider = prefs.getString("ai_provider", "openrouter");
                if (position == ROW_PROVIDER_BASIC) {
                    cell.setText("Базовый", "openrouter".equals(provider), true);
                } else if (position == ROW_PROVIDER_GEMINI) {
                    cell.setText("Gemini (Ваш API)", "gemini".equals(provider), false);
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

    /** Two equally sized choices; GGUF is opt-in because it temporarily uses model RAM. */
    private class DnaEngineCell extends FrameLayout {
        private final TextView dictionaryButton;
        private final TextView ggufButton;

        DnaEngineCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(21), dp(8), dp(21), dp(12));
            addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView title = new TextView(context);
            title.setText("Режим локального анализа");
            title.setTextSize(16);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            content.addView(title, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(android.view.Gravity.CENTER_VERTICAL);
            content.addView(buttons, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, dp(40)) {{ topMargin = dp(8); }});

            dictionaryButton = createEngineButton(context, "Словарь");
            ggufButton = createEngineButton(context, "GGUF-рантайм");
            buttons.addView(dictionaryButton, new LinearLayout.LayoutParams(0, dp(40), 1f));
            LinearLayout.LayoutParams ggufParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
            ggufParams.leftMargin = dp(8);
            buttons.addView(ggufButton, ggufParams);

            dictionaryButton.setOnClickListener(v -> selectEngine(MglaChatDna.LOCAL_ENGINE_DICTIONARY));
            ggufButton.setOnClickListener(v -> {
                if (MglaLocalModelsManager.isGgufSupported(context)) {
                    selectEngine(MglaChatDna.LOCAL_ENGINE_GGUF);
                }
            });
        }

        private TextView createEngineButton(Context context, String text) {
            TextView button = new TextView(context);
            button.setText(text);
            button.setTextSize(14);
            button.setGravity(android.view.Gravity.CENTER);
            button.setClickable(true);
            return button;
        }

        private void selectEngine(String engine) {
            MglaChatDna.setLocalEngine(engine);
            bind();
        }

        void bind() {
            boolean supported = MglaLocalModelsManager.isGgufSupported(getContext());
            boolean gguf = supported && MglaChatDna.isGgufRuntimeSelected();
            styleEngineButton(dictionaryButton, !gguf);
            styleEngineButton(ggufButton, gguf);
            ggufButton.setEnabled(supported);
            ggufButton.setAlpha(supported ? 1f : 0.45f);
            ggufButton.setText(supported ? "GGUF-рантайм" : "GGUF (нет на устройстве)");
        }

        private void styleEngineButton(TextView button, boolean selected) {
            int color = Theme.getColor(selected ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText);
            button.setTextColor(selected ? Theme.getColor(Theme.key_windowBackgroundWhite) : color);
            button.setBackground(Theme.createRoundRectDrawable(dp(8), selected ? color : Theme.getColor(Theme.key_windowBackgroundGray)));
        }
    }
}
