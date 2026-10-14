package org.telegram.utils.proxy;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.utils.wsbypass.MglaWsConfig;
import org.telegram.utils.wsbypass.WsBypassCore;

import java.util.Iterator;
import java.util.Locale;

/**
 * Внутренние прокси авто-обхода / ByeDPI не должны попадать в пользовательский список
 * и не должны шариться в другие клиенты через t.me/proxy|webproxy.
 */
public final class MglaInternalProxy {

    private MglaInternalProxy() {
    }

    /** Адрес/секрет совпадают с нашим WEB proxy, локальным WS MTProto или типичным ByeDPI. */
    public static boolean isInternal(ProxySettings settings) {
        if (settings == null || !settings.isValid()) {
            return false;
        }
        String address = settings.getAddress() == null ? "" : settings.getAddress().trim();
        String secret = settings.getSecret() == null ? "" : settings.getSecret().trim();
        String addressLower = address.toLowerCase(Locale.US);

        if (settings.getType() == ProxySettings.Type.WEB) {
            String ours = MglaWsConfig.getWebProxyHost();
            if (!TextUtils.isEmpty(ours) && addressLower.equals(ours.toLowerCase(Locale.US))) {
                return true;
            }
            String ourSecret = MglaWsConfig.getWebProxySecret();
            if (!TextUtils.isEmpty(ourSecret) && secretEquals(secret, ourSecret)) {
                return true;
            }
            String upd = MglaWsConfig.getWebUpdateSecret();
            if (!TextUtils.isEmpty(upd) && secretEquals(secret, upd)) {
                return true;
            }
            // Любой webproxy на наш хост /tgweb — тоже внутренний.
            if (addressLower.startsWith("mglabot.mooo.com/") || addressLower.equals("mglabot.mooo.com")) {
                return true;
            }
            return false;
        }

        if (isLoopback(addressLower)) {
            // Локальный MTProto авто-обхода (секрет из конфига) или SOCKS ByeDPI.
            if (settings.getType() == ProxySettings.Type.MTPROTO) {
                String wsSecret = MglaWsConfig.getSecret();
                if (!TextUtils.isEmpty(wsSecret) && secretEquals(secret, wsSecret)) {
                    return true;
                }
                // Наш локальный порт WS (если известен) — тоже прячем.
                try {
                    int port = WsBypassCore.getInstance().getPort();
                    if (port > 0 && settings.getPort() == port) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (settings.getType() == ProxySettings.Type.SOCKS5
                    && TextUtils.isEmpty(settings.getUser())
                    && TextUtils.isEmpty(settings.getPassword())) {
                // ByeDPI поднимает анонимный SOCKS на 127.0.0.1 — не для шаринга.
                return true;
            }
        }
        return false;
    }

    /** Удаляет из SharedConfig.proxyList все внутренние записи (после прошлых версий / импорта). */
    public static void scrubProxyList() {
        try {
            SharedConfig.loadProxyList();
            boolean changed = false;
            Iterator<SharedConfig.ProxyInfo> it = SharedConfig.proxyList.iterator();
            while (it.hasNext()) {
                SharedConfig.ProxyInfo info = it.next();
                if (info != null && isInternal(info.settings)) {
                    it.remove();
                    if (SharedConfig.currentProxy == info) {
                        // Не трогаем active bypass: вызывающий код сам держит currentProxy.
                        SharedConfig.currentProxy = null;
                    }
                    changed = true;
                }
            }
            if (changed) {
                SharedConfig.saveProxyList();
                AndroidUtilities.runOnUIThread(() ->
                    NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.proxySettingsChanged));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * HMAC-контекст capability для нашего WEB proxy — отличается от stock Telegram Desktop,
     * поэтому ссылка/секрет из Mgla не заводятся в чужих клиентах.
     */
    public static boolean useMglaWebCapability(String host, String path) {
        String ours = MglaWsConfig.getWebProxyHost();
        if (TextUtils.isEmpty(ours) || TextUtils.isEmpty(host)) {
            return false;
        }
        String value = path == null || path.isEmpty() ? host : host + "/" + path;
        return ours.equalsIgnoreCase(value.trim())
            || host.equalsIgnoreCase("mglabot.mooo.com");
    }

    public static String webCapabilityContext(String host, String path) {
        if (useMglaWebCapability(host, path)) {
            return path == null || path.isEmpty()
                ? "mgla-web-proxy-bridge-v1\n" + host
                : "mgla-web-proxy-bridge-v2\n" + host + "\n" + path;
        }
        return path == null || path.isEmpty()
            ? "tdesktop-web-proxy-bridge-v1\n" + host
            : "tdesktop-web-proxy-bridge-v2\n" + host + "\n" + path;
    }

    private static boolean isLoopback(String addressLower) {
        return "127.0.0.1".equals(addressLower)
            || "localhost".equals(addressLower)
            || "::1".equals(addressLower)
            || "[::1]".equals(addressLower);
    }

    private static boolean secretEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        // Hex secrets сравниваем без регистра; marked base64 — как есть.
        if (a.matches("[0-9a-fA-F]{32,34}") && b.matches("[0-9a-fA-F]{32,34}")) {
            return a.equalsIgnoreCase(b);
        }
        return a.equals(b);
    }
}
