package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

public class MglaMainSettingsActivity extends BaseFragment {

    private SharedPreferences prefs;
    private TextView effectValueView;

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
        actionBar.setTitle("Основные настройки");
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

        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        block.setBackground(bg);
        block.setClipToOutline(true);
        block.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        // Прокси в шапке
        TextCheckCell proxyCell = new TextCheckCell(context);
        proxyCell.setBackground(null);
        proxyCell.setTextAndCheck("Прокси в шапке", prefs.getBoolean("proxy_in_header", false), true);
        proxyCell.setOnClickListener(v -> {
            boolean newVal = !prefs.getBoolean("proxy_in_header", false);
            prefs.edit().putBoolean("proxy_in_header", newVal).apply();
            proxyCell.setChecked(newVal);
        });
        block.addView(proxyCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Виброотклик
        TextCheckCell hapticCell = new TextCheckCell(context);
        hapticCell.setBackground(null);
        hapticCell.setTextAndCheck("Виброотклик", prefs.getBoolean("haptic_enabled", false), true);
        hapticCell.setOnClickListener(v -> {
            boolean newVal = !prefs.getBoolean("haptic_enabled", false);
            prefs.edit().putBoolean("haptic_enabled", newVal).apply();
            hapticCell.setChecked(newVal);
        });
        block.addView(hapticCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Эффект вибрации
        int effect = prefs.getInt("haptic_effect", MglaHapticManager.EFFECT_LIGHT);
        addSelectRow(block, "Эффект вибрации", MglaHapticManager.EFFECT_NAMES[effect], () -> {
            AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
            dlg.setTitle("Эффект вибрации");
            dlg.setItems(MglaHapticManager.EFFECT_NAMES, (dialog, which) -> {
                prefs.edit().putInt("haptic_effect", which).apply();
                if (effectValueView != null) {
                    effectValueView.setText(MglaHapticManager.EFFECT_NAMES[which]);
                }
            });
            showDialog(dlg.create());
        });

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        fragmentView = rootLayout;
        return fragmentView;
    }

    private void addSelectRow(LinearLayout block, String title, String value, Runnable onClick) {
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

        effectValueView = new TextView(getContext());
        effectValueView.setText(value);
        effectValueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        effectValueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(effectValueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        block.addView(row);
    }
}
