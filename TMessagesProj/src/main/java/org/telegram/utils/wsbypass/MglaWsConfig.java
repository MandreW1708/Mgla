package org.telegram.utils.wsbypass;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;

/**
 * Настройки WS-обхода и хаба (статистика).
 * <p>
 * Обычный обход (всем): {@link #REGION_DE} «Германия1» ({@code mglabot}) или
 * {@link #REGION_HUB} «Германия2» ({@code mglahub}), path {@link #DEFAULT_RELAY_WS_PATH}
 * (legacy {@code /apiws} ещё принимается сервером).
 * Ultra-обход: {@link #FINLAND_RELAY_HOST} ({@code fin.mgla.mooo.com}), только для Ultra;
 * выбор региона при Ultra не используется.
 * Статистика: напрямую хаб ({@code /mgla-stats/}), либо через релей {@code /mgla-ai/v1/stats-batch}.
 * Хосты/токены — из {@code BuildConfig} / {@code local.properties}, prefs могут переопределить.
 * Для DE1/DE2 клиент пробует список alias-хостов (SNI failover) без смены региона в UI.
 */
public final class MglaWsConfig {

    public static final String PREFS = "mgla_config";

    /** Обычный обход: Германия1 (mglabot.mooo.com). */
    public static final String REGION_DE = "de";
    /** Обычный обход: Германия2 (mglahub.mooo.com). */
    public static final String REGION_HUB = "hub";
    /** @deprecated Pref value from older builds; migrated to {@link #REGION_DE}. */
    public static final String REGION_FI = "fi";

    public static final String DEFAULT_RELAY_HOST = "mglabot.mooo.com";
    /** Ultra-обход (платный): Финляндия. */
    public static final String FINLAND_RELAY_HOST = "fin.mgla.mooo.com";
    public static final String DEFAULT_HUB_HOST = "mglahub.mooo.com";

    /** Camouflage WS path (server still accepts legacy {@code /apiws}). */
    public static final String DEFAULT_RELAY_WS_PATH = "/ws/v1/live";
    public static final String LEGACY_RELAY_WS_PATH = "/apiws";

    /** DE1 alias without {@code mgla*} in the SNI (sslip.io → same IP). */
    public static final String DEFAULT_RELAY_HOST_ALIAS = "31-77-218-160.sslip.io";
    /** DE2 alias without {@code mgla*} in the SNI. */
    public static final String DEFAULT_HUB_HOST_ALIAS = "194-58-33-22.sslip.io";

    /**
     * SPKI SHA-256 (base64) сертификата WS-релея (mglabot) — pinning при TLS.
     * При смене ключа на сервере нужно обновить константу.
     */
    public static final String RELAY_SPKI_SHA256_BASE64 = "R+FPCkeJ/GAQPWYcX7SZeDQnZYQdrq/MYzTCgYkieOY=";

    /**
     * SPKI SHA-256 (base64) сертификата хаба статистики (mglahub).
     */
    public static final String HUB_SPKI_SHA256_BASE64 = "whkC3sYvKrHQgAVj2Xx2/QfBEH9DHCEPyyOw5sqI/3E=";

    private static final String PREF_ENABLED = "ws_enabled";
    private static final String PREF_ULTRA_BYPASS = "ws_ultra_bypass";
    private static final String PREF_BYPASS_REGION = "ws_bypass_region";
    private static final String PREF_PORT = "ws_port";
    private static final String PREF_SECRET = "ws_secret";
    private static final String PREF_RELAY_HOST = "ws_relay_host";
    private static final String PREF_RELAY_HOST_INDEX = "ws_relay_host_index";
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

    /**
     * Raw Ultra-обход preference (ignores subscription). Prefer {@link #isUltraBypassEnabled()}.
     */
    public static boolean isUltraBypassPrefEnabled() {
        return getPrefs().getBoolean(PREF_ULTRA_BYPASS, false);
    }

    /**
     * Ultra path via Finland ({@link #FINLAND_RELAY_HOST}): preference on and Ultra sub.
     * Does <b>not</b> clear the preference when Ultra is temporarily missing from
     * local cache (features poll glitch) — otherwise the toggle never comes back.
     */
    public static boolean isUltraBypassEnabled() {
        return isUltraBypassPrefEnabled()
                && org.telegram.messenger.MglaSubscription.hasUltraOnAnyAccount();
    }

    public static void setUltraBypassEnabled(boolean enabled) {
        if (enabled && !org.telegram.messenger.MglaSubscription.hasUltraOnAnyAccount()) {
            enabled = false;
        }
        getPrefs().edit().putBoolean(PREF_ULTRA_BYPASS, enabled).apply();
    }

    /**
     * Region for normal (non-Ultra) bypass: {@link #REGION_DE} or {@link #REGION_HUB}.
     * Ignored while {@link #isUltraBypassEnabled()} — traffic goes to Finland.
     */
    public static String getBypassRegion() {
        String r = getPrefs().getString(PREF_BYPASS_REGION, REGION_DE);
        if (REGION_HUB.equalsIgnoreCase(r)) {
            return REGION_HUB;
        }
        // Older builds stored Finland as a free region — map to Germany1.
        if (REGION_FI.equalsIgnoreCase(r)) {
            getPrefs().edit().putString(PREF_BYPASS_REGION, REGION_DE).apply();
        }
        return REGION_DE;
    }

    public static void setBypassRegion(String region) {
        String norm = REGION_HUB.equalsIgnoreCase(region) ? REGION_HUB : REGION_DE;
        getPrefs().edit().putString(PREF_BYPASS_REGION, norm).apply();
    }

    /** Human label for the region picker (RU). */
    public static String getBypassRegionLabel() {
        return REGION_HUB.equals(getBypassRegion()) ? "Германия2" : "Германия1";
    }

    /** Host for the selected normal-bypass region (never Ultra Finland). */
    public static String getRegionRelayHost() {
        String[] hosts = getNormalRegionRelayHosts();
        if (hosts.length > 0) {
            return hosts[0];
        }
        if (REGION_HUB.equals(getBypassRegion())) {
            return getHubHost();
        }
        String fromBuild = BuildConfig.MGLA_WS_RELAY_HOST;
        return TextUtils.isEmpty(fromBuild) ? DEFAULT_RELAY_HOST : fromBuild.trim();
    }

    /** Ordered SNI candidates for the selected DE1/DE2 region (primary + aliases). */
    public static String[] getNormalRegionRelayHosts() {
        if (REGION_HUB.equals(getBypassRegion())) {
            return parseHostList(BuildConfig.MGLA_HUB_RELAY_HOSTS,
                getHubHost(), DEFAULT_HUB_HOST_ALIAS);
        }
        return parseHostList(BuildConfig.MGLA_WS_RELAY_HOSTS,
            TextUtils.isEmpty(BuildConfig.MGLA_WS_RELAY_HOST)
                ? DEFAULT_RELAY_HOST : BuildConfig.MGLA_WS_RELAY_HOST.trim(),
            DEFAULT_RELAY_HOST_ALIAS);
    }

    /**
     * Ordered SNI candidates for the active bypass path.
     * Ultra returns only Finland; otherwise DE1/DE2 primary + aliases.
     */
    public static String[] getRelayHostCandidates() {
        if (isUltraBypassEnabled()) {
            return new String[]{FINLAND_RELAY_HOST};
        }
        return getNormalRegionRelayHosts();
    }

    /** Camouflage WS path used by new clients ({@link #DEFAULT_RELAY_WS_PATH}). */
    public static String getRelayWsPath() {
        String fromBuild = BuildConfig.MGLA_WS_PATH;
        if (!TextUtils.isEmpty(fromBuild)) {
            String p = fromBuild.trim();
            if (!p.startsWith("/")) {
                p = "/" + p;
            }
            return p;
        }
        return DEFAULT_RELAY_WS_PATH;
    }

    private static String[] parseHostList(String csv, String primaryFallback, String aliasFallback) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (!TextUtils.isEmpty(csv)) {
            for (String part : csv.split("[,;\\s]+")) {
                if (part == null) {
                    continue;
                }
                String h = part.trim();
                if (!h.isEmpty()) {
                    out.add(h);
                }
            }
        }
        if (out.isEmpty() && !TextUtils.isEmpty(primaryFallback)) {
            out.add(primaryFallback.trim());
        }
        if (!TextUtils.isEmpty(aliasFallback)) {
            out.add(aliasFallback.trim());
        }
        return out.toArray(new String[0]);
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
        if (isUltraBypassEnabled()) {
            return FINLAND_RELAY_HOST;
        }
        String override = getPrefs().getString(PREF_RELAY_HOST, null);
        if (!TextUtils.isEmpty(override)) {
            return override.trim();
        }
        String[] hosts = getRelayHostCandidates();
        if (hosts.length == 0) {
            return getRegionRelayHost();
        }
        int idx = getPrefs().getInt(PREF_RELAY_HOST_INDEX, 0);
        if (idx < 0 || idx >= hosts.length) {
            idx = 0;
        }
        return hosts[idx];
    }

    /** Sticky preferred host after a successful TLS/WS handshake. */
    public static void markRelayHostOk(String host) {
        if (TextUtils.isEmpty(host) || isUltraBypassEnabled()) {
            return;
        }
        String[] hosts = getNormalRegionRelayHosts();
        for (int i = 0; i < hosts.length; i++) {
            if (host.equalsIgnoreCase(hosts[i])) {
                getPrefs().edit().putInt(PREF_RELAY_HOST_INDEX, i).apply();
                return;
            }
        }
    }

    /** Rotate to the next alias when TLS/handshake fails for the current host. */
    public static void markRelayHostFailed(String host) {
        if (TextUtils.isEmpty(host) || isUltraBypassEnabled()) {
            return;
        }
        String[] hosts = getNormalRegionRelayHosts();
        if (hosts.length <= 1) {
            return;
        }
        int cur = getPrefs().getInt(PREF_RELAY_HOST_INDEX, 0);
        for (int i = 0; i < hosts.length; i++) {
            if (host.equalsIgnoreCase(hosts[i])) {
                cur = i;
                break;
            }
        }
        getPrefs().edit().putInt(PREF_RELAY_HOST_INDEX, (cur + 1) % hosts.length).apply();
    }

    /**
     * Alias SNIs (sslip.io) are served with the primary leaf cert when LE SAN expand
     * is blocked (port 80). Client skips hostname verify and trusts {@link #getRelaySpkiPins}.
     */
    public static boolean isPinOnlyRelayHost(String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        return DEFAULT_RELAY_HOST_ALIAS.equalsIgnoreCase(host)
            || DEFAULT_HUB_HOST_ALIAS.equalsIgnoreCase(host)
            || host.toLowerCase(java.util.Locale.US).endsWith(".sslip.io")
            || host.toLowerCase(java.util.Locale.US).endsWith(".nip.io");
    }

    /** TLS pin for the host returned by {@link #getRelayHost()}, or null for system CAs. */
    public static String[] getRelaySpkiPins(String host) {
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        // Finland (Ultra) uses Let's Encrypt — system CAs until a dedicated pin is published.
        if (FINLAND_RELAY_HOST.equalsIgnoreCase(host)) {
            return null;
        }
        if (isDe1RelayHost(host)) {
            return new String[]{RELAY_SPKI_SHA256_BASE64};
        }
        if (isDe2RelayHost(host)) {
            return new String[]{HUB_SPKI_SHA256_BASE64};
        }
        return null;
    }

    private static boolean isDe1RelayHost(String host) {
        if (DEFAULT_RELAY_HOST.equalsIgnoreCase(host)
                || DEFAULT_RELAY_HOST_ALIAS.equalsIgnoreCase(host)) {
            return true;
        }
        String primary = BuildConfig.MGLA_WS_RELAY_HOST;
        if (!TextUtils.isEmpty(primary) && primary.trim().equalsIgnoreCase(host)) {
            return true;
        }
        for (String h : parseHostList(BuildConfig.MGLA_WS_RELAY_HOSTS, primary, DEFAULT_RELAY_HOST_ALIAS)) {
            if (host.equalsIgnoreCase(h)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDe2RelayHost(String host) {
        if (DEFAULT_HUB_HOST.equalsIgnoreCase(host)
                || DEFAULT_HUB_HOST_ALIAS.equalsIgnoreCase(host)) {
            return true;
        }
        String hub = getHubHost();
        if (!TextUtils.isEmpty(hub) && hub.equalsIgnoreCase(host)) {
            return true;
        }
        for (String h : parseHostList(BuildConfig.MGLA_HUB_RELAY_HOSTS, hub, DEFAULT_HUB_HOST_ALIAS)) {
            if (host.equalsIgnoreCase(h)) {
                return true;
            }
        }
        return false;
    }

    public static void setRelayHost(String host) {
        getPrefs().edit().putString(PREF_RELAY_HOST, host == null ? "" : host.trim()).apply();
    }

    /**
     * Токен текущего обхода:
     * <ul>
     *   <li>Ultra (Finland) / Германия1 → JWT / {@code MGLA_WS_RELAY_TOKEN} (тот же, что на fin.mgla / mglabot)</li>
     *   <li>Германия2 → JWT / {@code MGLA_HUB_TOKEN} (mglahub)</li>
     * </ul>
     * Using the bot token against the hub (or vice versa) causes auth failures.
     */
    public static String getRelayToken() {
        if (!isUltraBypassEnabled() && REGION_HUB.equals(getBypassRegion())) {
            return getHubToken();
        }
        return getBotRelayToken();
    }

    /**
     * JWT / {@code MGLA_WS_RELAY_TOKEN} for mglabot / fin.mgla (never the hub WS token).
     */
    public static String getBotRelayToken() {
        try {
            String session = org.telegram.messenger.MglaAuth.getAccessToken();
            if (!TextUtils.isEmpty(session)) {
                return session;
            }
        } catch (Throwable ignored) {
        }
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

    /** Релей обхода готов: есть хост и способ получить токен (session / legacy). */
    public static boolean isRelayConfigured() {
        if (TextUtils.isEmpty(getRelayHost())) {
            return false;
        }
        if (!TextUtils.isEmpty(BuildConfig.MGLA_WS_RELAY_TOKEN)) {
            return true;
        }
        if (!TextUtils.isEmpty(getPrefs().getString(PREF_RELAY_TOKEN, null))) {
            return true;
        }
        try {
            return org.telegram.messenger.MglaAuth.hasSessionToken();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Адрес запасного WEB proxy в форме {@code host/path} (без https://).
     * Ultra → Finland; Германия2 → хаб; Германия1 → mglabot.
     */
    public static String getWebProxyHost() {
        if (isUltraBypassEnabled()) {
            return FINLAND_RELAY_HOST + "/tgweb";
        }
        // WebView requires a hostname-matching LE cert — do not use sslip aliases here.
        if (REGION_HUB.equals(getBypassRegion())) {
            String hubProxy = BuildConfig.MGLA_ULTRA_WEB_PROXY_HOST;
            if (!TextUtils.isEmpty(hubProxy)) {
                return hubProxy.trim();
            }
            String hub = getHubHost();
            return TextUtils.isEmpty(hub) ? "" : hub.trim() + "/tgweb";
        }
        String fromBuild = BuildConfig.MGLA_WEB_PROXY_HOST;
        return fromBuild == null ? "" : fromBuild.trim();
    }

    /** Секрет WEB proxy: 32 hex (как в profiles.json tproxy-server). */
    public static String getWebProxySecret() {
        // Германия2 (хаб) — отдельный secret профиля mgla на mglahub.
        if (!isUltraBypassEnabled() && REGION_HUB.equals(getBypassRegion())) {
            String hub = BuildConfig.MGLA_ULTRA_WEB_PROXY_SECRET;
            if (!TextUtils.isEmpty(hub)) {
                return hub.trim().toLowerCase(java.util.Locale.US);
            }
        }
        // Ultra (Finland) и Германия1 — один secret с mglabot / fin.mgla.
        String fromBuild = BuildConfig.MGLA_WEB_PROXY_SECRET;
        return fromBuild == null ? "" : fromBuild.trim().toLowerCase(java.util.Locale.US);
    }

    public static boolean isWebProxyConfigured() {
        return !TextUtils.isEmpty(getWebProxyHost()) && !TextUtils.isEmpty(getWebProxySecret());
    }

    /**
     * Хост WEB proxy для туннеля обновлений.
     * Ultra → Finland (профиль mgla-upd); обычный → mglabot (туннель там же).
     * Германия2 на WEB: туннель обновлений всё ещё на боте (на хабе его нет).
     */
    public static String getWebUpdateProxyHost() {
        if (isUltraBypassEnabled()) {
            return FINLAND_RELAY_HOST + "/tgweb";
        }
        String fromBuild = BuildConfig.MGLA_WEB_PROXY_HOST;
        return fromBuild == null ? "" : fromBuild.trim();
    }

    /** Секрет профиля mgla-upd tproxy-server: туннель к эндпоинтам обновлений через WEB proxy. */
    public static String getWebUpdateSecret() {
        String fromBuild = BuildConfig.MGLA_WEB_UPDATE_SECRET;
        return fromBuild == null ? "" : fromBuild.trim().toLowerCase(java.util.Locale.US);
    }

    public static boolean isWebUpdateTunnelConfigured() {
        // Tunnel terminates on mglabot/fin — needs bot token, not the Germany2 hub token.
        return !TextUtils.isEmpty(getWebUpdateProxyHost()) && !TextUtils.isEmpty(getWebUpdateSecret())
            && !TextUtils.isEmpty(getBotRelayToken());
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
     * Токен хаба: JWT установки, иначе legacy {@code MGLA_HUB_TOKEN} / prefs / relay token.
     */
    public static String getHubToken() {
        try {
            String session = org.telegram.messenger.MglaAuth.getAccessToken();
            if (!TextUtils.isEmpty(session)) {
                return session;
            }
        } catch (Throwable ignored) {
        }
        String fromBuild = BuildConfig.MGLA_HUB_TOKEN;
        if (!TextUtils.isEmpty(fromBuild)) {
            return fromBuild.trim();
        }
        String override = getPrefs().getString(PREF_HUB_TOKEN, null);
        if (!TextUtils.isEmpty(override)) {
            return override.trim();
        }
        String fromRelayBuild = BuildConfig.MGLA_WS_RELAY_TOKEN;
        return fromRelayBuild == null ? "" : fromRelayBuild.trim();
    }

    public static void setHubToken(String token) {
        getPrefs().edit().putString(PREF_HUB_TOKEN, token == null ? "" : token.trim()).apply();
    }

    /** Хаб (статистика, ИИ-прокси) готов к запросам. */
    public static boolean isHubConfigured() {
        if (TextUtils.isEmpty(getHubHost())) {
            return false;
        }
        if (!TextUtils.isEmpty(BuildConfig.MGLA_HUB_TOKEN) || !TextUtils.isEmpty(BuildConfig.MGLA_WS_RELAY_TOKEN)) {
            return true;
        }
        if (!TextUtils.isEmpty(getPrefs().getString(PREF_HUB_TOKEN, null))) {
            return true;
        }
        try {
            return org.telegram.messenger.MglaAuth.hasSessionToken();
        } catch (Throwable ignored) {
            return false;
        }
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
