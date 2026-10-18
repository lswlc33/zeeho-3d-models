package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * 原生侧持久化：网页同步下来的登录态/车辆摘要，以及通知、后台、自动任务、小组件的设置。
 *
 * 这里刻意不碰网页的 localStorage：WebView 的 localStorage 在页面进程里，原生后台服务读不到；
 * 网页每次刷新调用 ZeehoShell.syncSession/syncStatus 把需要后台用的少量字段推下来。
 */
final class NativeStore {

    private static final String SP = "webnext_native";

    private static final String K_TOKEN = "token";
    private static final String K_UID = "uid";
    private static final String K_VIN = "vin";
    private static final String K_NAME = "name";
    private static final String K_LAST_PAGE = "last_page_sync";
    private static final String K_LAST_POLL = "last_poll";
    private static final String K_AUTH_FAILED = "auth_failed";

    private static final String K_SOC = "soc";
    private static final String K_CHARGE = "charge";       // 0 未充 / 1 充电中 / 2 已充满
    private static final String K_POWER = "power";
    private static final String K_VOLTAGE = "voltage";
    private static final String K_VOLTAGE_TEXT = "voltage_text";
    private static final String K_CURRENT = "current";
    private static final String K_LOCK = "lock";
    private static final String K_SEAT = "seat";
    private static final String K_ONLINE = "online";
    private static final String K_ETA = "eta";
    private static final String K_FULL_MIN = "full_min";
    private static final String K_CHARGED = "charged";
    private static final String K_CHARGED_MIN = "charged_min";
    private static final String K_NOREAD = "no_read";
    private static final String K_MSG_TITLE = "msg_title";
    private static final String K_MSG_ID = "msg_id";
    private static final String K_MSG_TIME = "msg_time";
    private static final String K_UPDATED = "updated_at";

    private static final String K_SEEN_NOREAD = "seen_no_read";
    private static final String K_NOTIFIED_MSG = "notified_msg";
    private static final String K_FULL_NOTIFIED = "full_notified";
    private static final String K_STOP_NOTIFIED = "stop_notified";

    private static final String K_BG = "background";
    private static final String K_MSG_ON = "messages_on";
    private static final String K_CHARGE_ON = "charging_on";

    private static final String K_AUTO_ON = "auto_on";
    private static final String K_AUTO_TIME = "auto_time";
    private static final String K_AUTO_CONTENT = "auto_content";
    private static final String K_AUTO_DELETE = "auto_delete";
    private static final String K_AUTO_GAP = "auto_gap";
    private static final String K_AUTO_LAST_AT = "auto_last_at";
    private static final String K_AUTO_LAST_RESULT = "auto_last_result";

    private static final String K_SECRET = "shortcut_secret";

    /** 车辆摘要。字段 -1 / 空串表示未知。 */
    static final class Status {
        int soc = -1;
        int charge = 0;
        int power = -1;
        int voltage = -1;
        int current = -1;
        int lock = -1;
        int seat = -1;
        int online = -1;
        int noRead = -1;
        int fullMin = -1;
        int chargedMin = -1;
        String eta = "";
        String charged = "";
        String voltageText = "";
        String msgTitle = "";
        String msgId = "";
        String msgTime = "";
        long updatedAt = 0;

        Status copy() {
            Status s = new Status();
            s.soc = soc; s.charge = charge; s.power = power; s.voltage = voltage; s.current = current;
            s.lock = lock; s.seat = seat; s.online = online; s.noRead = noRead;
            s.fullMin = fullMin; s.chargedMin = chargedMin;
            s.eta = eta; s.charged = charged; s.voltageText = voltageText;
            s.msgTitle = msgTitle; s.msgId = msgId;
            s.msgTime = msgTime; s.updatedAt = updatedAt;
            return s;
        }

        /** 除 updatedAt 外，其余字段是否与另一份状态完全一致（updatedAt 每轮都变，不能算变化）。 */
        boolean sameData(Status o) {
            return o != null
                && soc == o.soc && charge == o.charge && power == o.power && voltage == o.voltage
                && current == o.current && lock == o.lock && seat == o.seat && online == o.online
                && noRead == o.noRead && fullMin == o.fullMin && chargedMin == o.chargedMin
                && eq(eta, o.eta) && eq(charged, o.charged) && eq(voltageText, o.voltageText)
                && eq(msgTitle, o.msgTitle) && eq(msgId, o.msgId) && eq(msgTime, o.msgTime);
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        void apply(JSONObject j) {
            if (j == null) return;
            if (j.has("soc") && !j.isNull("soc")) soc = j.optInt("soc", soc);
            if (j.has("charging") && !j.isNull("charging")) charge = j.optInt("charging", charge);
            if (j.has("power") && !j.isNull("power")) power = j.optInt("power", power);
            if (j.has("voltage") && !j.isNull("voltage")) voltage = j.optInt("voltage", voltage);
            if (j.has("voltageText")) voltageText = j.optString("voltageText", voltageText);
            if (j.has("current") && !j.isNull("current")) current = j.optInt("current", current);
            if (j.has("lock") && !j.isNull("lock")) lock = j.optInt("lock", lock);
            if (j.has("seat") && !j.isNull("seat")) seat = j.optInt("seat", seat);
            if (j.has("online") && !j.isNull("online")) online = j.optInt("online", online);
            if (j.has("noRead") && !j.isNull("noRead")) noRead = j.optInt("noRead", noRead);
            if (j.has("fullMin") && !j.isNull("fullMin")) fullMin = j.optInt("fullMin", fullMin);
            if (j.has("chargedMin") && !j.isNull("chargedMin")) chargedMin = j.optInt("chargedMin", chargedMin);
            if (j.has("eta")) eta = j.optString("eta", eta);
            if (j.has("charged")) charged = j.optString("charged", charged);
            if (j.has("msgTitle")) msgTitle = j.optString("msgTitle", msgTitle);
            if (j.has("msgId")) msgId = j.optString("msgId", msgId);
            if (j.has("msgTime")) msgTime = j.optString("msgTime", msgTime);
            if (j.has("at") && !j.isNull("at")) updatedAt = j.optLong("at", updatedAt);
        }
    }

    private NativeStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    static String token(Context c) {
        return sp(c).getString(K_TOKEN, "");
    }

    static String uid(Context c) {
        return sp(c).getString(K_UID, "");
    }

    static String vin(Context c) {
        return sp(c).getString(K_VIN, "");
    }

    static String name(Context c) {
        return sp(c).getString(K_NAME, "");
    }

    static boolean isConfigured(Context c) {
        return !token(c).isEmpty() && !vin(c).isEmpty();
    }

    static long lastPageSync(Context c) {
        return sp(c).getLong(K_LAST_PAGE, 0L);
    }

    static void setLastPageSync(Context c) {
        sp(c).edit().putLong(K_LAST_PAGE, System.currentTimeMillis()).apply();
    }

    static long lastPoll(Context c) {
        return sp(c).getLong(K_LAST_POLL, 0L);
    }

    static void setLastPoll(Context c) {
        sp(c).edit().putLong(K_LAST_POLL, System.currentTimeMillis()).apply();
    }

    static boolean authFailed(Context c) {
        return sp(c).getBoolean(K_AUTH_FAILED, false);
    }

    static void setAuthFailed(Context c, boolean failed) {
        sp(c).edit().putBoolean(K_AUTH_FAILED, failed).apply();
    }

    /** 网页同步登录态；换了 VIN 或退出登录时把旧车摘要清掉，别让小组件继续显示上一台车。 */
    static void updateSession(Context c, String token, String uid, String vin, String name) {
        SharedPreferences p = sp(c);
        String oldVin = p.getString(K_VIN, "");
        String oldToken = p.getString(K_TOKEN, "");
        SharedPreferences.Editor e = p.edit()
            .putString(K_TOKEN, safe(token))
            .putString(K_UID, safe(uid))
            .putString(K_VIN, safe(vin))
            .putString(K_NAME, safe(name));
        boolean tokenChanged = !oldToken.equals(safe(token));
        if (!oldVin.equals(safe(vin)) || tokenChanged) {
            clearStatus(e);
            e.putBoolean(K_AUTH_FAILED, false);
        }
        e.apply();
    }

    static void clearSession(Context c) {
        SharedPreferences.Editor e = sp(c).edit()
            .remove(K_TOKEN).remove(K_UID).remove(K_VIN).remove(K_NAME).remove(K_AUTH_FAILED);
        clearStatus(e);
        e.apply();
    }

    private static void clearStatus(SharedPreferences.Editor e) {
        e.remove(K_SOC).remove(K_CHARGE).remove(K_POWER).remove(K_VOLTAGE).remove(K_CURRENT)
            .remove(K_LOCK).remove(K_SEAT).remove(K_ONLINE)
            .remove(K_ETA).remove(K_FULL_MIN).remove(K_VOLTAGE_TEXT)
            .remove(K_CHARGED).remove(K_CHARGED_MIN).remove(K_NOREAD).remove(K_MSG_TITLE)
            .remove(K_MSG_ID).remove(K_MSG_TIME).remove(K_UPDATED)
            .remove(K_SEEN_NOREAD).remove(K_NOTIFIED_MSG).remove(K_FULL_NOTIFIED).remove(K_STOP_NOTIFIED);
    }

    static Status status(Context c) {
        SharedPreferences p = sp(c);
        Status s = new Status();
        s.soc = p.getInt(K_SOC, -1);
        s.charge = p.getInt(K_CHARGE, 0);
        s.power = p.getInt(K_POWER, -1);
        s.voltage = p.getInt(K_VOLTAGE, -1);
        s.current = p.getInt(K_CURRENT, -1);
        s.lock = p.getInt(K_LOCK, -1);
        s.seat = p.getInt(K_SEAT, -1);
        s.online = p.getInt(K_ONLINE, -1);
        s.noRead = p.getInt(K_NOREAD, -1);
        s.fullMin = p.getInt(K_FULL_MIN, -1);
        s.chargedMin = p.getInt(K_CHARGED_MIN, -1);
        s.eta = p.getString(K_ETA, "");
        s.charged = p.getString(K_CHARGED, "");
        s.voltageText = p.getString(K_VOLTAGE_TEXT, "");
        s.msgTitle = p.getString(K_MSG_TITLE, "");
        s.msgId = p.getString(K_MSG_ID, "");
        s.msgTime = p.getString(K_MSG_TIME, "");
        s.updatedAt = p.getLong(K_UPDATED, 0L);
        return s;
    }

    static void saveStatus(Context c, Status s) {
        sp(c).edit()
            .putInt(K_SOC, s.soc).putInt(K_CHARGE, s.charge).putInt(K_POWER, s.power)
            .putInt(K_VOLTAGE, s.voltage).putInt(K_CURRENT, s.current)
            .putInt(K_LOCK, s.lock).putInt(K_SEAT, s.seat).putInt(K_ONLINE, s.online)
            .putInt(K_NOREAD, s.noRead).putInt(K_FULL_MIN, s.fullMin).putInt(K_CHARGED_MIN, s.chargedMin)
            .putString(K_ETA, s.eta).putString(K_CHARGED, s.charged).putString(K_VOLTAGE_TEXT, s.voltageText)
            .putString(K_MSG_TITLE, s.msgTitle).putString(K_MSG_ID, s.msgId)
            .putString(K_MSG_TIME, s.msgTime).putLong(K_UPDATED, s.updatedAt)
            .apply();
    }

    static int seenNoRead(Context c) {
        return sp(c).getInt(K_SEEN_NOREAD, -1);
    }

    static String notifiedMsgId(Context c) {
        return sp(c).getString(K_NOTIFIED_MSG, "");
    }

    static void setSeen(Context c, int noRead, String msgId) {
        String id = safe(msgId);
        // 页面每 30 秒同步一次，值通常一字没变；没变就不写（SharedPreferences 是整文件覆写）。
        SharedPreferences p = sp(c);
        if (p.getInt(K_SEEN_NOREAD, -1) == noRead && safe(p.getString(K_NOTIFIED_MSG, "")).equals(id)) {
            return;
        }
        p.edit().putInt(K_SEEN_NOREAD, noRead).putString(K_NOTIFIED_MSG, id).apply();
    }

    static boolean fullNotified(Context c) {
        return sp(c).getBoolean(K_FULL_NOTIFIED, false);
    }

    static void setFullNotified(Context c, boolean on) {
        sp(c).edit().putBoolean(K_FULL_NOTIFIED, on).apply();
    }

    static boolean stopNotified(Context c) {
        return sp(c).getBoolean(K_STOP_NOTIFIED, false);
    }

    static void setStopNotified(Context c, boolean on) {
        sp(c).edit().putBoolean(K_STOP_NOTIFIED, on).apply();
    }

    static boolean backgroundEnabled(Context c) {
        return sp(c).getBoolean(K_BG, true);
    }

    static void setBackgroundEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_BG, on).apply();
    }

    static boolean messagesEnabled(Context c) {
        return sp(c).getBoolean(K_MSG_ON, true);
    }

    static boolean chargingEnabled(Context c) {
        return sp(c).getBoolean(K_CHARGE_ON, true);
    }

    static void setNotifications(Context c, boolean messages, boolean charging) {
        sp(c).edit().putBoolean(K_MSG_ON, messages).putBoolean(K_CHARGE_ON, charging).apply();
    }

    static boolean autoEnabled(Context c) {
        return sp(c).getBoolean(K_AUTO_ON, false);
    }

    static String autoTime(Context c) {
        String t = sp(c).getString(K_AUTO_TIME, "09:00");
        return validTime(t) ? t : "09:00";
    }

    static String autoContent(Context c) {
        String s = sp(c).getString(K_AUTO_CONTENT, "开心的一天");
        return s.isEmpty() ? "开心的一天" : s;
    }

    static boolean autoDelete(Context c) {
        return sp(c).getBoolean(K_AUTO_DELETE, true);
    }

    static int autoGap(Context c) {
        return Math.max(0, Math.min(60000, sp(c).getInt(K_AUTO_GAP, 2000)));
    }

    static long autoLastAt(Context c) {
        return sp(c).getLong(K_AUTO_LAST_AT, 0L);
    }

    static String autoLastResult(Context c) {
        return sp(c).getString(K_AUTO_LAST_RESULT, "");
    }

    static void setAuto(Context c, boolean on, String time, String content,
                        boolean delete, int gap, String lastResult) {
        sp(c).edit()
            .putBoolean(K_AUTO_ON, on)
            .putString(K_AUTO_TIME, validTime(time) ? time : "09:00")
            .putString(K_AUTO_CONTENT, safe(content))
            .putBoolean(K_AUTO_DELETE, delete)
            .putInt(K_AUTO_GAP, Math.max(0, Math.min(60000, gap)))
            .putString(K_AUTO_LAST_RESULT, safe(lastResult))
            .apply();
    }

    static void setAutoResult(Context c, long at, String result) {
        sp(c).edit().putLong(K_AUTO_LAST_AT, at).putString(K_AUTO_LAST_RESULT, safe(result)).apply();
    }

    static String autoJson(Context c) {
        JSONObject j = new JSONObject();
        try {
            j.put("enabled", autoEnabled(c));
            j.put("time", autoTime(c));
            j.put("postContent", autoContent(c));
            j.put("deleteAfterPost", autoDelete(c));
            j.put("gapMs", autoGap(c));
            j.put("lastAt", autoLastAt(c));
            j.put("lastResult", autoLastResult(c));
        } catch (Exception ignored) {
        }
        return j.toString();
    }

    static String shortcutSecret(Context c) {
        SharedPreferences p = sp(c);
        String s = p.getString(K_SECRET, "");
        if (s.length() >= 32) return s;
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(40);
        for (byte x : b) sb.append(String.format("%02x", x));
        s = sb.toString();
        p.edit().putString(K_SECRET, s).apply();
        return s;
    }

    static boolean validTime(String t) {
        if (t == null) return false;
        String[] a = t.split(":");
        if (a.length != 2) return false;
        try {
            int h = Integer.parseInt(a[0]);
            int m = Integer.parseInt(a[1]);
            return h >= 0 && h < 24 && m >= 0 && m < 60;
        } catch (Exception e) {
            return false;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
