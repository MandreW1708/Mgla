package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
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

        FrameLayout rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // Белый блок со скруглениями
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable blockBg = new GradientDrawable();
        blockBg.setCornerRadius(dp(10));
        blockBg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        block.setBackground(blockBg);

        // Стандартная телеграм-ячейка: текст + переключатель
        TextCheckCell cell = new TextCheckCell(context);
        cell.setBackground(null);
        cell.setTextAndCheck("Включение AI", prefs.getBoolean("ai_enabled", false), false);
        cell.setOnClickListener(v -> {
            boolean newValue = !prefs.getBoolean("ai_enabled", false);
            prefs.edit().putBoolean("ai_enabled", newValue).apply();
            cell.setChecked(newValue);
        });
        block.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, dp(50)));

        rootLayout.addView(block, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 16, 8, 16, 0));

        fragmentView = rootLayout;
        return fragmentView;
    }
}
