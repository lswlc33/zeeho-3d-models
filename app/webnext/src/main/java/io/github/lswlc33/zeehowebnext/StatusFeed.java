package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把网页/后台轮询拿到的同一份车辆摘要落到 NativeStore，并在这里统一决定要不要发通知。
 *
 * 前台（App 正在看）不弹通知：页面自己已经有红点/充电卡；但会把"已看到"的未读数记下来，
 * 退回后台后不会把刚才已经看过的消息再推一遍。后台（服务/定时任务）才真正发通知。
 */
final class StatusFeed {

    private static final String TAG = "ZeehoWebNext";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private StatusFeed() {
    }

    /** WebView 的 JavaBridge 线程不能直接做文件/通知，扔到单线程里顺序处理。 */
    static void pageAsync(Context c, String json) {
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            try {
                apply(app, new JSONObject(json), AppState.foreground);
            } catch (Exception e) {
                Log.w(TAG, "syncStatus parse failed", e);
            }
        });
    }

    static synchronized void apply(Context c, JSONObject j, boolean foreground) {
        NativeStore.Status old = NativeStore.status(c);
        NativeStore.Status s = old.copy();
        s.apply(j);
        if (s.updatedAt <= 0) s.updatedAt = System.currentTimeMillis();
        NativeStore.saveStatus(c, s);

        boolean messages = NativeStore.messagesEnabled(c);
        boolean charging = NativeStore.chargingEnabled(c);

        if (s.noRead >= 0) {
            if (foreground) {
                NativeStore.setSeen(c, s.noRead, s.msgId);
                Notify.cancelMessage(c);
            } else if (!messages) {
                Notify.cancelMessage(c);
            } else if (s.noRead == 0) {
                Notify.cancelMessage(c);
                NativeStore.setSeen(c, 0, s.msgId);
            } else {
                int seen = NativeStore.seenNoRead(c);
                String notified = NativeStore.notifiedMsgId(c);
                boolean fresh = s.noRead > seen
                    || (!s.msgId.isEmpty() && !s.msgId.equals(notified));
                if (fresh) {
                    Notify.message(c, s);
                    NativeStore.setSeen(c, s.noRead, s.msgId);
                }
            }
        }

        if (foreground) {
            // 在 App 里看得到状态，不需要再挂一条进度通知；进前台时把旧的收掉。
            if (s.charge == 0) {
                Notify.cancelAllCharge(c);
                NativeStore.setFullNotified(c, false);
            } else {
                Notify.cancelChargeProgress(c);
                if (s.charge == 1 && old.charge != 1) {
                    ChargeLog.startSession(c, NativeStore.vin(c));
                    NativeStore.setFullNotified(c, false);
                }
                // 在页面里看到充满、之后退后台时仍补一次"已充满"提醒
                if (s.charge == 2 && old.charge != 2) NativeStore.setFullNotified(c, false);
            }
        } else if (!charging) {
            Notify.cancelChargeProgress(c);
        } else if (s.charge == 2) {
            Notify.cancelChargeProgress(c);
            if (!NativeStore.fullNotified(c)) {
                Notify.chargeFull(c, s);
                NativeStore.setFullNotified(c, true);
            }
        } else if (s.charge == 1) {
            NativeStore.setFullNotified(c, false);
            if (old.charge != 1) {
                Notify.cancelAllCharge(c);
                Notify.chargeStart(c, s);
                ChargeLog.startSession(c, NativeStore.vin(c));
            }
            Notify.chargeProgress(c, s);
            ChargeLog.append(c, NativeStore.vin(c), s.soc, s.voltage, s.current, s.chargedMin);
        } else if (old.charge == 1) {
            // 结束充电：收掉进度；已经弹过的"已充满"保留给用户看
            Notify.cancelChargeProgress(c);
            NativeStore.setFullNotified(c, false);
        }

        WidgetProviders.updateAll(c);
    }

    /**
     * App 退回后台时调用：不发网络，只把当前已缓存的状态按"后台"口径补一次通知。
     * 典型场景：前台充电开始（页面里看得到），一按 Home 就该有进度通知。
     */
    static synchronized void reconcileBackground(Context c) {
        NativeStore.Status s = NativeStore.status(c);
        if (s.noRead > 0 && NativeStore.messagesEnabled(c)) {
            int seen = NativeStore.seenNoRead(c);
            String notified = NativeStore.notifiedMsgId(c);
            if (s.noRead > seen || (!s.msgId.isEmpty() && !s.msgId.equals(notified))) {
                Notify.message(c, s);
                NativeStore.setSeen(c, s.noRead, s.msgId);
            }
        }
        if (NativeStore.chargingEnabled(c) && s.charge == 1) {
            Notify.chargeProgress(c, s);
        } else if (NativeStore.chargingEnabled(c) && s.charge == 2) {
            if (!NativeStore.fullNotified(c)) {
                Notify.chargeFull(c, s);
                NativeStore.setFullNotified(c, true);
            }
            Notify.cancelChargeProgress(c);
        } else {
            Notify.cancelChargeProgress(c);
        }
        WidgetProviders.updateAll(c);
    }
}
