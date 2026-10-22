package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.utils.dpi.MglaDpiBypass;
import org.telegram.utils.dpi.MglaDpiConfig;
import org.telegram.utils.wsbypass.MglaWsConfig;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTP(S) for HuggingFace <b>API</b> / listing when the active bypass helps reach HF.
 * Large model <b>file</b> downloads go direct via {@link MglaHttpFetch} (no relay).
 * <ol>
 *   <li>Авто-обход (WS/WEB) → {@code GET /mgla-ai/v1/fetch?url=} on {@link MglaWsConfig#getRelayHost()}</li>
 *   <li>ByeDPI → local SOCKS5</li>
 *   <li>иначе → direct {@link Proxy#NO_PROXY}</li>
 * </ol>
 */
public final class MglaBypassHttp {

    private static final Object SOCKS_AUTH_LOCK = new Object();
    private static volatile boolean socksAuthInstalled;

    private MglaBypassHttp() {
    }

    public static boolean shouldUseRelayFetch() {
        return MglaRemoteConfig.shouldFetchViaRelay();
    }

    public static boolean shouldUseDpiSocks() {
        try {
            MglaDpiBypass dpi = MglaDpiBypass.getInstance();
            return dpi.isEnabled() && dpi.isServerRunning() && dpi.getPort() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Opens a connection to {@code targetUrl}. When relay bypass is on, the connection
     * is to the relay fetch endpoint (redirects resolved server-side). Caller sets timeouts.
     */
    public static HttpURLConnection open(String targetUrl) throws IOException {
        return open(new URL(targetUrl));
    }

    public static HttpURLConnection open(URL targetUrl) throws IOException {
        if (targetUrl == null) {
            throw new IOException("url required");
        }
        if (shouldUseRelayFetch()) {
            return openViaRelay(targetUrl.toString());
        }
        if (shouldUseDpiSocks()) {
            return openViaDpiSocks(targetUrl);
        }
        return MglaDirectHttp.open(targetUrl);
    }

    /** Same host as the active Telegram bypass ({@link MglaWsConfig#getRelayHost()}). */
    public static String getFetchRelayHost() {
        return MglaWsConfig.getRelayHost();
    }

    private static HttpURLConnection openViaRelay(String targetUrl) throws IOException {
        String host = getFetchRelayHost();
        if (TextUtils.isEmpty(host)) {
            throw new IOException("relay host empty");
        }
        String token = MglaWsConfig.getRelayToken();
        if (TextUtils.isEmpty(token)) {
            throw new IOException("relay token empty");
        }
        String encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8.name());
        URL fetch = new URL("https://" + host + "/mgla-ai/v1/fetch?url=" + encoded);
        HttpsURLConnection conn = (HttpsURLConnection) fetch.openConnection(Proxy.NO_PROXY);
        conn.setRequestProperty("X-Mgla-Token", token);
        conn.setRequestProperty("X-Mgla-Client", "mgla");
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private static HttpURLConnection openViaDpiSocks(URL targetUrl) throws IOException {
        int port = MglaDpiBypass.getInstance().getPort();
        ensureSocksAuthenticator();
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port));
        return (HttpURLConnection) targetUrl.openConnection(proxy);
    }

    private static void ensureSocksAuthenticator() {
        if (socksAuthInstalled) {
            return;
        }
        synchronized (SOCKS_AUTH_LOCK) {
            if (socksAuthInstalled) {
                return;
            }
            final String user = MglaDpiConfig.getSocksUser();
            final String pass = MglaDpiConfig.getSocksPass();
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() != RequestorType.PROXY) {
                        return null;
                    }
                    return new PasswordAuthentication(user, pass.toCharArray());
                }
            });
            socksAuthInstalled = true;
        }
    }
}
