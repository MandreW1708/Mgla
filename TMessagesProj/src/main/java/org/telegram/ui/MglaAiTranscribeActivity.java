package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

public class MglaAiTranscribeActivity extends BaseFragment {

    private SharedPreferences prefs;

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

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // === Блок: настройки ===
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        block.setBackground(bg);

        // Переключатель включения
        TextCheckCell enableCell = new TextCheckCell(context);
        enableCell.setBackground(null);
        enableCell.setTextAndCheck("Включить расшифровку", prefs.getBoolean("ai_transcribe_enabled", false), true);
        enableCell.setOnClickListener(v -> {
            boolean newVal = !prefs.getBoolean("ai_transcribe_enabled", false);
            prefs.edit().putBoolean("ai_transcribe_enabled", newVal).apply();
            enableCell.setChecked(newVal);
        });
        block.addView(enableCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // API ключ
        EditText apiKeyEdit = createEditRow(block, "API ключ Gemini", "ai_transcribe_api_key", "sk-...", true);

        // Модель
        EditText modelEdit = createEditRow(block, "Модель", "ai_transcribe_model", "gemini-2.0-flash", false);

        View fallbackDivider = new View(context);
        fallbackDivider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        block.addView(fallbackDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 21, 4, 21, 0));

        TextCheckCell fallbackCell = new TextCheckCell(context);
        fallbackCell.setBackground(null);
        fallbackCell.setTextAndCheck("Fallback на Telegram", prefs.getBoolean("ai_transcribe_fallback_telegram", true), false);
        fallbackCell.setOnClickListener(v -> {
            boolean newVal = !prefs.getBoolean("ai_transcribe_fallback_telegram", true);
            prefs.edit().putBoolean("ai_transcribe_fallback_telegram", newVal).apply();
            fallbackCell.setChecked(newVal);
        });
        block.addView(fallbackCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        // Подсказка
        TextView hint = new TextView(context);
        hint.setText("При включении голосовые и видеосообщения будут расшифровываться через Gemini в отдельном окне. Если fallback включён, при ошибке Gemini будет использована обычная расшифровка Telegram.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(dp(18), dp(8), dp(18), 0);
        rootLayout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scrollView.addView(rootLayout);
        fragmentView = scrollView;
        return fragmentView;
    }

    private EditText createEditRow(LinearLayout block, String title, String key, String placeholder, boolean multiline) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(6), dp(16), dp(10));

        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        LinearLayout fieldBox = new LinearLayout(getContext());
        fieldBox.setOrientation(LinearLayout.VERTICAL);
        fieldBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setCornerRadius(dp(12));
        fieldBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fieldBox.setBackground(fieldBg);

        TextView hintView = new TextView(getContext());
        hintView.setText(multiline ? "Вставьте ключ Gemini API" : "Название модели Gemini");
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        fieldBox.addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        EditText editText = new EditText(getContext());
        editText.setText(prefs.getString(key, ""));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setHint(placeholder);
        editText.setSingleLine(!multiline);
        editText.setMaxLines(multiline ? 3 : 1);
        editText.setMinHeight(0);
        editText.setMinimumHeight(0);
        editText.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, 0);
        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(key, s.toString()).apply();
            }
        });
        fieldBox.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, multiline ? 58 : 28));
        row.addView(fieldBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        block.addView(row);
        return editText;
    }
}
