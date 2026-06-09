package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MglaAiSettingsActivity extends BaseFragment {

    private SharedPreferences prefs;

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
        actionBar.setTitle("AI");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // === Блок 1: Включение AI ===
        LinearLayout block1 = createBlock(context);
        addSwitchRow(block1, "Включение AI", "ai_enabled", false);
        rootLayout.addView(block1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        // === Блок 2: дополнительные функции ===
        LinearLayout block2 = createBlock(context);
        addSwitchRow(block2, "Краткая Сводка", "ai_summary", true);
        addSwitchRow(block2, "Пересказ сообщений", "ai_retell", true);
        addSwitchRow(block2, "AI-редактор", "ai_editor", true);
        addLimitRow(block2, "Лимит AI-редактора", 50);
        rootLayout.addView(block2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        // === Блок 3: ИИ-расшифровка ===
        LinearLayout block3 = createBlock(context);
        addNavRow(block3, "ИИ-расшифровка", () -> presentFragment(new MglaAiTranscribeActivity()));
        rootLayout.addView(block3, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        fragmentView = rootLayout;
        return fragmentView;
    }

    private LinearLayout createBlock(Context context) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        block.setBackground(bg);
        return block;
    }

    private void addSwitchRow(LinearLayout block, String title, String key, boolean divider) {
        TextCheckCell cell = new TextCheckCell(getContext());
        cell.setBackground(null);
        cell.setTextAndCheck(title, prefs.getBoolean(key, false), divider);
        cell.setOnClickListener(v -> {
            boolean newValue = !prefs.getBoolean(key, false);
            prefs.edit().putBoolean(key, newValue).apply();
            cell.setChecked(newValue);
        });
        block.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void addLimitRow(LinearLayout block, String title, int maxRequests) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String savedDate = prefs.getString("ai_editor_date", "");
        int used = today.equals(savedDate) ? prefs.getInt("ai_editor_count", 0) : 0;
        int remaining = Math.max(0, maxRequests - used);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(21), 0, dp(21), dp(4));
        row.setMinimumHeight(dp(50));

        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL));

        TextView valueView = new TextView(getContext());
        valueView.setText(used + " / " + maxRequests);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        block.addView(row);
    }

    private void addNavRow(LinearLayout block, String title, Runnable onClick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(21), 0, dp(18), 0);
        row.setMinimumHeight(dp(50));
        row.setClickable(true);
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 0));
        row.setOnClickListener(v -> onClick.run());

        TextView titleView = new TextView(getContext());
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL));

        TextView arrow = new TextView(getContext());
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        arrow.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        arrow.setGravity(Gravity.CENTER);
        arrow.setIncludeFontPadding(false);
        row.addView(arrow, LayoutHelper.createLinear(dp(24), dp(24), Gravity.CENTER_VERTICAL));

        block.addView(row);
    }
}
