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
        EditText apiKeyEdit = createEditRow(block, "API ключ Gemini", "ai_transcribe_api_key", true);

        // Модель
        EditText modelEdit = createEditRow(block, "Модель", "ai_transcribe_model", false);

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        // Подсказка
        TextView hint = new TextView(context);
        hint.setText("При включении голосовые сообщения будут расшифровываться через Gemini (даже если у вас нет Telegram Premium)");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setPadding(dp(18), dp(8), dp(18), 0);
        rootLayout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scrollView.addView(rootLayout);
        fragmentView = scrollView;
        return fragmentView;
    }

    private EditText createEditRow(LinearLayout block, String hint, String key, boolean divider) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(21), dp(2), dp(21), dp(2));
        row.setMinimumHeight(dp(48));

        TextView label = new TextView(getContext());
        label.setText(hint);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 2, 0));

        EditText editText = new EditText(getContext());
        editText.setText(prefs.getString(key, ""));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setHint("Введите " + hint.toLowerCase());
        editText.setSingleLine(true);
        editText.setMinHeight(0);
        editText.setMinimumHeight(0);
        editText.setBackground(Theme.createRoundRectDrawable(dp(6), Theme.getColor(Theme.key_windowBackgroundGray)));
        editText.setPadding(dp(10), dp(3), dp(10), dp(3));
        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(key, s.toString()).apply();
            }
        });
        row.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 26));

        // Divider
        if (divider) {
            View div = new View(getContext());
            div.setBackgroundColor(Theme.getColor(Theme.key_divider));
            row.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 8, 0, 0));
        }

        block.addView(row);
        return editText;
    }
}
