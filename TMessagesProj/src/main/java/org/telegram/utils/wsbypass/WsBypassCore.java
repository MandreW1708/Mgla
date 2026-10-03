package org.telegram.utils.wsbypass;

import android.os.SystemClock;

import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;

/**
 * Локальный MTProto-прокси → WebSocket к kws*.web.telegram.org/apiws.
 * Без своих серверов: трафик идёт на домены Telegram Web, а не на IP дата-центров.
 * Алгоритм моста — тот же, что у веб-клиента Telegram / tg-ws-proxy.
 */
public final class WsBypassCore {

    public static final String LOCAL_PROXY_HOST = "127.0.0.1";
    public static final String WEB_TELEGRAM_DOMAIN = "web.telegram.org";

    static volatile boolean DEBUG = false;
    private static final AtomicInteger CONN_SEQ = new AtomicInteger();

    static void dbg(String msg) {
        if (!DEBUG) {
            return;
        }
        try {
            android.util.Log.i("MglaWsBypass", msg);
        } catch (Throwable ignored) {
        }
        try {
            FileLog.d("wsbypass: " + msg);
        } catch (Throwable ignored) {
        }
    }

    private static final long WS_ROUTE_DEADLINE_MS = 12_000L;
    private static final int MAX_DIRECT_ATTEMPTS = 6;
    private static final int SOCK_RCVBUF = 256 * 1024;
    private static final int SOCK_SNDBUF = 512 * 1024;
    private static final int RECV_CHUNK = 64 * 1024;
    private static final int ACCEPT_TIMEOUT_MS = 1_000;
    private static final int HANDSHAKE_READ_TIMEOUT_MS = 10_000;
    private static final int LISTEN_BACKLOG = 64;
    private static final int MAX_HANDLER_THREADS = 256;

    private static volatile WsBypassCore instance;

    public static WsBypassCore getInstance() {
        WsBypassCore local = instance;
        if (local == null) {
            synchronized (WsBypassCore.class) {
                local = instance;
                if (local == null) {
                    local = new WsBypassCore();
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Object lifecycleLock = new Object();
    private volatile boolean running;
    private volatile int port;
    private volatile ServerSocket listener;
    private volatile Thread acceptThread;
    private volatile ExecutorService handlerPool;
    private volatile byte[] secretBytes = new byte[0];
    private volatile String secretHex = "";

    private final Object bridgeStateLock = new Object();
    private int activeBridges;
    private long bridgeGeneration;
    private volatile long lastBridgeOkAtMs;

    private final Object trackedLock = new Object();
    private final Set<Object> tracked = new HashSet<>();

    private final Object failLock = new Object();
    private final Map<Long, Long> failUntilMs = new HashMap<>();
    private final Map<Long, String> preferredDomain = new HashMap<>();

    private WsBypassCore() {
    }

    public boolean isRunning() {
        return running && acceptThread != null && acceptThread.isAlive();
    }

    public int getPort() {
        return port;
    }

    public String getSecretHex() {
        return secretHex;
    }

    public boolean hasActiveBridge() {
        synchronized (bridgeStateLock) {
            return activeBridges > 0;
        }
    }

    public long getLastBridgeOkAtMs() {
        return lastBridgeOkAtMs;
    }

    /** @return пустая строка при успехе, иначе текст ошибки */
    public String start(int desiredPort, String secretHexIn) {
        synchronized (lifecycleLock) {
            try {
                stopLocked();

                String sec = MtprotoHandshake.validSecretHex(secretHexIn);
                if (sec == null || sec.isEmpty()) {
                    sec = MtprotoHandshake.generateSecretHex();
                }
                byte[] secBytes = MtprotoHandshake.toBytes16(sec);
                if (secBytes == null) {
                    return "неверный secret";
                }

                int bindPort = desiredPort > 0 && desiredPort <= 65535 ? desiredPort : 0;
                ServerSocket srv = bindLoopback(bindPort);
                if (srv == null && bindPort != 0) {
                    srv = bindLoopback(0);
                }
                if (srv == null) {
                    return "не удалось открыть локальный порт";
                }
                srv.setSoTimeout(ACCEPT_TIMEOUT_MS);

                listener = srv;
                port = srv.getLocalPort();
                secretHex = sec;
                secretBytes = secBytes;

                handlerPool = new ThreadPoolExecutor(
                    0, MAX_HANDLER_THREADS,
                    60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "mgla-ws-handler");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
                );

                running = true;
                final long generation = nextBridgeGeneration();
                Thread accept = new Thread(() -> acceptLoop(generation), "mgla-ws-accept");
                accept.setDaemon(true);
                acceptThread = accept;
                accept.start();

                FileLog.d("MglaWsBypass: started on " + LOCAL_PROXY_HOST + ":" + port);
                return "";
            } catch (Throwable t) {
                FileLog.e("MglaWsBypass: start failed", t);
                try {
                    stopLocked();
                } catch (Throwable ignored) {
                }
                return t.getMessage() == null ? "ошибка запуска" : t.getMessage();
            }
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            stopLocked();
        }
    }

    private ServerSocket bindLoopback(int bindPort) {
        try {
            ServerSocket srv = new ServerSocket();
            srv.setReuseAddress(true);
            try {
                srv.setReceiveBufferSize(SOCK_RCVBUF);
            } catch (Throwable ignored) {
            }
            srv.bind(new InetSocketAddress(InetAddress.getByName(LOCAL_PROXY_HOST), bindPort), LISTEN_BACKLOG);
            return srv;
        } catch (IOException e) {
            return null;
        }
    }

    private void stopLocked() {
        running = false;
        invalidateBridgeGeneration();
        ServerSocket s = listener;
        listener = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        closeAllTracked();
        ExecutorService hp = handlerPool;
        handlerPool = null;
        if (hp != null) {
            hp.shutdownNow();
        }
        Thread at = acceptThread;
        acceptThread = null;
        if (at != null) {
            try {
                at.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        port = 0;
    }

    private void acceptLoop(long generation) {
        while (running && isBridgeGenerationCurrent(generation)) {
            ServerSocket srv = listener;
            if (srv == null) {
                break;
            }
            try {
                Socket conn;
                try {
                    conn = srv.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (!isBridgeGenerationCurrent(generation)) {
                    closeQuietly(conn);
                    break;
                }
                try {
                    conn.setTcpNoDelay(true);
                    conn.setReceiveBufferSize(SOCK_RCVBUF);
                    conn.setSendBufferSize(SOCK_SNDBUF);
                } catch (Throwable ignored) {
                }
                if (!trackIfGenerationCurrent(conn, generation)) {
                    closeQuietly(conn);
                    continue;
                }
                ExecutorService pool = handlerPool;
                if (pool == null) {
                    closeQuietly(conn);
                    untrack(conn);
                    break;
                }
                try {
                    pool.execute(() -> {
                        try {
                            handleClient(conn, generation);
                        } finally {
                            closeQuietly(conn);
                            untrack(conn);
                        }
                    });
                } catch (Throwable e) {
                    closeQuietly(conn);
                    untrack(conn);
                }
            } catch (Throwable e) {
                if (!running) {
                    break;
                }
            }
        }
    }

    private void handleClient(Socket conn, long generation) {
        try {
            if (!isBridgeGenerationCurrent(generation)) {
                return;
            }
            byte[] connectionSecret = secretBytes;
            conn.setSoTimeout(HANDSHAKE_READ_TIMEOUT_MS);
            byte[] initPacket = recvExact(conn, MtprotoHandshake.HANDSHAKE_LEN);
            conn.setSoTimeout(0);
            if (!isBridgeGenerationCurrent(generation)) {
                return;
            }

            MtprotoHandshake.HandshakeResult hr = MtprotoHandshake.tryHandshake(initPacket, connectionSecret);
            if (hr == null) {
                dbg("handshake failed");
                return;
            }
            int dc = hr.dcId;
            boolean isMedia = hr.isMedia;
            dbg("handshake OK dc=" + dc + " media=" + isMedia);

            int relayDcIdx = isMedia ? -dc : dc;
            byte[] relayInit = MtprotoHandshake.generateRelayInit(hr.protoTag, relayDcIdx);
            CryptoCtx ctx = CryptoCtx.build(hr.decPrekeyIv, connectionSecret, relayInit);
            if (ctx == null) {
                return;
            }

            long dcKey = poolKey(dc, isMedia);
            if (isInFailBackoff(dcKey)) {
                dbg("dc " + dc + " in backoff");
                return;
            }

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WS_ROUTE_DEADLINE_MS);
            RawWebSocket ws;
            try {
                ws = connectRelay(dc, isMedia, deadline, generation);
            } catch (Throwable e) {
                dbg("relay connect failed: " + e.getMessage());
                failRecord(dcKey);
                return;
            }
            if (!trackIfGenerationCurrent(ws, generation)) {
                try {
                    ws.close();
                } catch (Throwable ignored) {
                }
                return;
            }

            failClear(dcKey);
            try {
                ws.send(relayInit);
                dbg("bridge start dc=" + dc);
                bridgeWs(conn, ws, ctx, generation);
            } catch (Throwable e) {
                dbg("bridge error: " + e.getMessage());
                try {
                    ws.close();
                } catch (Throwable ignored) {
                }
                untrack(ws);
            }
        } catch (Throwable e) {
            dbg("handleClient: " + e.getMessage());
        }
    }

    private RawWebSocket connectRelay(int dc, boolean isMedia, long deadlineNanos, long generation) throws IOException {
        String host = MglaWsConfig.getRelayHost();
        int effective = dc == 203 ? 2 : dc;
        String path = "/apiws?dc=" + effective;
        Map<String, String> headers = new HashMap<>();
        String token = MglaWsConfig.getRelayToken();
        if (token != null && !token.isEmpty()) {
            headers.put("X-Mgla-Token", token);
        }
        dbg("connect relay -> wss://" + host + path + (isMedia ? " (media)" : ""));
        long attemptDeadline = Math.min(deadlineNanos,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(8000L));
        RawWebSocket ws = RawWebSocket.connectUntil(host, host, path, headers, attemptDeadline,
            () -> isBridgeGenerationCurrent(generation));
        dbg("101 OK via relay " + host);
        return ws;
    }

    /** @deprecated оставлен для отладки; основной путь — {@link #connectRelay}. */
    @SuppressWarnings("unused")
    private RawWebSocket connectKws(int dc, boolean isMedia, long deadlineNanos, long generation) throws IOException {
        List<String> domains = wsDomainsForDc(dc, isMedia);
        long dcKey = poolKey(dc, isMedia);
        String pref;
        synchronized (failLock) {
            pref = preferredDomain.get(dcKey);
        }
        if (pref != null && !pref.isEmpty()) {
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            ordered.add(pref);
            ordered.addAll(domains);
            domains = new ArrayList<>(ordered);
        }

        IOException last = null;
        int attempts = 0;
        for (String domain : domains) {
            if (!isBridgeGenerationCurrent(generation) || System.nanoTime() >= deadlineNanos) {
                break;
            }
            if (attempts >= MAX_DIRECT_ATTEMPTS) {
                break;
            }
            attempts++;
            dbg("connect kws -> " + domain);
            try {
                long attemptDeadline = Math.min(deadlineNanos,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500L));
                RawWebSocket ws = RawWebSocket.connectUntil(domain, domain, "/apiws", null, attemptDeadline,
                    () -> isBridgeGenerationCurrent(generation));
                synchronized (failLock) {
                    preferredDomain.put(dcKey, domain);
                }
                dbg("101 OK via " + domain);
                return ws;
            } catch (IOException e) {
                last = e;
                dbg(domain + " -> " + e.getMessage());
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("kws недоступен");
    }

    static List<String> wsDomainsForDc(int dc, boolean isMedia) {
        int effective = dc;
        if (dc == 203) {
            effective = 2;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (isMedia) {
            out.add("kws" + effective + "-1." + WEB_TELEGRAM_DOMAIN);
            out.add("kws" + effective + "." + WEB_TELEGRAM_DOMAIN);
        } else {
            out.add("kws" + effective + "." + WEB_TELEGRAM_DOMAIN);
            out.add("kws" + effective + "-1." + WEB_TELEGRAM_DOMAIN);
        }
        return new ArrayList<>(out);
    }

    private void bridgeWs(Socket client, RawWebSocket ws, CryptoCtx ctx, long generation) {
        if (!markBridgeStarted(generation)) {
            try {
                ws.close();
            } catch (Throwable ignored) {
            }
            closeQuietly(client);
            untrack(ws);
            return;
        }
        try {
            AtomicBoolean done = new AtomicBoolean(false);
            int connId = CONN_SEQ.incrementAndGet();

            Thread up = new Thread(() -> {
                try {
                    InputStream in = client.getInputStream();
                    byte[] buf = new byte[RECV_CHUNK];
                    while (!done.get()) {
                        int n = in.read(buf);
                        if (n <= 0) {
                            break;
                        }
                        byte[] chunk = n == buf.length ? buf : Arrays.copyOf(buf, n);
                        byte[] plain = cipherUpdate(ctx.cltDec, chunk);
                        byte[] data = cipherUpdate(ctx.tgEnc, plain);
                        if (data != null && data.length > 0) {
                            ws.send(data);
                        }
                    }
                } catch (Throwable e) {
                    dbg("up#" + connId + " " + e.getMessage());
                }
                done.set(true);
            }, "mgla-ws-up");
            up.setDaemon(true);

            Thread down = new Thread(() -> {
                try {
                    OutputStream out = client.getOutputStream();
                    while (!done.get()) {
                        byte[] payload = ws.recv();
                        if (payload == null) {
                            break;
                        }
                        if (payload.length == 0) {
                            continue;
                        }
                        byte[] plain = cipherUpdate(ctx.tgDec, payload);
                        byte[] outBuf = cipherUpdate(ctx.cltEnc, plain);
                        if (outBuf != null && outBuf.length > 0) {
                            out.write(outBuf);
                            out.flush();
                            markBridgeOk(generation);
                        }
                    }
                } catch (Throwable e) {
                    dbg("down#" + connId + " " + e.getMessage());
                }
                done.set(true);
            }, "mgla-ws-down");
            down.setDaemon(true);

            up.start();
            down.start();
            try {
                while (!done.get() && (up.isAlive() || down.isAlive())) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.set(true);
            try {
                ws.close();
            } catch (Throwable ignored) {
            }
            closeQuietly(client);
            try {
                up.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                down.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            untrack(ws);
        } finally {
            markBridgeStopped(generation);
        }
    }

    // --- crypto helpers ---

    private static byte[] cipherUpdate(Cipher cipher, byte[] data) {
        if (cipher == null || data == null || data.length == 0) {
            return data;
        }
        try {
            return cipher.update(data);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] recvExact(Socket socket, int length) throws IOException {
        byte[] buf = new byte[length];
        InputStream in = socket.getInputStream();
        int got = 0;
        while (got < length) {
            int n = in.read(buf, got, length - got);
            if (n < 0) {
                throw new IOException("EOF during handshake");
            }
            got += n;
        }
        return buf;
    }

    // --- fail / bridge state ---

    private static long poolKey(int dc, boolean isMedia) {
        return ((long) dc << 1) | (isMedia ? 1L : 0L);
    }

    private boolean isInFailBackoff(long dcKey) {
        synchronized (failLock) {
            Long until = failUntilMs.get(dcKey);
            return until != null && SystemClock.elapsedRealtime() < until;
        }
    }

    private void failRecord(long dcKey) {
        synchronized (failLock) {
            failUntilMs.put(dcKey, SystemClock.elapsedRealtime() + 15_000L);
        }
    }

    private void failClear(long dcKey) {
        synchronized (failLock) {
            failUntilMs.remove(dcKey);
        }
    }

    private long nextBridgeGeneration() {
        synchronized (bridgeStateLock) {
            bridgeGeneration++;
            activeBridges = 0;
            lastBridgeOkAtMs = 0L;
            return bridgeGeneration;
        }
    }

    private void invalidateBridgeGeneration() {
        synchronized (bridgeStateLock) {
            bridgeGeneration++;
            activeBridges = 0;
            lastBridgeOkAtMs = 0L;
        }
    }

    private boolean isBridgeGenerationCurrent(long generation) {
        synchronized (bridgeStateLock) {
            return generation == bridgeGeneration;
        }
    }

    private boolean markBridgeStarted(long generation) {
        synchronized (bridgeStateLock) {
            if (generation != bridgeGeneration) {
                return false;
            }
            if (activeBridges++ == 0) {
                lastBridgeOkAtMs = 0L;
            }
            return true;
        }
    }

    private void markBridgeStopped(long generation) {
        synchronized (bridgeStateLock) {
            if (generation != bridgeGeneration) {
                return;
            }
            if (activeBridges > 0) {
                activeBridges--;
            }
            if (activeBridges == 0) {
                lastBridgeOkAtMs = 0L;
            }
        }
    }

    private void markBridgeOk(long generation) {
        synchronized (bridgeStateLock) {
            if (generation == bridgeGeneration && activeBridges > 0) {
                lastBridgeOkAtMs = System.currentTimeMillis();
            }
        }
    }

    private boolean trackIfGenerationCurrent(Object closer, long generation) {
        if (closer == null) {
            return false;
        }
        synchronized (bridgeStateLock) {
            if (generation != bridgeGeneration) {
                return false;
            }
        }
        synchronized (trackedLock) {
            tracked.add(closer);
        }
        return true;
    }

    private void untrack(Object closer) {
        if (closer == null) {
            return;
        }
        synchronized (trackedLock) {
            tracked.remove(closer);
        }
    }

    private void closeAllTracked() {
        Object[] snapshot;
        synchronized (trackedLock) {
            snapshot = tracked.toArray();
            tracked.clear();
        }
        for (Object o : snapshot) {
            if (o instanceof Socket) {
                closeQuietly((Socket) o);
            } else if (o instanceof RawWebSocket) {
                try {
                    ((RawWebSocket) o).close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
