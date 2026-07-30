package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

public class MglaCleanHeaderSettingsActivity extends BaseFragment {

    public MglaCleanHeaderSettingsActivity() {
        super();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Настройки чистой шапки");
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

        LinearLayout backgroundBlock = createBlock(context, "Убрать фон");

        TextCheckCell backBtnCell = new TextCheckCell(context);
        backBtnCell.setBackground(null);
        backBtnCell.setTextAndCheck("Кнопка назад", MglaGlassConfig.isCleanHeaderHideBackBtnEnabled(), true);

        TextCheckCell titleBlockCell = new TextCheckCell(context);
        titleBlockCell.setBackground(null);
        titleBlockCell.setTextAndCheck("Блок с ником", MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled(), true);

        TextCheckCell pinnedBlockCell = new TextCheckCell(context);
        pinnedBlockCell.setBackground(null);
        pinnedBlockCell.setTextAndCheck("Блок с закрепом", MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled(), true);

        TextCheckCell translationPanelCell = new TextCheckCell(context);
        translationPanelCell.setBackground(null);
        translationPanelCell.setTextAndCheck("Панель перевода", MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled(), false);

        Runnable toggleCheck = () -> {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Переключитесь тогда на стандартную шапку").show();
        };

        backBtnCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isCleanHeaderHideBackBtnEnabled();
            if (!newVal && !MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled() && !MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled() && !MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled()) {
                toggleCheck.run();
                return;
            }
            MglaGlassConfig.setCleanHeaderHideBackBtnEnabled(newVal);
            backBtnCell.setChecked(newVal);
        });

        titleBlockCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled();
            if (!newVal && !MglaGlassConfig.isCleanHeaderHideBackBtnEnabled() && !MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled() && !MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled()) {
                toggleCheck.run();
                return;
            }
            MglaGlassConfig.setCleanHeaderHideTitleBlockEnabled(newVal);
            titleBlockCell.setChecked(newVal);
        });

        pinnedBlockCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled();
            if (!newVal && !MglaGlassConfig.isCleanHeaderHideBackBtnEnabled() && !MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled() && !MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled()) {
                toggleCheck.run();
                return;
            }
            MglaGlassConfig.setCleanHeaderHidePinnedBlockEnabled(newVal);
            pinnedBlockCell.setChecked(newVal);
        });

        translationPanelCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled();
            if (!newVal && !MglaGlassConfig.isCleanHeaderHideBackBtnEnabled() && !MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled() && !MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled()) {
                toggleCheck.run();
                return;
            }
            MglaGlassConfig.setCleanHeaderHideTranslationPanelEnabled(newVal);
            translationPanelCell.setChecked(newVal);
        });

        backgroundBlock.addView(backBtnCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        backgroundBlock.addView(titleBlockCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        backgroundBlock.addView(pinnedBlockCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        backgroundBlock.addView(translationPanelCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(backgroundBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

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
