package org.telegram.utils.wsbypass;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;

/**
 * Настройки WS-обхода и хаба (статистика).
 * <p>
 * Обход: {@link #getRelayHost()} → обычно {@code mglabot.mooo.com} ({@code /apiws}).
 * Статистика: напрямую {@link #getHubHost()} → {@code mglahub.mooo.com} ({@code /mgla-stats/}),
 * либо через релей {@code /mgla-ai/v1/stats-batch}, если обход включён.
 * Хосты/токены — из {@code BuildConfig} / {@code local.properties}, prefs могут переопределить.
 */
public final class MglaWsConfig {

    public static final String PREFS = "mgla_config";

    public static final String DEFAULT_RELAY_HOST = "mglabot.mooo.com";
    public static final String DEFAULT_HUB_HOST = "mglahub.mooo.com";

    /**
     * SPKI SHA-256 (base64) сертификата WS-релея (mglabot) — pinning при TLS.
     * При смене ключа на сервере нужно обновить константу.
     */
    public static final String RELAY_SPKI_SHA256_BASE64 = "Ae0rI3yMlnvtv5bwOy5r9fqcniG46yrElv0ox6x5g60=";

    /**
     * SPKI SHA-256 (base64) сертификата хаба статистики (mglahub).
     */
    public static final String HUB_SPKI_SHA256_BASE64 = "whkC3sYvKrHQgAVj2Xx2/QfBEH9DHCEPyyOw5sqI/3E=";

    private static final String PREF_ENABLED = "ws_enabled";
    private static final String PREF_PORT = "ws_port";
    private static final String PREF_SECRET = "ws_secret";
    private static final String PREF_RELAY_HOST = "ws_relay_host";
    private static final String PREF_RELAY_TOKEN = "ws_relay_token";
    private static final String PREF_HUB_HOST = "hub_host";
    private static final String PREF_HUB_TOKEN = "hub_token";

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
        if (!org.telegram.messenger.MglaFeatureFlags.isAllowed("ws_enabled")) {
            return false;
        }
        return getPrefs().getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        if (enabled && !org.telegram.messenger.MglaFeatureFlags.isAllowed("ws_enabled")) {
            enabled = false;
        }
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
        String override = getPrefs().getString(PREF_RELAY_HOST, null);
        if (!TextUtils.isEmpty(override)) {
            return override.trim();
        }
        String fromBuild = BuildConfig.MGLA_WS_RELAY_HOST;
        return TextUtils.isEmpty(fromBuild) ? DEFAULT_RELAY_HOST : fromBuild.trim();
    }

    public static void setRelayHost(String host) {
        getPrefs().edit().putString(PREF_RELAY_HOST, host == null ? "" : host.trim()).apply();
    }

    /**
     * Токен WS-релея (mglabot): prefs → {@code MGLA_WS_RELAY_TOKEN} в local.properties.
     */
    public static String getRelayToken() {
        String override = getPrefs().getString(PREF_RELAY_TOKEN, null);
        if (!TextUtils.isEmpty(override)) {
            return override.trim();
        }
        String fromBuild = BuildConfig.MGLA_WS_RELAY_TOKEN;
        return fromBuild == null ? "" : fromBuild.trim();
    }

    public static void setRelayToken(String token) {
        getPrefs().edit().putString(PREF_RELAY_TOKEN, token == null ? "" : token.trim()).apply();
    }

    /** Релей обхода готов: есть хост и непустой токен. */
    public static boolean isRelayConfigured() {
        return !TextUtils.isEmpty(getRelayHost()) && !TextUtils.isEmpty(getRelayToken());
    }

    /** Хост хаба статистики / панели ({@code mglahub.mooo.com}). */
    public static String getHubHost() {
        // BuildConfig (local.properties) wins over stale prefs overrides.
        String fromBuild = BuildConfig.MGLA_HUB_HOST;
        if (!TextUtils.isEmpty(fromBuild)) {
            return fromBuild.trim();
        }
        String override = getPrefs().getString(PREF_HUB_HOST, null);
        if (!TextUtils.isEmpty(override)) {
            return override.trim();
        }
        return DEFAULT_HUB_HOST;
    }

    public static void setHubHost(String host) {
        getPrefs().edit().putString(PREF_HUB_HOST, host == null ? "" : host.trim()).apply();
    }

    /**
     * Токен хаба (stats / feature flags на mglahub): {@code MGLA_HUB_TOKEN} из сборки,
     * иначе prefs, иначе {@link #getRelayToken()}.
     */
    public static String getHubToken() {
        // Prefer BuildConfig so a rebuild always picks up local.properties;
        // an old prefs override previously caused silent 401 on /mgla-config/.
        String fromBuild = BuildConfig.MGLA_HUB_TOKEN;
        if (!TextUtils.isEmpty(fromBuild)) {
            return fromBuild.trim();
        }
        String override = getPrefs().getString(PREF_HUB_TOKEN, null);
        if (!TextUtils.isEmpty(override)) {
            return override.trim();
        }
        return getRelayToken();
    }

    public static void setHubToken(String token) {
        getPrefs().edit().putString(PREF_HUB_TOKEN, token == null ? "" : token.trim()).apply();
    }

    /** Хаб (статистика, ИИ-прокси) готов к запросам. */
    public static boolean isHubConfigured() {
        return !TextUtils.isEmpty(getHubHost()) && !TextUtils.isEmpty(getHubToken());
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
