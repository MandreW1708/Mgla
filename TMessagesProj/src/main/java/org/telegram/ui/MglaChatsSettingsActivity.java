package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MglaChatsConfig;
import org.telegram.messenger.MglaFeatureFlags;
import org.telegram.messenger.MglaHiddenChats;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

public class MglaChatsSettingsActivity extends BaseFragment {

    private SharedPreferences prefs;
    private ImageView previewInIcon;
    private ImageView previewOutIcon;
    private TextView hiddenOpenValue;
    private TextView hiddenPasscodeValue;
    private TextCheckCell hiddenFingerprintCell;

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

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        LinearLayout basicBlock = createBlock(context, "Базовое");

        TextSettingsCell menuCell = new TextSettingsCell(context);
        menuCell.setBackground(null);
        menuCell.setText("Элементы меню сообщения", false);
        menuCell.setCanDisable(false);
        menuCell.setOnClickListener(v -> presentFragment(new MglaMessageMenuSettingsActivity()));
        basicBlock.addView(menuCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        basicBlock.addView(MglaUi.createDivider(context));

        TextCheckCell timeCell = new TextCheckCell(context);
        timeCell.setBackground(null);
        timeCell.setTextAndCheck("Время с секундами", MglaChatsConfig.isChatTimeSecondsEnabled(), false);
        timeCell.setOnClickListener(v -> {
            boolean enabled = !MglaChatsConfig.isChatTimeSecondsEnabled();
            if (enabled && !MglaFeatureFlags.isAllowed(MglaChatsConfig.PREF_CHAT_TIME_SECONDS)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                timeCell.setChecked(false);
                return;
            }
            MglaChatsConfig.setChatTimeSecondsEnabled(enabled);
            timeCell.setChecked(MglaChatsConfig.isChatTimeSecondsEnabled());
            notifyTimeFormatChanged();
        });
        basicBlock.addView(timeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(basicBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        LinearLayout chatsBlock = createBlock(context, "В чатах");

        TextView[] recentValueRef = new TextView[1];
        addSelectRow(chatsBlock, "Количество недавних стикеров", String.valueOf(MglaChatsConfig.getRecentStickersLimit()), () -> showRecentStickersDialog(recentValueRef[0]), recentValueRef);

        chatsBlock.addView(MglaUi.createDivider(context));

        TextCheckCell stickerTimeCell = new TextCheckCell(context);
        stickerTimeCell.setBackground(null);
        stickerTimeCell.setTextAndCheck("Убрать время на стикерах", MglaChatsConfig.isStickerTimeHidden(), false);
        stickerTimeCell.setOnClickListener(v -> {
            boolean newVal = !MglaChatsConfig.isStickerTimeHidden();
            MglaChatsConfig.setStickerTimeHidden(newVal);
            stickerTimeCell.setChecked(newVal);
        });
        chatsBlock.addView(stickerTimeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(chatsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout doubleTapBlock = createBlock(context, "Двойной тап");

        // The preview used to overlap the divider: its last row extends to the
        // bottom of the panel while this negative margin pulled the divider up.
        // Keep a small footer below both bubbles instead.
        doubleTapBlock.addView(createDoubleTapPreview(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        doubleTapBlock.addView(MglaUi.createDivider(context));

        TextView[] outValueRef = new TextView[1];
        addSelectRow(doubleTapBlock, "Исходящее сообщение", MglaChatsConfig.getDoubleTapActionTitle(MglaChatsConfig.getDoubleTapAction(true)),
            () -> showDoubleTapActionDialog(true, outValueRef[0]), outValueRef);

        doubleTapBlock.addView(MglaUi.createDivider(context));

        TextView[] inValueRef = new TextView[1];
        addSelectRow(doubleTapBlock, "Входящее сообщение", MglaChatsConfig.getDoubleTapActionTitle(MglaChatsConfig.getDoubleTapAction(false)),
            () -> showDoubleTapActionDialog(false, inValueRef[0]), inValueRef);

        rootLayout.addView(doubleTapBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout chatOptionsBlock = createBlock(context, "Чаты");

        TextView[] bottomButtonValueRef = new TextView[1];
        addSelectRow(chatOptionsBlock, "Нижняя кнопка", MglaChatsConfig.getBottomButtonModeTitle(MglaChatsConfig.getBottomButtonMode()),
            () -> showBottomButtonDialog(bottomButtonValueRef[0]), bottomButtonValueRef);

        chatOptionsBlock.addView(MglaUi.createDivider(context));

        TextCheckCell hideKeyboardCell = new TextCheckCell(context);
        hideKeyboardCell.setBackground(null);
        hideKeyboardCell.setTextAndCheck("Скрывать клавиатуру при прокрутке", MglaChatsConfig.isHideKeyboardOnScroll(), false);
        hideKeyboardCell.setOnClickListener(v -> {
            boolean newVal = !MglaChatsConfig.isHideKeyboardOnScroll();
            if (newVal && !MglaFeatureFlags.isAllowed(MglaChatsConfig.PREF_HIDE_KEYBOARD_ON_SCROLL)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                hideKeyboardCell.setChecked(false);
                return;
            }
            MglaChatsConfig.setHideKeyboardOnScroll(newVal);
            hideKeyboardCell.setChecked(MglaChatsConfig.isHideKeyboardOnScroll());
        });
        chatOptionsBlock.addView(hideKeyboardCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        chatOptionsBlock.addView(MglaUi.createDivider(context));

        TextCheckCell commaCell = new TextCheckCell(context);
        commaCell.setBackground(null);
        commaCell.setTextAndCheck("Запятая после упоминания", MglaChatsConfig.isCommaAfterMention(), false);
        commaCell.setOnClickListener(v -> {
            boolean newVal = !MglaChatsConfig.isCommaAfterMention();
            if (newVal && !MglaFeatureFlags.isAllowed(MglaChatsConfig.PREF_COMMA_AFTER_MENTION)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                commaCell.setChecked(false);
                return;
            }
            MglaChatsConfig.setCommaAfterMention(newVal);
            commaCell.setChecked(MglaChatsConfig.isCommaAfterMention());
        });
        chatOptionsBlock.addView(commaCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(chatOptionsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout hiddenBlock = createBlock(context, "Скрытые чаты");

        TextView[] hiddenOpenRef = new TextView[1];
        addSelectRow(hiddenBlock, "Открыть скрытые чаты", hiddenCountText(), this::openHiddenChats, hiddenOpenRef);
        hiddenOpenValue = hiddenOpenRef[0];

        hiddenBlock.addView(MglaUi.createDivider(context));

        TextView[] passcodeRef = new TextView[1];
        addSelectRow(hiddenBlock, "Код-пароль", hiddenPasscodeText(), this::changeHiddenPasscode, passcodeRef);
        hiddenPasscodeValue = passcodeRef[0];

        hiddenBlock.addView(MglaUi.createDivider(context));

        hiddenFingerprintCell = new TextCheckCell(context);
        hiddenFingerprintCell.setBackground(null);
        hiddenFingerprintCell.setTextAndValueAndCheck("Разблокировка отпечатком", MglaHiddenChats.isFingerprintAvailable() ? "Вместо ввода кода" : "Отпечаток не настроен на устройстве", MglaHiddenChats.isFingerprintEnabled(), true, false);
        hiddenFingerprintCell.setEnabled(MglaHiddenChats.isFingerprintAvailable(), null);
        hiddenFingerprintCell.setOnClickListener(v -> {
            if (!MglaHiddenChats.isFingerprintAvailable()) {
                return;
            }
            boolean newVal = !MglaHiddenChats.isFingerprintEnabled();
            MglaHiddenChats.setFingerprintEnabled(newVal);
            hiddenFingerprintCell.setChecked(newVal);
        });
        hiddenBlock.addView(hiddenFingerprintCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        hiddenBlock.addView(MglaUi.createDivider(context));

        TextView[] notifyRef = new TextView[1];
        addSelectRow(hiddenBlock, "Уведомления", notifyModeTitle(MglaHiddenChats.getNotifyMode()), () -> showHiddenNotifyDialog(notifyRef[0]), notifyRef);

        rootLayout.addView(hiddenBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        TextView hiddenInfo = new TextView(context);
        hiddenInfo.setText("Чтобы скрыть чат, удерживайте его в списке чатов и выберите «Скрыть чат» в меню «⋮». Чтобы открыть скрытые чаты, удерживайте кнопку «⋮» в шапке списка чатов.");
        hiddenInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hiddenInfo.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        rootLayout.addView(hiddenInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 8, 32, 16));

        fragmentView = scrollView;
        return fragmentView;
    }

    private LinearLayout createBlock(Context context, String title) {
        return MglaUi.createBlock(context, title);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateHiddenValues();
    }

    private void updateHiddenValues() {
        if (hiddenOpenValue != null) {
            hiddenOpenValue.setText(hiddenCountText());
        }
        if (hiddenPasscodeValue != null) {
            hiddenPasscodeValue.setText(hiddenPasscodeText());
        }
    }

    private String hiddenCountText() {
        int count = MglaHiddenChats.getHiddenCount(currentAccount);
        return count == 0 ? "Нет" : String.valueOf(count);
    }

    private String hiddenPasscodeText() {
        return MglaHiddenChats.hasPasscode() ? "Изменить" : "Не задан";
    }

    private static String notifyModeTitle(int mode) {
        switch (mode) {
            case MglaHiddenChats.NOTIFY_SHOW:
                return "Показывать";
            case MglaHiddenChats.NOTIFY_OFF:
                return "Отключены";
            default:
                return "Без текста";
        }
    }

    private void openHiddenChats() {
        if (!MglaHiddenChats.isFeatureAllowed()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
            return;
        }
        Utilities.Callback<PasscodeActivity> open = fragment -> {
            Bundle args = new Bundle();
            args.putBoolean("mgla_hidden", true);
            fragment.presentFragment(new DialogsActivity(args), true);
        };
        presentFragment(PasscodeActivity.createMglaHiddenChats(MglaHiddenChats.hasPasscode() ? PasscodeActivity.TYPE_ENTER_CODE_TO_MANAGE_SETTINGS : PasscodeActivity.TYPE_SETUP_CODE, open));
    }

    private void changeHiddenPasscode() {
        Utilities.Callback<PasscodeActivity> done = BaseFragment::finishFragment;
        if (MglaHiddenChats.hasPasscode()) {
            presentFragment(PasscodeActivity.createMglaHiddenChats(PasscodeActivity.TYPE_ENTER_CODE_TO_MANAGE_SETTINGS,
                fragment -> fragment.presentFragment(PasscodeActivity.createMglaHiddenChats(PasscodeActivity.TYPE_SETUP_CODE, done), true)));
        } else {
            presentFragment(PasscodeActivity.createMglaHiddenChats(PasscodeActivity.TYPE_SETUP_CODE, done));
        }
    }

    private void showHiddenNotifyDialog(TextView valueView) {
        if (getParentActivity() == null) {
            return;
        }
        final int[] modes = {MglaHiddenChats.NOTIFY_SHOW, MglaHiddenChats.NOTIFY_NO_CONTENT, MglaHiddenChats.NOTIFY_OFF};
        String[] names = {"Показывать как обычно", "Без имени и текста", "Отключить"};
        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Уведомления скрытых чатов");
        dlg.setItems(names, (dialog, which) -> {
            MglaHiddenChats.setNotifyMode(modes[which]);
            if (valueView != null) {
                valueView.setText(notifyModeTitle(modes[which]));
            }
        });
        showDialog(dlg.create());
    }

    /**
     * Мини-превью чата: входящее (слева) и исходящее (справа) сообщение,
     * вместо текста — значок выбранного действия двойного тапа (обновляется динамически).
     */
    private View createDoubleTapPreview(Context context) {
        FrameLayout panel = new FrameLayout(context);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        panel.setBackground(panelBg);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(6));
        panel.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout inRow = new FrameLayout(context);
        LinearLayout inBubble = createPreviewBubble(context, false);
        previewInIcon = createPreviewIcon(context, false);
        inBubble.addView(previewInIcon, LayoutHelper.createLinear(20, 20));
        inRow.addView(inBubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        content.addView(inRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, dp(32), 0, 0, 0, -dp(20)));

        FrameLayout outRow = new FrameLayout(context);
        LinearLayout outBubble = createPreviewBubble(context, true);
        previewOutIcon = createPreviewIcon(context, true);
        outBubble.addView(previewOutIcon, LayoutHelper.createLinear(20, 20));
        outRow.addView(outBubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        content.addView(outRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, dp(32), 0, -dp(4), 0, 0));

        updateDoubleTapPreview();
        return panel;
    }

    private LinearLayout createPreviewBubble(Context context, boolean out) {
        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.HORIZONTAL);
        bubble.setGravity(Gravity.CENTER);
        bubble.setPadding(dp(16), dp(6), dp(16), dp(6));
        bubble.setMinimumWidth(dp(124));

        float r = dp(16);
        float tail = dp(5);
        GradientDrawable bg = new GradientDrawable();
        if (out) {
            bg.setCornerRadii(new float[]{r, r, r, r, tail, tail, r, r});
            bg.setColor(Theme.getColor(Theme.key_chat_outBubble));
        } else {
            bg.setCornerRadii(new float[]{r, r, r, r, r, r, tail, tail});
            bg.setColor(Theme.getColor(Theme.key_chat_inBubble));
        }
        bubble.setBackground(bg);
        return bubble;
    }

    private ImageView createPreviewIcon(Context context, boolean out) {
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(Theme.getColor(out ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn), PorterDuff.Mode.SRC_IN);
        return icon;
    }

    private void updateDoubleTapPreview() {
        if (previewInIcon != null) {
            previewInIcon.setImageResource(MglaChatsConfig.getDoubleTapActionIcon(MglaChatsConfig.getDoubleTapAction(false)));
        }
        if (previewOutIcon != null) {
            previewOutIcon.setImageResource(MglaChatsConfig.getDoubleTapActionIcon(MglaChatsConfig.getDoubleTapAction(true)));
        }
    }

    private void showBottomButtonDialog(TextView valueView) {
        if (getParentActivity() == null) {
            return;
        }
        final int[] modes = {MglaChatsConfig.BOTTOM_BUTTON_MUTE, MglaChatsConfig.BOTTOM_BUTTON_HIDE, MglaChatsConfig.BOTTOM_BUTTON_DISCUSS};
        String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            names[i] = MglaChatsConfig.getBottomButtonModeTitle(modes[i]);
        }

        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Нижняя кнопка");
        dlg.setItems(names, (dialog, which) -> {
            MglaChatsConfig.setBottomButtonMode(modes[which]);
            if (valueView != null) {
                valueView.setText(names[which]);
            }
        });
        showDialog(dlg.create());
    }

    private void showDoubleTapActionDialog(boolean outgoing, TextView valueView) {
        if (getParentActivity() == null) {
            return;
        }
        final int[] actions = MglaChatsConfig.DOUBLE_TAP_ACTIONS;
        String[] names = new String[actions.length];
        int[] icons = new int[actions.length];
        for (int i = 0; i < actions.length; i++) {
            names[i] = MglaChatsConfig.getDoubleTapActionTitle(actions[i]);
            icons[i] = MglaChatsConfig.getDoubleTapActionIcon(actions[i]);
        }

        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle(outgoing ? "Исходящее сообщение" : "Входящее сообщение");
        dlg.setItems(names, icons, (dialog, which) -> {
            MglaChatsConfig.setDoubleTapAction(outgoing, actions[which]);
            if (valueView != null) {
                valueView.setText(MglaChatsConfig.getDoubleTapActionTitle(actions[which]));
            }
            updateDoubleTapPreview();
        });
        showDialog(dlg.create());
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

    private void notifyTimeFormatChanged() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_MESSAGE_TEXT);
        }
    }
}
