package org.telegram.messenger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Direct HTTPS download with HTTP Range resume into {@code .part}.
 * Model files are fetched without the WS/WEB bypass — HF CDN works without VPN.
 */
final class MglaHttpFetch {

    interface Progress {
        void onProgress(long downloaded, long total);
    }

    private MglaHttpFetch() {
    }

    /**
     * Downloads {@code url} into {@code temp}, resuming if a partial file exists.
     * Returns final byte count. Caller renames {@code temp} → target on success.
     */
    static long downloadToPart(URL url, File temp, Progress progress) throws Exception {
        long existing = temp.exists() ? Math.max(0L, temp.length()) : 0L;
        HttpURLConnection conn = null;
        int code = -1;
        try {
            for (int i = 0; i < 8; i++) {
                if (conn != null) {
                    conn.disconnect();
                    conn = null;
                }
                conn = MglaDirectHttp.open(url);
                conn.setRequestProperty("User-Agent", "Mgla/1.0");
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(120_000);
                conn.setInstanceFollowRedirects(false);
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existing + "-");
                }
                code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new Exception("HTTP " + code);
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (code == 200) {
                    existing = 0;
                } else if (code == 206) {
                    // append
                } else if (code == 416 && existing > 0) {
                    return existing;
                } else {
                    throw new Exception("HTTP " + code);
                }
                break;
            }
            if (conn == null) {
                throw new Exception("no connection");
            }
            long total = contentTotal(conn, code, existing);
            long downloaded = existing;
            byte[] buffer = new byte[256 * 1024];
            boolean append = existing > 0 && code == 206;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp, append)) {
                int read;
                long lastPublish = 0;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (progress != null && now - lastPublish > 300) {
                        lastPublish = now;
                        final long d = downloaded;
                        final long t = total;
                        AndroidUtilities.runOnUIThread(() -> progress.onProgress(d, t));
                    }
                }
                out.getFD().sync();
            }
            return downloaded;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static long contentTotal(HttpURLConnection conn, int code, long existing) {
        try {
            if (code == 206) {
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0 && slash + 1 < cr.length()) {
                        String totalStr = cr.substring(slash + 1).trim();
                        if (!"*".equals(totalStr)) {
                            return Long.parseLong(totalStr);
                        }
                    }
                }
                long cl = conn.getContentLengthLong();
                if (cl > 0) {
                    return existing + cl;
                }
            } else {
                long cl = conn.getContentLengthLong();
                if (cl > 0) {
                    return cl;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
