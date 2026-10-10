package org.telegram.utils.wsbypass;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.VpnMonitor;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.bypass.MglaBypassVpnGuard;
import org.telegram.utils.dpi.MglaDpiBypass;
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
    private static final long WS_STALL_MS = 45_000L;
    /** Сколько WEB может висеть в Connecting, прежде чем вернуться на WS. */
    private static final long WEB_STALL_MS = 60_000L;
    /** Как часто из WEB пробовать вернуться на WS. */
    private static final long WEB_RETRY_WS_MS = 8 * 60_000L;
    private static final long WS_PROBE_TIMEOUT_MS = 10_000L;

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
    }

    private synchronized void switchRoute(Route target, String reason) {
        if (!proxyApplied || route == target || !MglaWsConfig.isEnabled()) {
            return;
        }
        FileLog.d("MglaWsBypass: route " + route + " -> " + target + " (" + reason + ")");
        long now = SystemClock.elapsedRealtime();
        route = target;
        routeSinceElapsed = now;
        lastConnectedElapsed = now;
        lastWsProbeElapsed = now;
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
        int state;
        try {
            state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        } catch (Throwable e) {
            return;
        }
        if (state == ConnectionsManager.ConnectionStateConnected
            || state == ConnectionsManager.ConnectionStateUpdating) {
            lastConnectedElapsed = now;
            if (route == Route.WS) {
                return;
            }
        }
        boolean connecting = state == ConnectionsManager.ConnectionStateConnecting
            || state == ConnectionsManager.ConnectionStateConnectingToProxy;
        long stalledFor = now - Math.max(lastConnectedElapsed, routeSinceElapsed);

        if (route == Route.WS) {
            if (!connecting || stalledFor < WS_STALL_MS || !isWebFallbackAvailable()) {
                return;
            }
            long lastOk = WsBypassCore.getInstance().getLastUpstreamOkAtMs();
            if (lastOk > 0 && System.currentTimeMillis() - lastOk < WS_STALL_MS) {
                return;
            }
            switchRoute(Route.WEB, "ws stalled " + (stalledFor / 1000) + "s");
            return;
        }

        if (connecting && stalledFor >= WEB_STALL_MS) {
            switchRoute(Route.WS, "web stalled " + (stalledFor / 1000) + "s");
            return;
        }
        if (now - routeSinceElapsed >= WEB_RETRY_WS_MS && now - lastWsProbeElapsed >= WEB_RETRY_WS_MS) {
            lastWsProbeElapsed = now;
            if (WsBypassCore.getInstance().probeRelay(WS_PROBE_TIMEOUT_MS)) {
                switchRoute(Route.WS, "ws relay reachable again");
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

    private void addBypassProxyToList() {
        try {
            SharedConfig.loadProxyList();
            if (bypassProxyInfo == null) {
                savedCurrentProxyRef = SharedConfig.currentProxy;
                bypassProxyInfo = SharedConfig.addProxy(new SharedConfig.ProxyInfo(buildActiveSettings()));
            }
            SharedConfig.currentProxy = bypassProxyInfo;
            SharedConfig.saveProxyList();
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
            SharedConfig.loadProxyList();
            // deleteProxy() on the current entry would wipe proxy_* prefs and disable the proxy.
            if (SharedConfig.currentProxy == old) {
                SharedConfig.currentProxy = null;
            }
            SharedConfig.deleteProxy(old);
            bypassProxyInfo = SharedConfig.addProxy(new SharedConfig.ProxyInfo(next));
            SharedConfig.currentProxy = bypassProxyInfo;
            SharedConfig.saveProxyList();
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
            if (info != null) {
                SharedConfig.deleteProxy(info);
            }
            if (savedCurrentProxyRef != null) {
                SharedConfig.currentProxy = savedCurrentProxyRef;
                savedCurrentProxyRef = null;
            }
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
