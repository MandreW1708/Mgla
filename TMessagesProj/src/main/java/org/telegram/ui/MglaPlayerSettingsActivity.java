package org.telegram.ui;

import android.content.Context;
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
import org.telegram.ui.Components.LayoutHelper;

public class MglaPlayerSettingsActivity extends MglaBaseSettingsActivity {

    public MglaPlayerSettingsActivity() {
        this(null);
    }

    public MglaPlayerSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Плеер");
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

        LinearLayout lookBlock = MglaUi.createBlock(context, "Внешний вид");
        lookBlock.addView(checkCell(context, "Большая обложка", MglaPlayerConfig.isLargeCoverEnabled(),
                v -> {
                    boolean on = !MglaPlayerConfig.isLargeCoverEnabled();
                    MglaPlayerConfig.setLargeCoverEnabled(on);
                    ((TextCheckCell) v).setChecked(on);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        lookBlock.addView(MglaUi.createDivider(context));
        lookBlock.addView(checkCell(context, "Динамические цвета из обложки", MglaPlayerConfig.isDynamicColorsEnabled(),
                v -> {
                    boolean on = !MglaPlayerConfig.isDynamicColorsEnabled();
                    MglaPlayerConfig.setDynamicColorsEnabled(on);
                    ((TextCheckCell) v).setChecked(on);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(lookBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 6));

        LinearLayout animBlock = MglaUi.createBlock(context, "Анимации");
        animBlock.addView(checkCell(context, "Анимация появления", MglaPlayerConfig.isOpenAnimationEnabled(),
                v -> {
                    boolean on = !MglaPlayerConfig.isOpenAnimationEnabled();
                    MglaPlayerConfig.setOpenAnimationEnabled(on);
                    ((TextCheckCell) v).setChecked(on);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        animBlock.addView(MglaUi.createDivider(context));
        animBlock.addView(checkCell(context, "Смена трека", MglaPlayerConfig.isTrackChangeAnimationEnabled(),
                v -> {
                    boolean on = !MglaPlayerConfig.isTrackChangeAnimationEnabled();
                    MglaPlayerConfig.setTrackChangeAnimationEnabled(on);
                    ((TextCheckCell) v).setChecked(on);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        animBlock.addView(MglaUi.createDivider(context));
        animBlock.addView(checkCell(context, "Пульсация обложки", MglaPlayerConfig.isCoverPulseEnabled(),
                v -> {
                    boolean on = !MglaPlayerConfig.isCoverPulseEnabled();
                    MglaPlayerConfig.setCoverPulseEnabled(on);
                    ((TextCheckCell) v).setChecked(on);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        animBlock.addView(MglaUi.createDivider(context));
        animBlock.addView(checkCell(context, "Анимация кнопок", MglaPlayerConfig.isButtonAnimationsEnabled(),
                v -> {
                    boolean on = !MglaPlayerConfig.isButtonAnimationsEnabled();
                    MglaPlayerConfig.setButtonAnimationsEnabled(on);
                    ((TextCheckCell) v).setChecked(on);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        animBlock.addView(MglaUi.createDivider(context));
        animBlock.addView(checkCell(context, "Движение фона", MglaPlayerConfig.isAmbientMotionEnabled(),
                v -> {
                    boolean on = !MglaPlayerConfig.isAmbientMotionEnabled();
                    MglaPlayerConfig.setAmbientMotionEnabled(on);
                    ((TextCheckCell) v).setChecked(on);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        animBlock.addView(MglaUi.createDivider(context));
        animBlock.addView(checkCell(context, "Анимированный таймлайн", MglaPlayerConfig.isSeekbarAnimationsEnabled(),
                v -> {
                    boolean on = !MglaPlayerConfig.isSeekbarAnimationsEnabled();
                    MglaPlayerConfig.setSeekbarAnimationsEnabled(on);
                    ((TextCheckCell) v).setChecked(on);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(animBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 10, 16, 6));

        LinearLayout speedBlock = MglaUi.createBlock(context, "Скорость анимаций");
        final int[] speeds = {MglaPlayerConfig.SPEED_SLOW, MglaPlayerConfig.SPEED_NORMAL, MglaPlayerConfig.SPEED_FAST};
        final RadioCell[] speedCells = new RadioCell[speeds.length];
        final int current = MglaPlayerConfig.getAnimationSpeed();
        for (int i = 0; i < speeds.length; i++) {
            final int speed = speeds[i];
            RadioCell cell = new RadioCell(context);
            cell.setBackground(null);
            cell.setText(MglaPlayerConfig.getAnimationSpeedTitle(speed), current == speed, i < speeds.length - 1);
            cell.setOnClickListener(v -> {
                MglaPlayerConfig.setAnimationSpeed(speed);
                for (int j = 0; j < speedCells.length; j++) {
                    speedCells[j].setChecked(speeds[j] == speed, true);
                }
            });
            speedCells[i] = cell;
            speedBlock.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            if (i < speeds.length - 1) {
                speedBlock.addView(MglaUi.createDivider(context));
            }
        }
        rootLayout.addView(speedBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 10, 16, 16));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        fragmentView = scrollView;
        return fragmentView;
    }

    private static TextCheckCell checkCell(Context context, String title, boolean checked, View.OnClickListener listener) {
        TextCheckCell cell = new TextCheckCell(context);
        cell.setBackground(null);
        cell.setTextAndCheck(title, checked, false);
        cell.setOnClickListener(listener);
        return cell;
    }
}
