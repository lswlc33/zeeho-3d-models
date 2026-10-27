package io.github.lswlc33.zeehowebnext.cast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 车机投屏二维码的模型：解析、打码、序列化。
 *
 * <p>解析规则逐条对齐 {@code docs/CAST.md} §2 与反编译 {@code d0.java}（{@code h()} 切段、
 * {@code g()} 校验、{@code c()} 错误码映射），并与本仓 {@code sdk/vehicle-cast.js} 的
 * {@code parsePairingPayload()} 保持同一顺序、同一错误码去重规则——页面会先解析一次再交给
 * 原生 {@code pair()}，两边判定必须一致，否则会出现“页面说行、原生说不行”。
 *
 * <p>为什么口令/SSID/SN 只在本类里以明文存在：它们是**一次性凭证**（CAST.md §10），
 * 出了这个类就只能拿 {@link #toMaskedJson()} / {@link #redact(JSONObject)} 的结果。
 */
public final class CastProfile {

    /** 支持的二维码版本（d0.g：只接受 1）。 */
    public static final int SUPPORTED_VERSION = 1;
    /** 支持的连接模式（action/mode，9 = Wi-Fi Direct 投屏）。 */
    public static final String SUPPORTED_MODE = "9";
    /** 二维码文本长度上限（d0.g）。 */
    public static final int QR_MAX_CHARS = 4096;
    /** SN 长度上限（d0.g）。 */
    public static final int SERIAL_MAX_CHARS = 128;
    /** 密码长度区间（WPA2 PSK）。 */
    public static final int PASSWORD_MIN_CHARS = 8;
    public static final int PASSWORD_MAX_CHARS = 63;
    /** SSID 长度上限（802.11 SSID 32 字节）。 */
    public static final int SSID_MAX_CHARS = 32;

    private static final Pattern MODEL_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern P2P_MAC_PATTERN = Pattern.compile("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}");

    /** 车型/渠道，例如 AE5I。 */
    public final String modelId;
    /** 车机 P2P 组 SSID。 */
    public final String ssid;
    /** 车机 P2P 组口令。 */
    public final String password;
    /** 车机 Wi-Fi Direct 设备地址，已规范化为大写冒号分隔。 */
    public final String p2pMac;
    /** 车机 SN，可能为空串。 */
    public final String serial;
    /** 二维码版本，解析成功时恒为 1。 */
    public final int version;
    /** action/mode 原文，成功时恒为 "9"。 */
    public final String mode;

    private CastProfile(int version, String mode, String modelId, String ssid,
                        String password, String p2pMac, String serial) {
        this.version = version;
        this.mode = mode == null ? SUPPORTED_MODE : mode;
        this.modelId = modelId == null ? "" : modelId;
        this.ssid = ssid == null ? "" : ssid;
        this.password = password == null ? "" : password;
        this.p2pMac = p2pMac == null ? "" : p2pMac;
        this.serial = serial == null ? "" : serial;
    }

    /** 解析结果：失败时 {@code profile == null}，{@code errors} 里是按出现顺序去重后的错误码。 */
    public static final class Result {
        public final boolean ok;
        public final CastProfile profile;
        public final List<String> errors;
        /** 各必填项是否出现（诊断用，不含明文）。 */
        public final JSONObject fields;

        Result(boolean ok, CastProfile profile, List<String> errors, JSONObject fields) {
            this.ok = ok;
            this.profile = profile;
            this.errors = errors;
            this.fields = fields;
        }

        /** 第一条错误码，失败时用来回给页面。 */
        public String firstError() {
            return errors.isEmpty() ? CastErrors.QR_INVALID : errors.get(0);
        }
    }

    /**
     * 解析车机屏幕上的二维码文本（URL 或 key=value 串）。
     *
     * <p>顺序固定：长度 → 版本 → 模式 → 缺字段 → 各字段格式。与 d0.g 一致，
     * 因此同一段文本在页面与原生侧得到的错误码必然相同。
     */
    public static Result parse(String raw) {
        JSONObject fields = new JSONObject();
        List<String> errors = new ArrayList<String>();
        String text = raw == null ? "" : raw;
        String trimmed = text.trim();
        boolean withinLimit = trimmed.length() > 0 && trimmed.length() <= QR_MAX_CHARS;
        putQuiet(fields, "qrInputWithinLimit", withinLimit);
        putQuiet(fields, "pairingVersionPresent", false);
        putQuiet(fields, "connectionModePresent", false);
        putQuiet(fields, "modelIdPresent", false);
        putQuiet(fields, "networkNamePresent", false);
        putQuiet(fields, "networkCredentialPresent", false);
        putQuiet(fields, "deviceAddressPresent", false);
        putQuiet(fields, "serialPresent", false);
        if (!withinLimit) {
            errors.add(CastErrors.QR_INPUT_INVALID);
            return new Result(false, null, errors, fields);
        }

        Map<String, String> map = splitQuery(trimmed);

        String versionRaw = pick(map, "v", "version", "ver");
        String modeRaw = pick(map, "action", "mode");
        String modelId = pick(map, "modelid", "model_id", "modelId", "channel");
        String ssid = pick(map, "ssid");
        String password = pick(map, "pwd", "password");
        String macRaw = pick(map, "mac", "p2pmac", "p2p_mac");
        String serial = pick(map, "sn", "serial");

        putQuiet(fields, "pairingVersionPresent", versionRaw.length() > 0);
        putQuiet(fields, "connectionModePresent", modeRaw.length() > 0);
        putQuiet(fields, "modelIdPresent", modelId.length() > 0);
        putQuiet(fields, "networkNamePresent", ssid.length() > 0);
        putQuiet(fields, "networkCredentialPresent", password.length() > 0);
        putQuiet(fields, "deviceAddressPresent", macRaw.length() > 0);
        putQuiet(fields, "serialPresent", serial.length() > 0);

        int version = SUPPORTED_VERSION;
        if (versionRaw.length() > 0) {
            int parsed = parseIntPrefix(versionRaw);
            if (parsed == Integer.MIN_VALUE) {
                errors.add(CastErrors.QR_VERSION_INVALID);
            } else {
                version = parsed;
                if (version != SUPPORTED_VERSION) {
                    errors.add(CastErrors.QR_VERSION_UNSUPPORTED);
                }
            }
        }
        if (!SUPPORTED_MODE.equals(modeRaw)) {
            errors.add(CastErrors.CONNECTION_MODE_UNSUPPORTED);
        }
        if (modelId.length() == 0 || ssid.length() == 0 || password.length() == 0
                || macRaw.length() == 0) {
            errors.add(CastErrors.PAIRING_FIELD_MISSING);
        }
        if (modelId.length() > 0 && !MODEL_ID_PATTERN.matcher(modelId).matches()) {
            errors.add(CastErrors.MODEL_ID_INVALID);
        }
        // 注意：JS 侧对 ssid 只查“>32 或含 NUL”，空串已在“缺字段”里拦过，这里不再重复。
        if (ssid.length() > 0 && (ssid.length() > SSID_MAX_CHARS || ssid.indexOf('\0') >= 0)) {
            errors.add(CastErrors.NETWORK_NAME_INVALID);
        }
        if (password.length() > 0
                && (password.length() < PASSWORD_MIN_CHARS || password.length() > PASSWORD_MAX_CHARS)) {
            errors.add(CastErrors.NETWORK_CREDENTIAL_INVALID);
        }

        String p2pMac = "";
        if (macRaw.length() > 0) {
            String normalized = macRaw.replace('-', ':').toUpperCase(Locale.US);
            if (P2P_MAC_PATTERN.matcher(normalized).matches()) {
                p2pMac = normalized;
            } else {
                errors.add(CastErrors.DEVICE_ADDRESS_INVALID);
            }
        }
        if (serial.length() > SERIAL_MAX_CHARS) {
            errors.add(CastErrors.SERIAL_INVALID);
        }

        // 同一码只留第一条，避免弹层刷屏（JS 侧同样的去重规则）
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        unique.addAll(errors);
        List<String> deduped = new ArrayList<String>(unique);
        if (!deduped.isEmpty()) {
            return new Result(false, null, deduped, fields);
        }
        CastProfile profile = new CastProfile(version, modeRaw, modelId, ssid, password,
                p2pMac, serial);
        return new Result(true, profile, deduped, fields);
    }

    /** 从 {@code pair()} 收到已经成形的 profile JSON 还原（页面可能直接传对象）。 */
    public static CastProfile fromJson(JSONObject json) throws JSONException {
        String p2pMac = json.optString("p2pMac", "");
        if (!P2P_MAC_PATTERN.matcher(p2pMac).matches()) {
            throw new JSONException("p2pMac");
        }
        String ssid = json.optString("ssid", "");
        String password = json.optString("password", "");
        if (ssid.length() == 0 || password.length() == 0) {
            throw new JSONException("ssid/password");
        }
        return new CastProfile(json.optInt("version", SUPPORTED_VERSION),
                json.optString("mode", SUPPORTED_MODE),
                json.optString("modelId", ""),
                ssid,
                password,
                p2pMac,
                json.optString("serial", ""));
    }

    /** **明文**序列化：只允许用于进程内传递与原生持久化，禁止进日志。 */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        putQuiet(json, "version", version);
        putQuiet(json, "mode", mode);
        putQuiet(json, "modelId", modelId);
        putQuiet(json, "ssid", ssid);
        putQuiet(json, "password", password);
        putQuiet(json, "p2pMac", p2pMac);
        putQuiet(json, "serial", serial);
        return json;
    }

    /** 打码后的展示/日志形态（CAST.md §2：MAC 只露后 5 位）。 */
    public JSONObject toMaskedJson() {
        JSONObject json = new JSONObject();
        putQuiet(json, "version", version);
        putQuiet(json, "modelId", modelId);
        putQuiet(json, "ssid", maskSecret(ssid, 3));
        putQuiet(json, "p2pMac", maskP2pMac(p2pMac));
        putQuiet(json, "serial", serial.length() == 0 ? "" : maskSecret(serial, 4));
        // 口令永不出现在展示形态里
        return json;
    }

    /** 展示摘要（“车型 · **:**:**:EE:FF”），对应 d0.a.a()。 */
    public String summary() {
        return modelId + " · " + maskP2pMac(p2pMac);
    }

    /** 保留末尾 keep 位，其余打星；长度不足全打码（对应 JS maskSecret）。 */
    public static String maskSecret(String value, int keep) {
        if (value == null || value.length() == 0) {
            return "";
        }
        int keepCount = Math.max(0, keep);
        if (value.length() <= keepCount) {
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                all.append('*');
            }
            return all.toString();
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() - keepCount; i++) {
            out.append('*');
        }
        out.append(value.substring(value.length() - keepCount));
        return out.toString();
    }

    /** P2P MAC 打码：Motexa 只露后 5 位（d0.e）。 */
    public static String maskP2pMac(String mac) {
        if (mac == null || mac.trim().length() == 0) {
            return "";
        }
        String text = mac.trim();
        if (text.length() < 5) {
            return "--";
        }
        return "**:**:**:" + text.substring(text.length() - 5);
    }

    /**
     * 递归打码诊断 payload：口令、SSID、MAC、SN、HUID、公钥等一次性凭证都不进日志
     * （CAST.md §10 的硬要求，JS 侧 redactDiagnostic 同款规则）。
     */
    public static JSONObject redact(JSONObject source) {
        if (source == null) {
            return new JSONObject();
        }
        JSONObject out = new JSONObject();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = source.opt(key);
            if (isSecretKey(key)) {
                if (value instanceof String) {
                    putQuiet(out, key, maskSecret((String) value, 4));
                } else if (value instanceof Number) {
                    putQuiet(out, key, maskSecret(String.valueOf(value), 4));
                } else {
                    putQuiet(out, key, "[REDACTED]");
                }
            } else if (value instanceof JSONObject) {
                putQuiet(out, key, redact((JSONObject) value));
            } else if (value instanceof JSONArray) {
                putQuiet(out, key, redactArray((JSONArray) value));
            } else {
                putQuiet(out, key, value);
            }
        }
        return out;
    }

    private static JSONArray redactArray(JSONArray source) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            Object value = source.opt(i);
            if (value instanceof JSONObject) {
                out.put(redact((JSONObject) value));
            } else if (value instanceof JSONArray) {
                out.put(redactArray((JSONArray) value));
            } else {
                out.put(value);
            }
        }
        return out;
    }

    private static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.US);
        if ("mac".equals(lower) || "sn".equals(lower) || "key".equals(lower) || "iv".equals(lower)) {
            return true;
        }
        return lower.contains("pwd")
                || lower.contains("password")
                || lower.contains("passwd")
                || lower.contains("ssid")
                || lower.contains("p2pmac")
                || lower.contains("p2p_mac")
                || lower.contains("serial")
                || lower.contains("huid")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("pubkey")
                || lower.contains("encryptedhuid")
                || lower.contains("envinfo")
                || lower.contains("encryptinfo")
                || lower.contains("encryptvalue");
    }

    /* ------------------------------ 解析细节 ------------------------------ */

    /** {'?':} 之后切段；key/value 都做 URL 解码，key 小写（d0.h）。 */
    private static Map<String, String> splitQuery(String text) {
        String query = text;
        int question = text.indexOf('?');
        if (question >= 0) {
            query = text.substring(question + 1);
        }
        Map<String, String> map = new LinkedHashMap<String, String>();
        String[] segments = query.split("[&;\\n]");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            int eq = segment.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = decodeUrl(segment.substring(0, eq)).trim().toLowerCase(Locale.US);
            String value = decodeUrl(segment.substring(eq + 1)).trim();
            if (key.length() > 0 && value.length() > 0) {
                map.put(key, value);
            }
        }
        return map;
    }

    /** '+' → 空格再 URL 解码；解码失败原样返回（d0.a 的 try/catch 语义）。 */
    private static String decodeUrl(String text) {
        try {
            return URLDecoder.decode(text.replace('+', ' '), "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
            return text;
        } catch (IllegalArgumentException ignored) {
            // 非法 % 序列：保留原文，让后续校验去报错，不要在这里崩
            return text;
        }
    }

    private static String pick(Map<String, String> map, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            String value = map.get(keys[i]);
            if (value != null && value.length() > 0) {
                return value;
            }
        }
        return "";
    }

    /**
     * 模仿 JS {@code Number.parseInt}：取前导整数部分（允许正负号），没有数字返回
     * {@link Integer#MIN_VALUE}。用 Integer.parseInt 会在 "1abc" 这类输入上误报版本非法。
     */
    private static int parseIntPrefix(String text) {
        String value = text.trim();
        int index = 0;
        StringBuilder digits = new StringBuilder();
        if (index < value.length() && (value.charAt(index) == '+' || value.charAt(index) == '-')) {
            if (value.charAt(index) == '-') {
                digits.append('-');
            }
            index++;
        }
        int startDigits = digits.length();
        while (index < value.length() && value.charAt(index) >= '0' && value.charAt(index) <= '9') {
            digits.append(value.charAt(index));
            index++;
        }
        if (digits.length() == startDigits) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException error) {
            return Integer.MIN_VALUE;
        }
    }

    private static void putQuiet(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException ignored) {
            // org.json 的 put 只在 key 为 null 时抛，这里 key 都是常量
        }
    }
}
