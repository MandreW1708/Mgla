package org.telegram.messenger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTP for Mgla features that must never ride Telegram's proxy / auto-bypass
 * (OpenRouter, Gemini, etc.). {@link URL#openConnection()} can still pick up a
 * default {@link java.net.ProxySelector}; we force a direct socket instead.
 */
public final class MglaDirectHttp {

    private MglaDirectHttp() {
    }

    public static HttpURLConnection open(String url) throws IOException {
        return open(new URL(url));
    }

    public static HttpURLConnection open(URL url) throws IOException {
        URLConnection conn = url.openConnection(Proxy.NO_PROXY);
        if (!(conn instanceof HttpURLConnection)) {
            throw new IOException("Expected HTTP(S) connection for " + url);
        }
        return (HttpURLConnection) conn;
    }

    public static HttpsURLConnection openHttps(String url) throws IOException {
        HttpURLConnection conn = open(url);
        if (!(conn instanceof HttpsURLConnection)) {
            throw new IOException("Expected HTTPS connection for " + url);
        }
        return (HttpsURLConnection) conn;
    }
}
