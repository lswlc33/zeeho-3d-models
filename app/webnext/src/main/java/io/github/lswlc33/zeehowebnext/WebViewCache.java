package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * WebView 的磁盘缓存上限。
 *
 * WebView 没有公开"缓存最大多少 MB"的 API，只能在进程启动、还没创建 WebView 的时候
 * 量一下 cache/WebView 下的 HTTP Cache；超过上限就整块删掉，让 Chromium 自己重建。
 * 刻意**不是每次启动清空**：地图瓦片、车图、字体都走 HTTP 缓存，每次清会让页面反复下载。
 *
 * 上限 32MB：一台车一个月的页面素材通常远小于这个数；真正会失控的是长期浏览地图/车图。
 * 页面快照（files/webcache）不在这里，删缓存不会影响离线启动。
 */
final class WebViewCache {

    private static final String TAG = "ZeehoWebNext";
    private static final long MAX_HTTP_CACHE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_DEPTH = 8;

    private WebViewCache() {
    }

    private static volatile Thread trimThread;

    /**
     * 启动时在后台量缓存（递归 stat 上千个文件，别压在主线程上）。
     * 创建 WebView 前用 awaitTrim() 等一下，尽量维持"先修剪、后创建"的顺序。
     */
    static void trimAsync(Context context) {
        if (trimThread != null) return;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> trim(app), "webview-cache-trim");
        trimThread = t;
        t.start();
    }

    /** 最多等 maxMs；超时就让它在后台继续（Chromium 对缺失的 HTTP Cache 会自己重建）。 */
    static void awaitTrim(long maxMs) {
        Thread t = trimThread;
        if (t == null) return;
        try {
            t.join(maxMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void trim(Context context) {
        if (context == null) return;
        try {
            File root = new File(context.getCacheDir(), "WebView");
            if (!root.isDirectory()) return;
            List<File> caches = new ArrayList<>();
            findHttpCaches(root, caches, 0);
            long total = 0;
            for (File f : caches) total += sizeOf(f);
            if (total <= MAX_HTTP_CACHE_BYTES) return;
            Log.i(TAG, "trim WebView HTTP cache: " + total + " bytes");
            for (File f : caches) deleteTree(f);
        } catch (Exception e) {
            Log.w(TAG, "trim WebView cache failed", e);
        }
    }

    private static void findHttpCaches(File dir, List<File> out, int depth) {
        if (dir == null || depth > MAX_DEPTH) return;
        if ("HTTP Cache".equals(dir.getName())) {
            out.add(dir);
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) if (f.isDirectory()) findHttpCaches(f, out, depth + 1);
    }

    private static long sizeOf(File f) {
        if (f == null) return 0;
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        if (kids == null) return 0;
        long n = 0;
        for (File k : kids) n += sizeOf(k);
        return n;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
