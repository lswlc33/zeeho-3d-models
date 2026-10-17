package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页静态快照缓存。
 *
 * 线上产物是单个自包含 index.html（约 640KB）+ manifest + 4 个图标 + version.json；
 * 每次发布 version.json 的 build 都会变。壳只缓存这些同源静态资源，接口数据、字体、
 * 车辆图片、地图瓦片仍然走网络。
 *
 * 目录：
 *   files/webcache/snapshots/<build-id>/{index.html,version.json,manifest.webmanifest,icons/,meta.json}
 *   files/webcache/.tmp-<build-id>/   下载中的临时目录
 *
 * prefs 里存的是目录 id（build 做过字符替换）；meta.json 里存原始 build，用于和线上比对。
 */
final class WebCache {

    private static final String TAG = "ZeehoWebNext";
    private static final String SP = "webnext";
    private static final String K_ACTIVE = "active_id";
    private static final String K_PENDING = "pending_id";
    private static final String META = "meta.json";
    private static final String INDEX = "index.html";
    private static final String VERSION = "version.json";
    private static final String MANIFEST = "manifest.webmanifest";
    private static final String[] ICONS = {
        "icons/icon-192.png",
        "icons/icon-512.png",
        "icons/icon-maskable-512.png",
        "icons/apple-touch-icon.png",
    };
    private static final Pattern BUILD_META = Pattern.compile(
        "<meta\\s+name=[\"']build[\"']\\s+content=[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE);

    static final class Snapshot {
        final File dir;
        final String build;

        Snapshot(File dir, String build) {
            this.dir = dir;
            this.build = build;
        }
    }

    interface Progress {
        void onProgress(int percent, long done, long total);
    }

    interface Callback {
        void onSuccess();

        void onError(String message);
    }

    interface UpdateCallback {
        void onUpdateReady(String build);

        void onNoUpdate();

        void onError(String message);
    }

    private final Context app;
    private final String baseUrl;
    private final File root;
    private final File snapshots;
    private final SharedPreferences prefs;
    private final ExecutorService worker;
    private volatile Snapshot active;
    private volatile Snapshot pending;

    WebCache(Context context, String baseUrl) {
        this.app = context.getApplicationContext();
        this.baseUrl = baseUrl;
        this.root = new File(app.getFilesDir(), "webcache");
        this.snapshots = new File(root, "snapshots");
        this.prefs = app.getSharedPreferences(SP, Context.MODE_PRIVATE);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "web-cache");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        this.worker = Executors.newSingleThreadExecutor(tf);
        this.snapshots.mkdirs();
        this.active = read(prefs.getString(K_ACTIVE, null));
        this.pending = read(prefs.getString(K_PENDING, null));
        if (active == null) prefs.edit().remove(K_ACTIVE).apply();
        if (pending == null) prefs.edit().remove(K_PENDING).apply();
    }

    Snapshot active() {
        return active;
    }

    boolean hasActive() {
        return active != null;
    }

    boolean hasPending() {
        Snapshot p = pending;
        Snapshot a = active;
        return p != null && (a == null || !p.build.equals(a.build));
    }

    /** 冷启动把上次下载好的 pending 提为 active；返回是否切换。 */
    boolean promotePendingAtStartup() {
        Snapshot p = pending;
        if (p == null) return false;
        if (active != null && p.build.equals(active.build)) {
            prefs.edit().remove(K_PENDING).apply();
            pending = null;
            return false;
        }
        active = p;
        pending = null;
        prefs.edit().putString(K_ACTIVE, safeId(p.build)).remove(K_PENDING).apply();
        Log.i(TAG, "promote pending at startup: " + p.build);
        return true;
    }

    /** 空闲刷新把 pending 提为 active；返回新快照，失败返回 null。 */
    Snapshot promotePendingNow() {
        Snapshot p = pending;
        if (p == null || !valid(p.dir)) return null;
        active = p;
        pending = null;
        prefs.edit().putString(K_ACTIVE, safeId(p.build)).remove(K_PENDING).apply();
        cleanupOld();
        Log.i(TAG, "promote pending now: " + p.build);
        return p;
    }

    /** 首次启动/缓存损坏：下载并激活一个完整快照。 */
    void initializeAsync(Progress progress, Callback cb) {
        worker.execute(() -> {
            try {
                String versionJson = fetchText(versionUrl(), 4096);
                String build = parseBuild(versionJson);
                if (build == null) throw new IOException("version.json 没有 build");
                Snapshot s = downloadSnapshot(build, versionJson, progress);
                active = s;
                pending = null;
                prefs.edit().putString(K_ACTIVE, safeId(build)).remove(K_PENDING).apply();
                cleanupOld();
                cb.onSuccess();
            } catch (Exception e) {
                Log.w(TAG, "initialize failed", e);
                cb.onError(readable(e));
            }
        });
    }

    /** 后台检查更新；远端 build 与 active/pending 都不同才下载。 */
    void checkForUpdateAsync(UpdateCallback cb) {
        worker.execute(() -> {
            try {
                String versionJson = fetchText(versionUrl(), 4096);
                String build = parseBuild(versionJson);
                if (build == null) {
                    cb.onNoUpdate();
                    return;
                }
                Snapshot a = active;
                if (a != null && build.equals(a.build)) {
                    clearPendingIfDifferent(build);
                    cb.onNoUpdate();
                    return;
                }
                Snapshot p = pending;
                if (p != null && build.equals(p.build) && valid(p.dir)) {
                    cb.onUpdateReady(build);
                    return;
                }
                Snapshot s = downloadSnapshot(build, versionJson, null);
                // 下载期间 active 可能被空闲刷新换掉，落地前再判一次
                if (active != null && build.equals(active.build)) {
                    deleteTree(s.dir);
                    cb.onNoUpdate();
                    return;
                }
                if (pending != null && !pending.build.equals(build)) deleteTree(pending.dir);
                pending = s;
                prefs.edit().putString(K_PENDING, safeId(build)).apply();
                cleanupOld();
                Log.i(TAG, "update ready: " + build);
                cb.onUpdateReady(build);
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
                cb.onError(readable(e));
            }
        });
    }

    void shutdown() {
        worker.shutdownNow();
    }

    // ---------------- WebView 拦截 ----------------

    WebResourceResponse intercept(WebResourceRequest req) {
        if (req == null || !"GET".equalsIgnoreCase(req.getMethod())) return null;
        Uri u = req.getUrl();
        if (u == null) return null;
        Uri b = Uri.parse(baseUrl);
        if (!eq(u.getScheme(), b.getScheme()) || !eq(u.getHost(), b.getHost()) || port(u) != port(b)) {
            return null;
        }
        String bp = b.getPath() == null ? "/" : b.getPath();
        if (!bp.endsWith("/")) bp += "/";
        String p = u.getPath() == null ? "/" : u.getPath();
        if (!p.startsWith(bp)) return null;
        String rel = p.substring(bp.length());

        if (rel.isEmpty() || INDEX.equals(rel)) {
            Snapshot s = active;
            if (s == null) return null;
            return fileResponse(new File(s.dir, INDEX), "text/html");
        }
        if (VERSION.equals(rel)) {
            Snapshot s = active;
            if (s == null) return null;
            return fileResponse(new File(s.dir, VERSION), "application/json");
        }
        if ("sw.js".equals(rel)) {
            // 壳不用 Service Worker：它网络优先，而且不经过 shouldInterceptRequest，
            // 注册上会把本地快照废掉。直接 404 让注册失败（页面侧也加了壳判断）。
            return textResponse(404, "Not Found", "application/javascript", "");
        }
        if (MANIFEST.equals(rel)) {
            Snapshot s = active;
            if (s != null) {
                WebResourceResponse r = fileResponse(
                    new File(s.dir, MANIFEST), "application/manifest+json");
                if (r != null) return r;
            }
            return null;
        }
        if (rel.startsWith("icons/")) {
            Snapshot s = active;
            if (s != null) {
                File f = new File(s.dir, rel);
                if (f.isFile()) return fileResponse(f, "image/png");
            }
        }
        // 其他同源子资源放行网络：站点以后加新文件时壳不至于白屏
        return null;
    }

    private WebResourceResponse fileResponse(File f, String mime) {
        if (f == null || !f.isFile()) return null;
        try {
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            h.put("Content-Length", String.valueOf(f.length()));
            String encoding = mime.startsWith("image/") ? null : "utf-8";
            return new WebResourceResponse(mime, encoding, 200, "OK", h, new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    private WebResourceResponse textResponse(int code, String reason, String mime, String body) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        h.put("Content-Length", String.valueOf(bytes.length));
        return new WebResourceResponse(mime, "utf-8", code, reason, h, new ByteArrayInputStream(bytes));
    }

    // ---------------- 下载 / 校验 ----------------

    private Snapshot downloadSnapshot(String build, String versionJson, Progress progress) throws IOException {
        cleanupTmp();
        String id = safeId(build);
        File tmp = new File(root, ".tmp-" + id);
        File finalDir = new File(snapshots, id);
        try {
            deleteTree(tmp);
            if (!tmp.mkdirs()) throw new IOException("无法创建缓存目录");
            deleteTree(finalDir);

            long indexBytes = 0;
            String indexUrl = baseUrl + "index.html?build=" + Uri.encode(build);
            for (int attempt = 0; attempt < 2; attempt++) {
                File out = new File(tmp, INDEX);
                long[] total = new long[1];
                downloadTo(indexUrl + (attempt == 0 ? "" : "&r=" + (attempt + 1)), out, (done, len) -> {
                    total[0] = len;
                    if (progress != null) progress.onProgress(percent(done, len, 0, 92), done, len);
                });
                indexBytes = total[0];
                String html = readAll(out);
                String metaBuild = parseBuildMeta(html);
                if (html.length() > 100_000 && html.contains("<title>极核面板</title>")
                    && build.equals(metaBuild)) {
                    break;
                }
                out.delete();
                if (attempt == 1) throw new IOException("页面校验失败（build 不一致或内容异常）");
            }

            // manifest / 图标是可选资源；失败不影响页面本身
            downloadOptional(tmp, MANIFEST,
                "manifest.webmanifest?build=" + Uri.encode(build), progress, 92, 94);
            int step = Math.max(1, 6 / ICONS.length);
            for (int i = 0; i < ICONS.length; i++) {
                int base = 94 + i * step;
                downloadOptional(tmp, ICONS[i],
                    ICONS[i] + "?build=" + Uri.encode(build), progress, base, Math.min(100, base + step));
            }

            try (OutputStream os = new FileOutputStream(new File(tmp, VERSION))) {
                os.write(versionJson.getBytes(StandardCharsets.UTF_8));
            }
            JSONObject meta = new JSONObject();
            meta.put("build", build);
            meta.put("createdAt", System.currentTimeMillis());
            try (OutputStream os = new FileOutputStream(new File(tmp, META))) {
                os.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (!tmp.renameTo(finalDir)) throw new IOException("缓存目录改名失败");
            Log.i(TAG, "snapshot ready: " + build + " indexBytes=" + indexBytes);
            return new Snapshot(finalDir, build);
        } catch (Exception e) {
            deleteTree(tmp);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
    }

    private void downloadOptional(File dir, String rel, String url, Progress progress, int base, int span) {
        File out = new File(dir, rel);
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            downloadTo(baseUrl + url, out, (done, len) -> {
                if (progress != null) progress.onProgress(percent(done, len, base, span), done, len);
            });
        } catch (Exception e) {
            out.delete();
            Log.w(TAG, "optional asset failed: " + rel, e);
        }
    }

    private void downloadTo(String url, File out, ProgressFn fn) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "ZeehoWebNext");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long len = c.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                long last = 0;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (fn != null && (now - last >= 80 || done == len)) {
                        last = now;
                        fn.onBytes(done, len);
                    }
                }
                if (fn != null) fn.onBytes(done, len);
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String fetchText(String url, int max) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "ZeehoWebNext");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > max) break;
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String versionUrl() {
        return baseUrl + "version.json?_=" + System.currentTimeMillis();
    }

    private static String parseBuild(String json) {
        try {
            return new JSONObject(json).optString("build", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseBuildMeta(String html) {
        Matcher m = BUILD_META.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static int percent(long done, long total, int base, int span) {
        if (total <= 0 || done <= 0) return base;
        int p = (int) Math.min(100, done * 100 / total);
        return Math.min(base + span, base + p * span / 100);
    }

    private Snapshot read(String id) {
        if (id == null || id.isEmpty()) return null;
        File dir = new File(snapshots, id);
        if (!valid(dir)) return null;
        String build = id;
        try {
            JSONObject meta = new JSONObject(readAll(new File(dir, META)));
            build = meta.optString("build", id);
        } catch (Exception ignored) {
            // meta 坏了但文件在：用目录名当 build 也能跑
        }
        return new Snapshot(dir, build);
    }

    private boolean valid(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File idx = new File(dir, INDEX);
        File ver = new File(dir, VERSION);
        File meta = new File(dir, META);
        return idx.isFile() && idx.length() > 100_000
            && ver.isFile() && ver.length() > 8
            && meta.isFile();
    }

    private void clearPendingIfDifferent(String build) {
        Snapshot p = pending;
        if (p != null && !p.build.equals(build)) {
            deleteTree(p.dir);
            pending = null;
            prefs.edit().remove(K_PENDING).apply();
        }
    }

    private void cleanupTmp() {
        File[] fs = root.listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.getName().startsWith(".tmp-")) deleteTree(f);
    }

    /** 只保留 active/pending + 最新一个旧快照，避免目录无限增长。 */
    private void cleanupOld() {
        File[] fs = snapshots.listFiles();
        if (fs == null) return;
        String a = active == null ? "" : safeId(active.build);
        String p = pending == null ? "" : safeId(pending.build);
        List<File> old = new ArrayList<>();
        for (File f : fs) {
            if (!f.isDirectory()) continue;
            if (f.getName().equals(a) || f.getName().equals(p)) continue;
            old.add(f);
        }
        old.sort((x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        for (int i = 0; i < old.size(); i++) {
            if (i >= 1) deleteTree(old.get(i));
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static String safeId(String build) {
        return build.replaceAll("[^0-9A-Za-z._-]", "_");
    }

    private static String readable(Exception e) {
        // 界面只给用户看得懂的原因；完整堆栈在 initialize/update 的 Log.w 里
        if (e instanceof java.net.UnknownHostException
            || e instanceof java.net.ConnectException
            || e instanceof java.net.SocketTimeoutException
            || e instanceof java.io.EOFException) {
            return "网络连接失败";
        }
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "未知错误";
        if (m.startsWith("HTTP ")) return "服务器返回 " + m;
        if (m.contains("stream") || m.contains("Connection refused")
            || m.contains("Failed to connect")) {
            return "网络连接失败";
        }
        return m;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static int port(Uri u) {
        if (u.getPort() >= 0) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static String readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private interface ProgressFn {
        void onBytes(long done, long total);
    }
}
