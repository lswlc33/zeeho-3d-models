package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 桌面微件用的车图缓存。
 *
 * 车图地址来自 vehicleHomePageV2 的 vehiclePicUrl（公开 OSS，不需要签名）。RemoteViews 不能直接
 * 加载网络图，所以在 Poller 的后台线程把图缩到微件尺寸后存到 filesDir；微件只读缓存，不碰网络。
 * 文件名按 VIN 区分，URL 变化才重新下载，下载失败保留旧图。
 */
final class WidgetCar {

    private static final String TAG = "ZeehoWebNext";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_H = 280;
    private static final int MAX_W = 420;
    /** 解码结果按「路径 + mtime + 长度」缓存：三套微件都绑定时，不必每次 bind 都重新解一张 420x280 的 PNG。 */
    private static Bitmap cached;
    private static String cachedKey = "";

    private WidgetCar() {
    }

    static synchronized Bitmap load(Context c, String vin) {
        File f = file(c, vin);
        if (!f.isFile() || f.length() == 0) return null;
        String key = f.getAbsolutePath() + "@" + f.lastModified() + "#" + f.length();
        if (cached != null && key.equals(cachedKey)) return cached;
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                cached = b;
                cachedKey = key;
            }
            return b;
        } catch (Exception e) {
            Log.w(TAG, "widget car decode failed", e);
            return null;
        }
    }

    static void ensure(Context c, String vin, String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return;
        File f = file(c, vin);
        File uf = urlFile(c, vin);
        String old = readText(uf);
        if (u.equals(old) && f.isFile() && f.length() > 0) return;

        try {
            byte[] raw = download(u);
            if (raw == null) return;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= MAX_W
                && bounds.outHeight / (sample * 2) >= MAX_H) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opt);
            if (bmp == null) return;

            float scale = Math.min(1f, Math.min((float) MAX_W / bmp.getWidth(),
                (float) MAX_H / bmp.getHeight()));
            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                    Math.max(1, Math.round(bmp.getWidth() * scale)),
                    Math.max(1, Math.round(bmp.getHeight() * scale)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return;
                out.getFD().sync();
            } finally {
                bmp.recycle();
            }
            if (f.exists() && !f.delete()) return;
            if (!tmp.renameTo(f)) {
                tmp.delete();
                return;
            }
            writeText(uf, u);
            Log.i(TAG, "widget car ready: " + f.length() + " bytes");
        } catch (Exception e) {
            Log.w(TAG, "widget car download failed", e);
        }
    }

    private static byte[] download(String spec) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(spec).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "ZeehoWebNext/1.3");
        conn.setRequestProperty("Accept", "image/*");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            int len = conn.getContentLength();
            if (len > MAX_BYTES) return null;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, len))) {
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return null;
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static File file(Context c, String vin) {
        return new File(c.getFilesDir(), "widget_car_" + key(vin) + ".png");
    }

    private static File urlFile(Context c, String vin) {
        return new File(c.getFilesDir(), "widget_car_" + key(vin) + ".url");
    }

    private static String key(String vin) {
        return Integer.toHexString((vin == null ? "" : vin).hashCode());
    }

    private static String readText(File f) {
        try {
            if (!f.isFile()) return "";
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeText(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
