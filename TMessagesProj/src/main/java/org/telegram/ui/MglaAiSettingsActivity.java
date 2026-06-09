package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

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
        addSwitchRow(block2, "AI-редактор", "ai_editor", false);
        rootLayout.addView(block2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

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
}
