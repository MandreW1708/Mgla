package org.telegram.utils.dpi;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Настройки встроенного обхода блокировок (Mgla -> Настройки Mgla -> Обход блокировок).
 * Хранятся в общем файле форка "mgla_config".
 */
public final class MglaDpiConfig {

    public static final String PREFS = "mgla_config";

    // Общий выключатель функции
    private static final String PREF_ENABLED = "dpi_enabled";
    // Текущая стратегия — командная строка ciadpi (формат ByeByeDPI)
    private static final String PREF_STRATEGY = "dpi_strategy_cmd";
    // SNI для фейковых пакетов, подставляется вместо {sni}
    private static final String PREF_FAKE_SNI = "dpi_fake_sni";
    // Локальный порт SOCKS5-прокси (чтобы переживать перезапуски)
    private static final String PREF_PORT = "dpi_port";
    // Локальная SOCKS5-аутентификация (другие приложения на устройстве не смогут пользоваться прокси)
    private static final String PREF_SOCKS_USER = "dpi_socks_user";
    private static final String PREF_SOCKS_PASS = "dpi_socks_pass";

    // Снимок пользовательского прокси до включения обхода (восстанавливается при выключении)
    private static final String PREF_SAVED_EXISTS = "dpi_saved_exists";
    private static final String PREF_SAVED_ENABLED = "dpi_saved_enabled";
    private static final String PREF_SAVED_IP = "dpi_saved_ip";
    private static final String PREF_SAVED_PORT = "dpi_saved_port";
    private static final String PREF_SAVED_USER = "dpi_saved_user";
    private static final String PREF_SAVED_PASS = "dpi_saved_pass";
    private static final String PREF_SAVED_SECRET = "dpi_saved_secret";
    private static final String PREF_SAVED_TYPE = "dpi_saved_type";

    // Настройки подбора стратегий
    private static final String PREF_TEST_REQUESTS = "dpi_test_requests";
    private static final String PREF_TEST_PARALLEL = "dpi_test_parallel";
    private static final String PREF_TEST_TIMEOUT = "dpi_test_timeout";
    private static final String PREF_TEST_CUSTOM_ENABLED = "dpi_test_custom_enabled";
    private static final String PREF_TEST_CUSTOM_LIST = "dpi_test_custom_list";

    // Итог последнего подбора (для главного экрана)
    private static final String PREF_TEST_SUMMARY = "dpi_test_summary";

    public static final int TEST_REQUESTS_MIN = 1;
    public static final int TEST_REQUESTS_MAX = 5;
    public static final int TEST_PARALLEL_MIN = 1;
    public static final int TEST_PARALLEL_MAX = 40;
    public static final int TEST_TIMEOUT_MIN = 2;
    public static final int TEST_TIMEOUT_MAX = 20;

    private MglaDpiConfig() {
    }

    public static boolean isEnabled() {
        return getPrefs().getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    public static String getStrategy() {
        String value = getPrefs().getString(PREF_STRATEGY, null);
        return TextUtils.isEmpty(value) ? MglaDpiStrategies.DEFAULT_STRATEGY : value;
    }

    public static void setStrategy(String command) {
        getPrefs().edit().putString(PREF_STRATEGY, MglaDpiStrategies.normalize(command)).apply();
    }

    public static String getFakeSni() {
        String value = getPrefs().getString(PREF_FAKE_SNI, null);
        return TextUtils.isEmpty(value) ? MglaDpiStrategies.DEFAULT_SNI : value;
    }

    public static void setFakeSni(String sni) {
        getPrefs().edit().putString(PREF_FAKE_SNI, sni == null ? "" : sni.trim()).apply();
    }

    public static int getPort() {
        return getPrefs().getInt(PREF_PORT, 0);
    }

    public static void setPort(int port) {
        getPrefs().edit().putInt(PREF_PORT, port).apply();
    }

    /** Гарантирует наличие случайных SOCKS5 credentials для локального прокси. */
    public static void ensureSocksAuth() {
        SharedPreferences prefs = getPrefs();
        String user = prefs.getString(PREF_SOCKS_USER, "");
        String pass = prefs.getString(PREF_SOCKS_PASS, "");
        if (!TextUtils.isEmpty(user) && !TextUtils.isEmpty(pass)) {
            return;
        }
        prefs.edit()
            .putString(PREF_SOCKS_USER, "mgla_" + randomHex(8))
            .putString(PREF_SOCKS_PASS, randomHex(24))
            .apply();
    }

    public static String getSocksUser() {
        ensureSocksAuth();
        return getPrefs().getString(PREF_SOCKS_USER, "");
    }

    public static String getSocksPass() {
        ensureSocksAuth();
        return getPrefs().getString(PREF_SOCKS_PASS, "");
    }

    private static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        Utilities.random.nextBytes(raw);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : raw) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
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

    public static int getTestRequests() {
        return clamp(getPrefs().getInt(PREF_TEST_REQUESTS, 1), TEST_REQUESTS_MIN, TEST_REQUESTS_MAX);
    }

    public static void setTestRequests(int value) {
        getPrefs().edit().putInt(PREF_TEST_REQUESTS, clamp(value, TEST_REQUESTS_MIN, TEST_REQUESTS_MAX)).apply();
    }

    public static int getTestParallel() {
        return clamp(getPrefs().getInt(PREF_TEST_PARALLEL, 20), TEST_PARALLEL_MIN, TEST_PARALLEL_MAX);
    }

    public static void setTestParallel(int value) {
        getPrefs().edit().putInt(PREF_TEST_PARALLEL, clamp(value, TEST_PARALLEL_MIN, TEST_PARALLEL_MAX)).apply();
    }

    public static int getTestTimeoutSec() {
        return clamp(getPrefs().getInt(PREF_TEST_TIMEOUT, 5), TEST_TIMEOUT_MIN, TEST_TIMEOUT_MAX);
    }

    public static void setTestTimeoutSec(int value) {
        getPrefs().edit().putInt(PREF_TEST_TIMEOUT, clamp(value, TEST_TIMEOUT_MIN, TEST_TIMEOUT_MAX)).apply();
    }

    public static boolean isTestCustomEnabled() {
        return getPrefs().getBoolean(PREF_TEST_CUSTOM_ENABLED, false);
    }

    public static void setTestCustomEnabled(boolean enabled) {
        getPrefs().edit().putBoolean(PREF_TEST_CUSTOM_ENABLED, enabled).apply();
    }

    public static String getTestCustomList() {
        return getPrefs().getString(PREF_TEST_CUSTOM_LIST, "");
    }

    public static void setTestCustomList(String list) {
        getPrefs().edit().putString(PREF_TEST_CUSTOM_LIST, list == null ? "" : list).apply();
    }

    /** Стратегии для подбора: свой список (по строке на стратегию) или встроенный. */
    public static List<String> getTestStrategies() {
        List<String> result = new ArrayList<>();
        if (isTestCustomEnabled()) {
            for (String line : getTestCustomList().split("\n")) {
                String cmd = MglaDpiStrategies.normalize(line);
                if (!cmd.isEmpty() && !result.contains(cmd)) {
                    result.add(cmd);
                }
            }
        }
        if (result.isEmpty()) {
            for (String cmd : MglaDpiStrategies.BUILT_IN) {
                result.add(cmd);
            }
        }
        return result;
    }

    public static String getTestSummary() {
        return getPrefs().getString(PREF_TEST_SUMMARY, "");
    }

    public static void setTestSummary(String summary) {
        getPrefs().edit().putString(PREF_TEST_SUMMARY, summary == null ? "" : summary).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
