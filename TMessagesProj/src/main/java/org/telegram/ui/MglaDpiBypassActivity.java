package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MglaFeatureFlags;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.dpi.MglaDpiBypass;
import org.telegram.utils.dpi.MglaDpiConfig;
import org.telegram.utils.dpi.MglaDpiStrategies;
import org.telegram.utils.wsbypass.MglaWsBypass;
import org.telegram.utils.wsbypass.MglaWsConfig;
import org.telegram.utils.wsbypass.WsBypassCore;

/**
 * Mgla -> Настройки Mgla -> Обход блокировок.
 * <p>
 * Два взаимоисключающих режима: авто-обход (WebSocket-релей + WEB proxy) и ByeDPI.
 * При системном VPN обход автоматически приостанавливается.
 */
public class MglaDpiBypassActivity extends MglaBaseSettingsActivity implements NotificationCenter.NotificationCenterDelegate {

    private TextCheckCell wsMasterCell;
    private TextCheckCell wsUltraCell;
    private TextView wsStatusValue;
    private TextSettingsCell wsTelegramCell;
    private TextSettingsCell wsProxyCell;
    private TextSettingsCell wsPingCell;
    private TextSettingsCell wsUptimeCell;

    private TextCheckCell dpiMasterCell;
    private TextView dpiStatusValue;
    private TextView strategyView;
    private TextSettingsCell sniCell;
    private TextSettingsCell lastTestCell;
    private TextSettingsCell dpiTelegramCell;
    private TextSettingsCell dpiProxyCell;

    private boolean destroyed;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            org.telegram.utils.bypass.MglaBypassVpnGuard.syncWithVpn();
            MglaWsBypass.getInstance().ensureRunning();
            MglaDpiBypass.getInstance().ensureRunning();
            refreshDynamic();
            uiHandler.postDelayed(this, 1000);
        }
    };

    public MglaDpiBypassActivity() {
        this(null);
    }

    public MglaDpiBypassActivity(android.os.Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.dpiBypassChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        uiHandler.removeCallbacks(ticker);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.dpiBypassChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDynamic();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dpiBypassChanged) {
            refreshDynamic();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Подключение без VPN");
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

        // ---- Auto bypass: WebSocket relay (primary) + WEB proxy (fallback)
        LinearLayout wsBlock = MglaUi.createBlock(context, "Авто-обход");

        wsMasterCell = new TextCheckCell(context);
        wsMasterCell.setBackground(null);
        wsMasterCell.setTextAndCheck("Авто-обход", MglaWsBypass.getInstance().isEnabled(), true);
        wsMasterCell.setOnClickListener(v -> {
            boolean newVal = !wsMasterCell.isChecked();
            if (newVal && !MglaFeatureFlags.isAllowed("ws_enabled")) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                refreshDynamic();
                return;
            }
            MglaWsBypass mgr = MglaWsBypass.getInstance();
            if (!mgr.setEnabled(newVal) && newVal) {
                showError("Не удалось включить авто-обход", mgr.getLastError());
            }
            if (!newVal && MglaWsConfig.isUltraBypassPrefEnabled()) {
                MglaWsConfig.setUltraBypassEnabled(false);
            }
            refreshDynamic();
        });
        wsBlock.addView(wsMasterCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        wsBlock.addView(MglaUi.createDivider(context));

        wsUltraCell = new TextCheckCell(context);
        wsUltraCell.setBackground(null);
        wsUltraCell.setTextAndCheck(ultraBypassLabel(context), MglaWsConfig.isUltraBypassEnabled(), false);
        wsUltraCell.setOnClickListener(v -> {
            if (!MglaSubscription.hasUltraOnAnyAccount()) {
                wsUltraCell.setChecked(false);
                BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.star_premium_2,
                    "Ultra-обход доступен только с подпиской Ultra",
                    "Оформить",
                    () -> {
                        Bundle args = new Bundle();
                        args.putInt(MglaPremiumActivity.ARG_INITIAL_PLAN, MglaPremiumActivity.PLAN_ULTRA);
                        presentFragment(new MglaPremiumActivity(args));
                    }
                ).show();
                return;
            }
            boolean newVal = !wsUltraCell.isChecked();
            MglaWsConfig.setUltraBypassEnabled(newVal);
            MglaWsBypass mgr = MglaWsBypass.getInstance();
            if (newVal && !mgr.isEnabled()) {
                if (!MglaFeatureFlags.isAllowed("ws_enabled")) {
                    MglaWsConfig.setUltraBypassEnabled(false);
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                    refreshDynamic();
                    return;
                }
                if (!mgr.setEnabled(true)) {
                    MglaWsConfig.setUltraBypassEnabled(false);
                    showError("Не удалось включить Ultra-обход", mgr.getLastError());
                    refreshDynamic();
                    return;
                }
            } else if (mgr.isEnabled()) {
                mgr.onRelayHostChanged();
            }
            refreshDynamic();
        });
        wsBlock.addView(wsUltraCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        wsBlock.addView(MglaUi.createDivider(context));
        wsBlock.addView(createStatusRow(context, status -> wsStatusValue = status),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        wsBlock.addView(MglaUi.createDivider(context));
        wsTelegramCell = createStatCell(context, wsBlock, "Соединение Telegram");
        wsBlock.addView(MglaUi.createDivider(context));
        wsProxyCell = createStatCell(context, wsBlock, "Маршрут");
        wsBlock.addView(MglaUi.createDivider(context));
        wsPingCell = createStatCell(context, wsBlock, "Пинг");
        wsPingCell.setOnClickListener(v -> {
            if (!MglaWsConfig.isRelayConfigured()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Релей не настроен").show();
                return;
            }
            MglaWsBypass.getInstance().requestPing(true);
            refreshDynamic();
        });
        wsBlock.addView(MglaUi.createDivider(context));
        wsUptimeCell = createStatCell(context, wsBlock, "Время работы");

        TextInfoCell wsInfo = new TextInfoCell(context);
        wsInfo.setText("Основной маршрут — WebSocket-релей. Ultra-обход направляет трафик на выделенный сервер только для подписчиков Ultra. Пинг обновляется раз в 30 мин (нажмите чтобы обновить). Если релей не подключается, обход сам переходит на резервный WEB proxy. Без сервера для жёстких блокировок используйте ByeDPI ниже.");
        rootLayout.addView(wsBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
        rootLayout.addView(wsInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        // ---- ByeDPI (secondary)
        LinearLayout dpiBlock = MglaUi.createBlock(context, "ByeDPI (если блок только DPI)");

        dpiMasterCell = new TextCheckCell(context);
        dpiMasterCell.setBackground(null);
        dpiMasterCell.setTextAndCheck("Обход через ByeDPI", MglaDpiBypass.getInstance().isEnabled(), false);
        dpiMasterCell.setOnClickListener(v -> {
            boolean newVal = !dpiMasterCell.isChecked();
            if (newVal && !MglaFeatureFlags.isAllowed("dpi_enabled")) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Функция отключена администратором").show();
                refreshDynamic();
                return;
            }
            MglaDpiBypass mgr = MglaDpiBypass.getInstance();
            if (!mgr.setEnabled(newVal) && newVal) {
                showError("Не удалось включить ByeDPI", mgr.getLastError());
            }
            refreshDynamic();
        });
        dpiBlock.addView(dpiMasterCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        dpiBlock.addView(MglaUi.createDivider(context));
        dpiBlock.addView(createStatusRow(context, status -> dpiStatusValue = status),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        dpiBlock.addView(MglaUi.createDivider(context));

        strategyView = new TextView(context);
        strategyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        strategyView.setTypeface(Typeface.MONOSPACE);
        strategyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        strategyView.setPadding(dp(21), dp(8), dp(21), dp(8));
        strategyView.setBackground(Theme.getSelectorDrawable(false));
        strategyView.setOnClickListener(v -> showStrategyEditor());
        strategyView.setOnLongClickListener(v -> {
            AndroidUtilities.addToClipboard(MglaDpiConfig.getStrategy());
            BulletinFactory.of(this).createCopyBulletin("Стратегия скопирована").show();
            return true;
        });
        dpiBlock.addView(strategyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        dpiBlock.addView(MglaUi.createDivider(context));

        TextSettingsCell listCell = new TextSettingsCell(context);
        listCell.setBackground(null);
        listCell.setText("Выбрать стратегию", false);
        listCell.setOnClickListener(v -> showStrategyList());
        dpiBlock.addView(listCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        dpiBlock.addView(MglaUi.createDivider(context));

        TextSettingsCell testerCell = new TextSettingsCell(context);
        testerCell.setBackground(null);
        testerCell.setTextAndValue("Подбор стратегий", "Открыть", false);
        testerCell.setOnClickListener(v -> presentFragment(new MglaDpiTestActivity()));
        dpiBlock.addView(testerCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        dpiBlock.addView(MglaUi.createDivider(context));

        sniCell = new TextSettingsCell(context);
        sniCell.setBackground(null);
        sniCell.setOnClickListener(v -> showFakeSniDialog(this));
        dpiBlock.addView(sniCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        dpiBlock.addView(MglaUi.createDivider(context));

        lastTestCell = new TextSettingsCell(context);
        lastTestCell.setBackground(null);
        lastTestCell.setClipChildren(true);
        lastTestCell.setClipToPadding(true);
        dpiBlock.addView(lastTestCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        dpiBlock.addView(MglaUi.createDivider(context));

        dpiTelegramCell = createStatCell(context, dpiBlock, "Соединение Telegram");
        dpiBlock.addView(MglaUi.createDivider(context));
        dpiProxyCell = createStatCell(context, dpiBlock, "Локальный SOCKS5");

        rootLayout.addView(dpiBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        TextInfoCell info = new TextInfoCell(context);
        info.setText("Обход помогает подключаться к Telegram без системного VPN. "
            + "Если на устройстве включён VPN, обход автоматически приостанавливается "
            + "и снова включается после его выключения — в том же режиме, что был выбран.");
        rootLayout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, AndroidUtilities.navigationBarHeight + 16));

        fragmentView = scrollView;
        refreshDynamic();
        uiHandler.postDelayed(ticker, 1000);
        return fragmentView;
    }

    private interface StatusHolder {
        void set(TextView view);
    }

    private LinearLayout createStatusRow(Context context, StatusHolder holder) {
        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(21), 0, dp(18), 0);
        statusRow.setMinimumHeight(dp(50));

        TextView statusTitle = new TextView(context);
        statusTitle.setText("Состояние");
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        statusTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        statusRow.addView(statusTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        TextView statusValue = new TextView(context);
        statusValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusValue.setGravity(Gravity.END);
        statusValue.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusRow.addView(statusValue, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
        holder.set(statusValue);
        return statusRow;
    }

    // ---------------------------------------------------------------- ByeDPI dialogs

    private void showStrategyEditor() {
        MglaUi.showInputDialog(this, "Стратегия",
            "Командная строка ByeDPI, например из «Подбора стратегий» ByeByeDPI.",
            MglaDpiConfig.getStrategy(), true, false, this::applyStrategy);
    }

    private void showStrategyList() {
        if (getParentActivity() == null) {
            return;
        }
        String[] items = new String[MglaDpiStrategies.BUILT_IN.length + 1];
        items[0] = "По умолчанию: " + MglaDpiStrategies.DEFAULT_STRATEGY;
        System.arraycopy(MglaDpiStrategies.BUILT_IN, 0, items, 1, MglaDpiStrategies.BUILT_IN.length);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Стратегии");
        builder.setItems(items, (dialog, which) ->
            applyStrategy(which == 0 ? MglaDpiStrategies.DEFAULT_STRATEGY : MglaDpiStrategies.BUILT_IN[which - 1]));
        showDialog(builder.create());
    }

    private void applyStrategy(String command) {
        String error = MglaDpiBypass.getInstance().applyStrategy(command);
        if (error != null) {
            showError("Стратегия не применена", error);
        }
        refreshDynamic();
    }

    static void showFakeSniDialog(BaseFragment fragment) {
        MglaUi.showInputDialog(fragment, "SNI фейк-пакетов",
            "Домен, который DPI увидит в поддельном ClientHello (подставляется вместо {sni}).",
            MglaDpiConfig.getFakeSni(), false, false, text -> {
                String sni = text.trim();
                if (sni.isEmpty() || sni.contains(" ")) {
                    sni = MglaDpiStrategies.DEFAULT_SNI;
                }
                MglaDpiConfig.setFakeSni(sni);
                MglaDpiBypass.getInstance().onFakeSniChanged();
            });
    }

    private void showError(String title, String message) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setMessage(TextUtils.isEmpty(message) ? "Неизвестная ошибка" : message);
        builder.setPositiveButton("ОК", null);
        showDialog(builder.create());
    }

    // ---------------------------------------------------------------- refresh

    private TextSettingsCell createStatCell(Context context, LinearLayout block, String title) {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBackground(null);
        cell.setTextAndValue(title, "—", false);
        block.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private static CharSequence ultraBypassLabel(Context context) {
        SpannableStringBuilder sb = new SpannableStringBuilder("  Ultra-обход");
        Drawable star = context.getResources().getDrawable(R.drawable.msg_premium_liststar).mutate();
        int size = dp(16);
        star.setBounds(0, 0, size, size);
        star.setColorFilter(new PorterDuffColorFilter(
            Theme.getColor(Theme.key_premiumGradient1), PorterDuff.Mode.SRC_IN));
        sb.setSpan(new ImageSpan(star, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void refreshDynamic() {
        MglaWsBypass ws = MglaWsBypass.getInstance();
        if (wsMasterCell != null) {
            wsMasterCell.setChecked(ws.isEnabled());
        }
        if (wsUltraCell != null) {
            boolean ultraOn = MglaWsConfig.isUltraBypassEnabled();
            wsUltraCell.setTextAndCheck(ultraBypassLabel(wsUltraCell.getContext()), ultraOn, false);
        }
        if (wsStatusValue != null) {
            wsStatusValue.setText(ws.getStatusText());
        }
        if (wsTelegramCell != null) {
            wsTelegramCell.setValue(ws.getTelegramConnectionStateText(), false);
        }
        if (wsProxyCell != null) {
            String routeText;
            if (!ws.isEnabled() || !ws.isProxyApplied()) {
                routeText = "—";
            } else if (ws.getRoute() == MglaWsBypass.Route.WEB) {
                routeText = "WEB proxy (резерв)";
            } else if (MglaWsConfig.isUltraBypassEnabled()) {
                routeText = "Ultra · " + MglaWsConfig.getRelayHost();
            } else {
                routeText = ws.isServerRunning() && ws.getPort() > 0
                    ? "WS · " + WsBypassCore.LOCAL_PROXY_HOST + ":" + ws.getPort() : "WS";
            }
            wsProxyCell.setValue(routeText, false);
        }
        if (wsPingCell != null) {
            wsPingCell.setValue(ws.getPingText(), false);
            // Soft refresh when opening/viewing the screen if the sample is stale.
            ws.maybeRefreshPing();
        }
        if (wsUptimeCell != null) {
            wsUptimeCell.setValue(formatUptime(ws.getUptimeSeconds()), false);
        }

        MglaDpiBypass dpi = MglaDpiBypass.getInstance();
        if (dpiMasterCell != null) {
            dpiMasterCell.setChecked(dpi.isEnabled());
        }
        if (dpiStatusValue != null) {
            dpiStatusValue.setText(dpi.getStatusText());
        }
        if (strategyView != null) {
            strategyView.setText(MglaDpiConfig.getStrategy());
        }
        if (sniCell != null) {
            sniCell.setTextAndValue("SNI фейк-пакетов", MglaDpiConfig.getFakeSni(), false);
        }
        if (lastTestCell != null) {
            String summary = MglaDpiConfig.getTestSummary();
            lastTestCell.setTextAndValue("Последний подбор",
                TextUtils.isEmpty(summary) ? "—" : ellipsizeRightValue(summary), false);
        }
        if (dpiTelegramCell != null) {
            dpiTelegramCell.setValue(dpi.getTelegramConnectionStateText(), false);
        }
        if (dpiProxyCell != null) {
            dpiProxyCell.setValue(dpi.isServerRunning() && dpi.getPort() > 0
                ? "127.0.0.1:" + dpi.getPort() : "—", false);
        }
    }

    /** Укорачивает правую подпись, чтобы не наезжала на заголовок пункта. */
    private static CharSequence ellipsizeRightValue(String text) {
        android.text.TextPaint paint = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(AndroidUtilities.dp(16));
        int maxWidth = Math.max(AndroidUtilities.dp(72),
            (int) (AndroidUtilities.displaySize.x * 0.38f) - AndroidUtilities.dp(28));
        return TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END);
    }

    private String formatUptime(long seconds) {
        if (seconds <= 0) {
            return "—";
        }
        if (seconds >= 3600) {
            return (seconds / 3600) + " ч " + ((seconds % 3600) / 60) + " мин";
        }
        if (seconds >= 60) {
            return (seconds / 60) + " мин";
        }
        return seconds + " с";
    }
}
