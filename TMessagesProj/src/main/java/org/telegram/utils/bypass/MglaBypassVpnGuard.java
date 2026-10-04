package org.telegram.utils.bypass;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.VpnMonitor;
import org.telegram.utils.dpi.MglaDpiBypass;
import org.telegram.utils.dpi.MglaDpiConfig;
import org.telegram.utils.wsbypass.MglaWsBypass;
import org.telegram.utils.wsbypass.MglaWsConfig;

/**
 * Взаимоисключение обхода блокировок и системного VPN:
 * при VPN обход приостанавливается, после выключения VPN
 * восстанавливается последний выбранный режим (WS или ByeDPI).
 */
public final class MglaBypassVpnGuard {

    public static final String MODE_WS = "ws";
    public static final String MODE_DPI = "dpi";

    private static final String PREFS = "mgla_config";
    private static final String PREF_LAST_MODE = "bypass_last_mode";
    private static final String PREF_VPN_SUSPENDED = "bypass_vpn_suspended";

    private MglaBypassVpnGuard() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getLastMode() {
        String mode = prefs().getString(PREF_LAST_MODE, "");
        return mode != null ? mode : "";
    }

    public static void noteUserEnabled(String mode) {
        if (MODE_WS.equals(mode) || MODE_DPI.equals(mode)) {
            prefs().edit().putString(PREF_LAST_MODE, mode).apply();
        }
    }

    public static void noteUserDisabled(String mode) {
        if (mode != null && mode.equals(getLastMode())) {
            prefs().edit().putString(PREF_LAST_MODE, "").apply();
        }
    }

    public static boolean isVpnSuspended() {
        return prefs().getBoolean(PREF_VPN_SUSPENDED, false);
    }

    private static void setVpnSuspended(boolean suspended) {
        // commit — чтобы статус в UI сразу видел флаг
        prefs().edit().putBoolean(PREF_VPN_SUSPENDED, suspended).commit();
    }

    /** Обход держит (или будет держать) прокси — не мешать логике proxy_no_vpn. */
    public static boolean isManagingProxy() {
        return MglaWsConfig.isEnabled() || MglaDpiConfig.isEnabled();
    }

    /** Пометить обход приостановленным (выбор пользователя уже сохранён, движок не стартовал). */
    public static void markSuspended() {
        setVpnSuspended(true);
    }

    public static void clearSuspended() {
        setVpnSuspended(false);
    }

    /** Показать в статусе «На паузе», если обход включён пользователем и сейчас активен VPN. */
    public static boolean shouldShowVpnPaused() {
        return isManagingProxy() && VpnMonitor.getInstance().isVpnActive();
    }

    /**
     * Вызов после init обходов и VpnMonitor.start():
     * если VPN уже активен — не стартовать; если флаг подвеса остался без VPN — восстановить.
     */
    public static void reconcileOnAppStart() {
        syncWithVpn();
    }

    /** Периодическая сверка (UI / тикер): подвес или восстановление по факту VPN. */
    public static void syncWithVpn() {
        try {
            onVpnStateChanged(VpnMonitor.getInstance().isVpnActive());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static synchronized void onVpnStateChanged(boolean vpnActive) {
        try {
            if (vpnActive) {
                suspendIfNeeded();
            } else {
                resumeIfNeeded();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void suspendIfNeeded() {
        MglaWsBypass ws = MglaWsBypass.getInstance();
        MglaDpiBypass dpi = MglaDpiBypass.getInstance();
        boolean managing = false;

        if (ws.isEnabled()) {
            noteUserEnabled(MODE_WS);
            if (ws.isProxyApplied() || ws.isServerRunning()) {
                ws.suspendForVpn();
            }
            managing = true;
        } else if (dpi.isEnabled()) {
            noteUserEnabled(MODE_DPI);
            if (dpi.isProxyApplied() || dpi.isServerRunning()) {
                dpi.suspendForVpn();
            }
            managing = true;
        }

        if (managing) {
            setVpnSuspended(true);
        }
    }

    private static void resumeIfNeeded() {
        if (!isVpnSuspended()) {
            return;
        }
        setVpnSuspended(false);

        String mode = getLastMode();
        if (MODE_WS.equals(mode) && MglaWsConfig.isEnabled()) {
            MglaWsBypass.getInstance().resumeAfterVpn();
        } else if (MODE_DPI.equals(mode) && MglaDpiConfig.isEnabled()) {
            MglaDpiBypass.getInstance().resumeAfterVpn();
        }
    }
}
