package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

public class MglaAnimationSettingsActivity extends MglaBaseSettingsActivity {

    public static final int TYPE_COMMON = 0;
    public static final int TYPE_CHATS = 1;
    public static final int TYPE_DIALOGS = 2;

    private final int type;
    private TextSettingsCell textAnimationCell;

    public static MglaAnimationSettingsActivity of(int type) {
        Bundle args = new Bundle();
        args.putInt("type", type);
        return new MglaAnimationSettingsActivity(args);
    }

    public MglaAnimationSettingsActivity(Bundle args) {
        super(args);
        type = args != null ? args.getInt("type", TYPE_COMMON) : TYPE_COMMON;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getTitleForType(type));
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

        if (type == TYPE_COMMON) {
            fillCommon(context, rootLayout);
        } else if (type == TYPE_CHATS) {
            fillChats(context, rootLayout);
        } else {
            fillDialogs(context, rootLayout);
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        fragmentView = scrollView;
        return fragmentView;
    }

    private void fillCommon(Context context, LinearLayout rootLayout) {
        LinearLayout block = MglaUi.createBlock(context, "Анимация назад");

        final int[] styles = MglaGlassConfig.PREDICTIVE_BACK_STYLES;
        final RadioCell[] styleCells = new RadioCell[styles.length];
        final int currentStyle = MglaGlassConfig.getPredictiveBackStyle();
        for (int i = 0; i < styles.length; i++) {
            final int style = styles[i];
            RadioCell cell = new RadioCell(context);
            cell.setBackground(null);
            cell.setText(MglaGlassConfig.getPredictiveBackStyleTitle(style), currentStyle == style, false);
            cell.setOnClickListener(v -> {
                MglaGlassConfig.setPredictiveBackStyle(style);
                for (int j = 0; j < styleCells.length; j++) {
                    styleCells[j].setChecked(styles[j] == style, true);
                }
            });
            styleCells[i] = cell;
            block.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            block.addView(MglaUi.createDivider(context));
        }

        TextCheckCell reverseOpenCell = new TextCheckCell(context);
        reverseOpenCell.setBackground(null);
        reverseOpenCell.setTextAndCheck("Открытие экранов как назад", MglaGlassConfig.isReverseChatOpenEnabled(), false);
        reverseOpenCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isReverseChatOpenEnabled();
            MglaGlassConfig.setReverseChatOpenEnabled(newVal);
            reverseOpenCell.setChecked(newVal);
        });
        block.addView(reverseOpenCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 16));

        LinearLayout profileBlock = MglaUi.createBlock(context, "Профиль");
        TextCheckCell mediaGrowCell = new TextCheckCell(context);
        mediaGrowCell.setBackground(null);
        mediaGrowCell.setTextAndCheck("Вырастание фото", MglaGlassConfig.isProfileMediaGrowEnabled(), false);
        mediaGrowCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isProfileMediaGrowEnabled();
            MglaGlassConfig.setProfileMediaGrowEnabled(newVal);
            mediaGrowCell.setChecked(newVal);
        });
        profileBlock.addView(mediaGrowCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(profileBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 16));

        LinearLayout settingsBlock = MglaUi.createBlock(context, "Настройки Mgla");
        TextCheckCell pressSpringCell = new TextCheckCell(context);
        pressSpringCell.setBackground(null);
        pressSpringCell.setTextAndCheck("Пружина при тапе", MglaGlassConfig.isSettingsPressSpringEnabled(), false);
        pressSpringCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isSettingsPressSpringEnabled();
            MglaGlassConfig.setSettingsPressSpringEnabled(newVal);
            pressSpringCell.setChecked(newVal);
            if (newVal && fragmentView != null) {
                MglaUi.attachPressSpringRecursive(fragmentView);
            }
        });
        settingsBlock.addView(pressSpringCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(settingsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 16));
    }

    private void fillChats(Context context, LinearLayout rootLayout) {
        LinearLayout block = MglaUi.createBlock(context, null);

        textAnimationCell = new TextSettingsCell(context);
        textAnimationCell.setBackground(null);
        textAnimationCell.setTextAndValue("Анимация текста", MglaTextAnimConfig.getSummary(), false);
        textAnimationCell.setOnClickListener(v -> presentFragment(new MglaTextAnimationActivity()));
        block.addView(textAnimationCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        block.addView(MglaUi.createDivider(context));

        TextCheckCell sendMorphCell = new TextCheckCell(context);
        sendMorphCell.setBackground(null);
        sendMorphCell.setTextAndCheck("Морф кнопки отправки", MglaGlassConfig.isChatSendButtonMorphEnabled(), false);
        sendMorphCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isChatSendButtonMorphEnabled();
            MglaGlassConfig.setChatSendButtonMorphEnabled(newVal);
            sendMorphCell.setChecked(newVal);
        });
        block.addView(sendMorphCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        block.addView(MglaUi.createDivider(context));

        TextCheckCell reactionSpringCell = new TextCheckCell(context);
        reactionSpringCell.setBackground(null);
        reactionSpringCell.setTextAndCheck("Пружина реакций", MglaGlassConfig.isChatReactionSpringEnabled(), false);
        reactionSpringCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isChatReactionSpringEnabled();
            MglaGlassConfig.setChatReactionSpringEnabled(newVal);
            reactionSpringCell.setChecked(newVal);
        });
        block.addView(reactionSpringCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 16));
    }

    private void fillDialogs(Context context, LinearLayout rootLayout) {
        LinearLayout block = MglaUi.createBlock(context, null);

        TextCheckCell avatarHeroCell = new TextCheckCell(context);
        avatarHeroCell.setBackground(null);
        avatarHeroCell.setTextAndCheck("Перелёт аватара", MglaGlassConfig.isDialogsAvatarHeroEnabled(), false);
        avatarHeroCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isDialogsAvatarHeroEnabled();
            MglaGlassConfig.setDialogsAvatarHeroEnabled(newVal);
            avatarHeroCell.setChecked(newVal);
        });
        block.addView(avatarHeroCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        block.addView(MglaUi.createDivider(context));

        TextCheckCell searchExpandCell = new TextCheckCell(context);
        searchExpandCell.setBackground(null);
        searchExpandCell.setTextAndCheck("Раскрытие поиска", MglaGlassConfig.isDialogsSearchExpandEnabled(), false);
        searchExpandCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isDialogsSearchExpandEnabled();
            MglaGlassConfig.setDialogsSearchExpandEnabled(newVal);
            searchExpandCell.setChecked(newVal);
        });
        block.addView(searchExpandCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        block.addView(MglaUi.createDivider(context));

        TextCheckCell fabMorphCell = new TextCheckCell(context);
        fabMorphCell.setBackground(null);
        fabMorphCell.setTextAndCheck("Кнопка «Создать»", MglaGlassConfig.isDialogsFabMorphEnabled(), false);
        fabMorphCell.setOnClickListener(v -> {
            boolean newVal = !MglaGlassConfig.isDialogsFabMorphEnabled();
            MglaGlassConfig.setDialogsFabMorphEnabled(newVal);
            fabMorphCell.setChecked(newVal);
        });
        block.addView(fabMorphCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 16));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (textAnimationCell != null) {
            textAnimationCell.setTextAndValue("Анимация текста", MglaTextAnimConfig.getSummary(), false);
        }
    }

    public static String getTitleForType(int type) {
        switch (type) {
            case TYPE_CHATS:
                return "В чатах";
            case TYPE_DIALOGS:
                return "В списке чатов";
            default:
                return "Общие анимации";
        }
    }

    public static String getSummaryForType(int type) {
        switch (type) {
            case TYPE_CHATS: {
                int on = 0;
                if (MglaGlassConfig.isChatSendButtonMorphEnabled()) on++;
                if (MglaGlassConfig.isChatReactionSpringEnabled()) on++;
                String md3 = on == 0 ? "Выкл." : on + " из 2";
                return MglaTextAnimConfig.getSummary() + " · " + md3;
            }
            case TYPE_DIALOGS: {
                int on = 0;
                if (MglaGlassConfig.isDialogsAvatarHeroEnabled()) on++;
                if (MglaGlassConfig.isDialogsSearchExpandEnabled()) on++;
                if (MglaGlassConfig.isDialogsFabMorphEnabled()) on++;
                return on == 0 ? "Выкл." : on + " из 3";
            }
            default: {
                String style = MglaGlassConfig.getPredictiveBackStyleTitle(MglaGlassConfig.getPredictiveBackStyle());
                int extras = 0;
                if (MglaGlassConfig.isReverseChatOpenEnabled()) extras++;
                if (MglaGlassConfig.isProfileMediaGrowEnabled()) extras++;
                if (MglaGlassConfig.isSettingsPressSpringEnabled()) extras++;
                return extras == 0 ? style : style + " · +" + extras;
            }
        }
    }
}
