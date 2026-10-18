package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 可选的车况提醒（默认全关）。只做"明确、不会误报"的三类：
 *   · 低电量：≤20% 且未充电，同一次放电只提醒一次，充电或回到 25% 以上后复位；
 *   · 车辆离线：在线 → 离线的那一次跃迁；
 *   · 胎压异常：车机 warningType 非 0，或压力低于 1.9 bar（15 分钟兜底任务里才查，不进 60 秒轮询）。
 *
 * 没有做「未锁车」：headLockState 实际是整车开机/关机，骑车途中就是"未锁"，
 * 按它提醒会在每次骑行时误报。要做只能结合蓝牙距离，代价和误报都不划算。
 */
final class Alerts {

    private static final String TAG = "ZeehoWebNext";
    private static final float TIRE_WARN_BAR = 1.9f;

    private Alerts() {
    }

    /** 60 秒轮询/页面同步都会走这里；前台不提醒（用户自己看得到）。 */
    static void checkStatus(Context c, NativeStore.Status s, NativeStore.Status old, boolean foreground) {
        if (foreground) return;
        if (NativeStore.alertLow(c)) {
            if (s.charge == 1 || s.charge == 2 || s.soc >= 25) {
                NativeStore.setLowNotified(c, false);
            } else if (s.soc >= 0 && s.soc <= 20 && !NativeStore.lowNotified(c)) {
                if (Notify.lowBattery(c, s)) NativeStore.setLowNotified(c, true);
            }
        }
        if (NativeStore.alertOffline(c) && s.online == 0 && old.online == 1) {
            Notify.offline(c, s);
        }
    }

    /** 胎压检查：只在 15 分钟兜底任务里调用，避免给 60 秒轮询再加一个请求。 */
    static void checkTire(Context c) {
        if (!NativeStore.alertTire(c) || NativeStore.authFailed(c)) return;
        String token = NativeStore.token(c);
        String vin = NativeStore.vin(c);
        if (token.isEmpty() || vin.isEmpty()) return;
        try {
            ApiClient api = new ApiClient(token);
            ApiClient.Result r = api.tireMonitor(vin);
            if (!r.ok) return;
            JSONObject d = ApiClient.obj(r);
            if (d == null) return;
            JSONArray list = d.optJSONArray("realTimeData");
            if (list == null) return;
            String badPos = "";
            double badBar = 0;
            int badWarn = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                if (t == null) continue;
                double p = t.optDouble("tirePressure", -1);
                int warn = t.optInt("warningType", 0);
                boolean bad = warn > 0 || (p > 0 && p < TIRE_WARN_BAR);
                if (bad) {
                    badPos = c.getString(t.optInt("sensorPosition", 0) == 1
                        ? R.string.tire_front : R.string.tire_rear);
                    badBar = p;
                    badWarn = warn;
                    break;
                }
            }
            if (badPos.isEmpty()) {
                NativeStore.setTireNotified(c, false);
            } else if (!NativeStore.tireNotified(c)) {
                if (Notify.tire(c, badPos, badBar, badWarn)) NativeStore.setTireNotified(c, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "tire alert check failed", e);
        }
    }
}
