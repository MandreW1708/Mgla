package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class MglaAiTranscribeActivity extends BaseFragment {

    private static final int ROW_ENABLED = 0;
    private static final int ROW_API_KEY = 1;
    private static final int ROW_MODEL = 2;
    private static final int ROW_FALLBACK = 3;
    private static final int ROW_INFO = 4;
    private static final int ROW_COUNT = 5;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_TEXT = 1;
    private static final int VIEW_TYPE_INFO = 2;

    private SharedPreferences prefs;
    private RecyclerListView listView;

    public MglaAiTranscribeActivity() {
        this(null);
    }

    public MglaAiTranscribeActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        prefs = context.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("ИИ-расшифровка");
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
            if (position == ROW_ENABLED) {
                toggleSwitch(view, "ai_transcribe_enabled", false);
            } else if (position == ROW_API_KEY) {
                showTextInputDialog("API ключ Gemini", "ai_transcribe_api_key", "sk-...", true);
            } else if (position == ROW_MODEL) {
                showTextInputDialog("Модель", "ai_transcribe_model", "gemini-2.0-flash", false);
            } else if (position == ROW_FALLBACK) {
                toggleSwitch(view, "ai_transcribe_fallback_telegram", true);
            }
        });

        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void toggleSwitch(View view, String key, boolean defaultValue) {
        boolean enabled = !prefs.getBoolean(key, defaultValue);
        prefs.edit().putBoolean(key, enabled).apply();
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(enabled);
        }
    }

    private void showTextInputDialog(String title, String key, String hint, boolean multiline) {
        if (getParentActivity() == null) {
            return;
        }
        EditText editText = new EditText(getParentActivity());
        editText.setText(prefs.getString(key, ""));
        editText.setHint(hint);
        editText.setSingleLine(!multiline);
        editText.setMaxLines(multiline ? 4 : 1);
        editText.setSelectAllOnFocus(false);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        editText.setSelection(editText.length());

        LinearLayout inputWrap = new LinearLayout(getParentActivity());
        inputWrap.setPadding(dp(24), dp(4), dp(24), 0);
        inputWrap.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, multiline ? 96 : 48));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setView(inputWrap);
        builder.setPositiveButton("OK", (dialog, which) -> {
            prefs.edit().putString(key, editText.getText().toString().trim()).apply();
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyDataSetChanged();
            }
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }

    private String getValuePreview(String key, String emptyValue) {
        String value = prefs.getString(key, "");
        if (value == null || value.trim().isEmpty()) {
            return emptyValue;
        }
        value = value.trim();
        if ("ai_transcribe_api_key".equals(key) && value.length() > 10) {
            return value.substring(0, 6) + "..." + value.substring(value.length() - 4);
        } else if (value.length() > 18) {
            return value.substring(0, 18) + "...";
        }
        return value;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == ROW_ENABLED || position == ROW_API_KEY || position == ROW_MODEL || position == ROW_FALLBACK;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_API_KEY || position == ROW_MODEL) {
                return VIEW_TYPE_TEXT;
            } else if (position == ROW_INFO) {
                return VIEW_TYPE_INFO;
            }
            return VIEW_TYPE_CHECK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_TEXT) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_INFO) {
                view = new TextInfoPrivacyCell(context);
            } else {
                view = new TextCheckCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == ROW_ENABLED) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck("Включить расшифровку", prefs.getBoolean("ai_transcribe_enabled", false), true);
            } else if (position == ROW_API_KEY) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("API ключ Gemini", getValuePreview("ai_transcribe_api_key", "Не задан"), true);
                cell.setCanDisable(false);
            } else if (position == ROW_MODEL) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Модель", getValuePreview("ai_transcribe_model", "gemini-2.0-flash"), true);
                cell.setCanDisable(false);
            } else if (position == ROW_FALLBACK) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck("Fallback на Telegram", prefs.getBoolean("ai_transcribe_fallback_telegram", true), false);
            } else if (position == ROW_INFO) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText("При включении голосовые и видеосообщения будут расшифровываться через Gemini в отдельном окне. Если fallback включён, при ошибке Gemini будет использована обычная расшифровка Telegram.");
            }
        }
    }
}
