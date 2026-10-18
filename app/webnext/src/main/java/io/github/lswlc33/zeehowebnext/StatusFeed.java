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
    /** 数据没变时微件最多每 15 分钟重绘一次：只为让「更新于 HH:mm」不至于停在一个小时前。 */
    private static final long WIDGET_IDLE_REFRESH_MS = 15 * 60_000L;
    private static long lastWidgetAt;

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
        // 页面同步与后台轮询是两个写者。迟到的旧响应（尤其页面在 IO 队列里排过队）不能把
        // 新状态倒回去，否则会出现 1→0→1 的假跃迁，进而发"充电已停止"之类的假通知。
        long at = j.optLong("at", 0);
        if (at > 0 && old.updatedAt > 0 && at < old.updatedAt) {
            Log.d(TAG, "skip stale status at=" + at + " old=" + old.updatedAt);
            return;
        }
        NativeStore.Status s = old.copy();
        s.apply(j);
        if (s.updatedAt <= 0) s.updatedAt = System.currentTimeMillis();

        boolean messages = NativeStore.messagesEnabled(c);
        boolean charging = NativeStore.chargingEnabled(c);
        boolean widget = WidgetProviders.anyBound(c);

        // 车机/接口在 100% 时经常还会短暂报 charging=1；只要到 100% 就按"已充满"处理，
        // 否则进度通知会一直挂着。掉到 100% 且刚断开（old=1）也归到充满，而不是"中途停止"。
        if (s.soc >= 100) {
            if (s.charge == 1) {
                s.charge = 2;
                s.chargeKnown = true;
            } else if (s.charge == 0 && old.charge == 1) {
                s.charge = 2;
                s.chargeKnown = true;
            }
        }
        // 后台 60 秒一轮，绝大多数轮次车况是同一个数。以前每轮都 saveStatus + updateAll：
        // 整份 SharedPreferences 重写一遍，还要让桌面进程重绘三套 RemoteViews 并重新解码车图
        // （WidgetCar.load），是后台最贵的一段。现在只在两种情况写：
        //   1) 关键字段真的变了（sameData 比较，updatedAt 不参与）；
        //   2) 距上次写盘已超过 15 分钟（顺带把微件上的「更新于」推到最近一次同步，
        //      不然它会停在几小时前，看着像同步挂了）。
        // 通知判定照常往下跑：它看的是 old→s 的跃迁，数据没变时本来也不该发。
        final long nowMs = System.currentTimeMillis();
        final boolean changed = !s.sameData(old);
        final boolean idleRefresh = nowMs - lastWidgetAt > WIDGET_IDLE_REFRESH_MS;
        if (changed || idleRefresh) {
            NativeStore.saveStatus(c, s);
            WidgetProviders.updateAll(c);
            lastWidgetAt = nowMs;
        }
        // App 专属电量历史：页面同步与后台轮询都走这里，内部按 50 秒去重。
        BatteryHistory.record(c, s, j);

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
                    // 只有通知真的发出去才记"已提醒"：权限被关/渠道被静音时不要假装提醒过，
                    // 否则用户过两天打开权限，那条消息永远不会再出现。
                    if (Notify.message(c, s)) NativeStore.setSeen(c, s.noRead, s.msgId);
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
                    NativeStore.setStopNotified(c, false);
                }
                // 在页面里看到充满、之后退后台时仍补一次"已充满"提醒
                if (s.charge == 2 && old.charge != 2) {
                    NativeStore.setFullNotified(c, false);
                    NativeStore.setStopNotified(c, false);
                }
            }
        } else if (!charging) {
            Notify.cancelChargeProgress(c);
        } else if (s.charge == 2) {
            Notify.cancelChargeProgress(c);
            Notify.cancelChargeStop(c);
            if (!NativeStore.fullNotified(c)) {
                if (Notify.chargeFull(c, s)) NativeStore.setFullNotified(c, true);
            }
        } else if (s.charge == 1) {
            NativeStore.setFullNotified(c, false);
            if (old.charge != 1) {
                Notify.cancelAllCharge(c);
                NativeStore.setStopNotified(c, false);
                // 桌面已经挂着微件时，充满/停止事件仍提醒，但不再重复一条持续性的进度通知。
                if (!widget) Notify.chargeStart(c, s);
                ChargeLog.startSession(c, NativeStore.vin(c));
            }
            if (widget) Notify.cancelChargeProgress(c);
            else Notify.chargeProgress(c, s);
            ChargeLog.append(c, NativeStore.vin(c), s.soc, s.voltage, s.current, s.chargedMin);
        } else if (old.charge == 1) {
            // 未充满就断开：和「已充满」分开一条通知；已经提示过充满就不再补"停止"。
            Notify.cancelChargeProgress(c);
            if (!NativeStore.fullNotified(c) && !NativeStore.stopNotified(c)) {
                if (Notify.chargeStopped(c, s)) NativeStore.setStopNotified(c, true);
            }
            NativeStore.setFullNotified(c, false);
        } else {
            // 服务重启/旧进度通知残留：非充电状态下无条件收掉进度条。
            Notify.cancelChargeProgress(c);
        }

        // 可选提醒放最后：低电量/离线看的是 old→s 的跃迁，不受上面充电通知开关影响。
        Alerts.checkStatus(c, s, old, foreground);
    }

    /**
     * App 退回后台时调用：不发网络，只把当前已缓存的状态按"后台"口径补一次通知。
     * 典型场景：前台充电开始（页面里看得到），一按 Home 就该有进度通知。
     */
    static synchronized void reconcileBackground(Context c) {
        NativeStore.Status s = NativeStore.status(c);
        boolean widget = WidgetProviders.anyBound(c);
        if (s.soc >= 100 && s.charge == 1) {
            s.charge = 2;
            s.chargeKnown = true;
        }
        if (s.noRead > 0 && NativeStore.messagesEnabled(c)) {
            int seen = NativeStore.seenNoRead(c);
            String notified = NativeStore.notifiedMsgId(c);
            if (s.noRead > seen || (!s.msgId.isEmpty() && !s.msgId.equals(notified))) {
                if (Notify.message(c, s)) NativeStore.setSeen(c, s.noRead, s.msgId);
            }
        }
        if (NativeStore.chargingEnabled(c) && s.charge == 1) {
            if (widget) Notify.cancelChargeProgress(c);
            else Notify.chargeProgress(c, s);
        } else if (NativeStore.chargingEnabled(c) && s.charge == 2) {
            if (!NativeStore.fullNotified(c)) {
                if (Notify.chargeFull(c, s)) NativeStore.setFullNotified(c, true);
            }
            Notify.cancelChargeProgress(c);
        } else {
            Notify.cancelChargeProgress(c);
        }
        WidgetProviders.updateAll(c);
    }
}
