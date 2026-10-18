package io.github.lswlc33.zeehowebnext;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 原生后台用的极核云接口客户端。
 *
 * 网页版的后台任务（通知/小组件/自动任务/快捷方式）不能依赖 WebView 是否活着，所以这里把
 * 需要的少量接口和签名算法搬到了 Java。算法与 public/js/sign.js 完全一致：
 *   app 域：sign = md5(sha1(query + body? + "appId=..&nonce=..&timestamp=.." + SECRET))
 *   h5 域 ：sign = md5(sha1(body + param + H5_SECRET))，nonce 是 UUID
 *
 * 只实现通知/车控/任务用到的接口，不复制网页的整套接口表。密钥与网页版同源，均来自公开页面里的
 * 客户端常量；token 只存在应用私有 SharedPreferences 里。
 */
final class ApiClient {

    static final String APP_ID = "S7qPWPU1";
    static final String APP_SECRET = "c5e0da7f4da28df805694ec3dd1fc6792e9df99d";
    static final String BASE = "https://tapi.zeehoev.com";
    static final String H5 = "https://h5.zeehoev.com";
    static final String H5_APP_ID = "AiTXmBrm";
    static final String H5_SECRET = "70c2c7458ab88ca9504ad0521f170075bc91f2f7";
    static final String H5_SIGNIN_URL = H5 + "/cfmotoservermine/H5/signin";

    /** 云解锁 AES 密钥（ASCII 32 字节，来自 dex AESEncryptUtil.<clinit>）。 */
    private static final String CAR_AES_KEY = "9dbc2cbf2ec327699301b495010316ec";

    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern CHARGED = Pattern.compile("^(?:(\\d+)\\s*h)?\\s*(\\d+)\\s*min$", Pattern.CASE_INSENSITIVE);

    static final class Result {
        int http;
        String code = "";
        String message = "";
        Object data;
        String raw = "";
        boolean ok;
        boolean auth;

        boolean hasData() {
            return data instanceof JSONObject || data instanceof JSONArray;
        }
    }

    private final String token;

    ApiClient(String token) {
        this.token = token == null ? "" : token.trim();
    }

    // ---------------- 业务接口 ----------------

    Result vehicleHome(String vin) {
        return call("GET", "/v1.0/app/cfmotoserverapp/vehicleHomePageV2/" + vin, null, null);
    }

    Result batteryInfo(String vin) {
        return call("GET", "/v1.0/app/cfmotoserverapp/batteryInfo/" + vin, null, null);
    }

    /** 胎压（只在 15 分钟兜底任务里查一次，不进 60 秒轮询）。 */
    Result tireMonitor(String vin) {
        Map<String, String> q = new TreeMap<>();
        q.put("sensorType", "EXTERNAL");
        q.put("vinNo", vin);
        return call("GET", "/v1.0/app/cfmotoserverapp/app/vehicle/tire/monitoring", q, null);
    }

    Result msgList(String uid, int pageSize) {
        Map<String, String> q = new TreeMap<>();
        q.put("messageKind", "3");
        q.put("page", "1");
        q.put("pageSize", String.valueOf(Math.max(1, Math.min(20, pageSize))));
        q.put("userId", uid);
        return call("GET", "/v1.0/mine/cfmotoservermine/app/Message/list_v2", q, null);
    }

    Result signinCount() {
        return call("GET", "/v1.0/mine/cfmotoservermine/signin/count", null, null);
    }

    Result integralTasks(String uid) {
        Map<String, String> q = new TreeMap<>();
        q.put("userId", uid);
        return call("GET", "/v1.0/mine/cfmotoservermine/integral/getByUserId", q, null);
    }

    Result postMine(String uid) {
        Map<String, String> q = new TreeMap<>();
        q.put("userId", uid);
        return call("GET", "/v1.0/social/cfmotoserversocial/community/mineArticleInfo", q, null);
    }

    Result publish(String content) {
        JSONObject body = new JSONObject();
        try {
            JSONObject sub = new JSONObject();
            sub.put("topicList", new JSONArray());
            body.put("postSubInfo", sub);
            body.put("topicid", "");
            body.put("postcontent", content);
        } catch (Exception ignored) {
        }
        return call("POST", "/v1.0/social/cfmotoserversocial/commonArticle", null, body.toString());
    }

    Result like(String postId) {
        JSONObject body = new JSONObject();
        try {
            body.put("postId", postId);
            body.put("kindFlag", "0");
        } catch (Exception ignored) {
        }
        return call("POST", "/v1.0/social/cfmotoserversocial/socialCommu/likeFavoriteInfo", null, body.toString());
    }

    Result share(String postId) {
        return call("PUT", "/v1.0/social/cfmotoserversocial/article/share/" + postId, null, null);
    }

    Result adjustShare() {
        return call("GET", "/v1.0/mine/cfmotoservermine/integral/adjustByShare", null, null);
    }

    Result deletePost(String postId) {
        Map<String, String> q = new TreeMap<>();
        q.put("articleId", postId);
        q.put("postType", "1");
        return call("DELETE", "/v1.0/social/cfmotoserversocial/commonArticle/deleteArticle", q, null);
    }

    /** 寻车鸣笛：App 实际连发两条（dbpoint + vehicleInfo/control）。 */
    Result horn(String vin) {
        Result a = dbpoint(vin, "LOUDLY_SEARCH_CAR", "LOW");
        Result b = call("PUT", "/v1.0/app/cfmotoserverapp/vehicleInfo/control/" + vin, null, null);
        return a.ok ? a : b;
    }

    /** 整车开关：dbpoint ON_AND_OFF + 云解锁 AES secret。 */
    Result lock(String vin, boolean unlock) {
        Result a = dbpoint(vin, "ON_AND_OFF", unlock ? "ON" : "OFF");
        Result b = netUnlock(vin, unlock ? 1 : 0);
        if (!a.ok) return a;
        return b;
    }

    Result openSeat(String vin) {
        JSONObject body = new JSONObject();
        try {
            body.put("commond", "28");
            body.put("commondParam", "1");
            body.put("relatedFunctions", JSONObject.NULL);
            body.put("vcu", vin);
            body.put("version", "v2");
        } catch (Exception ignored) {
        }
        return call("PUT", "/v1.0/app/cfmotoserverapp/vehicleSet/propertyTwo/one", null, body.toString());
    }

    Result dbpoint(String vin, String functionCode, String type) {
        JSONObject param = new JSONObject();
        try {
            param.put("vinNo", vin);
            param.put("functionCode", functionCode);
            if (type != null && !type.isEmpty()) param.put("type", type);
        } catch (Exception ignored) {
        }
        JSONObject body = new JSONObject();
        try {
            body.put("eventId", 11631);
            body.put("param", param.toString());
            body.put("uuid", "");
            body.put("zeehoParamTypeBO", JSONObject.NULL);
        } catch (Exception ignored) {
        }
        return call("POST", "/v1.0/app/cfmotoserverapp/dbpoint/send", null, body.toString());
    }

    Result netUnlock(String vin, int lockFlag) {
        JSONObject body = new JSONObject();
        try {
            body.put("secret", unlockSecret(vin, lockFlag));
        } catch (Exception e) {
            Result r = new Result();
            r.message = "云解锁加密失败";
            return r;
        }
        return call("POST", "/v1.0/app/cfmotoserverapp/vehicleSet/network/unlock", null, body.toString());
    }

    /** 签到（h5 域，body 必须入签；与模块 doH5SignIn / tasks.js 同一套配方）。 */
    Result signin() {
        String body = "{}";
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String param = "appId=" + H5_APP_ID + "&nonce=" + nonce + "&timestamp=" + ts;
        String sign = md5Hex(sha1Hex(body + param + H5_SECRET));
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(H5_SIGNIN_URL).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            headers(c, token, param, sign);
            c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            Result r = read(c);
            if (!r.ok) {
                String m = (r.message + " " + r.raw).toLowerCase();
                if (m.contains("已签") || m.contains("repeatedly") || m.contains("repeatedly_operation")) {
                    r.ok = true;
                }
            }
            return r;
        } catch (Exception e) {
            Result r = new Result();
            r.message = "签到请求失败：" + e.getMessage();
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------- 签名 / HTTP ----------------

    private Result call(String method, String path, Map<String, String> params, String body) {
        String q = query(params);
        String url = BASE + path + (q.isEmpty() ? "" : "?" + q);
        long ts = System.currentTimeMillis();
        String nonce = ts + rand(16);
        String param = "appId=" + APP_ID + "&nonce=" + nonce + "&timestamp=" + ts;
        String sign = md5Hex(sha1Hex(q + (body == null ? "" : body) + param + APP_SECRET));
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(true);
            headers(c, token, param, sign);
            if (body != null) {
                c.setDoOutput(true);
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(out);
                }
            }
            return read(c);
        } catch (Exception e) {
            Result r = new Result();
            r.message = "请求失败：" + e.getMessage();
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void headers(HttpURLConnection c, String token, String param, String sign) {
        c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        c.setRequestProperty("Accept-Language", "zh-CN");
        c.setRequestProperty("interfaceversion", "2");
        c.setRequestProperty("cfmoto-x-param", param);
        c.setRequestProperty("cfmoto-x-sign", sign);
        c.setRequestProperty("cfmoto-x-sign-type", "0");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
    }

    private static Result read(HttpURLConnection c) throws Exception {
        int http = c.getResponseCode();
        InputStream in = http >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            try (InputStream is = in) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 512 * 1024) break;
                }
                text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        Result r = new Result();
        r.http = http;
        r.raw = text;
        JSONObject j = null;
        try {
            j = new JSONObject(text);
        } catch (Exception ignored) {
        }
        if (j != null) {
            r.code = j.optString("code", "");
            r.message = j.optString("message", "");
            Object data = j.opt("data");
            r.data = data == JSONObject.NULL ? null : data;
            r.ok = http >= 200 && http < 300 && "10000".equals(r.code);
        } else {
            r.message = http >= 200 && http < 300 ? "响应不是 JSON" : "HTTP " + http;
        }
        // 鉴权失败只认两种情况：HTTP 401，或**失败响应**里的明确文案。
        // 以前这里把 raw 正文（含 data 里的消息标题/内容）一起做子串匹配，而且成功响应也算——
        // 一条正文写着"您的账号在别处登录，请重新登录"的普通消息，就能把后台轮询永久锁死。
        r.auth = http == 401 || (!r.ok && isAuthText(r.code + " " + r.message));
        return r;
    }

    private static boolean isAuthText(String s) {
        String t = s == null ? "" : s.toLowerCase();
        return t.contains("invalid_token") || t.contains("invalidtoken")
            || t.contains("登录已过期") || t.contains("登录失效") || t.contains("请重新登录")
            || t.contains("未登录") || t.contains("token过期") || t.contains("token失效");
    }

    private static String query(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(v);
        }
        return sb.toString();
    }

    private static String rand(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(CHARS.charAt((int) (Math.random() * CHARS.length())));
        }
        return sb.toString();
    }

    static String md5Hex(String s) {
        return hex("MD5", s);
    }

    static String sha1Hex(String s) {
        return hex("SHA-1", s);
    }

    private static String hex(String alg, String s) {
        try {
            MessageDigest md = MessageDigest.getInstance(alg);
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String unlockSecret(String vin, int lockFlag) throws Exception {
        String plain = "{\"lockFlag\":" + lockFlag + ",\"vinNo\":\"" + vin + "\"}";
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(CAR_AES_KEY.getBytes(StandardCharsets.US_ASCII), "AES"));
        return Base64.encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    // ---------------- 解析小工具 ----------------

    static JSONObject obj(Result r) {
        return r != null && r.data instanceof JSONObject ? (JSONObject) r.data : null;
    }

    static JSONArray arr(Result r) {
        return r != null && r.data instanceof JSONArray ? (JSONArray) r.data : null;
    }

    static boolean on(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        String s = String.valueOf(v).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "online".equalsIgnoreCase(s);
    }

    static String text(JSONObject o, String key) {
        if (o == null) return "";
        Object v = o.opt(key);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    static int intOr(JSONObject o, String key, int def) {
        if (o == null || !o.has(key) || o.isNull(key)) return def;
        try {
            return (int) Math.round(o.optDouble(key, def));
        } catch (Exception e) {
            return def;
        }
    }

    static int chargedMinutes(String s) {
        if (s == null) return -1;
        Matcher m = CHARGED.matcher(s.trim());
        if (!m.matches()) return -1;
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return h * 60 + min;
    }

    static int fullMinutes(JSONObject d) {
        if (d == null) return -1;
        double v = d.optDouble("fullChargeTime", -1);
        if (v > 0) return (int) Math.round(v);
        double sec = d.optDouble("fullChargeTimeSecond", -1);
        if (sec > 0) return (int) Math.round(sec / 60.0);
        return -1;
    }

    static String firstPostId(Result r) {
        JSONArray a = arr(r);
        if (a == null || a.length() == 0) return "";
        JSONObject o = a.optJSONObject(0);
        if (o == null) return "";
        String id = text(o, "tuuid");
        if (id.isEmpty()) id = text(o, "id");
        if (id.isEmpty()) id = text(o, "articleId");
        return id;
    }

    static boolean isAuth(Result r) {
        return r != null && r.auth;
    }

    static String message(Result r) {
        if (r == null) return "未知错误";
        if (r.message != null && !r.message.isEmpty()) return r.message;
        if (r.code != null && !r.code.isEmpty()) return "业务码 " + r.code;
        return "请求失败";
    }

    /** 给自动任务用的动作映射（与 tasks.js 的 ACTION 表一致）。 */
    static String actionFor(String code) {
        if ("1023".equals(code)) return "post";
        if ("1026".equals(code)) return "like";
        if ("1024".equals(code)) return "share";
        return "";
    }
}
