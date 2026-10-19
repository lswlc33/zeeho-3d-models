package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 方案 3 的探针日志。
 *
 * 为什么不用 logcat：HyperOS 会吞第三方进程的 logcat 行，用户也不一定有 adb；
 * 抓到失败时需要在手机上直接看到「流量到底有没有进代理、进了之后是明文还是
 * HTTPS 盲转发、baseInfo 到底有没有命中、字段在哪」。
 *
 * 只写应用私有目录，页面通过 ZeehoShell.captureDiag() 读取并复制；
 * **不要记录 token 原文**，只记录来源和长度。
 */
final class CaptureLog {

    private static final String TAG = "ZeehoWebNext";
    private static final String FILE = "capture-diag.log";
    /** 环形上限：一次抓取 2 分钟，连接可能上百条，192KB 足够且不会无限长。 */
    private static final long MAX_BYTES = 192 * 1024L;
    private static final int READ_BYTES = 96 * 1024;
    private static final Object LOCK = new Object();

    private CaptureLog() {
    }

    /** 新的一轮抓取先清空，用户复现一次后拿到的就是这一轮的完整日志。 */
    static void begin(Context c, String note) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        synchronized (LOCK) {
            try {
                File f = new File(app.getFilesDir(), FILE);
                if (f.exists() && !f.delete()) {
                    // 删不掉就截断，不能把上一轮的日志混进来。
                    try (FileOutputStream out = new FileOutputStream(f, false)) {
                        out.write(new byte[0]);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String version = "";
        try {
            version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        add(app, "session", "===== v" + version + " sdk=" + Build.VERSION.SDK_INT
            + " " + note + " =====");
    }

    static void add(Context c, String tag, String message) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        String line;
        synchronized (LOCK) {
            line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " [" + tag + "] " + message;
            Log.i(TAG, "CAP " + line);
            try {
                File f = new File(app.getFilesDir(), FILE);
                if (f.exists() && f.length() > MAX_BYTES) trim(f);
                try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, true), "UTF-8")) {
                    w.write(line);
                    w.write('\n');
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 日志尾部；页面一次显示 96KB 足够，避免桥调用把 WebView 卡住。 */
    static String read(Context c) {
        if (c == null) return "";
        File f = new File(c.getApplicationContext().getFilesDir(), FILE);
        if (!f.exists() || f.length() == 0) return "";
        synchronized (LOCK) {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                long len = raf.length();
                long start = Math.max(0, len - READ_BYTES);
                raf.seek(start);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = raf.read(buf)) > 0) out.write(buf, 0, n);
                String text = out.toString("UTF-8");
                if (start > 0) {
                    int nl = text.indexOf('\n');
                    if (nl >= 0 && nl + 1 < text.length()) text = text.substring(nl + 1);
                }
                return text;
            } catch (Exception e) {
                return "读取日志失败：" + e.getMessage();
            }
        }
    }

    static void clear(Context c) {
        if (c == null) return;
        File f = new File(c.getApplicationContext().getFilesDir(), FILE);
        synchronized (LOCK) {
            try {
                if (f.exists()) f.delete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 只保留后一半，避免每次追加都扫全文件。 */
    private static void trim(File f) {
        try {
            long keep = MAX_BYTES / 2;
            long len = f.length();
            if (len <= keep) return;
            byte[] buf = new byte[(int) keep];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(len - keep);
                raf.readFully(buf);
            }
            // 从下一行开始，避免留下半行。
            int nl = 0;
            while (nl < buf.length && buf[nl] != '\n') nl++;
            int off = Math.min(nl + 1, buf.length);
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(buf, off, buf.length - off);
            }
        } catch (Exception ignored) {
        }
    }
}
