package org.telegram.utils.wsbypass;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.VpnMonitor;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.bypass.MglaBypassVpnGuard;
import org.telegram.utils.dpi.MglaDpiBypass;
import org.telegram.utils.proxy.MglaInternalProxy;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.utils.proxy.WebProxyTransport;

/**
 * Менеджер «Авто-обхода»: основной маршрут — {@link WsBypassCore} (локальный MTProto-прокси
 * на WebSocket-релей), запасной — Telegram WEB proxy ({@link WebProxyTransport}) на
 * {@link MglaWsConfig#getWebProxyHost()}. Переключение автоматическое по состоянию соединения.
 * <p>
 * Взаимоисключает с ByeDPI-обходом: при включении выключает ByeDPI и наоборот.
 */
public final class MglaWsBypass {

    public enum Route { WS, WEB }

    private static final long MONITOR_INTERVAL_MS = 5_000L;
    /** Сколько WS может висеть в Connecting без успешного апстрима, прежде чем уйти на WEB. */
    private static final long WS_STALL_MS = 90_000L;
    /** Ultra (любой аккаунт): дольше держимся за WS — приоритетный маршрут. */
    private static final long WS_STALL_ULTRA_MS = 150_000L;
    /** Сколько WEB может висеть в Connecting, прежде чем вернуться на WS. */
    private static final long WEB_STALL_MS = 90_000L;
    /**
     * После срыва WEB — как часто пробовать вернуть WS, пока ещё не Connected.
     * Пока маршрут Connected/Updating, переключений нет (иначе лишний reconnect Telegram).
     */
    private static final long WEB_RETRY_WS_MS = 12 * 60_000L;
    /** Ultra: быстрее возвращаемся на WS с WEB. */
    private static final long WEB_RETRY_WS_ULTRA_MS = 3 * 60_000L;
    private static final long WS_PROBE_TIMEOUT_MS = 8_000L;
    private static final long WS_PROBE_TIMEOUT_ULTRA_MS = 12_000L;
    /** Минимальная пауза между любыми сменами маршрута. */
    private static final long ROUTE_SWITCH_COOLDOWN_MS = 2 * 60_000L;
    /** Сколько подряд тиков монитора должны видеть stall после порога. */
    private static final int STALL_CONFIRM_TICKS = 2;
    private static final int STALL_CONFIRM_TICKS_ULTRA = 3;

    private boolean bypassPriority() {
        try {
            return MglaSubscription.hasBypassPriority();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final MglaWsBypass instance = new MglaWsBypass();

    public static MglaWsBypass getInstance() {
        return instance;
    }

    public static void initOnAppStart() {
        getInstance().startOnAppLaunch();
    }

    private volatile boolean proxyApplied;
    private volatile String lastError;
    private volatile long sessionStartElapsed;

    private SharedConfig.ProxyInfo bypassProxyInfo;
    private SharedConfig.ProxyInfo savedCurrentProxyRef;

    private volatile Route route = Route.WS;
    private volatile long routeSinceElapsed;
    private volatile long lastConnectedElapsed;
    private volatile long lastWsProbeElapsed;
    private volatile long lastSwitchElapsed;
    private int stallConfirmTicks;
    private DispatchQueue monitorQueue;
    private boolean monitorScheduled;
    private final Runnable monitorRunnable = this::monitorTick;

    private MglaWsBypass() {
    }

    private synchronized void startOnAppLaunch() {
        if (!MglaWsConfig.isEnabled()) {
            return;
        }
        if (VpnMonitor.getInstance().isVpnActive()) {
            // VPN уже активен — отложим старт до его выключения
            return;
        }
        // ByeDPI и WS не должны оба держать прокси
        if (MglaDpiBypass.getInstance().isEnabled()) {
            try {
                MglaDpiBypass.getInstance().setEnabled(false);
            } catch (Throwable ignored) {
            }
        }
        String err = startEngine();
        if (err != null) {
            FileLog.e("MglaWsBypass: start on launch failed: " + err);
            return;
        }
        resetRoute();
        writeBypassToMainConfig();
        addBypassProxyToList();
        proxyApplied = true;
        sessionStartElapsed = SystemClock.elapsedRealtime();
        scheduleMonitor();
    }

    public synchronized boolean setEnabled(boolean enable) {
        if (enable) {
            MglaBypassVpnGuard.noteUserEnabled(MglaBypassVpnGuard.MODE_WS);
            // Выключить ByeDPI, если был включён
            if (MglaDpiBypass.getInstance().isEnabled()) {
                try {
                    MglaDpiBypass.getInstance().setEnabled(false);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            MglaWsConfig.setEnabled(true);
            if (VpnMonitor.getInstance().isVpnActive()) {
                // VPN сам обходит блокировки — только запомним выбор пользователя
                lastError = null;
                MglaBypassVpnGuard.markSuspended();
                notifyChanged();
                return true;
            }
            if (proxyApplied && isServerRunning()) {
                notifyChanged();
                return true;
            }
            String err = startEngine();
            if (err != null) {
                lastError = err;
                notifyChanged();
                return false;
            }
            resetRoute();
            writeBypassToMainConfig();
            addBypassProxyToList();
            ConnectionsManager.setProxySettings(true, buildActiveSettings());
            proxyApplied = true;
            sessionStartElapsed = SystemClock.elapsedRealtime();
            lastError = null;
            scheduleMonitor();
        } else {
            MglaBypassVpnGuard.noteUserDisabled(MglaBypassVpnGuard.MODE_WS);
            if (proxyApplied) {
                removeBypassProxyFromList();
                ConnectionsManager.setProxySettings(false, null);
                restoreUserProxy();
                proxyApplied = false;
            }
            resetRoute();
            sessionStartElapsed = 0;
            WsBypassCore.getInstance().stop();
            MglaWsConfig.setEnabled(false);
            if (!MglaBypassVpnGuard.isManagingProxy()) {
                MglaBypassVpnGuard.clearSuspended();
            }
        }
        notifyChanged();
        return true;
    }

    /** Остановить движок и снять прокси, не сбрасывая выбор пользователя. */
    public synchronized void suspendForVpn() {
        if (proxyApplied) {
            removeBypassProxyFromList();
            ConnectionsManager.setProxySettings(false, null);
            restoreUserProxy();
            proxyApplied = false;
        }
        resetRoute();
        sessionStartElapsed = 0;
        WsBypassCore.getInstance().stop();
        notifyChanged();
    }

    /** Вернуть обход после выключения VPN, если пользователь его не отключал. */
    public synchronized void resumeAfterVpn() {
        if (!MglaWsConfig.isEnabled()) {
            return;
        }
        if (VpnMonitor.getInstance().isVpnActive()) {
            return;
        }
        if (proxyApplied && isServerRunning()) {
            notifyChanged();
            return;
        }
        if (MglaDpiBypass.getInstance().isEnabled()) {
            try {
                MglaDpiBypass.getInstance().setEnabled(false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        String err = startEngine();
        if (err != null) {
            lastError = err;
            FileLog.e("MglaWsBypass: resume after VPN failed: " + err);
            notifyChanged();
            return;
        }
        resetRoute();
        writeBypassToMainConfig();
        addBypassProxyToList();
        ConnectionsManager.setProxySettings(true, buildActiveSettings());
        proxyApplied = true;
        sessionStartElapsed = SystemClock.elapsedRealtime();
        lastError = null;
        scheduleMonitor();
        notifyChanged();
    }

    private String startEngine() {
        if (!MglaWsConfig.isRelayConfigured()) {
            lastError = "Задайте MGLA_WS_RELAY_TOKEN в local.properties (и на сервере)";
            return lastError;
        }
        WsBypassCore core = WsBypassCore.getInstance();
        String err = core.start(MglaWsConfig.getPort(), MglaWsConfig.getSecret());
        if (err != null && !err.isEmpty()) {
            lastError = err;
            return err;
        }
        MglaWsConfig.setPort(core.getPort());
        MglaWsConfig.setSecret(core.getSecretHex());
        lastError = null;
        return null;
    }

    public synchronized void ensureRunning() {
        if (!proxyApplied || isServerRunning()) {
            return;
        }
        if (VpnMonitor.getInstance().isVpnActive()) {
            return;
        }
        FileLog.e("MglaWsBypass: engine stopped, restarting");
        String err = startEngine();
        if (err == null && route == Route.WS) {
            writeBypassToMainConfig();
            replaceBypassProxyInList();
            ConnectionsManager.setProxySettings(true, buildActiveSettings());
        }
        scheduleMonitor();
        notifyChanged();
    }

    public Route getRoute() {
        return route;
    }

    /** WEB proxy можно использовать как запасной маршрут на этом устройстве. */
    public boolean isWebFallbackAvailable() {
        return MglaWsConfig.isWebProxyConfigured() && WebProxyTransport.isSupported();
    }

    private void resetRoute() {
        long now = SystemClock.elapsedRealtime();
        route = Route.WS;
        routeSinceElapsed = now;
        lastConnectedElapsed = now;
        lastWsProbeElapsed = now;
        lastSwitchElapsed = now;
        stallConfirmTicks = 0;
    }

    private synchronized void switchRoute(Route target, String reason) {
        if (!proxyApplied || route == target || !MglaWsConfig.isEnabled()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastSwitchElapsed > 0 && now - lastSwitchElapsed < ROUTE_SWITCH_COOLDOWN_MS) {
            FileLog.d("MglaWsBypass: skip route " + route + " -> " + target
                + " (cooldown, " + reason + ")");
            return;
        }
        FileLog.d("MglaWsBypass: route " + route + " -> " + target + " (" + reason + ")");
        route = target;
        routeSinceElapsed = now;
        lastConnectedElapsed = now;
        lastWsProbeElapsed = now;
        lastSwitchElapsed = now;
        stallConfirmTicks = 0;
        if (target == Route.WS && !isServerRunning()) {
            String err = startEngine();
            if (err != null) {
                FileLog.e("MglaWsBypass: engine restart for WS failed: " + err);
            }
        }
        writeBypassToMainConfig();
        replaceBypassProxyInList();
        ConnectionsManager.setProxySettings(true, buildActiveSettings());
        notifyChanged();
    }

    private synchronized void scheduleMonitor() {
        if (monitorScheduled) {
            return;
        }
        if (monitorQueue == null) {
            monitorQueue = new DispatchQueue("mglaAutoBypass");
        }
        monitorScheduled = true;
        monitorQueue.postRunnable(monitorRunnable, MONITOR_INTERVAL_MS);
    }

    private void monitorTick() {
        synchronized (this) {
            monitorScheduled = false;
            if (!proxyApplied || !MglaWsConfig.isEnabled()) {
                return;
            }
        }
        try {
            checkRoute();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (proxyApplied) {
            scheduleMonitor();
        }
    }

    private void checkRoute() {
        long now = SystemClock.elapsedRealtime();
        int state = bestConnectionState();
        if (state < 0) {
            return;
        }
        // Пока Telegram реально на связи — маршрут не трогаем. Раньше на WEB каждые
        // ~8 мин делался probe WS и setProxySettings → внезапный reconnect среди дня.
        if (state == ConnectionsManager.ConnectionStateConnected
            || state == ConnectionsManager.ConnectionStateUpdating) {
            lastConnectedElapsed = now;
            stallConfirmTicks = 0;
            return;
        }
        if (state == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            stallConfirmTicks = 0;
            return;
        }
        if (lastSwitchElapsed > 0 && now - lastSwitchElapsed < ROUTE_SWITCH_COOLDOWN_MS) {
            return;
        }

        boolean priority = bypassPriority();
        boolean connecting = state == ConnectionsManager.ConnectionStateConnecting
            || state == ConnectionsManager.ConnectionStateConnectingToProxy;
        long stalledFor = now - Math.max(lastConnectedElapsed, routeSinceElapsed);
        long wsStall = priority ? WS_STALL_ULTRA_MS : WS_STALL_MS;
        long stallLimit = route == Route.WS ? wsStall : WEB_STALL_MS;
        int stallTicksNeeded = priority && route == Route.WS
            ? STALL_CONFIRM_TICKS_ULTRA : STALL_CONFIRM_TICKS;

        if (connecting && stalledFor >= stallLimit) {
            stallConfirmTicks++;
        } else {
            stallConfirmTicks = 0;
        }

        if (route == Route.WS) {
            if (stallConfirmTicks < stallTicksNeeded || !isWebFallbackAvailable()) {
                return;
            }
            long lastOk = WsBypassCore.getInstance().getLastUpstreamOkAtMs();
            if (lastOk > 0 && System.currentTimeMillis() - lastOk < wsStall) {
                stallConfirmTicks = 0;
                return;
            }
            switchRoute(Route.WEB, "ws stalled " + (stalledFor / 1000) + "s"
                + (priority ? " (ultra)" : ""));
            return;
        }

        // WEB: уходим на WS только если WEB сам завис в Connecting, либо давно
        // нет связи и релей снова отвечает (не трогаем рабочий Connected WEB).
        if (stallConfirmTicks >= STALL_CONFIRM_TICKS) {
            switchRoute(Route.WS, "web stalled " + (stalledFor / 1000) + "s");
            return;
        }
        if (connecting) {
            return;
        }
        long webRetry = priority ? WEB_RETRY_WS_ULTRA_MS : WEB_RETRY_WS_MS;
        long probeTimeout = priority ? WS_PROBE_TIMEOUT_ULTRA_MS : WS_PROBE_TIMEOUT_MS;
        if (now - routeSinceElapsed >= webRetry && now - lastWsProbeElapsed >= webRetry) {
            lastWsProbeElapsed = now;
            if (WsBypassCore.getInstance().probeRelay(probeTimeout)) {
                switchRoute(Route.WS, "ws relay reachable while web idle"
                    + (priority ? " (ultra)" : ""));
            }
        }
    }

    public boolean isEnabled() {
        return MglaWsConfig.isEnabled();
    }

    public boolean isServerRunning() {
        return WsBypassCore.getInstance().isRunning();
    }

    public boolean isProxyApplied() {
        return proxyApplied;
    }

    public int getPort() {
        return MglaWsConfig.getPort();
    }

    public String getLastError() {
        return lastError;
    }

    public String getStatusText() {
        if (!MglaWsConfig.isEnabled()) {
            return "Отключён";
        }
        if (MglaBypassVpnGuard.shouldShowVpnPaused()) {
            return "На паузе";
        }
        WsBypassCore core = WsBypassCore.getInstance();
        if (!core.isRunning()) {
            return lastError != null ? "Ошибка: " + lastError : "Не запущен";
        }
        if (!proxyApplied) {
            return "Запускается…";
        }
        if (!MglaWsConfig.isRelayConfigured()) {
            return "Нет токена релея";
        }
        if (route == Route.WEB) {
            int state = getConnectionStateSafe();
            return state == ConnectionsManager.ConnectionStateConnected
                || state == ConnectionsManager.ConnectionStateUpdating
                ? "Работает (WEB)" : "Ожидание соединения (WEB)…";
        }
        if (core.hasActiveBridge() && core.getLastBridgeOkAtMs() > 0) {
            return "Работает (WS)";
        }
        return "Ожидание соединения…";
    }

    /** Любой живой аккаунт держит маршрут: переключение аккаунта само по себе не повод менять его. */
    private static int bestConnectionState() {
        int fallback = -1;
        try {
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                if (!UserConfig.getInstance(i).isClientActivated()) {
                    continue;
                }
                int state = ConnectionsManager.getInstance(i).getConnectionState();
                if (state == ConnectionsManager.ConnectionStateConnected
                    || state == ConnectionsManager.ConnectionStateUpdating) {
                    return state;
                }
                if (fallback < 0) {
                    fallback = state;
                }
            }
            if (fallback < 0) {
                fallback = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int getConnectionStateSafe() {
        try {
            return ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        } catch (Throwable e) {
            return -1;
        }
    }

    public String getTelegramConnectionStateText() {
        try {
            int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
            switch (state) {
                case ConnectionsManager.ConnectionStateConnected:
                    return "Подключено";
                case ConnectionsManager.ConnectionStateConnecting:
                    return "Подключение…";
                case ConnectionsManager.ConnectionStateConnectingToProxy:
                    return "Подключение к прокси…";
                case ConnectionsManager.ConnectionStateWaitingForNetwork:
                    return "Ожидание сети";
                case ConnectionsManager.ConnectionStateUpdating:
                    return "Обновление…";
                default:
                    return "—";
            }
        } catch (Throwable e) {
            return "—";
        }
    }

    public long getUptimeSeconds() {
        if (sessionStartElapsed <= 0) {
            return 0;
        }
        return Math.max(0, (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000);
    }

    private ProxySettings buildLocalSettings() {
        return ProxySettings.builder()
            .setType(ProxySettings.Type.MTPROTO)
            .setAddress(WsBypassCore.LOCAL_PROXY_HOST)
            .setPort(getPort())
            .setSecret(MglaWsConfig.getSecret())
            .build();
    }

    private ProxySettings buildWebSettings() {
        return ProxySettings.builder()
            .setType(ProxySettings.Type.WEB)
            .setAddress(MglaWsConfig.getWebProxyHost())
            .setSecret(MglaWsConfig.getWebProxySecret())
            .build();
    }

    private ProxySettings buildActiveSettings() {
        if (route == Route.WEB) {
            ProxySettings web = buildWebSettings();
            if (web.isValid()) {
                return web;
            }
        }
        return buildLocalSettings();
    }

    private void writeBypassToMainConfig() {
        SharedPreferences main = MessagesController.getGlobalMainSettings();
        if (!MglaWsConfig.hasSavedUserProxy()) {
            MglaWsConfig.saveUserProxy(
                main.getBoolean("proxy_enabled", false),
                main.getString("proxy_ip", ""),
                main.getInt("proxy_port", 1080),
                main.getString("proxy_user", ""),
                main.getString("proxy_pass", ""),
                main.getString("proxy_secret", ""),
                main.getInt("proxy_type", 0)
            );
        }
        SharedPreferences.Editor editor = main.edit();
        buildActiveSettings().toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.apply();
    }

    private void restoreUserProxy() {
        SharedPreferences main = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = main.edit();
        if (MglaWsConfig.hasSavedUserProxy()) {
            ProxySettings restored = ProxySettings.builder()
                .setType(ProxySettings.intToType(MglaWsConfig.getSavedUserProxyType()))
                .setAddress(MglaWsConfig.getSavedUserProxyIp())
                .setPort(MglaWsConfig.getSavedUserProxyPort())
                .setUser(MglaWsConfig.getSavedUserProxyUser())
                .setPassword(MglaWsConfig.getSavedUserProxyPass())
                .setSecret(MglaWsConfig.getSavedUserProxySecret())
                .build();
            boolean wasEnabled = MglaWsConfig.isSavedUserProxyEnabled();
            MglaWsConfig.clearSavedUserProxy();
            if (restored.isValid()) {
                restored.toSharedPreferences(editor);
                editor.putBoolean("proxy_enabled", wasEnabled);
                editor.apply();
                if (wasEnabled) {
                    ConnectionsManager.setProxySettings(true, restored);
                }
            } else {
                clearProxyKeys(editor);
                editor.putBoolean("proxy_enabled", false);
                editor.apply();
            }
        } else {
            clearProxyKeys(editor);
            editor.putBoolean("proxy_enabled", false);
            editor.apply();
        }
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
    }

    private void clearProxyKeys(SharedPreferences.Editor editor) {
        editor.remove("proxy_ip")
            .remove("proxy_port")
            .remove("proxy_user")
            .remove("proxy_pass")
            .remove("proxy_secret")
            .remove("proxy_type");
    }

    /**
     * Активирует обход как currentProxy, но не кладёт его в SharedConfig.proxyList —
     * иначе WEB/локальный MTProto светятся в настройках и шарятся в другие клиенты.
     */
    private void addBypassProxyToList() {
        try {
            SharedConfig.loadProxyList();
            MglaInternalProxy.scrubProxyList();
            if (bypassProxyInfo == null) {
                savedCurrentProxyRef = SharedConfig.currentProxy;
                // Если ранее currentProxy был нашей же внутренней записью из списка — не восстанавливать.
                if (savedCurrentProxyRef != null && MglaInternalProxy.isInternal(savedCurrentProxyRef.settings)) {
                    savedCurrentProxyRef = null;
                }
            }
            bypassProxyInfo = new SharedConfig.ProxyInfo(buildActiveSettings());
            SharedConfig.currentProxy = bypassProxyInfo;
            AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void replaceBypassProxyInList() {
        try {
            SharedConfig.ProxyInfo old = bypassProxyInfo;
            if (old == null) {
                addBypassProxyToList();
                return;
            }
            ProxySettings next = buildActiveSettings();
            if (next.equals(old.settings)) {
                return;
            }
            bypassProxyInfo = new SharedConfig.ProxyInfo(next);
            SharedConfig.currentProxy = bypassProxyInfo;
            MglaInternalProxy.scrubProxyList();
            AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void removeBypassProxyFromList() {
        try {
            SharedConfig.ProxyInfo info = bypassProxyInfo;
            bypassProxyInfo = null;
            // Не вызываем deleteProxy: записи в proxyList нет, а deleteProxy ещё и сбрасывает prefs.
            if (info != null && SharedConfig.currentProxy == info) {
                SharedConfig.currentProxy = null;
            }
            if (savedCurrentProxyRef != null
                    && !MglaInternalProxy.isInternal(savedCurrentProxyRef.settings)) {
                SharedConfig.currentProxy = savedCurrentProxyRef;
            }
            savedCurrentProxyRef = null;
            MglaInternalProxy.scrubProxyList();
            AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void notifyChanged() {
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dpiBypassChanged));
    }
}
