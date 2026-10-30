package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MglaFeatureFlags;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

public class MglaAppearanceSettingsActivity extends MglaBaseSettingsActivity {

    private TextSettingsCell commonAnimCell;
    private TextSettingsCell chatsAnimCell;
    private TextSettingsCell dialogsAnimCell;
    private org.telegram.ui.Components.MglaChatHeaderPreview headerPreview;
    private org.telegram.ui.Components.MglaChatBottomPanelPreview bottomPanelPreview;

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
        // CHANGING animations fight ItemTouchHelper in «Нижний островок» (drag resets).
        android.animation.LayoutTransition layoutTransition = new android.animation.LayoutTransition();
        layoutTransition.disableTransitionType(android.animation.LayoutTransition.CHANGING);
        layoutTransition.disableTransitionType(android.animation.LayoutTransition.CHANGE_APPEARING);
        layoutTransition.disableTransitionType(android.animation.LayoutTransition.CHANGE_DISAPPEARING);
        rootLayout.setLayoutTransition(layoutTransition);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        rootLayout.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight);

        LinearLayout headerStyleBlock = createBlock(context, "Вид шапки");

        final int headerStyle = MglaGlassConfig.getChatHeaderStyle();

        final org.telegram.ui.Components.MglaChatHeaderPreview headerPreview =
                new org.telegram.ui.Components.MglaChatHeaderPreview(context);
        headerPreview.setPadding(dp(12), dp(4), dp(12), dp(8));
        headerStyleBlock.addView(headerPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        RadioCell standardCell = new RadioCell(context);
        standardCell.setBackground(null);
        standardCell.setText("Стандартная", headerStyle == MglaGlassConfig.HEADER_STYLE_STANDARD, false);

        RadioCell cleanCell = new RadioCell(context);
        cleanCell.setBackground(null);
        cleanCell.setText("Чистая", headerStyle == MglaGlassConfig.HEADER_STYLE_CLEAN, false);

        RadioCell telegramCell = new RadioCell(context);
        telegramCell.setBackground(null);
        telegramCell.setText("Telegram", headerStyle == MglaGlassConfig.HEADER_STYLE_TELEGRAM, false);

        // Keep default RadioCell padding (21) so the radio stays on the right edge.
        // Indent only the title for the clock; overlays handle clock + beta.
        RadioCell customCell = new RadioCell(context);
        customCell.setBackground(null);
        customCell.setText("\u2003\u2003Своя", headerStyle == MglaGlassConfig.HEADER_STYLE_CUSTOM, false);

        FrameLayout customRow = new FrameLayout(context);
        customRow.addView(customCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView customClockHint = new TextView(context);
        customClockHint.setText("🕐");
        customClockHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        customClockHint.setGravity(Gravity.CENTER);
        customClockHint.setPadding(dp(4), dp(4), dp(4), dp(4));
        customClockHint.setOnClickListener(v -> {
            if (getParentActivity() == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Своя шапка");
            builder.setMessage("Функция временно доступна всем, чтобы собрать обратную связь и баги.");
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(builder.create());
        });
        customRow.addView(customClockHint, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        TextView customBetaBadge = new TextView(context);
        customBetaBadge.setText("beta");
        customBetaBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        customBetaBadge.setTypeface(AndroidUtilities.bold());
        customBetaBadge.setTextColor(0xFFFFFFFF);
        customBetaBadge.setGravity(Gravity.CENTER);
        customBetaBadge.setPadding(dp(6), dp(1), dp(6), dp(2));
        GradientDrawable customBetaBg = new GradientDrawable();
        customBetaBg.setCornerRadius(dp(6));
        customBetaBg.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_featuredStickers_addButton), 200));
        customBetaBadge.setBackground(customBetaBg);
        // After default pad (21) + clock indent (~28) + «Своя» (~40).
        customRow.addView(customBetaBadge, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 21 + 28 + 44, 0, 0, 0));

        headerStyleBlock.addView(standardCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        headerStyleBlock.addView(MglaUi.createDivider(context));
        headerStyleBlock.addView(cleanCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        headerStyleBlock.addView(MglaUi.createDivider(context));
        headerStyleBlock.addView(telegramCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        headerStyleBlock.addView(MglaUi.createDivider(context));
        headerStyleBlock.addView(customRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout cleanSettingsBlock = MglaUi.createBlock(context, null);

        TextSettingsCell cleanConfigureCell = new TextSettingsCell(context);
        cleanConfigureCell.setBackground(null);
        cleanConfigureCell.setText("Настроить", false);
        cleanConfigureCell.setCanDisable(false);
        cleanConfigureCell.setOnClickListener(v -> presentFragment(new MglaCleanHeaderSettingsActivity()));
        cleanSettingsBlock.addView(cleanConfigureCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout customSettingsBlock = MglaUi.createBlock(context, null);

        TextSettingsCell customConfigureCell = new TextSettingsCell(context);
        customConfigureCell.setBackground(null);
        customConfigureCell.setText("Настроить", false);
        customConfigureCell.setCanDisable(false);
        customConfigureCell.setOnClickListener(v -> presentFragment(new MglaCustomHeaderSettingsActivity()));
        customSettingsBlock.addView(customConfigureCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        this.headerPreview = headerPreview;

        final Runnable applyHeaderRadios = () -> {
            final int style = MglaGlassConfig.getChatHeaderStyle();
            standardCell.setChecked(style == MglaGlassConfig.HEADER_STYLE_STANDARD, true);
            cleanCell.setChecked(style == MglaGlassConfig.HEADER_STYLE_CLEAN, true);
            telegramCell.setChecked(style == MglaGlassConfig.HEADER_STYLE_TELEGRAM, true);
            customCell.setChecked(style == MglaGlassConfig.HEADER_STYLE_CUSTOM, true);
            cleanSettingsBlock.setVisibility(style == MglaGlassConfig.HEADER_STYLE_CLEAN ? android.view.View.VISIBLE : android.view.View.GONE);
            customSettingsBlock.setVisibility(style == MglaGlassConfig.HEADER_STYLE_CUSTOM ? android.view.View.VISIBLE : android.view.View.GONE);
            if (this.headerPreview != null) {
                this.headerPreview.setStyleAnimated(style);
            }
        };

        standardCell.setOnClickListener(v -> {
            MglaGlassConfig.setChatHeaderStyle(MglaGlassConfig.HEADER_STYLE_STANDARD);
            applyHeaderRadios.run();
        });

        cleanCell.setOnClickListener(v -> {
            MglaGlassConfig.setChatHeaderStyle(MglaGlassConfig.HEADER_STYLE_CLEAN);
            applyHeaderRadios.run();
        });

        telegramCell.setOnClickListener(v -> {
            MglaGlassConfig.setChatHeaderStyle(MglaGlassConfig.HEADER_STYLE_TELEGRAM);
            applyHeaderRadios.run();
        });

        customCell.setOnClickListener(v -> {
            MglaGlassConfig.setChatHeaderStyle(MglaGlassConfig.HEADER_STYLE_CUSTOM);
            applyHeaderRadios.run();
        });

        LinearLayout bottomPanelBlock = createBlock(context, "Вид нижней панели");
        final int bottomPanelStyle = MglaGlassConfig.getChatBottomPanelStyle();
        final org.telegram.ui.Components.MglaChatBottomPanelPreview bottomPanelPreview =
                new org.telegram.ui.Components.MglaChatBottomPanelPreview(context);
        bottomPanelPreview.setPadding(dp(12), dp(4), dp(12), dp(8));
        bottomPanelBlock.addView(bottomPanelPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        RadioCell bottomStandardCell = new RadioCell(context);
        bottomStandardCell.setBackground(null);
        bottomStandardCell.setText("Стандартная", bottomPanelStyle == MglaGlassConfig.BOTTOM_PANEL_STYLE_STANDARD, false);

        RadioCell bottomTelegramCell = new RadioCell(context);
        bottomTelegramCell.setBackground(null);
        bottomTelegramCell.setText("Telegram", bottomPanelStyle == MglaGlassConfig.BOTTOM_PANEL_STYLE_TELEGRAM, false);

        bottomPanelBlock.addView(bottomStandardCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        bottomPanelBlock.addView(MglaUi.createDivider(context));
        bottomPanelBlock.addView(bottomTelegramCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        this.bottomPanelPreview = bottomPanelPreview;

        final Runnable applyBottomPanelRadios = () -> {
            final int style = MglaGlassConfig.getChatBottomPanelStyle();
            bottomStandardCell.setChecked(style == MglaGlassConfig.BOTTOM_PANEL_STYLE_STANDARD, true);
            bottomTelegramCell.setChecked(style == MglaGlassConfig.BOTTOM_PANEL_STYLE_TELEGRAM, true);
            if (this.bottomPanelPreview != null) {
                this.bottomPanelPreview.setStyleAnimated(style);
            }
        };

        bottomStandardCell.setOnClickListener(v -> {
            MglaGlassConfig.setChatBottomPanelStyle(MglaGlassConfig.BOTTOM_PANEL_STYLE_STANDARD);
            applyBottomPanelRadios.run();
        });

        bottomTelegramCell.setOnClickListener(v -> {
            MglaGlassConfig.setChatBottomPanelStyle(MglaGlassConfig.BOTTOM_PANEL_STYLE_TELEGRAM);
            applyBottomPanelRadios.run();
        });

        // Order: header → clean/custom configure → bottom panel → glass.
        rootLayout.addView(headerStyleBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
        rootLayout.addView(cleanSettingsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
        cleanSettingsBlock.setVisibility(headerStyle == MglaGlassConfig.HEADER_STYLE_CLEAN ? android.view.View.VISIBLE : android.view.View.GONE);
        rootLayout.addView(customSettingsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
        customSettingsBlock.setVisibility(headerStyle == MglaGlassConfig.HEADER_STYLE_CUSTOM ? android.view.View.VISIBLE : android.view.View.GONE);
        rootLayout.addView(bottomPanelBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

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
            if (newVal && !MglaFeatureFlags.isAllowed(MglaGlassConfig.PREF_EDITED_ICON)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                editedIconCell.setChecked(false);
                return;
            }
            MglaGlassConfig.setEditedIconEnabled(newVal);
            editedIconCell.setChecked(MglaGlassConfig.isEditedIconEnabled());
        });
        glassBlock.addView(editedIconCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        glassBlock.addView(MglaUi.createDivider(context));

        TextView notifyIconTitle = new TextView(context);
        notifyIconTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        notifyIconTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        notifyIconTitle.setText("Значок уведомлений");
        notifyIconTitle.setPadding(dp(22), dp(12), dp(22), 0);
        glassBlock.addView(notifyIconTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        org.telegram.ui.Components.MglaSegmentedControl notifyIconControl = new org.telegram.ui.Components.MglaSegmentedControl(context);
        // 0 = стандартный (самолётик), 1 = кастомный
        notifyIconControl.setOptions(
                "Стандартный",
                "Кастомный",
                MglaGlassConfig.isCustomNotificationIconEnabled() ? 1 : 0
        );
        notifyIconControl.setCallback(index -> MglaGlassConfig.setCustomNotificationIconEnabled(index == 1));
        glassBlock.addView(notifyIconControl, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(glassBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout foldersBlock = createBlock(context, "Папки");
        TextCheckCell folderOutlineCell = new TextCheckCell(context);
        folderOutlineCell.setBackground(null);
        folderOutlineCell.setTextAndCheck("Обводка активной папки", MglaGlassConfig.isFolderActiveOutlineEnabled(), false);
        folderOutlineCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isFolderActiveOutlineEnabled();
            MglaGlassConfig.setFolderActiveOutlineEnabled(newVal);
            folderOutlineCell.setChecked(newVal);
        });
        foldersBlock.addView(folderOutlineCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        foldersBlock.addView(MglaUi.createDivider(context));

        TextCheckCell folderNameHeaderCell = new TextCheckCell(context);
        folderNameHeaderCell.setBackground(null);
        folderNameHeaderCell.setTextAndCheck("Название папки в шапке", MglaGlassConfig.isFolderNameInHeaderEnabled(), false);
        folderNameHeaderCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isFolderNameInHeaderEnabled();
            MglaGlassConfig.setFolderNameInHeaderEnabled(newVal);
            folderNameHeaderCell.setChecked(newVal);
        });
        foldersBlock.addView(folderNameHeaderCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(foldersBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout mainTabsBlock = createBlock(context, "Нижний островок");
        final org.telegram.ui.Components.MglaMainTabsIslandEditor[] tabsEditorRef = new org.telegram.ui.Components.MglaMainTabsIslandEditor[1];
        tabsEditorRef[0] = new org.telegram.ui.Components.MglaMainTabsIslandEditor(
                context,
                getResourceProvider(),
                getCurrentAccount(),
                order -> org.telegram.messenger.MglaMainTabsConfig.setEnabledTabs(order)
        );
        tabsEditorRef[0].setPadding(dp(12), dp(4), dp(12), dp(4));
        mainTabsBlock.addView(tabsEditorRef[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextSettingsCell resetTabsCell = new TextSettingsCell(context);
        resetTabsCell.setBackground(null);
        resetTabsCell.setText("Сбросить по умолчанию", false);

        TextCheckCell searchButtonCell = new TextCheckCell(context);
        searchButtonCell.setBackground(null);
        searchButtonCell.setTextAndCheck("Кнопка поиска", org.telegram.messenger.MglaMainTabsConfig.isSearchButtonEnabled(), false);
        searchButtonCell.setOnClickListener(v -> {
            boolean newVal = !org.telegram.messenger.MglaMainTabsConfig.isSearchButtonEnabled();
            org.telegram.messenger.MglaMainTabsConfig.setSearchButtonEnabled(newVal);
            searchButtonCell.setChecked(newVal);
            if (tabsEditorRef[0] != null) {
                tabsEditorRef[0].setSearchButtonEnabled(newVal);
            }
        });

        resetTabsCell.setOnClickListener(v -> {
            org.telegram.messenger.MglaMainTabsConfig.resetToDefault();
            searchButtonCell.setChecked(false);
            int editorIndex = mainTabsBlock.indexOfChild(tabsEditorRef[0]);
            mainTabsBlock.removeView(tabsEditorRef[0]);
            tabsEditorRef[0] = new org.telegram.ui.Components.MglaMainTabsIslandEditor(
                    context,
                    getResourceProvider(),
                    getCurrentAccount(),
                    order -> org.telegram.messenger.MglaMainTabsConfig.setEnabledTabs(order)
            );
            tabsEditorRef[0].setPadding(dp(12), dp(4), dp(12), dp(4));
            mainTabsBlock.addView(tabsEditorRef[0], Math.max(0, editorIndex), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        });
        mainTabsBlock.addView(resetTabsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        mainTabsBlock.addView(MglaUi.createDivider(context));
        mainTabsBlock.addView(searchButtonCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(mainTabsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout sideMenuBlock = createBlock(context, "Боковое меню");

        TextSettingsCell configureCell = new TextSettingsCell(context);
        configureCell.setBackground(null);
        configureCell.setText("Настроить", false);
        configureCell.setCanDisable(false);
        configureCell.setOnClickListener(v -> presentFragment(new MglaSideMenuSettingsActivity()));
        sideMenuBlock.addView(configureCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(sideMenuBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout animationBlock = createBlock(context, "Анимация");

        commonAnimCell = new TextSettingsCell(context);
        commonAnimCell.setBackground(null);
        commonAnimCell.setTextAndValue("Общие анимации", MglaAnimationSettingsActivity.getSummaryForType(MglaAnimationSettingsActivity.TYPE_COMMON), false);
        commonAnimCell.setOnClickListener(v -> presentFragment(MglaAnimationSettingsActivity.of(MglaAnimationSettingsActivity.TYPE_COMMON)));
        animationBlock.addView(commonAnimCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        animationBlock.addView(MglaUi.createDivider(context));

        chatsAnimCell = new TextSettingsCell(context);
        chatsAnimCell.setBackground(null);
        chatsAnimCell.setTextAndValue("В чатах", MglaAnimationSettingsActivity.getSummaryForType(MglaAnimationSettingsActivity.TYPE_CHATS), false);
        chatsAnimCell.setOnClickListener(v -> presentFragment(MglaAnimationSettingsActivity.of(MglaAnimationSettingsActivity.TYPE_CHATS)));
        animationBlock.addView(chatsAnimCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        animationBlock.addView(MglaUi.createDivider(context));

        dialogsAnimCell = new TextSettingsCell(context);
        dialogsAnimCell.setBackground(null);
        dialogsAnimCell.setTextAndValue("В списке чатов", MglaAnimationSettingsActivity.getSummaryForType(MglaAnimationSettingsActivity.TYPE_DIALOGS), false);
        dialogsAnimCell.setOnClickListener(v -> presentFragment(MglaAnimationSettingsActivity.of(MglaAnimationSettingsActivity.TYPE_DIALOGS)));
        animationBlock.addView(dialogsAnimCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(animationBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 16));

        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        fragmentView = scrollView;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (headerPreview != null) {
            headerPreview.applyFromConfig();
        }
        if (bottomPanelPreview != null) {
            bottomPanelPreview.applyFromConfig();
        }
        if (commonAnimCell != null) {
            commonAnimCell.setTextAndValue("Общие анимации", MglaAnimationSettingsActivity.getSummaryForType(MglaAnimationSettingsActivity.TYPE_COMMON), true);
        }
        if (chatsAnimCell != null) {
            chatsAnimCell.setTextAndValue("В чатах", MglaAnimationSettingsActivity.getSummaryForType(MglaAnimationSettingsActivity.TYPE_CHATS), true);
        }
        if (dialogsAnimCell != null) {
            dialogsAnimCell.setTextAndValue("В списке чатов", MglaAnimationSettingsActivity.getSummaryForType(MglaAnimationSettingsActivity.TYPE_DIALOGS), false);
        }
    }

    private LinearLayout createBlock(Context context, String title) {
        return MglaUi.createBlock(context, title);
    }
}
