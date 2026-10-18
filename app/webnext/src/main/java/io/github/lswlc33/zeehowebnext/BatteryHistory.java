package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * App 专属电量历史。
 *
 * 设计目标：后台每 60 秒记一条车况，保留 31 天，页面按 24h/3d/7d/30d 拉取统计。
 * 车机接口本身不提供历史曲线，只能由壳自己采样；没有网络/没有 token 时自然没有记录。
 *
 * 存储（files/battery/hist-<sha256(vin) 前 16 位>.bin）：
 *   [magic int]["ZBAT"][version int]
 *   之后是一串 chunk：
 *     [plainLen int][compLen int][ivLen int][iv 12B][AES-GCM 密文]
 *   chunk 内是记录明文（每 16 字节一条，ByteBuffer 默认大端）：
 *     long time / short 电压×10 / short 电流×10 / byte soc / byte 温度(未知=-128)
 *     / byte 充电状态(0/1/2) / byte flags
 *
 * 为什么这样存：
 *   - 固定 16 字节，一个月 1 分钟一条约 650KB，压缩后通常只有几百 KB；
 *   - 每 8 条一个 chunk，先 Deflate 再 AES-GCM，既压缩又加密，且进程被杀最多丢 8 分钟；
 *   - 密钥放 Android Keystore（不可导出），磁盘上不留明文 token/位置，文件名也不含 VIN；
 *   - 31 天保留期在写入时按需重写，页面只拿到降采样后的点（最多约 360 个），不会把整月原始数据塞进 JS 桥。
 *
 * 记录来源是 StatusFeed（页面每 30 秒同步一次 + 后台 Poller 每 60 秒一次），
 * 50 秒去重保证每分钟最多一条。充电/放电区间由记录里的状态与电流符号推导：
 * 充电 = state>0；放电 = 未充电且电流绝对值 >= 0.3A。电流符号保留原始方向，统计里取绝对值。
 */
final class BatteryHistory {

    private static final String TAG = "ZeehoWebNext";
    private static final int MAGIC = 0x5a424154;             // "ZBAT"
    private static final int VERSION = 1;
    private static final int REC_SIZE = 16;
    private static final int CHUNK_RECORDS = 8;
    private static final int MAX_CHUNK_RECORDS = 4096;
    private static final long RETENTION_MS = 31L * 24 * 60 * 60 * 1000;
    private static final long MIN_RECORD_GAP_MS = 50_000L;
    private static final long SESSION_GAP_MS = 15L * 60 * 1000;
    private static final long MAX_GAP_FOR_ENERGY_MS = 5L * 60 * 1000;
    private static final long REPORT_CACHE_MS = 60_000L;
    private static final long TRIM_GAP_MS = 24L * 60 * 60 * 1000;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final String KEY_ALIAS = "zeeho_battery_history_v1";

    private static final Object LOCK = new Object();
    private static String pendingVin = "";
    private static ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private static int pendingCount;
    private static long lastRecordAt;
    private static long lastTrimAt;
    private static SecretKey key;

    private static String cacheVin = "";
    private static String cacheRange = "";
    private static String cacheJson = "";
    private static long cacheAt;

    private BatteryHistory() {
    }

    /** StatusFeed 每处理一份车况调用一次；内部会按 50 秒去重。 */
    static void record(Context context, NativeStore.Status s, JSONObject j) {
        if (context == null || s == null || s.soc < 0 || s.voltage <= 0) return;
        Context c = context.getApplicationContext();
        String vin = NativeStore.vin(c);
        if (vin.isEmpty()) return;

        long t = s.updatedAt > 0 ? s.updatedAt : System.currentTimeMillis();
        synchronized (LOCK) {
            if (t <= lastRecordAt || t - lastRecordAt < MIN_RECORD_GAP_MS) return;
            if (!pendingVin.equals(vin)) {
                flushPendingLocked(c);
                pendingVin = vin;
            }
            double v = number(j, "voltageText", s.voltage);
            double a = number(j, "current", s.current);
            if (!(v > 0)) v = s.voltage;
            if (!Double.isFinite(a)) a = s.current;
            byte[] rec = encode(t, v, a, s.soc, s.charge);
            pending.write(rec, 0, rec.length);
            pendingCount++;
            lastRecordAt = t;
            cacheAt = 0;
            if (pendingCount >= CHUNK_RECORDS) {
                flushPendingLocked(c);
            }
            maybeTrimLocked(c, t);
        }
    }

    /** 页面退到后台/进程即将结束时把不足 8 条的尾块落盘。 */
    static void flush(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            flushPendingLocked(context.getApplicationContext());
        }
    }

    static String report(Context context, String vin, String range) {
        if (context == null || vin == null || vin.isEmpty()) {
            return emptyReport(range);
        }
        Context c = context.getApplicationContext();
        String r = normalizeRange(range);
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (r.equals(cacheRange) && vin.equals(cacheVin)
                && cacheAt > 0 && now - cacheAt < REPORT_CACHE_MS) {
                return cacheJson;
            }
            List<Rec> all = readAll(fileFor(c, vin));
            if (vin.equals(pendingVin) && pendingCount > 0) {
                all.addAll(decodeAll(pending.toByteArray()));
                Collections.sort(all, Comparator.comparingLong(x -> x.t));
            }
            String json = buildReport(all, r, now);
            cacheVin = vin;
            cacheRange = r;
            cacheJson = json;
            cacheAt = now;
            return json;
        }
    }

    // ---------------- 记录编解码 ----------------

    private static byte[] encode(long t, double v, double a, int soc, int state) {
        ByteBuffer b = ByteBuffer.allocate(REC_SIZE);
        b.putLong(t);
        b.putShort((short) clamp(Math.round(v * 10), Short.MIN_VALUE, Short.MAX_VALUE));
        b.putShort((short) clamp(Math.round(a * 10), Short.MIN_VALUE, Short.MAX_VALUE));
        b.put((byte) clamp(soc, 0, 100));
        b.put((byte) -128);                                      // 温度：目前没有稳定来源，标为未知
        b.put((byte) clamp(state, 0, 2));
        b.put((byte) 0);
        return b.array();
    }

    private static Rec decode(ByteBuffer b) {
        long t = b.getLong();
        double v = b.getShort() / 10.0;
        double a = b.getShort() / 10.0;
        int soc = b.get() & 0xff;
        int temp = b.get();
        int state = b.get() & 0xff;
        b.get();
        return new Rec(t, v, a, soc, temp, state);
    }

    private static List<Rec> decodeAll(byte[] plain) {
        List<Rec> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(plain);
        while (b.remaining() >= REC_SIZE) out.add(decode(b));
        return out;
    }

    private static double number(JSONObject j, String key, double fallback) {
        if (j == null || !j.has(key) || j.isNull(key)) return fallback;
        Object v = j.opt(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ---------------- 文件读写 ----------------

    private static File fileFor(Context c, String vin) {
        File dir = new File(c.getFilesDir(), "battery");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "battery history dir create failed");
        }
        return new File(dir, "hist-" + sha(vin).substring(0, 16) + ".bin");
    }

    private static void flushPendingLocked(Context c) {
        if (pendingCount <= 0) {
            pending.reset();
            return;
        }
        String vin = pendingVin.isEmpty() ? NativeStore.vin(c) : pendingVin;
        if (vin.isEmpty()) {
            pending.reset();
            pendingCount = 0;
            return;
        }
        try {
            appendChunk(fileFor(c, vin), pending.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "battery history write failed", e);
        } finally {
            pending = new ByteArrayOutputStream();
            pendingCount = 0;
        }
    }

    private static void appendChunk(File file, byte[] plain) throws Exception {
        if (plain.length == 0) return;
        byte[] compressed = deflate(plain);
        Enc enc = encrypt(compressed);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file, true);
             DataOutputStream out = new DataOutputStream(fos)) {
            if (file.length() == 0) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
            }
            out.writeInt(plain.length);
            out.writeInt(compressed.length);
            out.writeInt(enc.iv.length);
            out.write(enc.iv);
            out.write(enc.data);
            out.flush();
        }
    }

    private static List<Rec> readAll(File file) {
        List<Rec> out = new ArrayList<>();
        if (file == null || !file.isFile()) return out;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return out;
            while (true) {
                int plainLen;
                try {
                    plainLen = in.readInt();
                } catch (EOFException eof) {
                    break;
                }
                int compLen = in.readInt();
                int ivLen = in.readInt();
                if (plainLen <= 0 || plainLen > REC_SIZE * MAX_CHUNK_RECORDS
                    || compLen <= 0 || compLen > plainLen + 4096
                    || ivLen != 12) {
                    Log.w(TAG, "battery history chunk header invalid");
                    break;
                }
                byte[] iv = new byte[ivLen];
                in.readFully(iv);
                byte[] cipherText = new byte[compLen + 16];
                in.readFully(cipherText);
                byte[] compressed = decrypt(cipherText, iv);
                byte[] plain = inflate(compressed, plainLen);
                ByteBuffer b = ByteBuffer.wrap(plain);
                while (b.remaining() >= REC_SIZE) out.add(decode(b));
            }
        } catch (Exception e) {
            Log.w(TAG, "battery history read failed", e);
        }
        Collections.sort(out, Comparator.comparingLong(x -> x.t));
        return out;
    }

    private static byte[] deflate(byte[] plain) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, plain.length / 2));
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream out = new DeflaterOutputStream(bos, d)) {
            out.write(plain);
        } finally {
            d.end();
        }
        return bos.toByteArray();
    }

    private static byte[] inflate(byte[] compressed, int expected) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(expected);
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > expected + 64) throw new IOException("battery history inflate overflow");
            }
        }
        byte[] out = bos.toByteArray();
        if (out.length != expected) throw new IOException("battery history size mismatch");
        return out;
    }

    /** 加密时**不传 IV**：Android Keystore 默认只允许它自己生成随机 IV（caller IV 会抛异常）。 */
    private static Enc encrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return new Enc(cipher.getIV(), cipher.doFinal(data));
    }

    private static byte[] decrypt(byte[] data, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(data);
    }

    private static SecretKey key() throws Exception {
        if (key != null) return key;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            key = (SecretKey) ks.getKey(KEY_ALIAS, null);
            if (key != null) return key;
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        key = kg.generateKey();
        return key;
    }

    private static void maybeTrimLocked(Context c, long now) {
        if (lastTrimAt > 0 && now - lastTrimAt < TRIM_GAP_MS) return;
        lastTrimAt = now;
        String vin = pendingVin.isEmpty() ? NativeStore.vin(c) : pendingVin;
        if (vin.isEmpty()) return;
        File file = fileFor(c, vin);
        if (!file.isFile()) return;
        try {
            List<Rec> all = readAll(file);
            long cutoff = now - RETENTION_MS;
            List<Rec> keep = new ArrayList<>();
            for (Rec r : all) if (r.t >= cutoff) keep.add(r);
            if (keep.size() != all.size() || file.length() > MAX_FILE_BYTES) {
                rewrite(file, keep);
            }
        } catch (Exception e) {
            Log.w(TAG, "battery history trim failed", e);
        }
    }

    private static void rewrite(File file, List<Rec> records) throws Exception {
        File tmp = new File(file.getPath() + ".tmp");
        tmp.delete();
        try (FileOutputStream fos = new FileOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(fos)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            for (int i = 0; i < records.size(); i += CHUNK_RECORDS) {
                int end = Math.min(records.size(), i + CHUNK_RECORDS);
                byte[] plain = new byte[(end - i) * REC_SIZE];
                ByteBuffer buf = ByteBuffer.wrap(plain);
                for (int k = i; k < end; k++) {
                    Rec r = records.get(k);
                    buf.putLong(r.t);
                    buf.putShort((short) clamp(Math.round(r.v * 10), Short.MIN_VALUE, Short.MAX_VALUE));
                    buf.putShort((short) clamp(Math.round(r.a * 10), Short.MIN_VALUE, Short.MAX_VALUE));
                    buf.put((byte) clamp(r.soc, 0, 100));
                    buf.put((byte) r.temp);
                    buf.put((byte) clamp(r.state, 0, 2));
                    buf.put((byte) 0);
                }
                byte[] compressed = deflate(plain);
                Enc enc = encrypt(compressed);
                out.writeInt(plain.length);
                out.writeInt(compressed.length);
                out.writeInt(enc.iv.length);
                out.write(enc.iv);
                out.write(enc.data);
            }
            out.flush();
        }
        if (!tmp.renameTo(file)) {
            // Linux 上 rename(2) 本身是原子替换，失败时**不要先删原文件**——
            // 早先的 delete-then-rename 在磁盘满/打盹窗口里会把 31 天历史整份清掉。
            // 保留 tmp：原文件完好，下次 rewrite 开头会清掉它。
            throw new IOException("battery history rename failed");
        }
    }

    // ---------------- 统计 ----------------

    private static String buildReport(List<Rec> all, String range, long now) {
        long span = rangeMs(range);
        long from = now - span;
        List<Rec> inRange = new ArrayList<>();
        for (Rec r : all) {
            if (r.t >= from && r.t <= now + 5 * 60_000L) inRange.add(r);
        }
        Collections.sort(inRange, Comparator.comparingLong(x -> x.t));

        JSONObject root = new JSONObject();
        try {
            root.put("ok", true);
            root.put("range", range);
            root.put("from", from);
            root.put("to", now);
            root.put("count", inRange.size());
            root.put("points", pointsJson(inRange, from, now, bucketMs(range)));

            JSONObject summary = new JSONObject();
            int max = -1;
            int min = 101;
            double chargeKwh = 0;
            double dischargeKwh = 0;
            long chargeMs = 0;
            long dischargeMs = 0;
            for (int i = 0; i < inRange.size(); i++) {
                Rec r = inRange.get(i);
                if (r.soc > max) max = r.soc;
                if (r.soc < min) min = r.soc;
                if (i + 1 >= inRange.size()) continue;
                Rec n = inRange.get(i + 1);
                long dt = Math.min(MAX_GAP_FOR_ENERGY_MS, Math.max(0, n.t - r.t));
                if (dt <= 0) continue;
                double p = power(r);
                if (r.state > 0) {
                    chargeKwh += p * dt / 3_600_000_000.0;
                    chargeMs += dt;
                } else if (isDischarge(r)) {
                    dischargeKwh += p * dt / 3_600_000_000.0;
                    dischargeMs += dt;
                }
            }
            summary.put("socMax", max < 0 ? JSONObject.NULL : max);
            summary.put("socMin", min > 100 ? JSONObject.NULL : min);
            summary.put("chargeKwh", round2(chargeKwh));
            summary.put("dischargeKwh", round2(dischargeKwh));
            summary.put("chargeMin", Math.round(chargeMs / 60_000.0));
            summary.put("dischargeMin", Math.round(dischargeMs / 60_000.0));
            root.put("summary", summary);

            root.put("lastCharge", nullable(sessionJson(lastSession(all, true, now), now, true)));
            root.put("lastDischarge", nullable(sessionJson(lastSession(all, false, now), now, false)));
        } catch (Exception e) {
            Log.w(TAG, "battery history report failed", e);
            return emptyReport(range);
        }
        return root.toString();
    }

    private static JSONArray pointsJson(List<Rec> list, long from, long to, long bucket) {
        JSONArray arr = new JSONArray();
        if (list.isEmpty()) return arr;
        Map<Long, Rec> buckets = new LinkedHashMap<>();
        for (Rec r : list) {
            long b = Math.max(0, (r.t - from) / bucket);
            buckets.put(b, r);          // 同一桶保留最后一条：曲线按"桶末状态"画
        }
        for (Map.Entry<Long, Rec> e : buckets.entrySet()) {
            Rec r = e.getValue();
            try {
                JSONObject p = new JSONObject();
                p.put("t", r.t);
                p.put("soc", r.soc);
                p.put("v", round1(r.v));
                p.put("a", round1(r.a));
                p.put("w", Math.round(power(r)));
                p.put("state", r.state);
                arr.put(p);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static Session lastSession(List<Rec> all, boolean charging, long now) {
        Session last = null;
        Session cur = null;
        for (Rec r : all) {
            boolean match = charging ? r.state > 0 : isDischarge(r);
            if (!match) {
                if (cur != null) {
                    last = cur;
                    cur = null;
                }
                continue;
            }
            if (cur == null || r.t - cur.end > SESSION_GAP_MS) {
                if (cur != null) last = cur;
                cur = new Session(charging, r);
            } else {
                cur.add(r);
            }
        }
        if (cur != null) last = cur;
        if (last == null) return null;
        Rec tail = all.isEmpty() ? null : all.get(all.size() - 1);
        boolean tailInSession = tail != null && (charging ? tail.state > 0 : isDischarge(tail));
        last.ongoing = tailInSession && last.end >= now - 10 * 60_000L;
        return last;
    }

    private static JSONObject sessionJson(Session s, long now, boolean charging) throws Exception {
        if (s == null) return null;
        JSONObject j = new JSONObject();
        long end = s.ongoing ? now : s.end;
        j.put("start", s.start);
        j.put("end", end);
        j.put("startSoc", s.startSoc);
        j.put("endSoc", s.endSoc);
        j.put("minutes", Math.max(0, Math.round((end - s.start) / 60_000.0)));
        j.put("kwh", round2(s.kwh));
        j.put("peakW", Math.round(s.peakW));
        j.put("ongoing", s.ongoing);
        j.put("charging", charging);
        return j;
    }

    private static Object nullable(Object o) {
        return o == null ? JSONObject.NULL : o;
    }

    private static boolean isDischarge(Rec r) {
        return r.state == 0 && Math.abs(r.a) >= 0.3;
    }

    private static double power(Rec r) {
        return Math.abs(r.v * r.a);
    }

    private static long rangeMs(String range) {
        switch (range) {
            case "3d": return 3L * 24 * 60 * 60 * 1000;
            case "7d": return 7L * 24 * 60 * 60 * 1000;
            case "30d": return RETENTION_MS;
            default: return 24L * 60 * 60 * 1000;
        }
    }

    private static long bucketMs(String range) {
        switch (range) {
            case "3d": return 15L * 60 * 1000;
            case "7d": return 30L * 60 * 1000;
            case "30d": return 2L * 60 * 60 * 1000;
            default: return 5L * 60 * 1000;
        }
    }

    private static String normalizeRange(String range) {
        if ("3d".equals(range) || "7d".equals(range) || "30d".equals(range)) return range;
        return "24h";
    }

    private static String emptyReport(String range) {
        String r = normalizeRange(range);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("range", r);
            o.put("count", 0);
            o.put("points", new JSONArray());
            JSONObject s = new JSONObject();
            s.put("socMax", JSONObject.NULL);
            s.put("socMin", JSONObject.NULL);
            s.put("chargeKwh", 0);
            s.put("dischargeKwh", 0);
            s.put("chargeMin", 0);
            s.put("dischargeMin", 0);
            o.put("summary", s);
            o.put("lastCharge", JSONObject.NULL);
            o.put("lastDischarge", JSONObject.NULL);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static int clamp(long v, int lo, int hi) {
        return (int) Math.max(lo, Math.min(hi, v));
    }

    private static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static final class Enc {
        final byte[] iv;
        final byte[] data;
        Enc(byte[] iv, byte[] data) {
            this.iv = iv;
            this.data = data;
        }
    }

    private static final class Rec {
        final long t;
        final double v;
        final double a;
        final int soc;
        final int temp;
        final int state;

        Rec(long t, double v, double a, int soc, int temp, int state) {
            this.t = t;
            this.v = v;
            this.a = a;
            this.soc = soc;
            this.temp = temp;
            this.state = state;
        }
    }

    private static final class Session {
        final boolean charging;
        final long start;
        long end;
        int startSoc;
        int endSoc;
        double kwh;
        double peakW;
        boolean ongoing;

        Session(boolean charging, Rec r) {
            this.charging = charging;
            this.start = r.t;
            this.end = r.t;
            this.startSoc = r.soc;
            this.endSoc = r.soc;
            this.peakW = power(r);
        }

        void add(Rec r) {
            long dt = Math.min(MAX_GAP_FOR_ENERGY_MS, Math.max(0, r.t - end));
            kwh += power(r) * dt / 3_600_000_000.0;
            peakW = Math.max(peakW, power(r));
            end = r.t;
            endSoc = r.soc;
        }
    }
}
