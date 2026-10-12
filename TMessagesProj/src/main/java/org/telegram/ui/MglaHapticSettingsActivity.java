package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaFeatureFlags;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

public class MglaHapticSettingsActivity extends MglaBaseSettingsActivity {

    private TextCheckCell enabledCell;
    private TextView strengthValueView;
    private TextView defaultPatternValueView;
    private final TextView[] actionValueViews = new TextView[MglaHapticManager.CUSTOMIZABLE_ACTIONS.length];
    private LinearLayout optionsBlock;
    private LinearLayout actionsBlock;

    public MglaHapticSettingsActivity() {
        this(null);
    }

    public MglaHapticSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Виброэффекты");
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

        LinearLayout mainBlock = MglaUi.createBlock(context, "Основные");

        enabledCell = new TextCheckCell(context);
        enabledCell.setBackground(null);
        enabledCell.setTextAndCheck("Виброотклик", MglaHapticManager.isEnabled(), false);
        enabledCell.setOnClickListener(v -> {
            boolean newVal = !MglaHapticManager.isEnabled();
            if (newVal && !MglaFeatureFlags.isAllowed("haptic_enabled")) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                enabledCell.setChecked(false);
                return;
            }
            MglaHapticManager.setEnabled(newVal);
            enabledCell.setChecked(MglaHapticManager.isEnabled());
            updateSectionsEnabled();
            if (MglaHapticManager.isEnabled()) {
                MglaHapticManager.previewPattern(MglaHapticManager.getPattern());
            }
        });
        mainBlock.addView(enabledCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(mainBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        optionsBlock = MglaUi.createBlock(context, "Общие");

        TextView[] strengthRef = new TextView[1];
        addSelectRow(optionsBlock, "Сила вибрации", MglaHapticManager.STRENGTH_NAMES[MglaHapticManager.getStrength()], () -> {
            showStrengthDialog();
        }, strengthRef);
        strengthValueView = strengthRef[0];

        optionsBlock.addView(MglaUi.createDivider(context));

        TextView[] defaultRef = new TextView[1];
        addSelectRow(optionsBlock, "Эффект по умолчанию", MglaHapticManager.PATTERN_NAMES[MglaHapticManager.getPattern()], () -> {
            showDefaultPatternDialog();
        }, defaultRef);
        defaultPatternValueView = defaultRef[0];

        rootLayout.addView(optionsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        actionsBlock = MglaUi.createBlock(context, "По действиям");
        for (int i = 0; i < MglaHapticManager.CUSTOMIZABLE_ACTIONS.length; i++) {
            final int action = MglaHapticManager.CUSTOMIZABLE_ACTIONS[i];
            if (i > 0) {
                actionsBlock.addView(MglaUi.createDivider(context));
            }
            TextView[] valueRef = new TextView[1];
            addSelectRow(
                    actionsBlock,
                    MglaHapticManager.ACTION_NAMES[action],
                    MglaHapticManager.getPatternDisplayNameForAction(action),
                    () -> showActionPatternDialog(action),
                    valueRef
            );
            actionValueViews[i] = valueRef[0];
        }
        rootLayout.addView(actionsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, AndroidUtilities.navigationBarHeight + 16));

        updateSectionsEnabled();

        fragmentView = scrollView;
        return fragmentView;
    }

    private void updateSectionsEnabled() {
        boolean on = MglaHapticManager.isEnabled();
        float alpha = on ? 1f : 0.4f;
        if (optionsBlock != null) {
            optionsBlock.setAlpha(alpha);
            setGroupEnabled(optionsBlock, on);
        }
        if (actionsBlock != null) {
            actionsBlock.setAlpha(alpha);
            setGroupEnabled(actionsBlock, on);
        }
    }

    private void setGroupEnabled(LinearLayout group, boolean enabled) {
        group.setEnabled(enabled);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setEnabled(enabled);
            child.setClickable(enabled);
        }
    }

    private void showStrengthDialog() {
        if (getParentActivity() == null || !MglaHapticManager.isEnabled()) {
            return;
        }
        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Сила вибрации");
        dlg.setItems(MglaHapticManager.STRENGTH_NAMES, (dialog, which) -> {
            MglaHapticManager.setStrength(which);
            if (strengthValueView != null) {
                strengthValueView.setText(MglaHapticManager.STRENGTH_NAMES[which]);
            }
            MglaHapticManager.previewPattern(MglaHapticManager.getPattern());
        });
        showDialog(dlg.create());
    }

    private void showDefaultPatternDialog() {
        if (getParentActivity() == null || !MglaHapticManager.isEnabled()) {
            return;
        }
        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Эффект по умолчанию");
        dlg.setItems(MglaHapticManager.PATTERN_NAMES, (dialog, which) -> {
            MglaHapticManager.setPattern(which);
            if (defaultPatternValueView != null) {
                defaultPatternValueView.setText(MglaHapticManager.PATTERN_NAMES[which]);
            }
            refreshActionLabels();
            MglaHapticManager.previewPattern(which);
        });
        showDialog(dlg.create());
    }

    private void showActionPatternDialog(int action) {
        if (getParentActivity() == null || !MglaHapticManager.isEnabled()) {
            return;
        }
        final String[] items = new String[MglaHapticManager.PATTERN_NAMES.length + 1];
        items[0] = "Как по умолчанию";
        System.arraycopy(MglaHapticManager.PATTERN_NAMES, 0, items, 1, MglaHapticManager.PATTERN_NAMES.length);

        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle(MglaHapticManager.ACTION_NAMES[action]);
        dlg.setItems(items, (dialog, which) -> {
            if (which == 0) {
                MglaHapticManager.setPatternForAction(action, -1);
                MglaHapticManager.previewPattern(MglaHapticManager.getPattern());
            } else {
                int pattern = which - 1;
                MglaHapticManager.setPatternForAction(action, pattern);
                MglaHapticManager.previewPattern(pattern);
            }
            refreshActionLabels();
        });
        showDialog(dlg.create());
    }

    private void refreshActionLabels() {
        for (int i = 0; i < MglaHapticManager.CUSTOMIZABLE_ACTIONS.length; i++) {
            if (actionValueViews[i] != null) {
                actionValueViews[i].setText(MglaHapticManager.getPatternDisplayNameForAction(MglaHapticManager.CUSTOMIZABLE_ACTIONS[i]));
            }
        }
    }

    private void addSelectRow(LinearLayout block, String title, String value, Runnable onClick, TextView[] valueRef) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(21), 0, dp(18), 0);
        row.setMinimumHeight(dp(50));
        row.setClickable(true);
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
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
}
