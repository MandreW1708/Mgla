package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

public class MglaCleanHeaderSettingsActivity extends MglaBaseSettingsActivity {

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

        LinearLayout previewBlock = createBlock(context, "Превью");
        final org.telegram.ui.Components.MglaChatHeaderPreview headerPreview =
                new org.telegram.ui.Components.MglaChatHeaderPreview(context);
        headerPreview.setPadding(dp(12), dp(4), dp(12), dp(8));
        // Force Clean style in this screen so toggles are visible immediately.
        headerPreview.setPreviewState(
                MglaGlassConfig.HEADER_STYLE_CLEAN,
                MglaGlassConfig.isCleanHeaderHideBackBtnEnabled(),
                MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled(),
                false
        );
        previewBlock.addView(headerPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(previewBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout backgroundBlock = createBlock(context, "Убрать фон");

        TextCheckCell backBtnCell = new TextCheckCell(context);
        backBtnCell.setBackground(null);
        backBtnCell.setTextAndCheck("Кнопка назад", MglaGlassConfig.isCleanHeaderHideBackBtnEnabled(), false);

        TextCheckCell titleBlockCell = new TextCheckCell(context);
        titleBlockCell.setBackground(null);
        titleBlockCell.setTextAndCheck("Блок с ником", MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled(), false);

        TextCheckCell pinnedBlockCell = new TextCheckCell(context);
        pinnedBlockCell.setBackground(null);
        pinnedBlockCell.setTextAndCheck("Блок с закрепом", MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled(), false);

        TextCheckCell translationPanelCell = new TextCheckCell(context);
        translationPanelCell.setBackground(null);
        translationPanelCell.setTextAndCheck("Панель перевода", MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled(), false);

        final Runnable refreshPreview = () -> headerPreview.setPreviewState(
                MglaGlassConfig.HEADER_STYLE_CLEAN,
                MglaGlassConfig.isCleanHeaderHideBackBtnEnabled(),
                MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled(),
                MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled(),
                false
        );

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
            refreshPreview.run();
        });

        titleBlockCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled();
            if (!newVal && !MglaGlassConfig.isCleanHeaderHideBackBtnEnabled() && !MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled() && !MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled()) {
                toggleCheck.run();
                return;
            }
            MglaGlassConfig.setCleanHeaderHideTitleBlockEnabled(newVal);
            titleBlockCell.setChecked(newVal);
            refreshPreview.run();
        });

        pinnedBlockCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled();
            if (!newVal && !MglaGlassConfig.isCleanHeaderHideBackBtnEnabled() && !MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled() && !MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled()) {
                toggleCheck.run();
                return;
            }
            MglaGlassConfig.setCleanHeaderHidePinnedBlockEnabled(newVal);
            pinnedBlockCell.setChecked(newVal);
            refreshPreview.run();
        });

        translationPanelCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isCleanHeaderHideTranslationPanelEnabled();
            if (!newVal && !MglaGlassConfig.isCleanHeaderHideBackBtnEnabled() && !MglaGlassConfig.isCleanHeaderHideTitleBlockEnabled() && !MglaGlassConfig.isCleanHeaderHidePinnedBlockEnabled()) {
                toggleCheck.run();
                return;
            }
            MglaGlassConfig.setCleanHeaderHideTranslationPanelEnabled(newVal);
            translationPanelCell.setChecked(newVal);
            refreshPreview.run();
        });

        backgroundBlock.addView(backBtnCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        backgroundBlock.addView(MglaUi.createDivider(context));
        backgroundBlock.addView(titleBlockCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        backgroundBlock.addView(MglaUi.createDivider(context));
        backgroundBlock.addView(pinnedBlockCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        backgroundBlock.addView(MglaUi.createDivider(context));
        backgroundBlock.addView(translationPanelCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(backgroundBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        fragmentView = rootLayout;
        return fragmentView;
    }

    private LinearLayout createBlock(Context context, String title) {
        return MglaUi.createBlock(context, title);
    }
}
