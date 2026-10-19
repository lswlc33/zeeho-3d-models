package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SNIHostName;

/**
 * 方案 3 的本地代理：VPN 把官方 App 的 HTTP(S) 流量指到 127.0.0.1 的这个小服务。
 *
 * 只对 h5.zeehoev.com 做 TLS 中间人（自签名证书），其他 HTTPS 盲转发。
 * 目标请求是 /cfmotoservermine/H5/baseInfo：从请求头拿 token，从响应 JSON/HTML 里拿 userId。
 *
 * 为什么能不解密：官方 App 对 h5 这条链路不校验系统 CA（用户已验证）。
 * 如果哪天它改成校验证书，这里会握手失败，服务会把错误交给页面，不要静默。
 */
final class CaptureProxy {

    private static final String TAG = "ZeehoWebNext";
    private static final String TARGET_HOST = "h5.zeehoev.com";
    private static final int MAX_HEAD = 64 * 1024;
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final char[] PASS = "zeeho".toCharArray();

    interface Callback {
        void onCaptured(String token, String uid);
        void onError(String message);
    }

    private final Context app;
    private final Callback cb;
    private final AtomicBoolean captured = new AtomicBoolean();
    private volatile boolean stopped;
    /** 官方 App 不认本地证书时置位：后续 h5 的 CONNECT 直接盲转发，至少让页面能打开。 */
    private volatile boolean mitmFailed;
    private ServerSocket server;
    private ExecutorService pool;
    private SSLContext serverTls;

    CaptureProxy(Context c, Callback cb) {
        this.app = c.getApplicationContext();
        this.cb = cb;
    }

    /** 启动本地代理并返回端口；调用方随后把它交给 VpnService.Builder.setHttpProxy。 */
    int start() throws Exception {
        serverTls = buildServerTls();
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        pool = Executors.newCachedThreadPool();
        Thread t = new Thread(this::acceptLoop, "capture-proxy");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "capture proxy on 127.0.0.1:" + server.getLocalPort());
        return server.getLocalPort();
    }

    void stop() {
        stopped = true;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    private SSLContext buildServerTls() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = app.getResources().openRawResource(R.raw.zeeho_capture)) {
            ks.load(in, PASS);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (Exception e) {
                if (!stopped) Log.w(TAG, "capture accept failed", e);
                return;
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(20_000);
            BufferedInputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            String head = readHead(in);
            if (head.isEmpty()) {
                close(client);
                return;
            }
            String first = head.split("\r\n", 2)[0];
            String[] parts = first.split(" ");
            if (parts.length < 2) {
                close(client);
                return;
            }
            if (!"CONNECT".equalsIgnoreCase(parts[0])) {
                // WebView 的网页请求是明文 HTTP（代理形式是绝对 URL）：必须转发，
                // 否则官方 App 的 H5 页会直接打不开；token/userId 往往就在这个 URL 里。
                handlePlain(client, in, out, head);
                return;
            }
            String hostPort = parts[1];
            String host = hostPort;
            int port = 443;
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0) {
                host = hostPort.substring(0, colon);
                try { port = Integer.parseInt(hostPort.substring(colon + 1)); } catch (Exception ignored) { }
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("ISO-8859-1"));
            out.flush();
            if (TARGET_HOST.equalsIgnoreCase(host)) {
                // 已经从明文网页 URL/请求头拿到凭据后，不再 MITM：盲转发，页面才能正常加载。
                if (captured.get() || mitmFailed) {
                    tunnel(client, host, port);
                    return;
                }
                try {
                    mitm(client, host, port);
                } catch (javax.net.ssl.SSLException e) {
                    // 官方 App 校验证书时先别把整轮判死：标记后后续连接盲转发，
                    // 至少 H5 页面能打开；能不能抓到就看明文那次有没有带 token/userId。
                    Log.w(TAG, "capture TLS handshake failed; fallback to blind tunnel", e);
                    mitmFailed = true;
                    close(client);
                }
            } else {
                tunnel(client, host, port);
            }
        } catch (Exception e) {
            if (!stopped) Log.w(TAG, "capture connection failed: " + e.getMessage());
            close(client);
        }
    }

    /**
     * 明文 HTTP 代理请求（WebView 的网页导航就是这种：请求行是绝对 URL）。
     * 原样转发到目标 80 端口；如果就是 h5 的 baseInfo 页面，从 **请求 URL/头** 里拿
     * token/userId（服务端随后会 301 到 HTTPS，那一跳不再需要解密）。
     */
    private void handlePlain(Socket client, BufferedInputStream in, OutputStream out, String head) {
        Socket up = null;
        try {
            Req req = Req.parse(head);
            URL u = new URL(req.path);
            if (!"http".equalsIgnoreCase(u.getProtocol())) {
                close(client);
                return;
            }
            String host = u.getHost();
            if (host == null || host.isEmpty()) {
                close(client);
                return;
            }
            int port = u.getPort() > 0 ? u.getPort() : 80;
            String path = u.getFile();
            if (path == null || path.isEmpty()) path = "/";
            byte[] reqBody = readRequestBody(in, req.headers);

            up = new Socket(host, port);
            up.setSoTimeout(20_000);
            Req fwd = new Req();
            fwd.method = req.method;
            fwd.path = path;
            fwd.headers.putAll(req.headers);
            writeRequest(up.getOutputStream(), host, fwd, reqBody);

            BufferedInputStream uin = new BufferedInputStream(up.getInputStream());
            String respHead = readHead(uin);
            if (respHead.isEmpty()) {
                close(client);
                return;
            }
            Resp resp = Resp.parse(respHead);
            byte[] respBody = readBody(uin, resp.headers);
            // 先把 301/页面回给 WebView，再报抓取成功：服务端还有 10 秒才拆代理，
            // WebView 有时间跟着 Location 走完 HTTPS。
            writeResponse(out, resp, respBody);
            out.flush();

            if (TARGET_HOST.equalsIgnoreCase(host)
                && path.toLowerCase(Locale.US).contains("/h5/baseinfo")) {
                String token = extractToken(req, resp, respBody);
                String uid = extractUid(req, resp, respBody);
                if (token != null && !token.isEmpty() && uid != null && !uid.isEmpty()) {
                    if (captured.compareAndSet(false, true)) {
                        Log.i(TAG, "capture success from plain http, uid=" + uid);
                        cb.onCaptured(token, uid);
                    }
                } else {
                    Log.w(TAG, "plain baseInfo missing token/uid; path=" + path
                        + " body=" + preview(respBody));
                }
            }
        } catch (Exception e) {
            if (!stopped) Log.w(TAG, "capture plain http failed: " + e.getMessage());
        } finally {
            close(up);
            close(client);
        }
    }

    private void mitm(Socket client, String host, int port) throws Exception {
        SSLSocket tls = (SSLSocket) serverTls.getSocketFactory()
            .createSocket(client, host, port, true);
        tls.setUseClientMode(false);
        SSLParameters sp = tls.getSSLParameters();
        try { sp.setApplicationProtocols(new String[]{"http/1.1"}); } catch (Exception ignored) { }
        tls.setSSLParameters(sp);
        tls.setSoTimeout(20_000);
        tls.startHandshake();

        BufferedInputStream cin = new BufferedInputStream(tls.getInputStream());
        OutputStream cout = tls.getOutputStream();
        String reqHead = readHead(cin);
        if (reqHead.isEmpty()) { close(tls); return; }
        Req req = Req.parse(reqHead);
        byte[] reqBody = readRequestBody(cin, req.headers);

        SSLSocket upstream = connectUpstream(host, port);
        OutputStream uout = upstream.getOutputStream();
        writeRequest(uout, host, req, reqBody);

        BufferedInputStream uin = new BufferedInputStream(upstream.getInputStream());
        String respHead = readHead(uin);
        if (respHead.isEmpty()) { close(upstream); close(tls); return; }
        Resp resp = Resp.parse(respHead);
        byte[] respBody = readBody(uin, resp.headers);

        if (req.path != null && req.path.contains("/H5/baseInfo")
            && resp.code >= 200 && resp.code < 300) {
            String token = extractToken(req, resp, respBody);
            String uid = extractUid(req, resp, respBody);
            if (token != null && !token.isEmpty() && uid != null && !uid.isEmpty()) {
                if (captured.compareAndSet(false, true)) {
                    Log.i(TAG, "capture success, uid=" + uid);
                    cb.onCaptured(token, uid);
                    stopped = true;
                    try { server.close(); } catch (Exception ignored) { }
                }
            } else {
                Log.w(TAG, "baseInfo captured but token/uid missing; token="
                    + (token != null && !token.isEmpty() ? "yes" : "no")
                    + " uid=" + (uid != null && !uid.isEmpty() ? "yes" : "no")
                    + " body=" + preview(respBody));
            }
        }

        // 原样回给客户端（已去掉 chunked，补上 Content-Length）。
        writeResponse(cout, resp, respBody);
        cout.flush();
        close(upstream);
        close(tls);
    }

    private SSLSocket connectUpstream(String host, int port) throws Exception {
        SSLSocket up = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        up.connect(new InetSocketAddress(host, port), 12_000);
        up.setSoTimeout(20_000);
        SSLParameters p = up.getSSLParameters();
        try {
            p.setServerNames(java.util.Collections.singletonList(new SNIHostName(host)));
            p.setApplicationProtocols(new String[]{"http/1.1"});
        } catch (Exception ignored) { }
        up.setSSLParameters(p);
        up.startHandshake();
        return up;
    }

    private void writeRequest(OutputStream out, String host, Req req, byte[] body) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(req.method).append(' ').append(req.path).append(" HTTP/1.1\r\n");
        boolean hasHost = false;
        for (Map.Entry<String, String> e : req.headers.entrySet()) {
            String k = e.getKey();
            String kl = k.toLowerCase(Locale.US);
            if (kl.equals("proxy-connection") || kl.equals("proxy-authorization")
                || kl.equals("connection") || kl.equals("keep-alive")
                || kl.equals("transfer-encoding") || kl.equals("te")
                || kl.equals("trailer") || kl.equals("upgrade")
                || kl.equals("accept-encoding")) {
                continue;
            }
            if (kl.equals("host")) hasHost = true;
            sb.append(k).append(": ").append(e.getValue()).append("\r\n");
        }
        if (!hasHost) sb.append("Host: ").append(host).append("\r\n");
        sb.append("Accept-Encoding: identity\r\n");
        sb.append("Connection: close\r\n");
        if (body != null && body.length > 0) sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
        if (body != null && body.length > 0) out.write(body);
        out.flush();
    }

    private void writeResponse(OutputStream out, Resp resp, byte[] body) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(resp.code).append(' ').append(resp.reason).append("\r\n");
        for (Map.Entry<String, String> e : resp.headers.entrySet()) {
            String kl = e.getKey().toLowerCase(Locale.US);
            if (kl.equals("transfer-encoding") || kl.equals("connection")
                || kl.equals("keep-alive") || kl.equals("content-length")) {
                continue;
            }
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
        if (body != null && body.length > 0) out.write(body);
    }

    private void tunnel(Socket client, String host, int port) {
        Socket upstream = null;
        try {
            upstream = new Socket(host, port);
            upstream.setSoTimeout(20_000);
            final Socket up = upstream;
            final Socket cl = client;
            Thread t = new Thread(() -> copy(cl, up), "capture-tunnel-up");
            t.setDaemon(true);
            t.start();
            copy(up, cl);
        } catch (Exception e) {
            Log.w(TAG, "capture tunnel failed: " + e.getMessage());
        } finally {
            close(upstream);
            close(client);
        }
    }

    private void copy(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            close(from);
            close(to);
        }
    }

    // ---------------- HTTP 解析 ----------------

    private static final class Req {
        String method = "GET";
        String path = "";
        final Map<String, String> headers = new HashMap<>();

        static Req parse(String head) {
            Req r = new Req();
            String[] lines = head.split("\r\n");
            if (lines.length == 0) return r;
            String[] p = lines[0].split(" ");
            if (p.length >= 2) {
                r.method = p[0];
                r.path = p[1];
            }
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) r.headers.put(lines[i].substring(0, c).trim(), lines[i].substring(c + 1).trim());
            }
            return r;
        }

        String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return "";
        }
    }

    private static final class Resp {
        int code = 0;
        String reason = "";
        final Map<String, String> headers = new HashMap<>();

        static Resp parse(String head) {
            Resp r = new Resp();
            String[] lines = head.split("\r\n");
            if (lines.length > 0) {
                String[] p = lines[0].split(" ", 3);
                try { r.code = Integer.parseInt(p[1]); } catch (Exception ignored) { }
                if (p.length > 2) r.reason = p[2];
            }
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) r.headers.put(lines[i].substring(0, c).trim(), lines[i].substring(c + 1).trim());
            }
            return r;
        }

        String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return "";
        }
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) >= 0) {
            out.write(b);
            if (out.size() > MAX_HEAD) throw new Exception("header too large");
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) break;
            } else state = 0;
        }
        return out.toString("ISO-8859-1");
    }

    private static byte[] readRequestBody(InputStream in, Map<String, String> headers) throws Exception {
        // 请求没有 Content-Length/Transfer-Encoding 就是没有 body；不能像响应那样读到 EOF，
        // 否则 GET 会一直等到超时。
        boolean hasLen = false;
        boolean chunked = false;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("content-length")) hasLen = true;
            if (e.getKey().equalsIgnoreCase("transfer-encoding")
                && e.getValue().toLowerCase(Locale.US).contains("chunked")) chunked = true;
        }
        if (!hasLen && !chunked) return new byte[0];
        return readBody(in, headers);
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws Exception {
        String len = null;
        String te = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("content-length")) len = e.getValue();
            if (e.getKey().equalsIgnoreCase("transfer-encoding")) te = e.getValue();
        }
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            return readChunked(in);
        }
        if (len != null) {
            int n;
            try { n = Integer.parseInt(len.trim()); } catch (Exception e) { return new byte[0]; }
            if (n <= 0) return new byte[0];
            if (n > MAX_BODY) n = MAX_BODY;
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) break;
                off += r;
            }
            return off == n ? buf : java.util.Arrays.copyOf(buf, off);
        }
        // 没有长度（GET 请求体通常为空；响应若到这里说明 Connection: close）
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) break;
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            if (line == null) break;
            int semi = line.indexOf(';');
            String hex = (semi >= 0 ? line.substring(0, semi) : line).trim();
            int n;
            try { n = Integer.parseInt(hex, 16); } catch (Exception e) { break; }
            if (n <= 0) { readLine(in); break; }
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) break;
                off += r;
            }
            out.write(buf, 0, off);
            readLine(in);
            if (out.size() > MAX_BODY) break;
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') out.write(b);
            if (out.size() > 8192) break;
        }
        if (b < 0 && out.size() == 0) return null;
        return out.toString("ISO-8859-1");
    }

    // ---------------- 提取 token / userId ----------------

    private static String extractToken(Req req, Resp resp, byte[] body) {
        String auth = req.header("authorization");
        if (auth != null && !auth.isEmpty()) {
            auth = auth.trim().replaceFirst("(?i)^bearer\\s+", "");
            if (!auth.isEmpty()) return auth;
        }
        // WebView.loadUrl(url, headers) 也可能把 token 放在自定义头里。
        for (String k : new String[]{"token", "access_token", "satoken", "x-token"}) {
            String v = req.header(k);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        // 有些 h5 页面把 token 放在 cookie 或 URL query 里，不一定是 Authorization。
        String cookie = req.header("cookie");
        if (cookie != null) {
            Matcher m = Pattern.compile("(?i)(?:^|;\\s*)(?:token|access_token|satoken)=([^;]+)").matcher(cookie);
            if (m.find()) return decode(m.group(1));
        }
        Matcher q = Pattern.compile("[?&](?:token|access_token|accessToken|satoken)=([^&]+)",
            Pattern.CASE_INSENSITIVE).matcher(req.path == null ? "" : req.path);
        if (q.find()) return decode(q.group(1));
        String fromBody = findInBody(body, resp.header("content-encoding"),
            new String[]{"token", "access_token", "accessToken", "h5Token", "h5_token"});
        if (fromBody != null && !fromBody.isEmpty()) return fromBody;
        return "";
    }

    private static String decode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String extractUid(Req req, Resp resp, byte[] body) {
        // 明文网页的 URL 往往直接带 userId（这就是 HttpCanary 无证书能看到的原因）。
        String path = req.path == null ? "" : req.path;
        Matcher q = Pattern.compile("[?&](?:userId|user_id|uid|userid|accountId|account_id)=([^&]+)",
            Pattern.CASE_INSENSITIVE).matcher(path);
        if (q.find()) return decode(q.group(1));
        for (String k : new String[]{"userId", "user_id", "uid", "accountId"}) {
            String v = req.header(k);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        String reqCookie = req.header("cookie");
        if (reqCookie != null) {
            Matcher m = Pattern.compile("(?i)(?:userId|user_id|uid)=([^;,\\s]+)").matcher(reqCookie);
            if (m.find()) return decode(m.group(1));
        }
        // baseInfo 的 data 是用户档案：内部用户 id 在 data.id（实测 ...353），不是 bindingUserId。
        // id 放最后当兜底；前面的专用键优先。
        String fromBody = findInBody(body, resp.header("content-encoding"),
            new String[]{"userId", "user_id", "uid", "userNo", "memberId", "id"});
        if (fromBody != null && !fromBody.isEmpty()) return fromBody;
        String cookie = resp.header("set-cookie");
        if (cookie != null) {
            Matcher m = Pattern.compile("(?i)(?:userId|user_id|uid)=([^;,\\s]+)").matcher(cookie);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    private static String findInBody(byte[] body, String encoding, String[] keys) {
        if (body == null || body.length == 0) return null;
        byte[] data = body;
        if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = gz.read(buf)) > 0) out.write(buf, 0, n);
                data = out.toByteArray();
            } catch (Exception ignored) {
                return null;
            }
        }
        String text;
        try { text = new String(data, "UTF-8"); } catch (Exception e) { return null; }
        // 先按 JSON 找；不是 JSON 再退回正则（h5 页面可能把值写在 JS 里）。
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                Object root = trimmed.startsWith("{") ? new JSONObject(trimmed) : new JSONArray(trimmed);
                for (String key : keys) {
                    String v = findJson(root, key);
                    if (v != null && !v.isEmpty()) return v;
                }
            } catch (Exception ignored) {
            }
        }
        for (String key : keys) {
            Matcher m = Pattern.compile("[\"']?" + Pattern.quote(key)
                + "[\"']?\\s*[:=]\\s*[\"']([^\"']{4,})[\"']").matcher(text);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String findJson(Object o, String key) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            if (j.has(key)) {
                Object v = j.opt(key);
                if (v != null && v != JSONObject.NULL) {
                    String s = String.valueOf(v);
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
                }
            }
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = findJson(j.opt(k), key);
                if (v != null && !v.isEmpty()) return v;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String v = findJson(a.opt(i), key);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return null;
    }

    private static String preview(byte[] body) {
        if (body == null) return "";
        try { return new String(body, 0, Math.min(body.length, 300), "UTF-8"); } catch (Exception e) { return ""; }
    }

    private static void close(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) { }
    }
}
