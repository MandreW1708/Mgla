package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaSideMenuConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;

public class MglaSideMenuSettingsActivity extends BaseFragment {

    public MglaSideMenuSettingsActivity() {
        this(null);
    }

    public MglaSideMenuSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Боковое меню");
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

        LinearLayout toggleBlock = new LinearLayout(context);
        toggleBlock.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable toggleBg = new GradientDrawable();
        toggleBg.setCornerRadius(dp(10));
        toggleBg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        toggleBlock.setBackground(toggleBg);
        toggleBlock.setClipToOutline(true);
        toggleBlock.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        TextCheckCell enableCell = new TextCheckCell(context);
        enableCell.setBackground(null);
        enableCell.setTextAndCheck("Включить боковое меню", MglaSideMenuConfig.isEnabled(), false);
        enableCell.setOnClickListener(v -> {
            boolean newVal = !MglaSideMenuConfig.isEnabled();
            MglaSideMenuConfig.setEnabled(newVal);
            enableCell.setChecked(newVal);
        });
        toggleBlock.addView(enableCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(toggleBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        TextInfoPrivacyCell infoCell = new TextInfoPrivacyCell(context);
        infoCell.setText("Боковое меню открывается кнопкой в шапке списка чатов.");
        infoCell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        rootLayout.addView(infoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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