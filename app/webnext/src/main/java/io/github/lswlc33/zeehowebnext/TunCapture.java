package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 方案 3 的 TUN 透明抓取（对齐 HttpCanary/NetBare 的做法）。
 *
 * 为什么不再用 setHttpProxy：官方 App 的 WebView/网络栈只要不理会系统代理，
 * 代理就一个包都收不到。这里改用 VpnService 把 h5.zeehoev.com 的 IPv4 路由进 TUN，
 * 自己读原始 IP 包；TCP 走「改写目标地址到本地 ServerSocket」的透明转发，
 * 由内核处理三次握手/重传，我们只搬字节并解析明文 HTTP。
 *
 * 只路由 h5 的 IP（以及 DNS 服务器），其他流量仍走原网络，页面/接口不受影响；
 * HTTPS 只做透明转发，不尝试解密（没有 CA 也能抓到明文的那一跳）。
 */
final class TunCapture {

    interface Callback {
        void onCaptured(String token, String uid);
        void onError(String message);
        /** 官方 WebView 拒绝了本地证书，错误页会显示带 token 的 URL；交给页面手动复制。 */
        void onManualPage();
    }

    private static final String TAG = "ZeehoWebNext";
    private static final String TARGET_HOST = "h5.zeehoev.com";
    private static final String VPN_IP = "10.1.10.1";
    private static final int MTU = 2560;
    // DNS 沿用当前活动网络的 DNS；默认路由会把 53 一起送进 TUN，由 relayDns 转发。
    private static final int MAX_HTTP = 128 * 1024;
    /** 测试用：允许 adb shell 的 nc 走 VPN；正式版必须为 false。 */
    private final VpnService vpn;
    private final Callback cb;
    private final AtomicBoolean captured = new AtomicBoolean();
    private final AtomicInteger tcpFlows = new AtomicInteger();
    private final AtomicInteger httpRequests = new AtomicInteger();
    private final AtomicInteger dnsQueries = new AtomicInteger();
    private final AtomicInteger dotBlocked = new AtomicInteger();
    private final AtomicInteger udpDropped = new AtomicInteger();
    private final AtomicInteger handshakeLogs = new AtomicInteger();
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final Set<Integer> routed = new LinkedHashSet<>();
    private final Set<Integer> h5Ips = new LinkedHashSet<>();
    private CaptureProxy tlsMitm;
    private volatile boolean mitmFailed;
    private final AtomicBoolean manualNotified = new AtomicBoolean();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private volatile boolean running;
    private ParcelFileDescriptor tun;
    private FileInputStream in;
    private FileOutputStream out;
    private ServerSocket server;
    private int proxyPort;
    private int vpnIp;

    TunCapture(VpnService vpn, Callback cb) {
        this.vpn = vpn;
        this.cb = cb;
    }

    String stats() {
        return "tun tcp=" + tcpFlows.get()
            + " http=" + httpRequests.get()
            + " dns=" + dnsQueries.get()
            + " captured=" + captured.get()
            + " routes=" + routed.size()
            + " port=" + proxyPort;
    }

    /** 建 VPN + 本地透明转发端口；失败直接抛给 Service。 */
    void start() throws Exception {
        vpnIp = ipToInt(InetAddress.getByName(VPN_IP));
        server = new ServerSocket(0);
        proxyPort = server.getLocalPort();

        VpnService.Builder b = vpn.new Builder();
        b.setSession("Zeeho 自动抓取");
        b.setMtu(MTU);
        b.addAddress(VPN_IP, 32);
        b.setBlocking(true);
        // 全量路由：官方 App 的其他接口/图片也必须能走通，不能只路由 h5 IP
        // （VPN 的 unreachable default 会把未路由流量全断掉）。
        b.addRoute("0.0.0.0", 0);
        routed.add(0);
        try {
            for (InetAddress a : InetAddress.getAllByName(TARGET_HOST)) {
                if (a instanceof java.net.Inet4Address) h5Ips.add(ipToInt(a));
            }
        } catch (Exception e) {
            CaptureLog.add(vpn, "tun", "h5 resolve for mitm failed: " + e.getMessage());
        }
        try {
            tlsMitm = new CaptureProxy(vpn, new CaptureProxy.Callback() {
                @Override
                public void onCaptured(String token, String uid) {
                    // 复用 CaptureProxy 的 token/uid 提取；TUN 自己只负责把连接送到这里。
                    TunCapture.this.capture(token, uid, "tls-mitm");
                }

                @Override
                public void onError(String message) {
                    CaptureLog.add(vpn, "tun-mitm", "error: " + message);
                }
            });
            tlsMitm.prepareTls();
            CaptureLog.add(vpn, "tun-mitm", "tls ready; h5 ips=" + h5Ips.size());
        } catch (Exception e) {
            CaptureLog.add(vpn, "tun-mitm", "prepare failed: " + e);
            tlsMitm = null;
        }
        List<String> dns = systemDnsServers();
        for (String d : dns) {
            b.addDnsServer(d);
        }
        // 不能用 addAllowedApplication：官方 h5 跑在 WebView sandbox 进程（isolated UID），
        // 按包名允许 com.cfmoto 抓不到它。HttpCanary 同样是全量 VPN；其他 App 的流量
        // 也一起进 TUN，短时间抓取结束即拆。
        // 本 App 自己的上游 socket 全部 protect()，不会进 TUN。
        tun = b.establish();
        if (tun == null) throw new IOException("VPN establish returned null");
        in = new FileInputStream(tun.getFileDescriptor());
        out = new FileOutputStream(tun.getFileDescriptor());
        running = true;
        new Thread(this::readLoop, "tun-read").start();
        new Thread(this::acceptLoop, "tun-accept").start();
        CaptureLog.add(vpn, "tun", "established target=" + TARGET_HOST
            + " route=0.0.0.0/0 dns=" + String.join(",", dns)
            + " vpn=" + VPN_IP + " proxyPort=" + proxyPort
            + " mtu=" + MTU + " h5Ips=" + h5Ips);
        Log.i(TAG, "tun established port=" + proxyPort + " " + stats());
    }

    void stop() {
        running = false;
        CaptureLog.add(vpn, "tun", "stop " + stats());
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (in != null) in.close(); } catch (Exception ignored) { }
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        try { if (tun != null) tun.close(); } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    /** 当前活动网络的 DNS；只写进 VPN 的 LinkProperties，不写路由，查询仍走底层网络。 */
    private List<String> systemDnsServers() {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) vpn.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    for (InetAddress a : lp.getDnsServers()) {
                        if (a instanceof java.net.Inet4Address) {
                            String s = a.getHostAddress();
                            if (!out.contains(s)) out.add(s);
                        }
                    }
                }
            }
        } catch (Exception e) {
            CaptureLog.add(vpn, "tun", "dns list failed: " + e.getMessage());
        }
        return out;
    }

    // ---------------- TUN 读包 ----------------

    private void readLoop() {
        byte[] buf = new byte[MTU];
        while (running) {
            int len;
            try {
                len = in.read(buf);
            } catch (Exception e) {
                if (running) CaptureLog.add(vpn, "tun-read", "read failed: " + e);
                break;
            }
            if (len < 20) continue;
            int version = (buf[0] >> 4) & 0x0F;
            if (version != 4) {
                // IPv6 没路由（target 只有 A 记录）；静默丢弃，避免日志刷屏。
                continue;
            }
            int ihl = (buf[0] & 0x0F) * 4;
            int total = u16(buf, 2);
            if (ihl < 20 || total < ihl || total > len) continue;
            int proto = buf[9] & 0xFF;
            if (proto == 6) {
                handleTcp(buf, total, ihl);
            } else if (proto == 17) {
                handleUdp(buf, total, ihl);
            }
        }
        CaptureLog.add(vpn, "tun-read", "loop ended " + stats());
    }

    private void handleTcp(byte[] buf, int total, int ihl) {
        int srcIp = i32(buf, 12);
        int dstIp = i32(buf, 16);
        int srcPort = u16(buf, ihl);
        int dstPort = u16(buf, ihl + 2);
        int tcpHdr = ((buf[ihl + 12] >> 4) & 0x0F) * 4;
        if (tcpHdr < 20 || ihl + tcpHdr > total) return;
        int flags = buf[ihl + 13] & 0xFF;
        boolean syn = (flags & 0x02) != 0;
        if (handshakeLogs.incrementAndGet() <= 30) {
            CaptureLog.add(vpn, "tun-tcp", "pkt " + ipToString(srcIp) + ":" + srcPort
                + " -> " + ipToString(dstIp) + ":" + dstPort
                + " flags=0x" + Integer.toHexString(flags)
                + " seq=" + i32(buf, ihl + 4) + " ack=" + i32(buf, ihl + 8));
        }

        // Private DNS 会先尝试 DoT(853)。路由器/公共 DNS 不一定支持，重试会把 DNS 拖死；
        // 回一个 RST 让系统解析器立刻回退到 UDP 53，由 relayDns 转发。
        if (srcPort != proxyPort && dstPort == 853) {
            sendTcpReset(buf, total, ihl);
            if (dotBlocked.incrementAndGet() <= 3) {
                CaptureLog.add(vpn, "tun-dns", "DoT 853 -> RST, force UDP fallback");
            }
            return;
        }

        Session s;
        if (srcPort != proxyPort) {
            // 客户端 -> 真实服务器：把目标改成本机透明转发端口，源 IP 改成真实服务器 IP。
            s = sessions.get(srcPort);
            if (s == null || syn) {
                s = new Session(dstIp, dstPort, srcIp, srcPort);
                sessions.put(srcPort, s);
                int n = tcpFlows.incrementAndGet();
                CaptureLog.add(vpn, "tun-tcp", "#" + n + " flow "
                    + ipToString(srcIp) + ":" + srcPort + " -> "
                    + ipToString(dstIp) + ":" + dstPort
                    + (syn ? " SYN" : ""));
            }
            putInt(buf, 12, dstIp);
            putInt(buf, 16, vpnIp);
            putShort(buf, ihl + 2, proxyPort);
        } else {
            // 本机转发端口的回包 -> 客户端：恢复成真实服务器 -> 客户端。
            // 判据是**源端口**为 proxyPort（NetBare 的 localPort 判据），不是目的端口。
            s = sessions.get(dstPort);
            if (s == null) {
                CaptureLog.add(vpn, "tun-tcp", "no session for client port " + dstPort);
                return;
            }
            if (handshakeLogs.get() <= 30) {
                CaptureLog.add(vpn, "tun-tcp", "resp key=" + dstPort
                    + " -> " + ipToString(s.remoteIp) + ":" + s.remotePort
                    + " client=" + ipToString(s.clientIp) + ":" + s.clientPort);
            }
            putInt(buf, 12, s.remoteIp);
            putInt(buf, 16, s.clientIp);
            putShort(buf, ihl, s.remotePort);
        }
        updateIpChecksum(buf, ihl);
        updateTcpChecksum(buf, ihl, total);
        writeTun(buf, total);
    }

    /** 对指定 TCP 包回一个 RST（用于逼 Private DNS 从 DoT 853 回退到 UDP 53）。 */
    private void sendTcpReset(byte[] in, int total, int ihl) {
        int srcIp = i32(in, 12);
        int dstIp = i32(in, 16);
        int srcPort = u16(in, ihl);
        int dstPort = u16(in, ihl + 2);
        int seq = i32(in, ihl + 4);
        int ack = i32(in, ihl + 8);
        int flags = in[ihl + 13] & 0xFF;
        int tcpHdr = ((in[ihl + 12] >> 4) & 0x0F) * 4;
        int dataLen = Math.max(0, total - ihl - tcpHdr);
        int outSeq = (flags & 0x10) != 0 ? ack : 0;
        int outAck = seq + 1 + dataLen;

        byte[] p = new byte[40];
        p[0] = 0x45;
        putShort(p, 2, 40);
        putShort(p, 6, 0x4000);
        p[8] = 64;
        p[9] = 6;
        putInt(p, 12, dstIp);
        putInt(p, 16, srcIp);
        updateIpChecksum(p, 20);
        putShort(p, 20, dstPort);
        putShort(p, 22, srcPort);
        putInt(p, 24, outSeq);
        putInt(p, 28, outAck);
        p[32] = (byte) (5 << 4);
        p[33] = (byte) 0x14; // RST | ACK
        putShort(p, 34, 0);
        putShort(p, 36, 0);
        putShort(p, 38, 0);
        updateTcpChecksum(p, 20, 40);
        writeTun(p, 40);
    }

    private void handleUdp(byte[] buf, int total, int ihl) {
        int srcIp = i32(buf, 12);
        int dstIp = i32(buf, 16);
        int srcPort = u16(buf, ihl);
        int dstPort = u16(buf, ihl + 2);
        if (dstPort != 53) {
            // QUIC 等 UDP 不转发（会拖垮整条链路）；只记前几条便于判断是否因此失败。
            if (udpDropped.incrementAndGet() <= 3) {
                CaptureLog.add(vpn, "tun-udp", "drop " + ipToString(dstIp) + ":" + dstPort);
            }
            return;
        }
        int off = ihl + 8;
        int len = total - off;
        if (len <= 0 || len > 4096) return;
        byte[] query = new byte[len];
        System.arraycopy(buf, off, query, 0, len);
        dnsQueries.incrementAndGet();
        pool.execute(() -> relayDns(srcIp, srcPort, dstIp, query));
    }

    private void relayDns(int clientIp, int clientPort, int dnsIp, byte[] query) {
        // 运营商 DNS（如 218.2.2.2）在部分网络/漫游下不可达；逐个尝试公共 DNS，
        // 但回给客户端的响应源 IP 仍写成它原本查询的 dnsIp，客户端才会接受。
        String[] fallback = {"223.5.5.5", "119.29.29.29", "8.8.8.8"};
        java.util.List<Integer> servers = new ArrayList<>();
        servers.add(dnsIp);
        for (String f : fallback) {
            try {
                int ip = ipToInt(InetAddress.getByName(f));
                if (!servers.contains(ip)) servers.add(ip);
            } catch (Exception ignored) { }
        }
        for (int server : servers) {
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket();
                vpn.protect(ds);
                ds.setSoTimeout(3000);
                ds.send(new DatagramPacket(query, query.length, ipToAddress(server), 53));
                byte[] resp = new byte[4096];
                DatagramPacket p = new DatagramPacket(resp, resp.length);
                ds.receive(p);
                byte[] packet = buildUdpPacket(dnsIp, 53, clientIp, clientPort, resp, p.getLength());
                writeTun(packet, packet.length);
                return;
            } catch (Exception e) {
                CaptureLog.add(vpn, "tun-dns", "dns " + ipToString(server)
                    + " failed: " + e.getMessage());
            } finally {
                try { if (ds != null) ds.close(); } catch (Exception ignored) { }
            }
        }
    }

    private void writeTun(byte[] buf, int len) {
        try {
            synchronized (out) {
                out.write(buf, 0, len);
                out.flush();
            }
        } catch (Exception e) {
            if (running) CaptureLog.add(vpn, "tun-write", "write failed: " + e.getMessage());
        }
    }

    // ---------------- 本地透明转发 ----------------

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = server.accept();
            } catch (Exception e) {
                if (running) CaptureLog.add(vpn, "tun-accept", "accept failed: " + e.getMessage());
                break;
            }
            int clientPort = client.getPort();
            Session s = sessions.get(clientPort);
            if (handshakeLogs.get() <= 30) {
                CaptureLog.add(vpn, "tun-accept", "peer=" + client.getInetAddress()
                    + ":" + clientPort + " session=" + (s != null));
            }
            if (s == null) {
                CaptureLog.add(vpn, "tun-accept", "no session for port " + clientPort);
                close(client);
                continue;
            }
            final Session session = s;
            pool.execute(() -> relay(client, session));
        }
    }

    private void relay(Socket client, Session s) {
        Socket up = null;
        try {
            client.setTcpNoDelay(true);
            if (s.remotePort == 443 && !mitmFailed && tlsMitm != null
                && h5Ips.contains(s.remoteIp)) {
                try {
                    CaptureLog.add(vpn, "tun-mitm", "MITM h5 " + ipToString(s.remoteIp));
                    tlsMitm.mitmSocket(client, TARGET_HOST, 443);
                    CaptureLog.add(vpn, "tun-mitm", "done");
                    return;
                } catch (javax.net.ssl.SSLException e) {
                    mitmFailed = true;
                    CaptureLog.add(vpn, "tun-mitm", "client rejected cert: " + e.getMessage());
                    if (manualNotified.compareAndSet(false, true)) cb.onManualPage();
                    return;
                } catch (Exception e) {
                    CaptureLog.add(vpn, "tun-mitm", "failed: " + e);
                    return;
                }
            }
            // new Socket() 在 Android 上是惰性 fd，protect() 会返回 false；
            // 用 SocketChannel 先拿到真实 fd，再 protect/connect（NetBare 的做法）。
            java.nio.channels.SocketChannel channel = java.nio.channels.SocketChannel.open();
            up = channel.socket();
            boolean prot = vpn.protect(up);
            CaptureLog.add(vpn, "tun-relay", "up " + ipToString(s.remoteIp) + ":"
                + s.remotePort + " protect=" + prot);
            if (!prot) {
                CaptureLog.add(vpn, "tun-relay", "protect failed; abort to avoid loop");
                close(up);
                return;
            }
            up.connect(new InetSocketAddress(ipToAddress(s.remoteIp), s.remotePort), 10000);
            up.setTcpNoDelay(true);
            CaptureLog.add(vpn, "tun-relay", "connected " + ipToString(s.remoteIp)
                + ":" + s.remotePort);
            Probe probe = new Probe(s);
            Socket upstream = up;
            Thread a = new Thread(() -> pump(client, upstream, probe, true), "tun-req");
            Thread b = new Thread(() -> pump(upstream, client, probe, false), "tun-resp");
            a.setDaemon(true);
            b.setDaemon(true);
            a.start();
            b.start();
            a.join();
            b.join();
        } catch (Exception e) {
            CaptureLog.add(vpn, "tun-relay", "flow " + ipToString(s.remoteIp) + ":"
                + s.remotePort + " failed: " + e.getMessage());
        } finally {
            close(up);
            close(client);
            sessions.remove(s.clientPort, s);
        }
    }

    private void pump(Socket from, Socket to, Probe probe, boolean request) {
        byte[] buf = new byte[8192];
        int total = 0;
        try (InputStream in = new BufferedInputStream(from.getInputStream());
             OutputStream out = to.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
                total += n;
                if (request) probe.onRequest(buf, n);
                else probe.onResponse(buf, n);
            }
        } catch (Exception e) {
            CaptureLog.add(vpn, "tun-pump", (request ? "req" : "resp")
                + " error after " + total + "B: " + e.getMessage());
        } finally {
            if (!request && total == 0) {
                CaptureLog.add(vpn, "tun-pump", "resp EOF with 0 bytes " + ipToString(probe.session.remoteIp)
                    + ":" + probe.session.remotePort);
            }
            close(from);
            close(to);
        }
    }

    // ---------------- 明文 HTTP 探针 ----------------

    private final class Probe {
        private final Session session;
        private final ByteArrayOutputStream req = new ByteArrayOutputStream();
        private final ByteArrayOutputStream resp = new ByteArrayOutputStream();
        private boolean reqHeadDone;
        private boolean respHeadDone;
        private boolean reqLogged;
        private boolean respLogged;
        private String method = "";
        private String target = "";
        private String host = "";
        private Map<String, String> reqHeaders = Collections.emptyMap();
        private Map<String, String> respHeaders = Collections.emptyMap();
        private int status;
        private String token = "";
        private String uid = "";

        Probe(Session s) {
            this.session = s;
        }

        synchronized void onRequest(byte[] b, int len) {
            append(req, b, len);
            if (!reqHeadDone) {
                int end = indexOf(req.toByteArray(), "\r\n\r\n");
                if (end < 0) {
                    if (req.size() > 64 * 1024) reqHeadDone = true;
                    else return;
                } else {
                    parseRequestHead(new String(req.toByteArray(), 0, end, StandardCharsets.ISO_8859_1));
                    reqHeadDone = true;
                }
            }
            if (!reqLogged) {
                reqLogged = true;
                httpRequests.incrementAndGet();
                CaptureLog.add(vpn, "http-req", method + " " + maskUrl(target)
                    + " host=" + host + " " + headerDigest(reqHeaders));
            }
            if (isBaseInfo()) {
                extractRequest();
                maybeCapture("http-request");
            }
        }

        synchronized void onResponse(byte[] b, int len) {
            append(resp, b, len);
            if (!respHeadDone) {
                int end = indexOf(resp.toByteArray(), "\r\n\r\n");
                if (end < 0) {
                    if (resp.size() > 64 * 1024) respHeadDone = true;
                    else return;
                } else {
                    parseResponseHead(new String(resp.toByteArray(), 0, end, StandardCharsets.ISO_8859_1));
                    respHeadDone = true;
                }
            }
            if (!respLogged) {
                respLogged = true;
                CaptureLog.add(vpn, "http-resp", status + " ct="
                    + header(respHeaders, "content-type") + " len=" + resp.size()
                    + " path=" + maskUrl(target));
            }
            if (isBaseInfo()) {
                extractResponse();
                maybeCapture("http-response");
            }
        }

        private void parseRequestHead(String head) {
            String[] lines = head.split("\r\n");
            if (lines.length > 0) {
                String[] p = lines[0].split(" ");
                if (p.length >= 2) {
                    method = p[0];
                    target = p[1];
                }
            }
            reqHeaders = parseHeaders(lines);
            host = header(reqHeaders, "host");
            if (host.isEmpty() && target.startsWith("http")) {
                try {
                    java.net.URL u = new java.net.URL(target);
                    host = u.getHost();
                    target = u.getFile();
                } catch (Exception ignored) { }
            }
        }

        private void parseResponseHead(String head) {
            String[] lines = head.split("\r\n");
            if (lines.length > 0) {
                String[] p = lines[0].split(" ");
                try { status = Integer.parseInt(p[1]); } catch (Exception ignored) { }
            }
            respHeaders = parseHeaders(lines);
        }

        private void extractRequest() {
            String q = target;
            String t = queryValue(q, new String[]{"token", "access_token", "accessToken", "satoken"});
            if (t.isEmpty()) t = bearer(header(reqHeaders, "authorization"));
            if (t.isEmpty()) {
                for (String k : new String[]{"token", "access_token", "satoken", "x-token"}) {
                    String v = header(reqHeaders, k);
                    if (!v.isEmpty()) { t = v; break; }
                }
            }
            if (t.isEmpty()) t = cookieValue(header(reqHeaders, "cookie"),
                new String[]{"token", "access_token", "satoken"});
            if (!t.isEmpty()) token = stripBearer(t);

            String u = queryValue(q, new String[]{"userId", "user_id", "uid", "userid",
                "accountId", "account_id", "bindingUserId"});
            if (u.isEmpty()) {
                for (String k : new String[]{"userId", "user_id", "uid", "accountId"}) {
                    String v = header(reqHeaders, k);
                    if (!v.isEmpty()) { u = v; break; }
                }
            }
            if (u.isEmpty()) u = cookieValue(header(reqHeaders, "cookie"),
                new String[]{"userId", "user_id", "uid"});
            if (!u.isEmpty()) uid = u;
            if (token.isEmpty()) {
                byte[] body = bodyOf(req);
                token = findInBody(body, header(reqHeaders, "content-encoding"),
                    new String[]{"token", "access_token", "accessToken", "h5Token", "h5_token"});
                if (uid.isEmpty()) {
                    uid = findInBody(body, header(reqHeaders, "content-encoding"),
                        new String[]{"userId", "user_id", "uid", "userNo", "memberId", "accountId"});
                }
            }
        }

        private void extractResponse() {
            String t = queryValue(header(respHeaders, "location"),
                new String[]{"token", "access_token", "accessToken", "satoken"});
            if (t.isEmpty()) t = cookieValue(header(respHeaders, "set-cookie"),
                new String[]{"token", "access_token", "satoken"});
            if (t.isEmpty()) t = findInBody(bodyOf(resp), header(respHeaders, "content-encoding"),
                new String[]{"token", "access_token", "accessToken", "h5Token", "h5_token"});
            if (!t.isEmpty()) token = stripBearer(t);

            String u = queryValue(header(respHeaders, "location"),
                new String[]{"userId", "user_id", "uid", "userid", "accountId", "account_id", "bindingUserId"});
            if (u.isEmpty()) u = cookieValue(header(respHeaders, "set-cookie"),
                new String[]{"userId", "user_id", "uid"});
            if (u.isEmpty()) {
                byte[] body = bodyOf(resp);
                u = findInBody(body, header(respHeaders, "content-encoding"),
                    new String[]{"userId", "user_id", "uid", "userNo", "memberId", "accountId"});
                if (u.isEmpty()) {
                    u = findJsonId(body, header(respHeaders, "content-encoding"));
                }
            }
            if (!u.isEmpty()) uid = u;
        }

        private boolean isBaseInfo() {
            String h = host == null ? "" : host.toLowerCase(Locale.US);
            String t = target == null ? "" : target.toLowerCase(Locale.US);
            return h.contains("h5.zeehoev.com") && t.contains("/h5/baseinfo");
        }

        private void maybeCapture(String source) {
            if (token.isEmpty()) return;
            if (!plausibleToken(token)) {
                CaptureLog.add(vpn, "http", source + ": token rejected len=" + token.length());
                return;
            }
            TunCapture.this.capture(token, uid, source);
        }
    }

    private void capture(String token, String uid, String source) {
        if (!captured.compareAndSet(false, true)) return;
        CaptureLog.add(vpn, "capture", "success source=" + source
            + " tokenLen=" + token.length() + " uid=" + (uid == null || uid.isEmpty() ? "no" : "yes"));
        cb.onCaptured(token, uid == null ? "" : uid);
    }

    private static boolean plausibleToken(String t) {
        if (t == null || t.length() < 12 || t.length() > 4096) return false;
        if (t.contains(" ") || t.contains("\n") || t.contains("\r")) return false;
        String l = t.toLowerCase(Locale.US);
        return !l.equals("undefined") && !l.equals("null") && !l.equals("false") && !l.equals("true");
    }

    private static byte[] bodyOf(ByteArrayOutputStream out) {
        byte[] all = out.toByteArray();
        int end = indexOf(all, "\r\n\r\n");
        if (end < 0) return new byte[0];
        int off = end + 4;
        if (off >= all.length) return new byte[0];
        byte[] body = new byte[all.length - off];
        System.arraycopy(all, off, body, 0, body.length);
        return body;
    }

    private static int indexOf(byte[] data, String s) {
        byte[] p = s.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + p.length <= data.length; i++) {
            for (int j = 0; j < p.length; j++) {
                if (data[i + j] != p[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void append(ByteArrayOutputStream out, byte[] b, int len) {
        if (out.size() >= MAX_HTTP) return;
        int n = Math.min(len, MAX_HTTP - out.size());
        out.write(b, 0, n);
    }

    private static Map<String, String> parseHeaders(String[] lines) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) continue;
            String k = lines[i].substring(0, c).trim();
            String v = lines[i].substring(c + 1).trim();
            String old = m.get(k);
            if (old != null && k.equalsIgnoreCase("set-cookie")) v = old + "\n" + v;
            m.put(k, v);
        }
        return m;
    }

    private static String header(Map<String, String> h, String name) {
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return "";
    }

    private static String headerDigest(Map<String, String> h) {
        List<String> names = new ArrayList<>(h.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        StringBuilder tok = new StringBuilder();
        for (String k : new String[]{"token", "access_token", "accessToken", "satoken", "x-token", "authorization"}) {
            if (!header(h, k).isEmpty()) {
                if (tok.length() > 0) tok.append(',');
                tok.append(k);
            }
        }
        return "headers=[" + String.join(",", names) + "]"
            + " auth=" + (header(h, "authorization").isEmpty() ? "n" : "y")
            + " tokenHdr=[" + tok + "]"
            + " cookieKeys=[" + cookieKeys(header(h, "cookie")) + "]";
    }

    private static String cookieKeys(String cookie) {
        if (cookie == null || cookie.isEmpty()) return "";
        List<String> keys = new ArrayList<>();
        for (String part : cookie.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            String k = eq > 0 ? p.substring(0, eq).trim() : p;
            if (!k.isEmpty()) keys.add(k);
        }
        return String.join(",", keys);
    }

    private static String cookieValue(String cookie, String[] keys) {
        if (cookie == null || cookie.isEmpty()) return "";
        for (String key : keys) {
            Matcher m = Pattern.compile("(?i)(?:^|[;,\\n]\\s*)" + Pattern.quote(key)
                + "=([^;,\\s]+)").matcher(cookie);
            if (m.find()) return decode(m.group(1));
        }
        return "";
    }

    private static String bearer(String auth) {
        if (auth == null || auth.isEmpty()) return "";
        return auth.replaceFirst("(?i)^bearer\\s+", "").trim();
    }

    private static String queryValue(String url, String[] keys) {
        if (url == null || url.isEmpty()) return "";
        for (String key : keys) {
            Matcher m = Pattern.compile("[?&]" + Pattern.quote(key) + "=([^&#\\s]*)",
                Pattern.CASE_INSENSITIVE).matcher(url);
            if (m.find()) return decode(m.group(1));
        }
        return "";
    }

    private static String decode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String stripBearer(String s) {
        if (s == null) return "";
        return s.trim().replaceFirst("(?i)^bearer\\s+", "");
    }

    private static String findInBody(byte[] body, String encoding, String[] keys) {
        if (body == null || body.length == 0) return "";
        byte[] data = decodeBody(body, encoding);
        if (data == null) return "";
        String text = new String(data, StandardCharsets.UTF_8);
        for (String key : keys) {
            Matcher m = Pattern.compile("(?i)[\"']?" + Pattern.quote(key)
                + "[\"']?\\s*[:=]\\s*[\"']?([^\"'&,}\\s]+)").matcher(text);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    /** baseInfo 的账号 id 在 data.id；只在这里做一次 JSON 解析，避免误抓其他 id。 */
    private static String findJsonId(byte[] body, String encoding) {
        if (body == null || body.length == 0) return "";
        byte[] data = decodeBody(body, encoding);
        if (data == null) return "";
        try {
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8).trim());
            JSONObject d = root.optJSONObject("data");
            if (d != null && d.has("id")) {
                Object v = d.opt("id");
                if (v != null && v != JSONObject.NULL) return String.valueOf(v);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static byte[] decodeBody(byte[] body, String encoding) {
        if (encoding == null || !encoding.toLowerCase(Locale.US).contains("gzip")) return body;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String maskUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        String s = url.replaceAll("([?&][^=&#\\s]+)=([^&#\\s]*)", "$1=***");
        if (s.length() > 360) s = s.substring(0, 360) + "...";
        return s;
    }

    // ---------------- IP / TCP / UDP 工具 ----------------

    private static final class Session {
        final int remoteIp;
        final int remotePort;
        final int clientIp;
        final int clientPort;

        Session(int remoteIp, int remotePort, int clientIp, int clientPort) {
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.clientIp = clientIp;
            this.clientPort = clientPort;
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int i32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
            | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static int ipToInt(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length != 4) return 0;
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
            | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static InetAddress ipToAddress(int ip) throws UnknownHostException {
        byte[] b = new byte[]{
            (byte) ((ip >> 24) & 0xFF), (byte) ((ip >> 16) & 0xFF),
            (byte) ((ip >> 8) & 0xFF), (byte) (ip & 0xFF)
        };
        return InetAddress.getByAddress(b);
    }

    private static String ipToString(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "."
            + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    private static void updateIpChecksum(byte[] b, int ihl) {
        putShort(b, 10, 0);
        putShort(b, 10, checksum(b, 0, ihl, 0));
    }

    private static void updateTcpChecksum(byte[] b, int ihl, int total) {
        int tcpLen = total - ihl;
        putShort(b, ihl + 16, 0);
        long sum = 0;
        sum += ((i32(b, 12) >>> 16) & 0xFFFF) + (i32(b, 12) & 0xFFFF);
        sum += ((i32(b, 16) >>> 16) & 0xFFFF) + (i32(b, 16) & 0xFFFF);
        sum += 6;
        sum += tcpLen;
        putShort(b, ihl + 16, checksum(b, ihl, tcpLen, sum));
    }

    private static byte[] buildUdpPacket(int srcIp, int srcPort, int dstIp, int dstPort,
                                         byte[] payload, int len) {
        int total = 20 + 8 + len;
        byte[] p = new byte[total];
        p[0] = 0x45;
        p[1] = 0;
        putShort(p, 2, total);
        putShort(p, 4, 0);
        putShort(p, 6, 0x4000);
        p[8] = 64;
        p[9] = 17;
        putInt(p, 12, srcIp);
        putInt(p, 16, dstIp);
        updateIpChecksum(p, 20);
        putShort(p, 20, srcPort);
        putShort(p, 22, dstPort);
        putShort(p, 24, 8 + len);
        putShort(p, 26, 0);
        System.arraycopy(payload, 0, p, 28, len);
        long sum = 0;
        sum += ((srcIp >>> 16) & 0xFFFF) + (srcIp & 0xFFFF);
        sum += ((dstIp >>> 16) & 0xFFFF) + (dstIp & 0xFFFF);
        sum += 17;
        sum += 8 + len;
        int c = checksum(p, 20, 8 + len, sum);
        if (c == 0) c = 0xFFFF;
        putShort(p, 26, c);
        return p;
    }

    private static int checksum(byte[] data, int off, int len, long sum) {
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) sum += (data[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }

    private static void close(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) { }
    }
}
