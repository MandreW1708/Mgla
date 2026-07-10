package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaAudioConfig;
import org.telegram.messenger.MglaHeaderConfig;
import org.telegram.messenger.MglaSpyConfig;
import org.telegram.messenger.MglaTransferConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

public class MglaMainSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private SharedPreferences prefs;
    private TextCheckCell ghostModeCell;

    public MglaMainSettingsActivity() {
        this(null);
    }

    public MglaMainSettingsActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ghostModeChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ghostModeChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ghostModeChanged && ghostModeCell != null) {
            ghostModeCell.setChecked(MglaSpyConfig.isGhostModeEnabled());
        }
    }

    @Override
    public View createView(Context context) {
        prefs = context.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Общие настройки");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(rootLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        LinearLayout block = createBlock(context, "Базовые");

        TextCheckCell proxyCell = new TextCheckCell(context);
        proxyCell.setBackground(null);
        proxyCell.setTextAndCheck("Прокси в шапке", MglaHeaderConfig.isProxyInHeader(), true);
        proxyCell.setOnClickListener(v -> {
            boolean newVal = !MglaHeaderConfig.isProxyInHeader();
            MglaHeaderConfig.setProxyInHeader(newVal);
            proxyCell.setChecked(newVal);
        });
        block.addView(proxyCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckCell downloadsHeaderCell = new TextCheckCell(context);
        downloadsHeaderCell.setBackground(null);
        downloadsHeaderCell.setTextAndCheck("Загрузки в шапке", MglaHeaderConfig.isDownloadsInHeader(), true);
        downloadsHeaderCell.setOnClickListener(v -> {
            boolean newVal = !MglaHeaderConfig.isDownloadsInHeader();
            MglaHeaderConfig.setDownloadsInHeader(newVal);
            downloadsHeaderCell.setChecked(newVal);
        });
        block.addView(downloadsHeaderCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckCell hapticCell = new TextCheckCell(context);
        hapticCell.setBackground(null);
        hapticCell.setTextAndCheck("Виброотклик", prefs.getBoolean("haptic_enabled", false), true);
        hapticCell.setOnClickListener(v -> {
            boolean newVal = !prefs.getBoolean("haptic_enabled", false);
            prefs.edit().putBoolean("haptic_enabled", newVal).apply();
            hapticCell.setChecked(newVal);
        });
        block.addView(hapticCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView[] strengthValueRef = new TextView[1];
        addSelectRow(block, "Сила вибрации", MglaHapticManager.STRENGTH_NAMES[MglaHapticManager.getStrength()], () -> {
            showStrengthDialog(strengthValueRef[0]);
        }, strengthValueRef);

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        LinearLayout spyBlock = createBlock(context, "Шпион");

        final TextCheckCell[] deletedSubCells = new TextCheckCell[4];

        TextCheckCell saveDeletedCell = new TextCheckCell(context);
        saveDeletedCell.setBackground(null);
        saveDeletedCell.setTextAndCheck("Сохранение удаленных", MglaSpyConfig.isSaveDeletedMessagesEnabled(), true);
        saveDeletedCell.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isSaveDeletedMessagesEnabled();
            MglaSpyConfig.setSaveDeletedMessagesEnabled(newVal);
            saveDeletedCell.setChecked(newVal);
            updateDeletedSubCellsState(deletedSubCells, newVal);
        });
        spyBlock.addView(saveDeletedCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        spyBlock.addView(createIndentedDivider(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        deletedSubCells[0] = createDeletedSubCell(context, "Личные чаты", MglaSpyConfig.isSaveDeletedForPrivateEnabled(), MglaSpyConfig::setSaveDeletedForPrivateEnabled);
        spyBlock.addView(deletedSubCells[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        deletedSubCells[1] = createDeletedSubCell(context, "Группы", MglaSpyConfig.isSaveDeletedForGroupsEnabled(), MglaSpyConfig::setSaveDeletedForGroupsEnabled);
        spyBlock.addView(deletedSubCells[1], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        deletedSubCells[2] = createDeletedSubCell(context, "Каналы", MglaSpyConfig.isSaveDeletedForChannelsEnabled(), MglaSpyConfig::setSaveDeletedForChannelsEnabled);
        spyBlock.addView(deletedSubCells[2], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        deletedSubCells[3] = createDeletedSubCell(context, "Комментарии в каналах", MglaSpyConfig.isSaveDeletedForCommentsEnabled(), MglaSpyConfig::setSaveDeletedForCommentsEnabled);
        spyBlock.addView(deletedSubCells[3], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateDeletedSubCellsState(deletedSubCells, MglaSpyConfig.isSaveDeletedMessagesEnabled());

        ghostModeCell = new TextCheckCell(context);
        ghostModeCell.setBackground(null);
        ghostModeCell.setTextAndCheck("Режим призрака", MglaSpyConfig.isGhostModeEnabled(), false);
        ghostModeCell.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isGhostModeEnabled();
            MglaSpyConfig.setGhostModeEnabled(newVal);
            ghostModeCell.setChecked(newVal);
        });
        spyBlock.addView(ghostModeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(spyBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout transferBlock = createBlock(context, "Загрузка");

        TextView[] downloadModeValueRef = new TextView[1];
        addSelectRow(transferBlock, "Ускорение загрузки", MglaTransferConfig.getModeName(MglaTransferConfig.getDownloadMode()), () -> {
            showTransferModeDialog(true, downloadModeValueRef[0]);
        }, downloadModeValueRef);

        transferBlock.addView(createDivider(context));

        TextView[] uploadModeValueRef = new TextView[1];
        addSelectRow(transferBlock, "Ускорение отправки", MglaTransferConfig.getModeName(MglaTransferConfig.getUploadMode()), () -> {
            showTransferModeDialog(false, uploadModeValueRef[0]);
        }, uploadModeValueRef);

        rootLayout.addView(transferBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout soundBlock = createBlock(context, "Звук");

        TextCheckCell autoPauseCell = new TextCheckCell(context);
        autoPauseCell.setBackground(null);
        autoPauseCell.setTextAndCheck("Автопауза", MglaAudioConfig.isAutoPauseEnabled(), false);
        autoPauseCell.setOnClickListener(v -> {
            boolean newVal = !MglaAudioConfig.isAutoPauseEnabled();
            MglaAudioConfig.setAutoPauseEnabled(newVal);
            autoPauseCell.setChecked(newVal);
        });
        soundBlock.addView(autoPauseCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(soundBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, AndroidUtilities.navigationBarHeight + 16));

        fragmentView = scrollView;
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

    private View createDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        divider.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 21, 0, 21, 0));
        return divider;
    }

    private void showTransferModeDialog(boolean download, TextView valueView) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle(download ? "Ускорение загрузки" : "Ускорение отправки");
        dlg.setItems(MglaTransferConfig.MODE_NAMES, (dialog, which) -> {
            if (download) {
                MglaTransferConfig.setDownloadMode(which);
            } else {
                MglaTransferConfig.setUploadMode(which);
            }
            if (valueView != null) {
                valueView.setText(MglaTransferConfig.MODE_NAMES[which]);
            }
        });
        showDialog(dlg.create());
    }

    private void showStrengthDialog(TextView valueView) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder dlg = new AlertDialog.Builder(getParentActivity());
        dlg.setTitle("Сила вибрации");
        dlg.setItems(MglaHapticManager.STRENGTH_NAMES, (dialog, which) -> {
            prefs.edit().putInt("haptic_strength", which).apply();
            if (valueView != null) {
                valueView.setText(MglaHapticManager.STRENGTH_NAMES[which]);
            }
            MglaHapticManager.previewPattern(MglaHapticManager.getPatternForAction(MglaHapticManager.ACTION_DEFAULT));
        });
        showDialog(dlg.create());
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

    private void addSelectRow(LinearLayout block, String title, String value, Runnable onClick) {
        addSelectRow(block, title, value, onClick, null);
    }

    private TextCheckCell createDeletedSubCell(Context context, String title, boolean checked, MglaDeletedSetter setter) {
        TextCheckCell cell = new TextCheckCell(context);
        cell.setBackground(null);
        cell.setTextAndCheck(title, checked, true);
        cell.setPadding(dp(36), cell.getPaddingTop(), cell.getPaddingRight(), cell.getPaddingBottom());
        cell.setOnClickListener(v -> {
            if (!MglaSpyConfig.isSaveDeletedMessagesEnabled()) return;
            boolean newVal = !cell.isChecked();
            setter.set(newVal);
            cell.setChecked(newVal);
        });
        return cell;
    }

    private View createIndentedDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        divider.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 36, 0, 0, 0));
        return divider;
    }

    private void updateDeletedSubCellsState(TextCheckCell[] cells, boolean enabled) {
        float alpha = enabled ? 1f : 0.4f;
        for (TextCheckCell cell : cells) {
            if (cell != null) {
                cell.setAlpha(alpha);
                cell.setEnabled(enabled);
            }
        }
    }

    private interface MglaDeletedSetter {
        void set(boolean enabled);
    }
}
