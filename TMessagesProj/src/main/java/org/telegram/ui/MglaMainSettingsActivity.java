package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaAudioConfig;
import org.telegram.messenger.MglaFeatureFlags;
import org.telegram.messenger.MglaHeaderConfig;
import org.telegram.messenger.MglaSettingsSnapshot;
import org.telegram.messenger.MglaSettingsSync;
import org.telegram.messenger.MglaSpyConfig;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.MglaTransferConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

public class MglaMainSettingsActivity extends MglaBaseSettingsActivity implements NotificationCenter.NotificationCenterDelegate {

    private TextCheckCell ghostModeCell;
    private TextSettingsCell hapticCell;
    private ImageView saveDeletedHangWarning;

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
    public void onResume() {
        super.onResume();
        if (hapticCell != null) {
            hapticCell.setTextAndValue("Виброэффекты", MglaHapticManager.getSummary(), true);
        }
    }

    @Override
    public View createView(Context context) {
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
        proxyCell.setTextAndCheck("Прокси в шапке", MglaHeaderConfig.isProxyInHeader(), false);
        proxyCell.setOnClickListener(v -> {
            boolean newVal = !MglaHeaderConfig.isProxyInHeader();
            if (newVal && !MglaFeatureFlags.isAllowed(MglaHeaderConfig.KEY_PROXY_IN_HEADER)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                return;
            }
            MglaHeaderConfig.setProxyInHeader(newVal);
            proxyCell.setChecked(MglaHeaderConfig.isProxyInHeader());
        });
        block.addView(proxyCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        block.addView(MglaUi.createDivider(context));

        TextCheckCell downloadsHeaderCell = new TextCheckCell(context);
        downloadsHeaderCell.setBackground(null);
        downloadsHeaderCell.setTextAndCheck("Загрузки в шапке", MglaHeaderConfig.isDownloadsInHeader(), false);
        downloadsHeaderCell.setOnClickListener(v -> {
            boolean newVal = !MglaHeaderConfig.isDownloadsInHeader();
            if (newVal && !MglaFeatureFlags.isAllowed(MglaHeaderConfig.KEY_DOWNLOADS_IN_HEADER)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                return;
            }
            MglaHeaderConfig.setDownloadsInHeader(newVal);
            downloadsHeaderCell.setChecked(MglaHeaderConfig.isDownloadsInHeader());
        });
        block.addView(downloadsHeaderCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        block.addView(MglaUi.createDivider(context));

        TextCheckCell hangInBackgroundCell = new TextCheckCell(context);
        hangInBackgroundCell.setBackground(null);
        hangInBackgroundCell.setTextAndCheck("Висеть в фоне", MglaSpyConfig.isHangInBackgroundEnabled(), false);
        hangInBackgroundCell.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isHangInBackgroundEnabled();
            MglaSpyConfig.setHangInBackgroundEnabled(newVal);
            hangInBackgroundCell.setChecked(MglaSpyConfig.isHangInBackgroundEnabled());
            updateSaveDeletedHangWarning();
            BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.chats_infotip,
                    "Перезапустите клиент, чтобы применить фоновый режим",
                    "Перезапуск",
                    Bulletin.DURATION_PROLONG,
                    this::restartClient
            ).show();
        });
        block.addView(hangInBackgroundCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        block.addView(MglaUi.createDivider(context));

        hapticCell = new TextSettingsCell(context);
        hapticCell.setBackground(null);
        hapticCell.setTextAndValue("Виброэффекты", MglaHapticManager.getSummary(), false);
        hapticCell.setOnClickListener(v -> presentFragment(new MglaHapticSettingsActivity()));
        block.addView(hapticCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        LinearLayout spyBlock = createBlock(context, "Шпион");

        TextCheckCell lastOnlineCell = new TextCheckCell(context);
        lastOnlineCell.setBackground(null);
        lastOnlineCell.setTextAndCheck("Последний онлайн", MglaSpyConfig.isLastOnlineEnabled(), false);
        lastOnlineCell.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isLastOnlineEnabled();
            if (newVal && !MglaFeatureFlags.isAllowed("spy_last_online")) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                lastOnlineCell.setChecked(false);
                return;
            }
            MglaSpyConfig.setLastOnlineEnabled(newVal);
            lastOnlineCell.setChecked(MglaSpyConfig.isLastOnlineEnabled());
        });
        spyBlock.addView(lastOnlineCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        spyBlock.addView(MglaUi.createDivider(context));

        final TextCheckCell[] deletedSubCells = new TextCheckCell[4];

        FrameLayout saveDeletedRow = new FrameLayout(context);
        TextCheckCell saveDeletedCell = new TextCheckCell(context);
        saveDeletedCell.setBackground(null);
        saveDeletedCell.setTextAndCheck("Сохранение удаленных", MglaSpyConfig.isSaveDeletedMessagesEnabled(), false);
        saveDeletedCell.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isSaveDeletedMessagesEnabled();
            if (newVal && !MglaFeatureFlags.isAllowed("spy_save_deleted_messages")) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                saveDeletedCell.setChecked(false);
                return;
            }
            MglaSpyConfig.setSaveDeletedMessagesEnabled(newVal);
            boolean on = MglaSpyConfig.isSaveDeletedMessagesEnabled();
            saveDeletedCell.setChecked(on);
            updateDeletedSubCellsState(deletedSubCells, on);
            updateSaveDeletedHangWarning();
        });
        saveDeletedRow.addView(saveDeletedCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        saveDeletedHangWarning = new ImageView(context);
        saveDeletedHangWarning.setImageResource(R.drawable.warning_sign);
        saveDeletedHangWarning.setColorFilter(new PorterDuffColorFilter(0xFFFF9800, PorterDuff.Mode.SRC_IN));
        saveDeletedHangWarning.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        saveDeletedHangWarning.setPadding(dp(4), dp(12), dp(4), dp(12));
        saveDeletedHangWarning.setContentDescription("Предупреждение");
        saveDeletedHangWarning.setOnClickListener(v -> {
            if (getParentActivity() == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Сохранение удаленных");
            builder.setMessage("При выключенном «Висеть в фоне» клиент может останавливаться системой, и сохранение удалённых сообщений в фоне будет работать нестабильно.");
            builder.setPositiveButton("Понятно", null);
            showDialog(builder.create());
        });
        // Right of title text (16sp, 21dp padding); leave switch free on the right.
        TextView measureTv = new TextView(context);
        measureTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        int warnLeft = AndroidUtilities.dp(21) + Math.round(measureTv.getPaint().measureText("Сохранение удаленных")) + AndroidUtilities.dp(2);
        FrameLayout.LayoutParams warnLp = LayoutHelper.createFrame(28, 50, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        warnLp.leftMargin = warnLeft;
        saveDeletedRow.addView(saveDeletedHangWarning, warnLp);
        spyBlock.addView(saveDeletedRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateSaveDeletedHangWarning();

        spyBlock.addView(createIndentedDivider(context));

        deletedSubCells[0] = createDeletedSubCell(context, "Личные чаты", MglaSpyConfig.isSaveDeletedForPrivateEnabled(), MglaSpyConfig::setSaveDeletedForPrivateEnabled);
        spyBlock.addView(deletedSubCells[0], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        spyBlock.addView(createIndentedDivider(context));

        deletedSubCells[1] = createDeletedSubCell(context, "Группы до 100 человек", MglaSpyConfig.isSaveDeletedForGroupsSmallEnabled(), MglaSpyConfig::setSaveDeletedForGroupsSmallEnabled);
        spyBlock.addView(deletedSubCells[1], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        spyBlock.addView(createIndentedDivider(context));

        deletedSubCells[2] = createDeletedSubCell(context, "Каналы", MglaSpyConfig.isSaveDeletedForChannelsEnabled(), MglaSpyConfig::setSaveDeletedForChannelsEnabled);
        spyBlock.addView(deletedSubCells[2], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        spyBlock.addView(createIndentedDivider(context));

        deletedSubCells[3] = createDeletedSubCell(context, "Группы более 100 человек", MglaSpyConfig.isSaveDeletedForGroupsLargeEnabled(), MglaSpyConfig::setSaveDeletedForGroupsLargeEnabled);
        spyBlock.addView(deletedSubCells[3], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        spyBlock.addView(MglaUi.createDivider(context));

        updateDeletedSubCellsState(deletedSubCells, MglaSpyConfig.isSaveDeletedMessagesEnabled());

        ghostModeCell = new TextCheckCell(context);
        ghostModeCell.setBackground(null);
        ghostModeCell.setTextAndCheck("Режим призрака", MglaSpyConfig.isGhostModeEnabled(), false);
        ghostModeCell.setOnClickListener(v -> {
            boolean newVal = !MglaSpyConfig.isGhostModeEnabled();
            if (newVal && !MglaFeatureFlags.isAllowed("spy_ghost_mode")) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                ghostModeCell.setChecked(false);
                return;
            }
            MglaSpyConfig.setGhostModeEnabled(newVal);
            ghostModeCell.setChecked(MglaSpyConfig.isGhostModeEnabled());
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

        LinearLayout cloudBlock = createBlock(context, "Облако");
        final boolean canSync = MglaSubscription.canSyncSettings();

        TextSettingsCell syncSettingsCell = new TextSettingsCell(context);
        syncSettingsCell.setBackground(null);
        syncSettingsCell.setTextAndValue(premiumStarLabel(context, "Синхронизировать настройки"),
                syncCooldownValue(), true);
        syncSettingsCell.setEnabled(canSync);
        syncSettingsCell.setAlpha(canSync ? 1f : 0.4f);
        syncSettingsCell.setOnClickListener(v -> {
            if (!MglaSubscription.canSyncSettings()) {
                showSettingsSyncUpsell();
                return;
            }
            syncSettingsNow(syncSettingsCell);
        });
        cloudBlock.addView(syncSettingsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        cloudBlock.addView(createDivider(context));

        TextSettingsCell applySettingsCell = new TextSettingsCell(context);
        applySettingsCell.setBackground(null);
        applySettingsCell.setTextAndValue(premiumStarLabel(context, "Применить настройки"), "", true);
        applySettingsCell.setEnabled(canSync);
        applySettingsCell.setAlpha(canSync ? 1f : 0.4f);
        applySettingsCell.setOnClickListener(v -> {
            if (!MglaSubscription.canSyncSettings()) {
                showSettingsSyncUpsell();
                return;
            }
            applySettingsNow();
        });
        cloudBlock.addView(applySettingsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView syncHint = new TextView(context);
        syncHint.setText(canSync
                ? "Синхронизация отправляет настройки в облако (раз в 5 мин). На другом устройстве нажмите «Применить настройки»."
                : "Доступно с Premium, Premium+ и Ultra на текущем аккаунте.");
        syncHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        syncHint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        syncHint.setPadding(dp(21), dp(4), dp(21), dp(12));
        syncHint.setAlpha(canSync ? 1f : 0.4f);
        cloudBlock.addView(syncHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(cloudBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        LinearLayout soundBlock = createBlock(context, "Звук");

        TextCheckCell autoPauseCell = new TextCheckCell(context);
        autoPauseCell.setBackground(null);
        autoPauseCell.setTextAndCheck("Автопауза", MglaAudioConfig.isAutoPauseEnabled(), false);
        autoPauseCell.setOnClickListener(v -> {
            boolean newVal = !MglaAudioConfig.isAutoPauseEnabled();
            if (newVal && !MglaFeatureFlags.isAllowed(MglaAudioConfig.KEY_AUTO_PAUSE)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                autoPauseCell.setChecked(false);
                return;
            }
            MglaAudioConfig.setAutoPauseEnabled(newVal);
            autoPauseCell.setChecked(MglaAudioConfig.isAutoPauseEnabled());
        });
        soundBlock.addView(autoPauseCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rootLayout.addView(soundBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, AndroidUtilities.navigationBarHeight + 16));

        fragmentView = scrollView;
        return fragmentView;
    }

    private static CharSequence premiumStarLabel(Context context, String title) {
        SpannableStringBuilder sb = new SpannableStringBuilder("  " + title);
        Drawable star = context.getResources().getDrawable(R.drawable.msg_premium_liststar).mutate();
        int size = dp(16);
        star.setBounds(0, 0, size, size);
        star.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_starsGradient1), PorterDuff.Mode.SRC_IN));
        sb.setSpan(new ImageSpan(star, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static String syncCooldownValue() {
        long left = MglaSettingsSnapshot.getSyncCooldownRemainingMs();
        if (left <= 0L) {
            return "";
        }
        long totalSec = Math.max(1L, (left + 999L) / 1000L);
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        if (min <= 0L) {
            return sec + " с";
        }
        if (sec <= 0L) {
            return min + " мин";
        }
        return min + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private void showSettingsSyncUpsell() {
        BulletinFactory.of(this).createSimpleBulletin(
                R.raw.star_premium_2,
                "Синхронизация настроек доступна с любой подпиской Mgla Premium",
                "Оформить",
                () -> presentFragment(new MglaPremiumActivity())
        ).show();
    }

    private void syncSettingsNow(TextSettingsCell syncCell) {
        if (!MglaSubscription.canSyncSettings()) {
            return;
        }
        if (MglaSettingsSync.isBusy()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Операция уже выполняется…").show();
            return;
        }
        long cooldown = MglaSettingsSnapshot.getSyncCooldownRemainingMs();
        if (cooldown > 0L) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error,
                    "Подождите ещё " + syncCooldownValue()).show();
            return;
        }
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        showDialog(progress);
        MglaSettingsSync.syncNow(true, new MglaSettingsSync.Callback() {
            @Override
            public void onSuccess(String message) {
                try {
                    progress.dismiss();
                } catch (Throwable ignore) {
                }
                if (syncCell != null) {
                    syncCell.setTextAndValue(premiumStarLabel(getContext(), "Синхронизировать настройки"),
                            syncCooldownValue(), true);
                }
                BulletinFactory.of(MglaMainSettingsActivity.this)
                        .createSimpleBulletin(R.raw.contact_check, message == null ? "Готово" : message)
                        .show();
            }

            @Override
            public void onError(String message) {
                try {
                    progress.dismiss();
                } catch (Throwable ignore) {
                }
                BulletinFactory.of(MglaMainSettingsActivity.this)
                        .createSimpleBulletin(R.raw.error, message == null ? "Ошибка" : message)
                        .show();
            }
        });
    }

    private void applySettingsNow() {
        if (!MglaSubscription.canSyncSettings()) {
            return;
        }
        if (MglaSettingsSync.isBusy()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Операция уже выполняется…").show();
            return;
        }
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        showDialog(progress);
        MglaSettingsSync.applyNow(true, new MglaSettingsSync.Callback() {
            @Override
            public void onSuccess(String message) {
                try {
                    progress.dismiss();
                } catch (Throwable ignore) {
                }
                BulletinFactory.of(MglaMainSettingsActivity.this)
                        .createSimpleBulletin(R.raw.contact_check, message == null ? "Готово" : message)
                        .show();
                if (getParentLayout() != null) {
                    getParentLayout().rebuildAllFragmentViews(false, false);
                }
            }

            @Override
            public void onError(String message) {
                try {
                    progress.dismiss();
                } catch (Throwable ignore) {
                }
                BulletinFactory.of(MglaMainSettingsActivity.this)
                        .createSimpleBulletin(R.raw.error, message == null ? "Ошибка" : message)
                        .show();
            }
        });
    }

    private LinearLayout createBlock(Context context, String title) {
        return MglaUi.createBlock(context, title);
    }

    private View createDivider(Context context) {
        return MglaUi.createDivider(context);
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

    private void addSelectRow(LinearLayout block, String title, String value, Runnable onClick) {
        addSelectRow(block, title, value, onClick, null);
    }

    private TextCheckCell createDeletedSubCell(Context context, String title, boolean checked, MglaDeletedSetter setter) {
        TextCheckCell cell = new TextCheckCell(context);
        cell.setBackground(null);
        cell.setTextAndCheck(title, checked, false);
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
        divider.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 36, 0, 36, 0));
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

    private void updateSaveDeletedHangWarning() {
        if (saveDeletedHangWarning == null) {
            return;
        }
        boolean show = MglaSpyConfig.isSaveDeletedMessagesEnabled() && !MglaSpyConfig.isHangInBackgroundEnabled();
        saveDeletedHangWarning.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void restartClient() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            PackageManager pm = activity.getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(activity.getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                activity.finishAffinity();
                activity.startActivity(intent);
            }
        } catch (Throwable ignore) {
        }
        System.exit(0);
    }

    private interface MglaDeletedSetter {
        void set(boolean enabled);
    }
}
