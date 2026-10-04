package org.telegram.utils.dpi;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.VpnMonitor;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.bypass.MglaBypassVpnGuard;
import org.telegram.utils.proxy.ProxySettings;

/**
 * Менеджер встроенного обхода блокировок (Mgla).
 * <p>
 * При включении поднимает в процессе приложения движок ByeDPI ({@link MglaDpiNative}) —
 * локальный SOCKS5-прокси на 127.0.0.1, который применяет к исходящим соединениям выбранную
 * стратегию дезинхронизации DPI (как ByeByeDPI, но без VPN и только для этого приложения),
 * и направляет в него сетевой стек Telegram через {@link ConnectionsManager#setProxySettings}.
 * Пользовательский прокси (если был настроен) сохраняется и восстанавливается при выключении.
 */
public final class MglaDpiBypass {

    private static final MglaDpiBypass instance = new MglaDpiBypass();

    public static MglaDpiBypass getInstance() {
        return instance;
    }

    public static void initOnAppStart() {
        getInstance().startOnAppLaunch();
    }

    private volatile boolean proxyApplied;
    private volatile boolean testing;
    private volatile long sessionStartElapsed;
    private volatile String lastError;
    private long lastRestartAttemptElapsed;

    // Запись локального прокси в списке прокси Telegram на время работы обхода
    private SharedConfig.ProxyInfo bypassProxyInfo;
    private SharedConfig.ProxyInfo savedCurrentProxyRef;

    private MglaDpiBypass() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** Вызывается из ApplicationLoader ПОСЛЕ SharedConfig.loadConfig() и ДО создания аккаунтов:
     *  ConnectionsManager.init() сам подхватит прокси из mainconfig. */
    private synchronized void startOnAppLaunch() {
        if (!MglaDpiConfig.isEnabled()) {
            return;
        }
        if (VpnMonitor.getInstance().isVpnActive()) {
            return;
        }
        int port = startEngine(MglaDpiConfig.getStrategy(), MglaDpiConfig.getPort());
        if (port <= 0) {
            FileLog.e("MglaDpi: failed to start engine on app launch: " + lastError);
            return;
        }
        writeBypassToMainConfig();
        addBypassProxyToList();
        proxyApplied = true;
        sessionStartElapsed = SystemClock.elapsedRealtime();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("MglaDpi: bypass restored on app start, port " + port);
        }
    }

    /** Включение/выключение из UI. Возвращает true, если состояние применено успешно. */
    public synchronized boolean setEnabled(boolean enable) {
        if (enable) {
            MglaBypassVpnGuard.noteUserEnabled(MglaBypassVpnGuard.MODE_DPI);
            // WS-обход и ByeDPI взаимоисключающие — оба нельзя держать на прокси одновременно
            try {
                if (org.telegram.utils.wsbypass.MglaWsBypass.getInstance().isEnabled()) {
                    org.telegram.utils.wsbypass.MglaWsBypass.getInstance().setEnabled(false);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            MglaDpiConfig.setEnabled(true);
            if (VpnMonitor.getInstance().isVpnActive()) {
                lastError = null;
                MglaBypassVpnGuard.markSuspended();
                notifyChanged();
                return true;
            }
            if (proxyApplied && isServerRunning()) {
                notifyChanged();
                return true;
            }
            int port = startEngine(MglaDpiConfig.getStrategy(), MglaDpiConfig.getPort());
            if (port <= 0) {
                FileLog.e("MglaDpi: failed to start engine: " + lastError);
                notifyChanged();
                return false;
            }
            writeBypassToMainConfig();
            addBypassProxyToList();
            ConnectionsManager.setProxySettings(true, buildLocalSettings());
            proxyApplied = true;
            sessionStartElapsed = SystemClock.elapsedRealtime();
        } else {
            MglaBypassVpnGuard.noteUserDisabled(MglaBypassVpnGuard.MODE_DPI);
            if (proxyApplied) {
                removeBypassProxyFromList();
                ConnectionsManager.setProxySettings(false, null);
                restoreUserProxy();
                proxyApplied = false;
            }
            sessionStartElapsed = 0;
            stopEngine();
            MglaDpiConfig.setEnabled(false);
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
        sessionStartElapsed = 0;
        stopEngine();
        notifyChanged();
    }

    /** Вернуть обход после выключения VPN, если пользователь его не отключал. */
    public synchronized void resumeAfterVpn() {
        if (!MglaDpiConfig.isEnabled() || testing) {
            return;
        }
        if (VpnMonitor.getInstance().isVpnActive()) {
            return;
        }
        if (proxyApplied && isServerRunning()) {
            notifyChanged();
            return;
        }
        try {
            if (org.telegram.utils.wsbypass.MglaWsBypass.getInstance().isEnabled()) {
                org.telegram.utils.wsbypass.MglaWsBypass.getInstance().setEnabled(false);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        int port = startEngine(MglaDpiConfig.getStrategy(), MglaDpiConfig.getPort());
        if (port <= 0) {
            FileLog.e("MglaDpi: resume after VPN failed: " + lastError);
            notifyChanged();
            return;
        }
        writeBypassToMainConfig();
        addBypassProxyToList();
        ConnectionsManager.setProxySettings(true, buildLocalSettings());
        proxyApplied = true;
        sessionStartElapsed = SystemClock.elapsedRealtime();
        notifyChanged();
    }

    /**
     * Сохранить и применить стратегию. Если обход включён, движок перезапускается на том же
     * порту, а Telegram переподключается. При ошибке в стратегии остаётся прежняя.
     * @return null при успехе, иначе текст ошибки
     */
    public synchronized String applyStrategy(String command) {
        String cmd = MglaDpiStrategies.normalize(command);
        String error = MglaDpiStrategies.validate(cmd);
        if (error != null) {
            return error;
        }
        if (proxyApplied) {
            String previous = MglaDpiConfig.getStrategy();
            if (restartLive(cmd) <= 0) {
                String failure = lastError;
                restartLive(previous);
                notifyChanged();
                return failure;
            }
        }
        MglaDpiConfig.setStrategy(cmd);
        notifyChanged();
        return null;
    }

    /** SNI фейков изменился: перезапуск с той же стратегией. */
    public synchronized void onFakeSniChanged() {
        if (proxyApplied) {
            restartLive(MglaDpiConfig.getStrategy());
        }
        notifyChanged();
    }

    /** Перезапуск движка, если он неожиданно остановился (вызывается периодически из UI). */
    public synchronized void ensureRunning() {
        if (!proxyApplied || testing || isServerRunning()) {
            return;
        }
        if (VpnMonitor.getInstance().isVpnActive()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRestartAttemptElapsed < 5000) {
            return;
        }
        lastRestartAttemptElapsed = now;
        FileLog.e("MglaDpi: engine stopped unexpectedly, restarting");
        restartLive(MglaDpiConfig.getStrategy());
        notifyChanged();
    }

    public boolean isEnabled() {
        return MglaDpiConfig.isEnabled();
    }

    public boolean isServerRunning() {
        try {
            return MglaDpiNative.isAvailable() && MglaDpiNative.nativeIsRunning();
        } catch (Throwable e) {
            return false;
        }
    }

    public boolean isProxyApplied() {
        return proxyApplied;
    }

    public int getPort() {
        return MglaDpiConfig.getPort();
    }

    public String getLastError() {
        return lastError;
    }

    // ---------------------------------------------------------------- tester support

    /**
     * Запустить движок со стратегией для проверки. Если обход включён, используется тот же
     * порт — соединения Telegram на время проверки тоже идут через проверяемую стратегию.
     * @return порт или код ошибки ({@link MglaDpiNative#ERR_ARGS} и т.п.)
     */
    synchronized int startTestEngine(String command) {
        testing = true;
        int port = startEngine(command, proxyApplied ? MglaDpiConfig.getPort() : 0, false);
        if (port > 0 && proxyApplied && port != MglaDpiConfig.getPort()) {
            MglaDpiConfig.setPort(port);
            writeBypassToMainConfig();
            ConnectionsManager.setProxySettings(true, buildLocalSettings());
        }
        return port;
    }

    /** Завершение проверки: вернуть рабочую стратегию (или остановить движок, если обход выключен). */
    synchronized void finishTest() {
        testing = false;
        if (proxyApplied) {
            restartLive(MglaDpiConfig.getStrategy());
        } else {
            stopEngine();
        }
        notifyChanged();
    }

    // ---------------------------------------------------------------- engine

    private int startEngine(String command, int preferredPort) {
        return startEngine(command, preferredPort, true);
    }

    private int startEngine(String command, int preferredPort, boolean savePort) {
        if (!MglaDpiNative.isAvailable()) {
            lastError = "нативная библиотека движка не загружена";
            return MglaDpiNative.ERR_INIT;
        }
        String[] args = MglaDpiStrategies.buildArgs(command, MglaDpiConfig.getFakeSni());
        MglaDpiConfig.ensureSocksAuth();
        int port;
        try {
            port = MglaDpiNative.nativeStart(args, preferredPort,
                MglaDpiConfig.getSocksUser(), MglaDpiConfig.getSocksPass());
        } catch (Throwable e) {
            FileLog.e(e);
            port = MglaDpiNative.ERR_INIT;
        }
        if (port > 0) {
            lastError = null;
            if (savePort) {
                MglaDpiConfig.setPort(port);
            }
        } else {
            lastError = MglaDpiNative.describeError(port);
        }
        return port;
    }

    private int restartLive(String command) {
        int oldPort = MglaDpiConfig.getPort();
        int port = startEngine(command, oldPort);
        if (port > 0) {
            if (port != oldPort) {
                writeBypassToMainConfig();
            }
            ConnectionsManager.setProxySettings(true, buildLocalSettings());
        }
        return port;
    }

    private void stopEngine() {
        if (!MglaDpiNative.isAvailable()) {
            return;
        }
        try {
            MglaDpiNative.nativeStop();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------------------- proxy settings plumbing

    private ProxySettings buildLocalSettings() {
        MglaDpiConfig.ensureSocksAuth();
        return ProxySettings.builder()
            .setType(ProxySettings.Type.SOCKS5)
            .setAddress("127.0.0.1")
            .setPort(getPort())
            .setUser(MglaDpiConfig.getSocksUser())
            .setPassword(MglaDpiConfig.getSocksPass())
            .build();
    }

    private void writeBypassToMainConfig() {
        SharedPreferences main = MessagesController.getGlobalMainSettings();
        if (!MglaDpiConfig.hasSavedUserProxy()) {
            MglaDpiConfig.saveUserProxy(
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
        buildLocalSettings().toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.apply();
    }

    private void restoreUserProxy() {
        SharedPreferences main = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = main.edit();
        if (MglaDpiConfig.hasSavedUserProxy()) {
            ProxySettings restored = ProxySettings.builder()
                .setType(ProxySettings.intToType(MglaDpiConfig.getSavedUserProxyType()))
                .setAddress(MglaDpiConfig.getSavedUserProxyIp())
                .setPort(MglaDpiConfig.getSavedUserProxyPort())
                .setUser(MglaDpiConfig.getSavedUserProxyUser())
                .setPassword(MglaDpiConfig.getSavedUserProxyPass())
                .setSecret(MglaDpiConfig.getSavedUserProxySecret())
                .build();
            boolean wasEnabled = MglaDpiConfig.isSavedUserProxyEnabled();
            MglaDpiConfig.clearSavedUserProxy();
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

    // ---------------------------------------------------------------- proxy list visibility

    /** Пока обход включён, локальный прокси показывается в списке прокси Telegram как активный. */
    private void addBypassProxyToList() {
        try {
            SharedConfig.loadProxyList();
            if (bypassProxyInfo == null) {
                savedCurrentProxyRef = SharedConfig.currentProxy;
                bypassProxyInfo = SharedConfig.addProxy(new SharedConfig.ProxyInfo(buildLocalSettings()));
            }
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
                // deleteProxy очистит mainconfig и вызовет setProxySettings(false) —
                // restoreUserProxy() ниже всё равно восстановит пользовательский прокси.
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

    // ---------------------------------------------------------------- status for UI

    private void notifyChanged() {
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dpiBypassChanged));
    }

    public String getStatusText() {
        if (!MglaDpiConfig.isEnabled()) {
            return "Отключён";
        }
        if (MglaBypassVpnGuard.shouldShowVpnPaused()) {
            return "На паузе";
        }
        if (testing) {
            return "Идёт подбор стратегий…";
        }
        if (!isServerRunning()) {
            return lastError != null ? "Ошибка: " + lastError : "Движок не запущен";
        }
        if (proxyApplied) {
            return "Работает";
        }
        return "Запускается…";
    }

    public String getTelegramConnectionStateText() {
        int state;
        try {
            state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        } catch (Throwable e) {
            return "—";
        }
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
    }

    public long getUptimeSeconds() {
        if (sessionStartElapsed <= 0) {
            return 0;
        }
        return Math.max(0, (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000);
    }
}
