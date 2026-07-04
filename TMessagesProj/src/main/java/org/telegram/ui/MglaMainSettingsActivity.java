package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaSpyConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

public class MglaMainSettingsActivity extends BaseFragment {

    private SharedPreferences prefs;

    public MglaMainSettingsActivity() {
        this(null);
    }

    public MglaMainSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        prefs = context.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Общие настройки");
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
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        LinearLayout block = createBlock(context, "Базовые");

        TextCheckCell proxyCell = new TextCheckCell(context);
        proxyCell.setBackground(null);
        proxyCell.setTextAndCheck("Прокси в шапке", prefs.getBoolean("proxy_in_header", false), true);
        proxyCell.setOnClickListener(v -> {
            boolean newVal = !prefs.getBoolean("proxy_in_header", false);
            prefs.edit().putBoolean("proxy_in_header", newVal).apply();
            proxyCell.setChecked(newVal);
        });
        block.addView(proxyCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckCell hapticCell = new TextCheckCell(context);
        hapticCell.setBackground(null);
        hapticCell.setTextAndCheck("Виброотклик", prefs.getBoolean("haptic_enabled", false), true);
        hapticCell.setOnClickListener(v -> {
            boolean newVal = !prefs.getBoolean("haptic_enabled", false);
            prefs.edit().putBoolean("haptic_enabled", newVal).apply();
            hapticCell.setChecked(newVal);
        });
        block.addView(hapticCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView[] strengthValueRef = new TextView[1];
        addSelectRow(block, "Сила вибрации", MglaHapticManager.STRENGTH_NAMES[MglaHapticManager.getStrength()], () -> {
            showStrengthDialog(strengthValueRef[0]);
        }, strengthValueRef);

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        LinearLayout spyBlock = createBlock(context, "Шпион");

        TextCheckCell saveDeletedCell = new TextCheckCell(context);
        saveDeletedCell.setBackground(null);
        saveDeletedCell.setTextAndCheck("Сохранение удаленных", MglaSpyConfig.isSaveDeletedMessagesEnabled(), true);
        saveDeletedCell.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isSaveDeletedMessagesEnabled();
            MglaSpyConfig.setSaveDeletedMessagesEnabled(newVal);
            saveDeletedCell.setChecked(newVal);
        });
        spyBlock.addView(saveDeletedCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckCell ghostModeCell = new TextCheckCell(context);
        ghostModeCell.setBackground(null);
        ghostModeCell.setTextAndCheck("Режим призрака", MglaSpyConfig.isGhostModeEnabled(), false);
        ghostModeCell.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isGhostModeEnabled();
            MglaSpyConfig.setGhostModeEnabled(newVal);
            ghostModeCell.setChecked(newVal);
        });
        spyBlock.addView(ghostModeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(spyBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, AndroidUtilities.navigationBarHeight + 16));

        fragmentView = scrollView;
        return fragmentView;
    }

    private LinearLayout createBlock(Context context, String title) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        block.setBackground(bg);
        block.setClipToOutline(true);
        block.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        HeaderCell header = new HeaderCell(context, 22);
        header.setBackground(null);
        header.setText(title);
        block.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return block;
    }

    private void showStrengthDialog(TextView valueView) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Сила вибрации");
        dlg.setItems(MglaHapticManager.STRENGTH_NAMES, (dialog, which) -> {
            prefs.edit().putInt("haptic_strength", which).apply();
            if (valueView != null) {
                valueView.setText(MglaHapticManager.STRENGTH_NAMES[which]);
            }
            MglaHapticManager.previewPattern(MglaHapticManager.getPatternForAction(MglaHapticManager.ACTION_DEFAULT));
        });
        showDialog(dlg.create());
    }

    private void addSelectRow(LinearLayout block, String title, String value, Runnable onClick, TextView[] valueRef) {
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

        TextView valueView = new TextView(getContext());
        valueView.setText(value);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        if (valueRef != null) {
            valueRef[0] = valueView;
        }

        block.addView(row);
    }

    private void addSelectRow(LinearLayout block, String title, String value, Runnable onClick) {
        addSelectRow(block, title, value, onClick, null);
    }
}
