package org.telegram.messenger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct HTTPS download (no WS/WEB bypass). HuggingFace CDN is often far (US);
 * a single TCP stream looks “stuck” on a fast local link — we use parallel Range
 * requests for large files and a browser-like User-Agent.
 */
final class MglaHttpFetch {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int BUFFER = 256 * 1024;
    /** Below this, one connection is enough. */
    private static final long PARALLEL_MIN_BYTES = 4L * 1024 * 1024;
    private static final int PARALLEL_PARTS = 4;

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
        Resolved resolved = resolveFinal(url, 0);
        long existing = temp.exists() ? Math.max(0L, temp.length()) : 0L;

        // Resume only with single-stream when we already have a partial and size known.
        if (existing > 0 && existing == resolved.total && resolved.total > 0) {
            return existing;
        }
        if (existing > 0 && (resolved.total <= 0 || existing > resolved.total)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            existing = 0;
        }

        boolean canParallel = resolved.total >= PARALLEL_MIN_BYTES
                && resolved.acceptRanges
                && existing == 0;

        if (canParallel) {
            try {
                return downloadParallel(resolved.url, temp, resolved.total, progress);
            } catch (Throwable parallelErr) {
                FileLog.e("MglaHttpFetch parallel failed, fallback single", parallelErr);
                if (temp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            }
        }
        return downloadSingle(resolved.url, temp, existing, resolved.total, progress);
    }

    private static final class Resolved {
        final URL url;
        final long total;
        final boolean acceptRanges;

        Resolved(URL url, long total, boolean acceptRanges) {
            this.url = url;
            this.total = total;
            this.acceptRanges = acceptRanges;
        }
    }

    /** Follow redirects to the CDN URL; probe size / Accept-Ranges without downloading the body. */
    private static Resolved resolveFinal(URL url, long rangeStart) throws Exception {
        HttpURLConnection conn = null;
        try {
            for (int i = 0; i < 8; i++) {
                if (conn != null) {
                    conn.disconnect();
                    conn = null;
                }
                conn = open(url);
                // Prefer HEAD when possible; some HF endpoints reject it → GET + close.
                conn.setRequestMethod("GET");
                if (rangeStart > 0) {
                    conn.setRequestProperty("Range", "bytes=" + rangeStart + "-" + rangeStart);
                } else {
                    // One-byte probe keeps the resolve cheap on huge files.
                    conn.setRequestProperty("Range", "bytes=0-0");
                }
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new Exception("HTTP " + code);
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (code != 200 && code != 206) {
                    // Retry without Range (some edges dislike probe Range on resolve host).
                    conn.disconnect();
                    conn = open(url);
                    conn.setRequestMethod("GET");
                    code = conn.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = conn.getHeaderField("Location");
                        if (location == null || location.isEmpty()) {
                            throw new Exception("HTTP " + code);
                        }
                        url = new URL(url, location);
                        continue;
                    }
                    if (code != 200 && code != 206) {
                        throw new Exception("HTTP " + code);
                    }
                }
                long total = contentTotal(conn, code, 0);
                boolean ranges = code == 206
                        || "bytes".equalsIgnoreCase(conn.getHeaderField("Accept-Ranges"));
                return new Resolved(url, total, ranges);
            }
            throw new Exception("too many redirects");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static HttpURLConnection open(URL url) throws Exception {
        HttpURLConnection conn = MglaDirectHttp.open(url);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private static long downloadSingle(URL url, File temp, long existing, long knownTotal,
                                       Progress progress) throws Exception {
        HttpURLConnection conn = null;
        int code;
        try {
            for (int i = 0; i < 4; i++) {
                if (conn != null) {
                    conn.disconnect();
                    conn = null;
                }
                conn = open(url);
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
            code = conn.getResponseCode();
            long total = knownTotal > 0 ? knownTotal : contentTotal(conn, code, existing);
            long downloaded = existing;
            boolean append = existing > 0 && code == 206;
            AtomicLong lastPublish = new AtomicLong(0);
            try (InputStream raw = conn.getInputStream();
                 BufferedInputStream in = new BufferedInputStream(raw, BUFFER);
                 FileOutputStream fos = new FileOutputStream(temp, append);
                 BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    publish(progress, downloaded, total, lastPublish);
                }
                out.flush();
                fos.getFD().sync();
            }
            return downloaded;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static long downloadParallel(URL url, File temp, long total, Progress progress)
            throws Exception {
        //noinspection ResultOfMethodCallIgnored
        if (temp.exists() && !temp.delete()) {
            throw new Exception("Не удалось очистить частичный файл");
        }
        try (RandomAccessFile raf = new RandomAccessFile(temp, "rw")) {
            raf.setLength(total);
        }

        final int parts = PARALLEL_PARTS;
        final long chunk = (total + parts - 1) / parts;
        final AtomicLong downloaded = new AtomicLong(0);
        final AtomicLong lastPublish = new AtomicLong(0);
        ExecutorService pool = Executors.newFixedThreadPool(parts);
        ArrayList<Future<?>> futures = new ArrayList<>(parts);
        try {
            for (int i = 0; i < parts; i++) {
                final long start = i * chunk;
                if (start >= total) {
                    break;
                }
                final long end = Math.min(total, start + chunk) - 1;
                futures.add(pool.submit(() -> {
                    downloadRange(url, temp, start, end, downloaded, total, progress, lastPublish);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        if (temp.length() != total) {
            throw new Exception("Неполный файл: " + temp.length() + "/" + total);
        }
        return total;
    }

    private static void downloadRange(URL url, File temp, long start, long end,
                                      AtomicLong downloaded, long total,
                                      Progress progress, AtomicLong lastPublish) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL current = url;
            for (int hop = 0; hop < 4; hop++) {
                if (conn != null) {
                    conn.disconnect();
                    conn = null;
                }
                conn = open(current);
                conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new Exception("HTTP " + code);
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (code != 206 && code != 200) {
                    throw new Exception("HTTP " + code + " range " + start + "-" + end);
                }
                // If server ignored Range and sent full body, only the first part may use it.
                if (code == 200 && start != 0) {
                    throw new Exception("CDN не поддерживает Range");
                }
                long expect = end - start + 1;
                long got = 0;
                try (InputStream raw = conn.getInputStream();
                     BufferedInputStream in = new BufferedInputStream(raw, BUFFER);
                     RandomAccessFile raf = new RandomAccessFile(temp, "rw")) {
                    raf.seek(start);
                    byte[] buffer = new byte[BUFFER];
                    int read;
                    while (got < expect && (read = in.read(buffer, 0, (int) Math.min(buffer.length, expect - got))) > 0) {
                        raf.write(buffer, 0, read);
                        got += read;
                        long now = downloaded.addAndGet(read);
                        publish(progress, now, total, lastPublish);
                    }
                }
                if (got != expect && code == 206) {
                    throw new Exception("range short read " + got + "/" + expect);
                }
                return;
            }
            throw new Exception("range redirect loop");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void publish(Progress progress, long downloaded, long total, AtomicLong lastPublish) {
        if (progress == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long prev = lastPublish.get();
        if (now - prev < 250 && downloaded < total) {
            return;
        }
        if (!lastPublish.compareAndSet(prev, now) && downloaded < total) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> progress.onProgress(downloaded, total));
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
