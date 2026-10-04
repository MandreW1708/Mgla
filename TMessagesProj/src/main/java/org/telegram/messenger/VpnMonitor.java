package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;

import androidx.annotation.NonNull;

import org.telegram.tgnet.ConnectionsManager;

import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

public class VpnMonitor {

    private static volatile VpnMonitor instance;
    private ConnectivityManager.NetworkCallback vpnCallback;
    private ConnectivityManager.NetworkCallback defaultCallback;
    private boolean registered;

    public static VpnMonitor getInstance() {
        VpnMonitor localInstance = instance;
        if (localInstance == null) {
            synchronized (VpnMonitor.class) {
                localInstance = instance;
                if (localInstance == null) {
                    instance = localInstance = new VpnMonitor();
                }
            }
        }
        return localInstance;
    }

    private VpnMonitor() {}

    public void start() {
        if (registered) return;
        if (android.os.Build.VERSION.SDK_INT < 23) return;

        ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        ConnectivityManager.NetworkCallback listener = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                onVpnStateChanged();
            }

            @Override
            public void onLost(@NonNull Network network) {
                onVpnStateChanged();
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                onVpnStateChanged();
            }
        };

        vpnCallback = listener;
        NetworkRequest vpnRequest = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build();
        cm.registerNetworkCallback(vpnRequest, vpnCallback);

        // Активная сеть может стать VPN без отдельного TRANSPORT_VPN callback на части прошивок
        try {
            defaultCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    onVpnStateChanged();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    onVpnStateChanged();
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                    onVpnStateChanged();
                }
            };
            cm.registerDefaultNetworkCallback(defaultCallback);
        } catch (Throwable ignored) {
            defaultCallback = null;
        }

        registered = true;
        // Сразу сверить текущее состояние (VPN мог быть включён до старта монитора)
        onVpnStateChanged();
    }

    public void stop() {
        if (!registered) return;
        if (android.os.Build.VERSION.SDK_INT < 23) return;

        ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (vpnCallback != null) {
                try {
                    cm.unregisterNetworkCallback(vpnCallback);
                } catch (Throwable ignored) {
                }
            }
            if (defaultCallback != null) {
                try {
                    cm.unregisterNetworkCallback(defaultCallback);
                } catch (Throwable ignored) {
                }
            }
        }
        vpnCallback = null;
        defaultCallback = null;
        registered = false;
    }

    public boolean isVpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        return true;
                    }
                }
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        return true;
                    }
                }
            }
            // Fallback для старых API / кривых прошивок
            NetworkInfo vpnInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_VPN);
            if (vpnInfo != null && vpnInfo.isConnectedOrConnecting()) {
                return true;
            }
            return hasVpnNetworkInterface();
        } catch (Exception e) {
            return false;
        }
    }

    /** tun/ppp/wg интерфейсы — запасной признак системного VPN. */
    private static boolean hasVpnNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return false;
            }
            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (iface == null || !iface.isUp()) {
                    continue;
                }
                String name = iface.getName();
                if (name == null) {
                    continue;
                }
                String lower = name.toLowerCase();
                if (lower.startsWith("tun") || lower.startsWith("ppp") || lower.startsWith("pptp")
                    || lower.startsWith("wg") || lower.contains("tun") || lower.contains("vpn")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void onVpnStateChanged() {
        boolean vpnActive = isVpnActive();

        // Обход блокировок всегда уступает системному VPN
        try {
            org.telegram.utils.bypass.MglaBypassVpnGuard.onVpnStateChanged(vpnActive);
        } catch (Throwable ignored) {
        }

        SharedPreferences mglaPrefs = ApplicationLoader.applicationContext.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
        if (!mglaPrefs.getBoolean("proxy_no_vpn", false)) return;

        // Если обход сам управляет прокси — не трогаем его отдельно
        if (org.telegram.utils.bypass.MglaBypassVpnGuard.isManagingProxy()) return;

        SharedPreferences mainPrefs = MessagesController.getGlobalMainSettings();

        if (vpnActive) {
            // VPN включён — отключаем прокси
            if (mainPrefs.getBoolean("proxy_enabled", false)) {
                mainPrefs.edit().putBoolean("proxy_enabled", false).putBoolean("proxy_was_enabled", true).commit();
                ConnectionsManager.setProxySettings(false, null);
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                });
            }
        } else {
            // VPN выключён — включаем прокси обратно
            if (mainPrefs.getBoolean("proxy_was_enabled", false)) {
                mainPrefs.edit().putBoolean("proxy_enabled", true).putBoolean("proxy_was_enabled", false).commit();
                AndroidUtilities.runOnUIThread(() -> {
                    SharedConfig.loadProxyList();
                    if (SharedConfig.currentProxy != null && !SharedConfig.proxyList.isEmpty()) {
                        ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.settings);
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                });
            }
        }
    }
}
