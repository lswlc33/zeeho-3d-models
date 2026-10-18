package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 后台轮询：车况 + 电池 + 消息未读。前台页面每 30 秒自己会推一份，这里在前台且刚推过时跳过，
 * 避免同一份数据打两遍接口；退出后台后由服务/定时任务接手。
 */
final class Poller {

    private static final String TAG = "ZeehoWebNext";
    private static final long PAGE_FRESH_MS = 90_000L;

    private Poller() {
    }

    static void poll(Context context) {
        Context c = context.getApplicationContext();
        String token = NativeStore.token(c);
        String vin = NativeStore.vin(c);
        String uid = NativeStore.uid(c);
        if (token.isEmpty() || vin.isEmpty()) return;
        if (NativeStore.authFailed(c)) return;
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
        ApiClient.Result msg = uid.isEmpty() ? null : api.msgList(uid, 20);
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
            int lock = -1;
            int seat = -1;
            int online = -1;
            int range = -1;
            String location = "";
            String eta = "";
            int fullMin = -1;
            String charged = "";
            int chargedMin = -1;

            if (h != null) {
                soc = ApiClient.intOr(h, "bmssoc", -1);
                String code = ApiClient.text(h, "chargeState");
                if ("2".equals(code)) charge = 2;
                else if ("1".equals(code) || "3".equals(code) || ApiClient.on(h.opt("whetherChargeState"))) charge = 1;
                lock = ApiClient.on(h.opt("headLockState")) ? 1 : 0;
                seat = ApiClient.on(h.opt("seatLockState")) ? 1 : 0;
                String os = ApiClient.text(h, "onlineStatus");
                online = ("ONLINE".equalsIgnoreCase(os) || ApiClient.on(os)) ? 1 : 0;
                eta = ApiClient.text(h, "expectFullTimeDescribe");
                fullMin = ApiClient.fullMinutes(h);
                charged = ApiClient.text(h, "chargeTimeString");
                chargedMin = ApiClient.chargedMinutes(charged);

                range = ApiClient.intOr(h, "hmiRidableMile", -1);
                if (range < 0) range = ApiClient.intOr(h, "hmiRidableMileAbnormalShow", -1);
                location = ApiClient.text(h, "address");
                double lat = Double.NaN, lng = Double.NaN;
                JSONObject loc = h.optJSONObject("location");
                if (loc != null) {
                    if (location.isEmpty()) location = ApiClient.text(loc, "address");
                    if (location.isEmpty()) location = ApiClient.text(loc, "addressName");
                    lat = loc.optDouble("latitude", Double.NaN);
                    lng = loc.optDouble("longitude", Double.NaN);
                }
                String pic = ApiClient.text(h, "vehiclePicUrl");
                if (!pic.isEmpty()) WidgetCar.ensure(c, vin, pic);
                if (location.isEmpty() && !Double.isNaN(lat) && !Double.isNaN(lng)) {
                    location = Geo.resolve(c, lat, lng);
                }
            }
            if (b != null) {
                if (soc < 0) soc = ApiClient.intOr(b, "bmssoc", -1);
                if (range < 0) range = ApiClient.intOr(b, "hmiRidableMile", -1);
                voltage = ApiClient.intOr(b, "voltage", ApiClient.intOr(b, "bmsVoltage", -1));
                current = ApiClient.intOr(b, "current", -1);
                if (fullMin <= 0) fullMin = ApiClient.fullMinutes(b);
                if (charge == 0) {
                    String bc = ApiClient.text(b, "chargeState");
                    if ("2".equals(bc)) charge = 2;
                    else if ("1".equals(bc) || "3".equals(bc)
                        || ApiClient.on(b.opt("whetherChargeState"))) charge = 1;
                    else {
                        String bt = ApiClient.text(b, "chargeStateStr");
                        if (bt.contains("充满")) charge = 2;
                        else if (bt.contains("充电")) charge = 1;
                    }
                }
            }
            if (soc >= 100 && charge == 1) charge = 2;
            if (soc >= 0) j.put("soc", soc);
            j.put("charging", charge);
            if (range >= 0) j.put("range", range);
            if (!location.isEmpty()) j.put("location", location);
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

            NativeStore.setLastPoll(c);
            StatusFeed.apply(c, j, false);
        } catch (Exception e) {
            Log.w(TAG, "poll parse failed", e);
        }
    }

    private static void failedAuth(Context c) {
        NativeStore.setAuthFailed(c, true);
        Notify.auth(c);
    }
}
