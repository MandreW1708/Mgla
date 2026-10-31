package org.telegram.utils.dpi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal SOCKS5 CONNECT client (user/pass) for routing TLS through the local ByeDPI engine.
 */
public final class MglaSocks5 {

    private MglaSocks5() {
    }

    public static Socket connect(String proxyHost, int proxyPort, String user, String pass,
                                 String destHost, int destPort, int timeoutMs) throws IOException {
        if (destHost == null || destHost.isEmpty()) {
            throw new IOException("SOCKS dest empty");
        }
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(proxyHost, proxyPort), Math.max(1, timeoutMs));
            s.setSoTimeout(Math.max(1, timeoutMs));
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            boolean auth = user != null && !user.isEmpty();
            // greeting
            if (auth) {
                out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
            } else {
                out.write(new byte[]{0x05, 0x01, 0x00});
            }
            out.flush();
            byte[] gre = readExact(in, 2);
            if (gre[0] != 0x05) {
                throw new IOException("bad SOCKS version");
            }
            if (gre[1] == 0x02) {
                if (!auth) {
                    throw new IOException("SOCKS auth required");
                }
                byte[] u = user.getBytes(StandardCharsets.UTF_8);
                byte[] p = pass == null ? new byte[0] : pass.getBytes(StandardCharsets.UTF_8);
                if (u.length > 255 || p.length > 255) {
                    throw new IOException("SOCKS credentials too long");
                }
                byte[] authMsg = new byte[3 + u.length + p.length];
                authMsg[0] = 0x01;
                authMsg[1] = (byte) u.length;
                System.arraycopy(u, 0, authMsg, 2, u.length);
                authMsg[2 + u.length] = (byte) p.length;
                System.arraycopy(p, 0, authMsg, 3 + u.length, p.length);
                out.write(authMsg);
                out.flush();
                byte[] ar = readExact(in, 2);
                if (ar[1] != 0x00) {
                    throw new IOException("SOCKS auth failed");
                }
            } else if (gre[1] != 0x00) {
                throw new IOException("SOCKS method rejected: " + (gre[1] & 0xff));
            }

            // CONNECT — prefer domain so ByeDPI sees the hostname; fall back to IPv4.
            byte[] hostBytes = destHost.getBytes(StandardCharsets.UTF_8);
            boolean useDomain = hostBytes.length > 0 && hostBytes.length <= 255 && !isLiteralIp(destHost);
            byte[] req;
            if (useDomain) {
                req = new byte[7 + hostBytes.length];
                req[0] = 0x05;
                req[1] = 0x01; // CONNECT
                req[2] = 0x00;
                req[3] = 0x03; // DOMAIN
                req[4] = (byte) hostBytes.length;
                System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
                req[5 + hostBytes.length] = (byte) ((destPort >> 8) & 0xff);
                req[6 + hostBytes.length] = (byte) (destPort & 0xff);
            } else {
                InetAddress ip = InetAddress.getByName(destHost);
                byte[] addr = ip.getAddress();
                if (addr.length != 4) {
                    throw new IOException("SOCKS IPv6 not supported");
                }
                req = new byte[10];
                req[0] = 0x05;
                req[1] = 0x01;
                req[2] = 0x00;
                req[3] = 0x01;
                System.arraycopy(addr, 0, req, 4, 4);
                req[8] = (byte) ((destPort >> 8) & 0xff);
                req[9] = (byte) (destPort & 0xff);
            }
            out.write(req);
            out.flush();

            byte[] head = readExact(in, 4);
            if (head[0] != 0x05 || head[1] != 0x00) {
                throw new IOException("SOCKS CONNECT failed status=" + (head[1] & 0xff));
            }
            int atyp = head[3] & 0xff;
            if (atyp == 0x01) {
                readExact(in, 4 + 2);
            } else if (atyp == 0x03) {
                int ln = readExact(in, 1)[0] & 0xff;
                readExact(in, ln + 2);
            } else if (atyp == 0x04) {
                readExact(in, 16 + 2);
            } else {
                throw new IOException("SOCKS bad atyp " + atyp);
            }
            s.setSoTimeout(0);
            return s;
        } catch (IOException e) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
            throw e;
        }
    }

    private static boolean isLiteralIp(String host) {
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 literal
        }
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dots == 3;
    }

    private static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("SOCKS eof");
            }
            off += r;
        }
        return buf;
    }
}
