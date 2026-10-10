package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import org.telegram.messenger.MglaFeatureFlags;
import org.telegram.messenger.MglaWhisperModelsManager;
import org.telegram.messenger.MglaWhisperNative;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class MglaAiTranscribeActivity extends MglaBaseSettingsActivity {

    private static final int ROW_ENABLED = 0;
    private static final int ROW_SHADOW_0 = 1;

    private static final int ROW_GEMINI = 2;
    private static final int ROW_API_KEY = 3;
    private static final int ROW_GEMINI_MODEL = 4;
    private static final int ROW_SHADOW_1 = 5;

    private static final int ROW_WHISPER = 6;
    private static final int ROW_WHISPER_MODEL = 7;
    private static final int ROW_WHISPER_LANG = 8;
    private static final int ROW_WHISPER_STATUS = 9;
    private static final int ROW_SHADOW_2 = 10;

    private static final int ROW_FALLBACK = 11;
    private static final int ROW_INFO = 12;
    private static final int ROW_COUNT = 13;

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_TEXT = 1;
    private static final int VIEW_TYPE_INFO = 2;
    private static final int VIEW_TYPE_RADIO = 3;
    private static final int VIEW_TYPE_SHADOW = 4;

    private static final float ALPHA_ACTIVE = 1f;
    private static final float ALPHA_DIMMED = 0.38f;

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
        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            private final Paint dividerPaint = new Paint();

            {
                dividerPaint.setColor(Theme.getColor(Theme.key_divider));
            }

            @Override
            public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    int position = parent.getChildAdapterPosition(child);
                    if (position != ROW_GEMINI && position != ROW_API_KEY
                            && position != ROW_WHISPER && position != ROW_WHISPER_MODEL
                            && position != ROW_WHISPER_LANG) {
                        continue;
                    }
                    float bottom = child.getBottom() + child.getTranslationY();
                    c.drawRect(child.getLeft() + dp(21), bottom - dp(1), child.getRight() - dp(21), bottom, dividerPaint);
                }
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position == ROW_ENABLED) {
                toggleSwitch(view, "ai_transcribe_enabled", false);
            } else if (position == ROW_GEMINI) {
                selectProvider(MglaWhisperModelsManager.PROVIDER_GEMINI);
            } else if (position == ROW_WHISPER) {
                selectProvider(MglaWhisperModelsManager.PROVIDER_WHISPER);
            } else if (position == ROW_API_KEY) {
                if (isWhisper()) {
                    return;
                }
                showTextInputDialog("API ключ Gemini", "ai_transcribe_api_key", "AIza...", true);
            } else if (position == ROW_GEMINI_MODEL) {
                if (isWhisper()) {
                    return;
                }
                showTextInputDialog("Модель Gemini", "ai_transcribe_model", "gemini-2.0-flash", false);
            } else if (position == ROW_WHISPER_MODEL) {
                if (!isWhisper()) {
                    return;
                }
                presentFragment(new MglaWhisperModelsActivity());
            } else if (position == ROW_WHISPER_LANG) {
                if (!isWhisper()) {
                    return;
                }
                showLanguageDialog();
            } else if (position == ROW_FALLBACK) {
                toggleSwitch(view, "ai_transcribe_fallback_telegram", true);
            }
        });

        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
    }

    private void selectProvider(String provider) {
        if (MglaWhisperModelsManager.PROVIDER_WHISPER.equals(provider) && !MglaWhisperNative.isAvailable()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Библиотека Whisper не собрана для этой сборки").show();
            return;
        }
        if (provider.equals(MglaWhisperModelsManager.getProvider())) {
            return;
        }
        MglaWhisperModelsManager.setProvider(provider);
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
    }

    private void toggleSwitch(View view, String key, boolean defaultValue) {
        boolean enabled = !prefs.getBoolean(key, defaultValue);
        if (enabled && "ai_transcribe_enabled".equals(key) && !MglaFeatureFlags.isAllowed("ai_transcribe_enabled")) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(false);
            }
            return;
        }
        prefs.edit().putBoolean(key, enabled).apply();
        if (view instanceof TextCheckCell) {
            boolean shown = enabled;
            if ("ai_transcribe_enabled".equals(key)) {
                shown = enabled && MglaFeatureFlags.isAllowed("ai_transcribe_enabled");
            }
            ((TextCheckCell) view).setChecked(shown);
        }
    }

    private void showLanguageDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final String[] names = {"Автоопределение", "Русский", "English", "Українська", "Беларуская", "Қазақша"};
        final String[] values = {"auto", "ru", "en", "uk", "be", "kk"};
        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Язык Whisper");
        dlg.setItems(names, (dialog, which) -> {
            MglaWhisperModelsManager.setLanguage(values[which]);
            if (listView != null && listView.getAdapter() != null) {
                listView.getAdapter().notifyDataSetChanged();
            }
        });
        showDialog(dlg.create());
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

    private boolean isWhisper() {
        return MglaWhisperModelsManager.PROVIDER_WHISPER.equals(MglaWhisperModelsManager.getProvider());
    }

    private boolean isGeminiBlock(int position) {
        return position == ROW_GEMINI || position == ROW_API_KEY || position == ROW_GEMINI_MODEL;
    }

    private boolean isWhisperBlock(int position) {
        return position == ROW_WHISPER || position == ROW_WHISPER_MODEL
            || position == ROW_WHISPER_LANG || position == ROW_WHISPER_STATUS;
    }

    private void applyBlockAlpha(View view, int position) {
        boolean whisper = isWhisper();
        if (isGeminiBlock(position)) {
            view.setAlpha(whisper ? ALPHA_DIMMED : ALPHA_ACTIVE);
        } else if (isWhisperBlock(position)) {
            view.setAlpha(whisper ? ALPHA_ACTIVE : ALPHA_DIMMED);
        } else {
            view.setAlpha(ALPHA_ACTIVE);
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
            if (position == ROW_ENABLED || position == ROW_FALLBACK
                    || position == ROW_GEMINI || position == ROW_WHISPER) {
                return true;
            }
            if (isWhisper()) {
                return position == ROW_WHISPER_MODEL || position == ROW_WHISPER_LANG;
            }
            return position == ROW_API_KEY || position == ROW_GEMINI_MODEL;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_SHADOW_0 || position == ROW_SHADOW_1 || position == ROW_SHADOW_2) {
                return VIEW_TYPE_SHADOW;
            } else if (position == ROW_GEMINI || position == ROW_WHISPER) {
                return VIEW_TYPE_RADIO;
            } else if (position == ROW_API_KEY || position == ROW_GEMINI_MODEL
                    || position == ROW_WHISPER_MODEL || position == ROW_WHISPER_LANG
                    || position == ROW_WHISPER_STATUS) {
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
            if (viewType == VIEW_TYPE_SHADOW) {
                view = new ShadowSectionCell(context);
            } else if (viewType == VIEW_TYPE_TEXT) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_RADIO) {
                view = new RadioCell(context);
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
            boolean whisper = isWhisper();
            if (position == ROW_ENABLED) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck("Включить расшифровку",
                    MglaFeatureFlags.isAllowed("ai_transcribe_enabled") && prefs.getBoolean("ai_transcribe_enabled", false), false);
            } else if (position == ROW_GEMINI) {
                RadioCell cell = (RadioCell) holder.itemView;
                cell.setText("Gemini (облако)", !whisper, true);
            } else if (position == ROW_API_KEY) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("API ключ", getValuePreview("ai_transcribe_api_key", "Не задан"), true);
                cell.setCanDisable(false);
            } else if (position == ROW_GEMINI_MODEL) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Модель", getValuePreview("ai_transcribe_model", "gemini-2.0-flash"), false);
                cell.setCanDisable(false);
            } else if (position == ROW_WHISPER) {
                RadioCell cell = (RadioCell) holder.itemView;
                cell.setText("Локальный Whisper", whisper, true);
            } else if (position == ROW_WHISPER_MODEL) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Модель", MglaWhisperModelsManager.getSelectedModelTitle(), true);
                cell.setCanDisable(false);
            } else if (position == ROW_WHISPER_LANG) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue("Язык", MglaWhisperModelsManager.getLanguageTitle(), true);
                cell.setCanDisable(false);
            } else if (position == ROW_WHISPER_STATUS) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                String status = MglaWhisperNative.isAvailable() ? "Готов" : "Библиотека недоступна";
                cell.setTextAndValue("Движок", status, false);
                cell.setCanDisable(false);
            } else if (position == ROW_FALLBACK) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck("Fallback на Telegram", prefs.getBoolean("ai_transcribe_fallback_telegram", true), false);
            } else if (position == ROW_INFO) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (whisper) {
                    cell.setText("Голосовые и видеосообщения расшифровываются на устройстве через Whisper. Нужна скачанная модель — данные никуда не отправляются. Если fallback включён, при ошибке используется расшифровка Telegram.");
                } else {
                    cell.setText("Голосовые и видеосообщения расшифровываются через Gemini в отдельном окне. Если fallback включён, при ошибке используется обычная расшифровка Telegram.");
                }
            }
            applyBlockAlpha(holder.itemView, position);
        }
    }
}
