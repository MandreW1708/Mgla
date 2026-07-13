package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
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
import org.telegram.ui.Cells.HeaderCell;
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
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);

        LinearLayout glassBlock = createBlock(context, "Стекло");

        TextCheckCell darkeningCell = new TextCheckCell(context);
        darkeningCell.setBackground(null);
        darkeningCell.setTextAndCheck("Затемнение стекла", MglaGlassConfig.isGlassDarkeningEnabled(), false);
        darkeningCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isGlassDarkeningEnabled();
            MglaGlassConfig.setGlassDarkeningEnabled(newVal);
            darkeningCell.setChecked(newVal);
        });
        glassBlock.addView(darkeningCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(glassBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

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
        predictiveBackCell.setTextAndCheck("Новая predective back анимация", MglaGlassConfig.isMd3PredictiveBackEnabled(), false);
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
}
