package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MglaChatsConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

public class MglaChatsSettingsActivity extends BaseFragment {

    private SharedPreferences prefs;

    public MglaChatsSettingsActivity() {
        this(null);
    }

    public MglaChatsSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        prefs = context.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Чаты");
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

        LinearLayout basicBlock = createBlock(context, "Базовое");

        TextSettingsCell menuCell = new TextSettingsCell(context);
        menuCell.setBackground(null);
        menuCell.setText("Настроить меню сообщения", true);
        menuCell.setCanDisable(false);
        menuCell.setOnClickListener(v -> presentFragment(new MglaMessageMenuSettingsActivity()));
        basicBlock.addView(menuCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckCell timeCell = new TextCheckCell(context);
        timeCell.setBackground(null);
        timeCell.setTextAndCheck("Время с секундами", prefs.getBoolean("chat_time_seconds", false), false);
        timeCell.setOnClickListener(v -> {
            boolean enabled = !prefs.getBoolean("chat_time_seconds", false);
            prefs.edit().putBoolean("chat_time_seconds", enabled).apply();
            timeCell.setChecked(enabled);
            notifyTimeFormatChanged();
        });
        basicBlock.addView(timeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(basicBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        LinearLayout chatsBlock = createBlock(context, "В чатах");

        TextView[] recentValueRef = new TextView[1];
        addSelectRow(chatsBlock, "Количество недавних стикеров", String.valueOf(MglaChatsConfig.getRecentStickersLimit()), () -> showRecentStickersDialog(recentValueRef[0]), recentValueRef);

        TextCheckCell stickerTimeCell = new TextCheckCell(context);
        stickerTimeCell.setBackground(null);
        stickerTimeCell.setTextAndCheck("Убрать время на стикерах", MglaChatsConfig.isStickerTimeHidden(), true);
        stickerTimeCell.setOnClickListener(v -> {
            boolean newVal = !MglaChatsConfig.isStickerTimeHidden();
            MglaChatsConfig.setStickerTimeHidden(newVal);
            stickerTimeCell.setChecked(newVal);
        });
        chatsBlock.addView(stickerTimeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(chatsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

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

    private void showRecentStickersDialog(TextView valueView) {
        if (getParentActivity() == null) {
            return;
        }

        BottomSheet bottomSheet = new BottomSheet(getParentActivity(), false) {
            @Override
            protected boolean canDismissWithSwipe() {
                return true;
            }
        };

        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(8), dp(16), dp(16));

        TextView titleView = new TextView(getContext());
        titleView.setText("Количество недавних стикеров");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        TextView valueLabel = new TextView(getContext());
        valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        valueLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        valueLabel.setGravity(Gravity.CENTER);
        container.addView(valueLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        final int[] currentValue = {MglaChatsConfig.getRecentStickersLimit()};
        valueLabel.setText(String.valueOf(currentValue[0]));

        SeekBarView seekBar = new SeekBarView(getContext());
        seekBar.setReportChanges(true);
        seekBar.setProgress((currentValue[0] - MglaChatsConfig.RECENT_STICKERS_MIN) / (float) (MglaChatsConfig.RECENT_STICKERS_MAX - MglaChatsConfig.RECENT_STICKERS_MIN));
        seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                currentValue[0] = MglaChatsConfig.RECENT_STICKERS_MIN + Math.round(progress * (MglaChatsConfig.RECENT_STICKERS_MAX - MglaChatsConfig.RECENT_STICKERS_MIN));
                valueLabel.setText(String.valueOf(currentValue[0]));
                if (stop) {
                    MglaChatsConfig.setRecentStickersLimit(currentValue[0]);
                    if (valueView != null) {
                        valueView.setText(String.valueOf(currentValue[0]));
                    }
                }
            }

            @Override
            public void onSeekBarPressed(boolean pressed) {
            }
        });
        container.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 0, 4, 0, 0));

        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(LinearLayout.HORIZONTAL);

        TextView minLabel = new TextView(getContext());
        minLabel.setText(String.valueOf(MglaChatsConfig.RECENT_STICKERS_MIN));
        minLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        minLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        labels.addView(minLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.LEFT));

        TextView maxLabel = new TextView(getContext());
        maxLabel.setText(String.valueOf(MglaChatsConfig.RECENT_STICKERS_MAX));
        maxLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        maxLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        maxLabel.setGravity(Gravity.RIGHT);
        labels.addView(maxLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.RIGHT));

        container.addView(labels, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        bottomSheet.setCustomView(container);
        showDialog(bottomSheet);
    }

    private void addSelectRow(LinearLayout block, String title, String value, Runnable onClick, TextView[] valueRef) {
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

    private void notifyTimeFormatChanged() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_MESSAGE_TEXT);
        }
    }
}
