package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台轮询：车况 + 电池 + 消息未读。前台页面每 30 秒自己会推一份，这里在前台且刚推过时跳过，
 * 避免同一份数据打两遍接口；退出后台后由服务/定时任务接手。
 */
final class Poller {

    private static final String TAG = "ZeehoWebNext";
    private static final long PAGE_FRESH_MS = 90_000L;
    /** 同一时刻只允许一轮在跑：前台服务和 15 分钟 Job 可能同时触发，重复打接口只会浪费。 */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** 两轮之间的最小间隔：服务 tick 是 60 秒，Job 兜底是 15 分钟，30 秒足够挡住并发重复。 */
    private static final long MIN_INTERVAL_MS = 30_000L;
    /** 鉴权失败后的冷却：到点仍会重试一次，成功就自动恢复（以前一旦置位就永久停摆）。 */
    private static final long AUTH_RETRY_MS = 30 * 60_000L;
    private static volatile long lastAttemptAt;

    private Poller() {
    }

    static void poll(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < MIN_INTERVAL_MS) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        lastAttemptAt = now;
        try {
            pollOnce(context, false);
        } finally {
            RUNNING.set(false);
        }
    }

    /**
     * 手动刷新（微件上的按钮）：绕过 30 秒节流和鉴权冷却，但仍然串行、不排队。
     * 不依赖后台同步开关/前台服务——用户点的那一下就该真的打一次接口。
     */
    static void pollNow(Context context) {
        if (!RUNNING.compareAndSet(false, true)) return;
        lastAttemptAt = System.currentTimeMillis();
        try {
            pollOnce(context, true);
        } finally {
            RUNNING.set(false);
        }
    }

    private static void pollOnce(Context context, boolean force) {
        Context c = context.getApplicationContext();
        String token = NativeStore.token(c);
        String vin = NativeStore.vin(c);
        String uid = NativeStore.uid(c);
        if (token.isEmpty() || vin.isEmpty()) return;
        // 冷却期内不再打网络，但到点必须retry：用同一条 token 重新登录、或上次是误判时，
        // 这里能自己恢复；不再需要用户去改 token/VIN 才解冻。
        if (!force && NativeStore.authFailed(c)
            && System.currentTimeMillis() - NativeStore.authFailedAt(c) < AUTH_RETRY_MS) {
            return;
        }
        if (AppState.foreground && System.currentTimeMillis() - NativeStore.lastPageSync(c) < PAGE_FRESH_MS) {
            return;
        }

        ApiClient api = new ApiClient(token);
        ApiClient.Result home = api.vehicleHome(vin);
        if (home.auth) {
            failedAuth(c);
            return;
        }
        ApiClient.Result battery = api.batteryInfo(vin);
        if (battery.auth) {
            failedAuth(c);
            return;
        }
        // 后台只需要 noRead（红点/通知判重）和第一条消息的标题；noRead 与页大小无关。
        // 拉 20 条正文（extra/地址/缩略图 URL）会让每分钟一轮的流量多出一个数量级，纯浪费。
        ApiClient.Result msg = uid.isEmpty() ? null : api.msgList(uid, 1);
        if (msg != null && msg.auth) {
            failedAuth(c);
            return;
        }

        JSONObject j = new JSONObject();
        try {
            JSONObject h = ApiClient.obj(home);
            JSONObject b = ApiClient.obj(battery);
            if (h == null && b == null && (msg == null || !msg.ok)) {
                return;
            }
            int soc = -1;
            int voltage = -1;
            int current = -1;
            int charge = 0;
            boolean chargeKnown = false;
            int lock = -1;
            int seat = -1;
            int online = -1;
            String voltageText = "";
            String eta = "";
            int fullMin = -1;
            String charged = "";
            int chargedMin = -1;

            if (h != null) {
                soc = ApiClient.intOr(h, "bmssoc", -1);
                String code = ApiClient.text(h, "chargeState");
                chargeKnown = h.has("chargeState") || h.has("whetherChargeState");
                if ("2".equals(code)) charge = 2;
                else if ("1".equals(code) || "3".equals(code)) charge = 1;
                else if (code.isEmpty() && ApiClient.on(h.opt("whetherChargeState"))) charge = 1;
                else charge = 0;
                lock = ApiClient.on(h.opt("headLockState")) ? 1 : 0;
                seat = ApiClient.on(h.opt("seatLockState")) ? 1 : 0;
                String os = ApiClient.text(h, "onlineStatus");
                online = ("ONLINE".equalsIgnoreCase(os) || ApiClient.on(os)) ? 1 : 0;
                eta = ApiClient.text(h, "expectFullTimeDescribe");
                fullMin = ApiClient.fullMinutes(h);
                charged = ApiClient.text(h, "chargeTimeString");
                chargedMin = ApiClient.chargedMinutes(charged);
                voltageText = ApiClient.text(h, "voltage");
                voltage = ApiClient.intOr(h, "voltage", ApiClient.intOr(h, "bmsVoltage", -1));
                String pic = ApiClient.text(h, "vehiclePicUrl");
                if (!pic.isEmpty()) WidgetCar.ensure(c, vin, pic);
            }
            if (b != null) {
                if (soc < 0) soc = ApiClient.intOr(b, "bmssoc", -1);
                voltage = ApiClient.intOr(b, "voltage", ApiClient.intOr(b, "bmsVoltage", -1));
                if (voltageText.isEmpty()) voltageText = ApiClient.text(b, "voltage");
                current = ApiClient.intOr(b, "current", -1);
                if (fullMin <= 0) fullMin = ApiClient.fullMinutes(b);
                if (!chargeKnown) {
                    boolean hasState = b.has("chargeState") || b.has("whetherChargeState")
                        || b.has("chargeStateStr");
                    String bc = ApiClient.text(b, "chargeState");
                    if ("2".equals(bc)) charge = 2;
                    else if ("1".equals(bc) || "3".equals(bc)
                        || ApiClient.on(b.opt("whetherChargeState"))) charge = 1;
                    else {
                        String bt = ApiClient.text(b, "chargeStateStr");
                        if (bt.contains("充满")) charge = 2;
                        else if (bt.contains("充电")) charge = 1;
                    }
                    // 只有接口真的带了充电状态字段才算"知道"；没带就不能往下写 charging=0
                    if (hasState) chargeKnown = true;
                }
            }
            if (soc >= 0) j.put("soc", soc);
            // ⚠️ 只有明确知道充电状态时才写。车况接口超时、电池也没报状态时，charge 仍是初始值 0——
            // 以前无条件 put，会把正在充电的车写成"未充电"，StatusFeed 看到 1→0 就发假的"充电已停止"。
            if (chargeKnown) {
                if (soc >= 100 && charge == 1) charge = 2;
                j.put("charging", charge);
            }
            if (voltageText.isEmpty() && voltage >= 0) voltageText = String.valueOf(voltage);
            if (!voltageText.isEmpty()) j.put("voltageText", voltageText);
            if (voltage >= 0 && current != 0) {
                j.put("power", Math.abs(Math.round(voltage * (float) current)));
            }
            if (voltage >= 0) j.put("voltage", voltage);
            if (current != -1) j.put("current", current);
            if (lock >= 0) j.put("lock", lock);
            if (seat >= 0) j.put("seat", seat);
            if (online >= 0) j.put("online", online);
            if (!eta.isEmpty()) j.put("eta", eta);
            if (fullMin > 0) j.put("fullMin", fullMin);
            if (!charged.isEmpty()) j.put("charged", charged);
            if (chargedMin >= 0) j.put("chargedMin", chargedMin);

            if (msg != null && msg.ok) {
                JSONObject md = ApiClient.obj(msg);
                if (md != null) {
                    j.put("noRead", ApiClient.intOr(md, "noRead", 0));
                    JSONArray list = md.optJSONArray("messageVOList");
                    if (list != null && list.length() > 0) {
                        JSONObject first = list.optJSONObject(0);
                        if (first != null) {
                            j.put("msgTitle", ApiClient.text(first, "content"));
                            j.put("msgId", ApiClient.text(first, "messageId"));
                            j.put("msgTime", ApiClient.text(first, "messageTime"));
                        }
                    }
                }
            }
            j.put("at", System.currentTimeMillis());

            // 只要有一条接口真的成功，就说明 token 是好的（可能是上一次误判，或者用户重新登录了）：
            // 立刻解除鉴权标记，别让后台继续停摆。
            if (home.ok || battery.ok || (msg != null && msg.ok)) {
                NativeStore.setAuthFailed(c, false);
            }
            NativeStore.setLastPoll(c);
            StatusFeed.apply(c, j, false);
        } catch (Exception e) {
            Log.w(TAG, "poll parse failed", e);
        }
    }

    private static void failedAuth(Context c) {
        boolean was = NativeStore.authFailed(c);
        NativeStore.setAuthFailed(c, true);
        // 冷却重试再次失败不重复弹：一次失效只提醒一次，恢复后（成功轮询）标记会自己清掉。
        if (!was) Notify.auth(c);
    }
}
