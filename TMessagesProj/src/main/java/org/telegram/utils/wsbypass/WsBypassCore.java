package org.telegram.utils.wsbypass;

import android.os.SystemClock;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MglaSubscription;
import org.telegram.messenger.UserConfig;

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
 * Локальный MTProto-прокси → WebSocket-релей → TCP Telegram DC.
 * <p>
 * Прямой {@code kws*.web.telegram.org} в регионах с блокировкой обычно тоже недоступен
 * (поэтому и нужен релей). Алгоритм моста — как у tg-ws-proxy / веб-клиента.
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

    private static final long WS_ROUTE_DEADLINE_MS = 18_000L;
    private static final long RELAY_ATTEMPT_MS = 12_000L;
    /** Soft Ultra QoS on shared relay / dedicated Ultra path. */
    private static final long RELAY_ATTEMPT_ULTRA_MS = 14_000L;
    private static final long FAIL_BACKOFF_MS = 3_000L;
    /** Ultra path: shorter shared backoff so one account's blip doesn't stall the others. */
    private static final long FAIL_BACKOFF_ULTRA_MS = 1_200L;
    private static final int FAILS_BEFORE_BACKOFF = 2;
    private static final int MAX_DIRECT_ATTEMPTS = 6;
    private static final int SOCK_RCVBUF = 256 * 1024;
    private static final int SOCK_SNDBUF = 512 * 1024;
    /** Match hub Ultra TCP buffers so local MTProto↔WS bridge is not the bottleneck. */
    private static final int SOCK_RCVBUF_ULTRA = 1024 * 1024;
    private static final int SOCK_SNDBUF_ULTRA = 2 * 1024 * 1024;
    private static final int RECV_CHUNK = 64 * 1024;
    private static final int RECV_CHUNK_ULTRA = 256 * 1024;
    private static final int ACCEPT_TIMEOUT_MS = 1_000;
    private static final int HANDSHAKE_READ_TIMEOUT_MS = 10_000;
    private static final int LISTEN_BACKLOG = 64;
    private static final int MAX_HANDLER_THREADS = 512;

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
    private final Map<Long, Integer> failStreak = new HashMap<>();
    private final Map<Long, String> preferredDomain = new HashMap<>();

    private volatile long lastUpstreamOkAtMs;
    private volatile long lastUpstreamFailAtMs;
    /** Host of the active/last Ultra relay session (for freeze quarantine). */
    private volatile String lastRelayHost = "";
    private volatile long lastRelaySessionStartMs;

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

    /** Wall-clock time of the last successful upstream WebSocket connect (0 = none yet). */
    public long getLastUpstreamOkAtMs() {
        return lastUpstreamOkAtMs;
    }

    /** Wall-clock time of the last failed upstream connect (0 = none yet). */
    public long getLastUpstreamFailAtMs() {
        return lastUpstreamFailAtMs;
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

                lastUpstreamOkAtMs = 0L;
                lastUpstreamFailAtMs = 0L;
                clearFailState();
                running = true;
                nextBridgeGeneration();
                // Accept loop must survive generation bumps (Ultra ↔ normal host switch).
                Thread accept = new Thread(this::acceptLoop, "mgla-ws-accept");
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

    /**
     * Hot-switch upstream relay host without tearing down the local MTProto listener.
     * Drops live bridges so Telegram reconnects through the new host immediately.
     */
    public void reconnectUpstream() {
        synchronized (lifecycleLock) {
            if (!running) {
                return;
            }
            clearFailState();
            lastUpstreamOkAtMs = 0L;
            lastUpstreamFailAtMs = 0L;
            invalidateBridgeGeneration();
            closeAllTracked();
            FileLog.d("MglaWsBypass: upstream reconnect (host=" + MglaWsConfig.getRelayHost() + ")");
        }
    }

    private void clearFailState() {
        synchronized (failLock) {
            failUntilMs.clear();
            failStreak.clear();
            preferredDomain.clear();
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

    private void acceptLoop() {
        // Keep accepting while running. Bridge generation only invalidates in-flight
        // bridges (e.g. Ultra↔normal host switch) — never kills this loop.
        while (running) {
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
                final long generation = currentBridgeGeneration();
                boolean ultraPath = MglaWsConfig.isUltraBypassEnabled();
                try {
                    conn.setTcpNoDelay(true);
                    conn.setReceiveBufferSize(ultraPath ? SOCK_RCVBUF_ULTRA : SOCK_RCVBUF);
                    conn.setSendBufferSize(ultraPath ? SOCK_SNDBUF_ULTRA : SOCK_SNDBUF);
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
            // In backoff we still make one quick attempt instead of dropping the client socket.
            boolean inBackoff = isInFailBackoff(dcKey);
            if (inBackoff) {
                dbg("dc " + dc + " in backoff, single attempt");
            }

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WS_ROUTE_DEADLINE_MS);
            RawWebSocket ws;
            try {
                ws = connectUpstream(dc, isMedia, deadline, generation, !inBackoff);
            } catch (Throwable e) {
                dbg("upstream connect failed: " + e.getMessage());
                failRecord(dcKey);
                lastUpstreamFailAtMs = System.currentTimeMillis();
                return;
            }
            lastUpstreamOkAtMs = System.currentTimeMillis();
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

    /**
     * Основной путь — релей (нужен, когда DC/kws заблокированы).
     * Прямой kws оставляем запасным: имеет смысл только там, где Telegram Web уже открыт.
     */
    private RawWebSocket connectUpstream(int dc, boolean isMedia, long deadlineNanos, long generation,
                                         boolean allowRetry) throws IOException {
        if (!MglaWsConfig.isRelayConfigured()) {
            throw new IOException("релей не сконфигурирован");
        }
        IOException relayError = null;
        try {
            return connectRelay(dc, isMedia, deadlineNanos, generation);
        } catch (IOException e) {
            relayError = e;
            dbg("relay failed: " + e.getMessage());
        }
        if (!allowRetry || !isBridgeGenerationCurrent(generation) || System.nanoTime() >= deadlineNanos) {
            throw relayError;
        }
        try {
            return connectRelay(dc, isMedia, deadlineNanos, generation);
        } catch (IOException e) {
            relayError = e;
            dbg("relay retry failed: " + e.getMessage());
        }
        if (!isBridgeGenerationCurrent(generation) || System.nanoTime() >= deadlineNanos) {
            throw relayError;
        }
        try {
            return connectKws(dc, isMedia, deadlineNanos, generation);
        } catch (IOException e) {
            if (relayError != null) {
                e.addSuppressed(relayError);
            }
            throw e;
        }
    }

    private RawWebSocket connectRelay(int dc, boolean isMedia, long deadlineNanos, long generation)
            throws IOException {
        return connectRelay(dc, isMedia, deadlineNanos, generation, true);
    }

    /**
     * @param mutateSticky when false (RTT probe), do not rotate preferred-host sticky state.
     */
    private RawWebSocket connectRelay(int dc, boolean isMedia, long deadlineNanos, long generation,
                                      boolean mutateSticky) throws IOException {
        // Билайн/DPI: tunnel TLS through local ByeDPI before touching foreign IPs.
        try {
            org.telegram.utils.dpi.MglaDpiBypass.getInstance().ensureUnderlayRunning();
        } catch (Throwable ignored) {
        }
        java.util.List<MglaWsConfig.RelayEndpoint> endpoints = MglaWsConfig.getRelayConnectEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IOException("релей не сконфигурирован");
        }
        // Sticky preferred host first within the endpoint list.
        String preferred = MglaWsConfig.getRelayHost();
        if (preferred != null && !preferred.isEmpty() && endpoints.size() > 1) {
            java.util.ArrayList<MglaWsConfig.RelayEndpoint> ordered = new java.util.ArrayList<>(endpoints.size());
            for (MglaWsConfig.RelayEndpoint ep : endpoints) {
                if (preferred.equalsIgnoreCase(ep.host)) {
                    ordered.add(ep);
                }
            }
            for (MglaWsConfig.RelayEndpoint ep : endpoints) {
                if (!preferred.equalsIgnoreCase(ep.host)) {
                    ordered.add(ep);
                }
            }
            endpoints = ordered;
        }

        int effective = dc == 203 ? 2 : dc;
        String[] pathPool = MglaWsConfig.getRelayWsPathCandidates();
        boolean ultraPath = MglaWsConfig.isUltraBypassEnabled();
        boolean priority = ultraPath || MglaSubscription.hasBypassPriority();
        String bypassPlan = MglaSubscription.getDeviceBypassPlan();
        if (ultraPath) {
            bypassPlan = MglaSubscription.PLAN_PREMIUM_ULTRA;
        }
        String tgIds = collectActivatedTgIdsCsv();

        long attemptMs = priority ? RELAY_ATTEMPT_ULTRA_MS : RELAY_ATTEMPT_MS;
        IOException last = null;
        // Always try primary (index 0), then up to 2 rotated camouflage/legacy paths.
        final int pathsPerHost = Math.min(3, Math.max(1, pathPool.length));
        int rot = Math.floorMod((int) (System.currentTimeMillis() / 3_600_000L),
                Math.max(1, pathPool.length - 1));
        for (MglaWsConfig.RelayEndpoint ep : endpoints) {
            if (!isBridgeGenerationCurrent(generation) || System.nanoTime() >= deadlineNanos) {
                break;
            }
            if (ep == null || ep.host == null || ep.host.isEmpty()
                    || ep.token == null || ep.token.isEmpty()) {
                continue;
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + ep.token);
            // Browser-like Origin matching the SNI host (not a fixed mgla brand).
            headers.put("Origin", "https://" + ep.host);

            // Camouflage meta: avoid the literal "mgla" client marker on the wire.
            StringBuilder meta = new StringBuilder(128);
            meta.append("{\"v\":1,\"c\":\"app\",\"t\":\"").append(jsonEscape(ep.token)).append('"');
            if (bypassPlan != null && !bypassPlan.isEmpty()) {
                meta.append(",\"p\":\"").append(jsonEscape(bypassPlan)).append('"');
            }
            if (priority) {
                meta.append(",\"u\":1");
            }
            if (tgIds != null && !tgIds.isEmpty()) {
                meta.append(",\"tg\":\"").append(jsonEscape(tgIds)).append('"');
            }
            meta.append('}');
            String metaJson = meta.toString();

            boolean transportDead = false;
            for (int pi = 0; pi < pathsPerHost; pi++) {
                if (!isBridgeGenerationCurrent(generation) || System.nanoTime() >= deadlineNanos) {
                    break;
                }
                String wsPath;
                if (pi == 0 || pathPool.length == 1) {
                    wsPath = pathPool[0];
                } else {
                    // Skip index 0 (already tried); rotate among the rest.
                    int idx = 1 + Math.floorMod(rot + pi - 1, pathPool.length - 1);
                    wsPath = pathPool[idx];
                }
                String path = wsPath + "?dc=" + effective + (isMedia ? "&media=1" : "");

                dbg("connect relay -> wss://" + ep.host + path + (isMedia ? " (media)" : "")
                        + " plan=" + bypassPlan + (ultraPath || ep.ultra ? " ultra-path" : (priority ? " prio" : "")));
                long attemptDeadline = Math.min(deadlineNanos,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(attemptMs));
                try {
                    RawWebSocket ws = RawWebSocket.connectUntil(ep.host, ep.host, path, headers, attemptDeadline,
                            () -> isBridgeGenerationCurrent(generation), ep.spkiPins, ep.pinOnly);
                    try {
                        ws.sendText(metaJson);
                    } catch (IOException e) {
                        try {
                            ws.close();
                        } catch (Throwable ignored) {
                        }
                        throw e;
                    }
                    if (mutateSticky) {
                        MglaWsConfig.markRelayHostOk(ep.host);
                    }
                    lastRelayHost = ep.host;
                    lastRelaySessionStartMs = System.currentTimeMillis();
                    dbg("101 OK via relay " + ep.host + path);
                    return ws;
                } catch (IOException e) {
                    last = e;
                    dbg(ep.host + path + " fail: " + e.getMessage());
                    if (isTransportLevelFailure(e)) {
                        transportDead = true;
                        break;
                    }
                }
            }
            // Only rotate sticky host on transport death; path 404s keep preferred SNI.
            if (mutateSticky && transportDead) {
                MglaWsConfig.markRelayHostFailed(ep.host);
            }
            rot++;
        }
        // All hosts failed — rotate ByeDPI underlay strategy for the next attempt.
        if (mutateSticky) {
            try {
                org.telegram.utils.dpi.MglaDpiBypass.getInstance().rotateUnderlayStrategy();
            } catch (Throwable ignored) {
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("релей недоступен");
    }

    /** TLS/connect/timeout — skip remaining paths on this host. HTTP 404/403 — try next path. */
    private static boolean isTransportLevelFailure(IOException e) {
        if (e instanceof RawWebSocket.HandshakeException) {
            int code = ((RawWebSocket.HandshakeException) e).statusCode;
            // Auth/path issues → try another path; anything else unusual still path-retry.
            if (code == 404 || code == 403 || code == 405 || code == 502 || code == 503) {
                return false;
            }
            if (code == 401 || code == 400) {
                return false;
            }
            // 101 never thrown; other HTTP → treat as path-ish unless 0 (parse fail / TLS).
            if (code > 0) {
                return false;
            }
        }
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String lower = msg.toLowerCase(java.util.Locale.US);
        return lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("tls") || lower.contains("ssl")
                || lower.contains("connect") || lower.contains("pin")
                || lower.contains("unreachable") || lower.contains("refused")
                || lower.contains("reset") || lower.contains("network");
    }

    private static String jsonEscape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                case '"':
                    sb.append('\\').append(c);
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Blocking check that the relay accepts a WebSocket upgrade; does not touch running bridges. */
    public boolean probeRelay(long timeoutMs) {
        return probeRelayRttMs(timeoutMs) >= 0;
    }

    /**
     * One-shot TLS+WS handshake RTT to the active relay host.
     * @return milliseconds on success, or {@code -1} on failure.
     */
    public long probeRelayRttMs(long timeoutMs) {
        RawWebSocket ws = null;
        long t0 = System.nanoTime();
        try {
            // Do not mutate sticky host on background RTT probes.
            ws = connectRelay(2, false,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs),
                currentBridgeGeneration(), false);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            return Math.max(1L, ms);
        } catch (Throwable e) {
            dbg("relay probe failed: " + e.getMessage());
            return -1L;
        } finally {
            if (ws != null) {
                try {
                    ws.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private long currentBridgeGeneration() {
        synchronized (bridgeStateLock) {
            return bridgeGeneration;
        }
    }

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
                    () -> isBridgeGenerationCurrent(generation), null);
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

    /** Comma-separated Telegram user ids of activated accounts (for hub entitlements). */
    private static String collectActivatedTgIdsCsv() {
        StringBuilder sb = new StringBuilder();
        try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig uc = UserConfig.getInstance(a);
                if (uc == null || !uc.isClientActivated()) {
                    continue;
                }
                long id = uc.getClientUserId();
                if (id <= 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
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

            final boolean ultraPath = MglaWsConfig.isUltraBypassEnabled();
            final int chunkSize = ultraPath ? RECV_CHUNK_ULTRA : RECV_CHUNK;
            final java.util.concurrent.atomic.AtomicLong sessionBytes =
                    new java.util.concurrent.atomic.AtomicLong(0L);
            final String sessionHost = lastRelayHost;
            final long sessionStart = lastRelaySessionStartMs > 0
                    ? lastRelaySessionStartMs : System.currentTimeMillis();
            Thread up = new Thread(() -> {
                try {
                    InputStream in = client.getInputStream();
                    byte[] buf = new byte[chunkSize];
                    while (!done.get()) {
                        int n = in.read(buf);
                        if (n <= 0) {
                            break;
                        }
                        sessionBytes.addAndGet(n);
                        byte[] chunk = n == buf.length ? buf : Arrays.copyOf(buf, n);
                        byte[] plain = cipherUpdate(ctx.cltDec, chunk);
                        byte[] data = cipherUpdate(ctx.tgEnc, plain);
                        if (data != null && data.length > 0) {
                            // Ultra: coalesce WS frames while the local socket still has more
                            // bytes queued; flush at end of burst so chat latency stays low.
                            if (ultraPath) {
                                ws.send(data, true);
                                int queued = 0;
                                try {
                                    queued = in.available();
                                } catch (Throwable ignored) {
                                }
                                if (queued <= 0) {
                                    ws.flushPending();
                                }
                            } else {
                                ws.send(data);
                            }
                        }
                    }
                    if (ultraPath) {
                        try {
                            ws.flushPending();
                        } catch (Throwable ignored) {
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
                        sessionBytes.addAndGet(payload.length);
                        byte[] plain = cipherUpdate(ctx.tgDec, payload);
                        byte[] outBuf = cipherUpdate(ctx.cltEnc, plain);
                        if (outBuf != null && outBuf.length > 0) {
                            out.write(outBuf);
                            // Ultra downloads: flush only when the WS side has nothing more
                            // ready yet — fewer syscalls, same interactive feel.
                            if (ultraPath) {
                                int more = 0;
                                try {
                                    more = ws.pendingReadableHint();
                                } catch (Throwable ignored) {
                                }
                                if (more <= 0) {
                                    out.flush();
                                }
                            } else {
                                out.flush();
                            }
                            markBridgeOk(generation);
                        }
                    }
                    out.flush();
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
            if (ultraPath) {
                long dur = Math.max(0L, System.currentTimeMillis() - sessionStart);
                MglaWsConfig.noteUltraRelaySession(sessionHost, dur, sessionBytes.get());
            }
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
            Integer prev = failStreak.get(dcKey);
            int streak = (prev == null ? 0 : prev) + 1;
            failStreak.put(dcKey, streak);
            if (streak >= FAILS_BEFORE_BACKOFF) {
                long backoff = MglaWsConfig.isUltraBypassEnabled()
                    ? FAIL_BACKOFF_ULTRA_MS : FAIL_BACKOFF_MS;
                failUntilMs.put(dcKey, SystemClock.elapsedRealtime() + backoff);
            }
        }
    }

    private void failClear(long dcKey) {
        synchronized (failLock) {
            failUntilMs.remove(dcKey);
            failStreak.remove(dcKey);
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
