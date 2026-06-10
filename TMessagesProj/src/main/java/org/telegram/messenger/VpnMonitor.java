package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import androidx.annotation.NonNull;

import org.telegram.tgnet.ConnectionsManager;

public class VpnMonitor {

    private static volatile VpnMonitor instance;
    private ConnectivityManager.NetworkCallback vpnCallback;
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

        vpnCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                onVpnStateChanged();
            }

            @Override
            public void onLost(@NonNull Network network) {
                onVpnStateChanged();
            }
        };

        NetworkRequest vpnRequest = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build();
        cm.registerNetworkCallback(vpnRequest, vpnCallback);
        registered = true;
    }

    public void stop() {
        if (!registered) return;
        if (android.os.Build.VERSION.SDK_INT < 23) return;

        ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && vpnCallback != null) {
            cm.unregisterNetworkCallback(vpnCallback);
        }
        vpnCallback = null;
        registered = false;
    }

    public boolean isVpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || android.os.Build.VERSION.SDK_INT < 23) return false;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
        } catch (Exception e) {}
        return false;
    }

    private void onVpnStateChanged() {
        boolean vpnActive = isVpnActive();
        SharedPreferences mglaPrefs = ApplicationLoader.applicationContext.getSharedPreferences("mgla_config", Context.MODE_PRIVATE);
        if (!mglaPrefs.getBoolean("proxy_no_vpn", false)) return;

        SharedPreferences mainPrefs = MessagesController.getGlobalMainSettings();

        if (vpnActive) {
            // VPN включён — отключаем прокси
            if (mainPrefs.getBoolean("proxy_enabled", false)) {
                mainPrefs.edit().putBoolean("proxy_enabled", false).putBoolean("proxy_was_enabled", true).commit();
                ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
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
                        ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.address, SharedConfig.currentProxy.port, SharedConfig.currentProxy.username, SharedConfig.currentProxy.password, SharedConfig.currentProxy.secret);
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                });
            }
        }
    }
}