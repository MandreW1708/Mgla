package org.telegram.utils.wsbypass;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;

/**
 * Настройки WS-обхода: свой релей на mglabot.mooo.com (Германия).
 */
public final class MglaWsConfig {

    public static final String PREFS = "mgla_config";

    /** Хост релея (TLS на 443, путь /apiws?dc=N). */
    public static final String DEFAULT_RELAY_HOST = "mglabot.mooo.com";
    /**
     * Общий секрет с сервером (заголовок X-Mgla-Token).
     * Должен совпадать с MGLA_WS_TOKEN в /opt/mgla-ws-relay/env на сервере.
     */
    public static final String DEFAULT_RELAY_TOKEN = "***REMOVED***";

    private static final String PREF_ENABLED = "ws_enabled";
    private static final String PREF_PORT = "ws_port";
    private static final String PREF_SECRET = "ws_secret";
    private static final String PREF_RELAY_HOST = "ws_relay_host";
    private static final String PREF_RELAY_TOKEN = "ws_relay_token";

    private static final String PREF_SAVED_EXISTS = "ws_saved_exists";
    private static final String PREF_SAVED_ENABLED = "ws_saved_enabled";
    private static final String PREF_SAVED_IP = "ws_saved_ip";
    private static final String PREF_SAVED_PORT = "ws_saved_port";
    private static final String PREF_SAVED_USER = "ws_saved_user";
    private static final String PREF_SAVED_PASS = "ws_saved_pass";
    private static final String PREF_SAVED_SECRET = "ws_saved_secret";
    private static final String PREF_SAVED_TYPE = "ws_saved_type";

    private MglaWsConfig() {
    }

    public static boolean isEnabled() {
        return getPrefs().getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    public static int getPort() {
        return getPrefs().getInt(PREF_PORT, 0);
    }

    public static void setPort(int port) {
        getPrefs().edit().putInt(PREF_PORT, port).apply();
    }

    public static String getSecret() {
        String s = getPrefs().getString(PREF_SECRET, null);
        if (TextUtils.isEmpty(s) || TextUtils.isEmpty(MtprotoHandshake.validSecretHex(s))) {
            String gen = MtprotoHandshake.generateSecretHex();
            getPrefs().edit().putString(PREF_SECRET, gen).apply();
            return gen;
        }
        return MtprotoHandshake.validSecretHex(s);
    }

    public static void setSecret(String secret) {
        String norm = MtprotoHandshake.validSecretHex(secret);
        if (!TextUtils.isEmpty(norm)) {
            getPrefs().edit().putString(PREF_SECRET, norm).apply();
        }
    }

    public static String getRelayHost() {
        String host = getPrefs().getString(PREF_RELAY_HOST, DEFAULT_RELAY_HOST);
        return TextUtils.isEmpty(host) ? DEFAULT_RELAY_HOST : host.trim();
    }

    public static void setRelayHost(String host) {
        getPrefs().edit().putString(PREF_RELAY_HOST, host == null ? "" : host.trim()).apply();
    }

    public static String getRelayToken() {
        String token = getPrefs().getString(PREF_RELAY_TOKEN, DEFAULT_RELAY_TOKEN);
        return token == null ? "" : token;
    }

    public static void setRelayToken(String token) {
        getPrefs().edit().putString(PREF_RELAY_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public static boolean hasSavedUserProxy() {
        return getPrefs().getBoolean(PREF_SAVED_EXISTS, false);
    }

    public static void saveUserProxy(boolean enabled, String ip, int port, String user, String pass, String secret, int type) {
        getPrefs().edit()
            .putBoolean(PREF_SAVED_EXISTS, true)
            .putBoolean(PREF_SAVED_ENABLED, enabled)
            .putString(PREF_SAVED_IP, ip == null ? "" : ip)
            .putInt(PREF_SAVED_PORT, port)
            .putString(PREF_SAVED_USER, user == null ? "" : user)
            .putString(PREF_SAVED_PASS, pass == null ? "" : pass)
            .putString(PREF_SAVED_SECRET, secret == null ? "" : secret)
            .putInt(PREF_SAVED_TYPE, type)
            .apply();
    }

    public static boolean isSavedUserProxyEnabled() {
        return getPrefs().getBoolean(PREF_SAVED_ENABLED, false);
    }

    public static String getSavedUserProxyIp() {
        return getPrefs().getString(PREF_SAVED_IP, "");
    }

    public static int getSavedUserProxyPort() {
        return getPrefs().getInt(PREF_SAVED_PORT, 1080);
    }

    public static String getSavedUserProxyUser() {
        return getPrefs().getString(PREF_SAVED_USER, "");
    }

    public static String getSavedUserProxyPass() {
        return getPrefs().getString(PREF_SAVED_PASS, "");
    }

    public static String getSavedUserProxySecret() {
        return getPrefs().getString(PREF_SAVED_SECRET, "");
    }

    public static int getSavedUserProxyType() {
        return getPrefs().getInt(PREF_SAVED_TYPE, 0);
    }

    public static void clearSavedUserProxy() {
        getPrefs().edit().putBoolean(PREF_SAVED_EXISTS, false).putBoolean(PREF_SAVED_ENABLED, false).apply();
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
