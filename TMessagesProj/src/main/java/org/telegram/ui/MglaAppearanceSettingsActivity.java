package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

public class MglaAppearanceSettingsActivity extends BaseFragment {

    public MglaAppearanceSettingsActivity() {
        this(null);
    }

    public MglaAppearanceSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Внешний вид");
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
        rootLayout.setLayoutTransition(new android.animation.LayoutTransition());
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);

        LinearLayout headerStyleBlock = createBlock(context, "Вид шапки");

        RadioCell standardCell = new RadioCell(context);
        standardCell.setBackground(null);
        standardCell.setText("Стандартная", !MglaGlassConfig.isCleanHeaderEnabled(), false);

        RadioCell cleanCell = new RadioCell(context);
        cleanCell.setBackground(null);
        cleanCell.setText("Чистая", MglaGlassConfig.isCleanHeaderEnabled(), false);

        headerStyleBlock.addView(standardCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        headerStyleBlock.addView(MglaUi.createDivider(context));
        headerStyleBlock.addView(cleanCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout cleanSettingsBlock = MglaUi.createBlock(context, null);

        TextSettingsCell cleanConfigureCell = new TextSettingsCell(context);
        cleanConfigureCell.setBackground(null);
        cleanConfigureCell.setText("Настроить", false);
        cleanConfigureCell.setCanDisable(false);
        cleanConfigureCell.setOnClickListener(v -> presentFragment(new MglaCleanHeaderSettingsActivity()));
        cleanSettingsBlock.addView(cleanConfigureCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(headerStyleBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
        rootLayout.addView(cleanSettingsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
        
        cleanSettingsBlock.setVisibility(MglaGlassConfig.isCleanHeaderEnabled() ? android.view.View.VISIBLE : android.view.View.GONE);

        standardCell.setOnClickListener(v -> {
            MglaGlassConfig.setCleanHeaderEnabled(false);
            standardCell.setChecked(true, true);
            cleanCell.setChecked(false, true);
            cleanSettingsBlock.setVisibility(android.view.View.GONE);
        });

        cleanCell.setOnClickListener(v -> {
            MglaGlassConfig.setCleanHeaderEnabled(true);
            standardCell.setChecked(false, true);
            cleanCell.setChecked(true, true);
            cleanSettingsBlock.setVisibility(android.view.View.VISIBLE);
        });

        LinearLayout glassBlock = createBlock(context, "Внешний вид");

        TextView darkeningTitleCell = new TextView(context);
        darkeningTitleCell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        darkeningTitleCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        darkeningTitleCell.setText("Затемнение стекла");
        darkeningTitleCell.setPadding(dp(22), dp(10), dp(22), 0);
        glassBlock.addView(darkeningTitleCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        org.telegram.ui.Cells.SlideIntChooseView slideView = new org.telegram.ui.Cells.SlideIntChooseView(context, null);
        slideView.set(
            MglaGlassConfig.getGlassDarkeningLevel(),
            org.telegram.ui.Cells.SlideIntChooseView.Options.make(0, 0, 100, (val) -> val + "%"),
            (val) -> MglaGlassConfig.setGlassDarkeningLevel(val)
        );
        glassBlock.addView(slideView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        glassBlock.addView(MglaUi.createDivider(context));

        TextCheckCell md3SwitchesCell = new TextCheckCell(context);
        md3SwitchesCell.setBackground(null);
        md3SwitchesCell.setTextAndCheck("Переключатели MD3", MglaGlassConfig.isMd3SwitchesEnabled(), false);
        md3SwitchesCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isMd3SwitchesEnabled();
            MglaGlassConfig.setMd3SwitchesEnabled(newVal);
            md3SwitchesCell.setChecked(newVal);
        });
        glassBlock.addView(md3SwitchesCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        glassBlock.addView(MglaUi.createDivider(context));

        TextCheckCell editedIconCell = new TextCheckCell(context);
        editedIconCell.setBackground(null);
        editedIconCell.setTextAndCheck("Значок вместо \"изменено\"", MglaGlassConfig.isEditedIconEnabled(), false);
        editedIconCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isEditedIconEnabled();
            MglaGlassConfig.setEditedIconEnabled(newVal);
            editedIconCell.setChecked(newVal);
        });
        glassBlock.addView(editedIconCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(glassBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout sideMenuBlock = createBlock(context, "Боковое меню");

        TextSettingsCell configureCell = new TextSettingsCell(context);
        configureCell.setBackground(null);
        configureCell.setText("Настроить", false);
        configureCell.setCanDisable(false);
        configureCell.setOnClickListener(v -> presentFragment(new MglaSideMenuSettingsActivity()));
        sideMenuBlock.addView(configureCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(sideMenuBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout animationBlock = createBlock(context, "Анимация");

        TextCheckCell predictiveBackCell = new TextCheckCell(context);
        predictiveBackCell.setBackground(null);
        predictiveBackCell.setTextAndCheck("В стиле MD3", MglaGlassConfig.isMd3PredictiveBackEnabled(), false);
        predictiveBackCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isMd3PredictiveBackEnabled();
            MglaGlassConfig.setMd3PredictiveBackEnabled(newVal);
            predictiveBackCell.setChecked(newVal);
        });
        animationBlock.addView(predictiveBackCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(animationBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        fragmentView = rootLayout;
        return fragmentView;
    }

    private LinearLayout createBlock(Context context, String title) {
        return MglaUi.createBlock(context, title);
    }
}
