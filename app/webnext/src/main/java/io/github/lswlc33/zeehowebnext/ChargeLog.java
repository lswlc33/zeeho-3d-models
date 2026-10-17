package io.github.lswlc33.zeehowebnext;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 原生充电采样。网页版把采样写在 localStorage（只在页面打开时攒），后台服务需要在页面不打开时
 * 也留下记录，所以这里按 VIN 存一份到 files/charge_log.json。
 *
 * 采样的字段与网页 chargelog.js 对齐（t/soc/v/a/w/cf），只用于后台通知里按当前增速估一个时间；
 * 真正的拟合仍是网页版更完整。每段充电开始时清空该车的旧点，避免把上次的斜率拿来用。
 */
final class ChargeLog {

    private static final String FILE = "charge_log.json";
    private static final int MAX_POINTS = 600;
    private static final long MIN_GAP_MS = 20_000L;
    private static final long WINDOW_MS = 60 * 60 * 1000L;

    private ChargeLog() {
    }

    static synchronized void startSession(Context c, String vin) {
        if (vin == null || vin.isEmpty()) return;
        JSONObject all = read(c);
        try {
            all.put(vin, new JSONArray());
            write(c, all);
        } catch (Exception ignored) {
        }
    }

    static synchronized void append(Context c, String vin, int soc, int voltage, int current, int chargedMin) {
        if (vin == null || vin.isEmpty() || soc < 0 || soc > 100) return;
        JSONObject all = read(c);
        try {
            JSONArray a = all.optJSONArray(vin);
            if (a == null) a = new JSONArray();
            long now = System.currentTimeMillis();
            JSONObject last = a.length() > 0 ? a.optJSONObject(a.length() - 1) : null;
            if (last != null && now - last.optLong("t", 0) < MIN_GAP_MS) return;
            JSONObject p = new JSONObject();
            p.put("t", now);
            p.put("soc", soc);
            p.put("v", voltage);
            p.put("a", current);
            p.put("w", voltage >= 0 && current >= 0 ? Math.round(voltage * current) : -1);
            p.put("cf", chargedMin);
            a.put(p);
            while (a.length() > MAX_POINTS) a.remove(0);
            all.put(vin, a);
            write(c, all);
        } catch (Exception ignored) {
        }
    }

    /**
     * 按最近 60 分钟的电量增速估"还要多少分钟"。样本不足/几乎没涨就返回 -1，
     * 调用方据此改为显示"已充 X"（用户 2026-10-16 的口径）。
     */
    static synchronized int estimateMinutes(Context c, String vin, int soc) {
        if (vin == null || vin.isEmpty() || soc < 0 || soc >= 100) return -1;
        JSONArray a = read(c).optJSONArray(vin);
        if (a == null || a.length() < 3) return -1;
        long now = System.currentTimeMillis();
        long t0 = 0;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null) continue;
            long t = p.optLong("t", 0);
            if (now - t > WINDOW_MS) continue;
            int s = p.optInt("soc", -1);
            if (s < 0) continue;
            if (t0 == 0) t0 = t;
            double x = (t - t0) / 60000.0;
            sx += x;
            sy += s;
            sxx += x * x;
            sxy += x * s;
            n++;
        }
        if (n < 3) return -1;
        double den = n * sxx - sx * sx;
        if (den <= 0.0001) return -1;
        double slope = (n * sxy - sx * sy) / den;   // %/分钟
        if (slope <= 0.02) return -1;
        int min = (int) Math.round((100 - soc) / slope);
        if (min <= 0 || min > 12 * 60) return -1;
        return min;
    }

    private static JSONObject read(Context c) {
        File f = new File(c.getApplicationContext().getFilesDir(), FILE);
        if (!f.isFile()) return new JSONObject();
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void write(Context c, JSONObject all) {
        File f = new File(c.getApplicationContext().getFilesDir(), FILE);
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(all.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        f.delete();
        if (!tmp.renameTo(f)) tmp.delete();
    }
}
