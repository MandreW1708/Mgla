package org.telegram.utils.dpi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Подбор стратегий (аналог «Подбора стратегий» ByeByeDPI).
 * <p>
 * Для каждой стратегии движок перезапускается, и через локальный прокси выполняются проверки:
 * <ul>
 *     <li>MTProto — настоящее рукопожатие с каждым ДЦ Telegram (obfuscated2, req_pq_multi → resPQ),
 *     ровно тот трафик, который идёт от клиента;</li>
 *     <li>HTTPS — загрузка сайтов Telegram целиком со сверкой размера с Content-Length
 *     (так ByeByeDPI ловит «заморозку» соединения после первых ~16 КБ).</li>
 * </ul>
 * Первой проверяется «без обхода» — по ней видно, есть ли блокировка вообще.
 */
public final class MglaDpiTester {

    public static final String BASELINE = "";

    private static final String[][] DATACENTERS = {
        {"DC1", "149.154.175.50", "1"},
        {"DC2", "149.154.167.51", "2"},
        {"DC3", "149.154.175.100", "3"},
        {"DC4", "149.154.167.91", "4"},
        {"DC5", "149.154.171.5", "5"},
    };

    private static final String[] SITES = {
        "telegram.org",
        "web.telegram.org",
        "core.telegram.org",
        "t.me",
        "telegra.ph",
    };

    private static final int MTPROTO_TEST = 0;
    private static final int HTTPS_TEST = 1;
    private static final long PAUSE_BETWEEN_STRATEGIES_MS = 500;
    private static final long MAX_BODY_BYTES = 1024 * 1024;

    public static final class TargetResult {
        public final String name;
        public final boolean mtproto;
        public final int total;
        final String host;
        final int dcId;
        final AtomicInteger ok = new AtomicInteger();

        TargetResult(String name, String host, int dcId, boolean mtproto, int total) {
            this.name = name;
            this.host = host;
            this.dcId = dcId;
            this.mtproto = mtproto;
            this.total = total;
        }

        public int getOk() {
            return ok.get();
        }
    }

    public static final class StrategyResult {
        public final String command;
        public final List<TargetResult> targets = new ArrayList<>();
        public volatile String error;
        public volatile boolean finished;
        final AtomicInteger done = new AtomicInteger();

        StrategyResult(String command) {
            this.command = command;
        }

        public boolean isBaseline() {
            return BASELINE.equals(command);
        }

        public int getTotal() {
            int total = 0;
            for (TargetResult t : targets) {
                total += t.total;
            }
            return total;
        }

        public int getOk() {
            int ok = 0;
            for (TargetResult t : targets) {
                ok += t.ok.get();
            }
            return ok;
        }

        public int getDone() {
            return done.get();
        }

        public int getMtprotoOk() {
            int ok = 0;
            for (TargetResult t : targets) {
                if (t.mtproto) {
                    ok += t.ok.get();
                }
            }
            return ok;
        }

        public int getMtprotoTotal() {
            int total = 0;
            for (TargetResult t : targets) {
                if (t.mtproto) {
                    total += t.total;
                }
            }
            return total;
        }

        long score() {
            return getMtprotoOk() * 1000L + (getOk() - getMtprotoOk());
        }
    }

    public interface Callback {
        void onStrategyStarted(StrategyResult result, int index, int total);

        void onProgress(StrategyResult result);

        void onFinished(boolean cancelled, List<StrategyResult> results);
    }

    private final SecureRandom random = new SecureRandom();
    private Thread thread;
    private volatile boolean cancelled;
    private volatile ExecutorService executor;

    public synchronized boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    public synchronized void start(Callback callback) {
        if (isRunning()) {
            return;
        }
        cancelled = false;
        thread = new Thread(() -> runTests(callback), "mgla-dpi-tester");
        thread.setDaemon(true);
        thread.start();
    }

    public void cancel() {
        cancelled = true;
        ExecutorService pool = executor;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** Лучшие результаты сверху: сначала по MTProto, затем по сайтам. */
    public static List<StrategyResult> sorted(List<StrategyResult> results) {
        List<StrategyResult> list = new ArrayList<>(results);
        Collections.sort(list, (a, b) -> Long.compare(b.score(), a.score()));
        return list;
    }

    private void runTests(Callback callback) {
        MglaDpiBypass bypass = MglaDpiBypass.getInstance();
        List<String> commands = new ArrayList<>();
        commands.add(BASELINE);
        commands.addAll(MglaDpiConfig.getTestStrategies());

        int requests = MglaDpiConfig.getTestRequests();
        int timeoutMs = MglaDpiConfig.getTestTimeoutSec() * 1000;
        int parallel = MglaDpiConfig.getTestParallel();

        List<StrategyResult> results = new ArrayList<>();
        try {
            runAll(bypass, commands, results, requests, timeoutMs, parallel, callback);
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            bypass.finishTest();
        }
        boolean wasCancelled = cancelled;
        AndroidUtilities.runOnUIThread(() -> callback.onFinished(wasCancelled, results));
    }

    private void runAll(MglaDpiBypass bypass, List<String> commands, List<StrategyResult> results,
                        int requests, int timeoutMs, int parallel, Callback callback) {
        for (int i = 0; i < commands.size() && !cancelled; i++) {
            StrategyResult result = createResult(commands.get(i), requests);
            results.add(result);
            final int index = i;
            AndroidUtilities.runOnUIThread(() -> callback.onStrategyStarted(result, index, commands.size()));

            int port = bypass.startTestEngine(result.command);
            if (port <= 0) {
                result.error = MglaDpiNative.describeError(port);
            } else {
                runChecks(result, port, requests, timeoutMs, parallel, callback);
            }
            result.finished = true;
            AndroidUtilities.runOnUIThread(() -> callback.onProgress(result));
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("MglaDpi: tester [" + result.command + "] " + result.getOk() + "/" + result.getTotal()
                    + (result.error != null ? " error: " + result.error : ""));
            }
            if (!cancelled) {
                sleep(PAUSE_BETWEEN_STRATEGIES_MS);
            }
        }
    }

    private StrategyResult createResult(String command, int requests) {
        StrategyResult result = new StrategyResult(command);
        for (String[] dc : DATACENTERS) {
            result.targets.add(new TargetResult("MTProto " + dc[0] + " (" + dc[1] + ")", dc[1], Integer.parseInt(dc[2]), true, requests));
        }
        for (String site : SITES) {
            result.targets.add(new TargetResult(site, site, 0, false, requests));
        }
        return result;
    }

    private void runChecks(StrategyResult result, int port, int requests, int timeoutMs, int parallel, Callback callback) {
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        executor = pool;
        try {
            for (TargetResult target : result.targets) {
                for (int r = 0; r < requests; r++) {
                    pool.execute(() -> {
                        if (cancelled) {
                            return;
                        }
                        boolean ok = target.mtproto
                            ? checkMtproto(port, target.host, target.dcId, timeoutMs)
                            : checkHttps(port, target.host, timeoutMs);
                        if (ok) {
                            target.ok.incrementAndGet();
                        }
                        result.done.incrementAndGet();
                        AndroidUtilities.runOnUIThread(() -> callback.onProgress(result));
                    });
                }
            }
            pool.shutdown();
            pool.awaitTermination(timeoutMs * 4L * requests + 10000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | RejectedExecutionException ignored) {
        } finally {
            pool.shutdownNow();
            executor = null;
        }
    }

    // ---------------------------------------------------------------- MTProto check

    /** Рукопожатие obfuscated2 + req_pq_multi через локальный SOCKS5; успех — корректный resPQ. */
    private boolean checkMtproto(int proxyPort, String ip, int dcId, int timeoutMs) {
        Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", proxyPort)));
        try {
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(ip, 443), timeoutMs);

            byte[] header = new byte[64];
            do {
                random.nextBytes(header);
            } while (!isValidObfuscationHeader(header));
            header[56] = header[57] = header[58] = header[59] = (byte) 0xee;
            header[60] = (byte) dcId;
            header[61] = (byte) (dcId >> 8);

            byte[] reversed = reverse(Arrays.copyOfRange(header, 8, 56));
            Cipher encryptor = createCtr(Arrays.copyOfRange(header, 8, 40), Arrays.copyOfRange(header, 40, 56));
            Cipher decryptor = createCtr(Arrays.copyOfRange(reversed, 0, 32), Arrays.copyOfRange(reversed, 32, 48));

            byte[] encryptedHeader = encryptor.update(header);
            System.arraycopy(encryptedHeader, 56, header, 56, 8);

            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            ByteBuffer packet = ByteBuffer.allocate(4 + 40).order(ByteOrder.LITTLE_ENDIAN);
            packet.putInt(40);
            packet.putLong(0);
            packet.putLong(generateMessageId());
            packet.putInt(20);
            packet.putInt(0xbe7e8ef1);
            packet.put(nonce);

            OutputStream out = socket.getOutputStream();
            byte[] first = new byte[64 + packet.capacity()];
            System.arraycopy(header, 0, first, 0, 64);
            System.arraycopy(encryptor.update(packet.array()), 0, first, 64, packet.capacity());
            out.write(first);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] lengthBytes = new byte[4];
            in.readFully(lengthBytes);
            int length = ByteBuffer.wrap(decryptor.update(lengthBytes)).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (length < 40 || length > 1024) {
                return false;
            }
            byte[] body = new byte[length];
            in.readFully(body);
            ByteBuffer response = ByteBuffer.wrap(decryptor.update(body)).order(ByteOrder.LITTLE_ENDIAN);
            if (response.getLong(0) != 0 || response.getInt(20) != 0x05162463) {
                return false;
            }
            byte[] responseNonce = new byte[16];
            response.position(24);
            response.get(responseNonce);
            return Arrays.equals(nonce, responseNonce);
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isValidObfuscationHeader(byte[] h) {
        if ((h[0] & 0xff) == 0xef) {
            return false;
        }
        int first = ByteBuffer.wrap(h, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int second = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return first != 0x44414548 && first != 0x54534f50 && first != 0x20544547 && first != 0x4954504f
            && first != 0xdddddddd && first != 0xeeeeeeee && first != 0x02010316 && second != 0;
    }

    private static long generateMessageId() {
        long millis = System.currentTimeMillis();
        long seconds = millis / 1000;
        long fraction = ((millis % 1000) << 32) / 1000;
        return ((seconds << 32) | fraction) & ~3L;
    }

    private static Cipher createCtr(byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

    private static byte[] reverse(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = data[data.length - 1 - i];
        }
        return out;
    }

    // ---------------------------------------------------------------- HTTPS check

    /** Как в ByeByeDPI: ответ получен, и тело загружено целиком (не оборвалось на середине). */
    private boolean checkHttps(int proxyPort, String host, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", proxyPort));
            connection = (HttpURLConnection) new URL("https://" + host + "/").openConnection(proxy);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Accept-Encoding", "identity");

            int code = connection.getResponseCode();
            long declared = connection.getContentLength();
            long actual = 0;
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream != null) {
                try {
                    byte[] buffer = new byte[8192];
                    long limit = declared > 0 ? declared : MAX_BODY_BYTES;
                    int n;
                    while (actual < limit && !cancelled && (n = stream.read(buffer, 0, (int) Math.min(buffer.length, limit - actual))) != -1) {
                        actual += n;
                    }
                } catch (IOException ignored) {
                }
            }
            return declared <= 0 || actual >= declared;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
