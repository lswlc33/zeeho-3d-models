package io.github.lswlc33.zeehowebnext;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 所有原生通知的唯一入口。充电进度通知沿用同一个 ID 更新（setOnlyAlertOnce），
 * 前台服务的常驻通知单独一条低优先级通知，不抢充电/消息的注意力。
 */
final class Notify {

    static final int ID_SERVICE = 1000;
    static final int ID_MSG = 1001;
    static final int ID_CHARGE = 1002;
    static final int ID_START = 1003;
    static final int ID_FULL = 1004;
    static final int ID_TASK = 1005;
    static final int ID_ACTION = 1006;
    static final int ID_AUTH = 1007;
    static final int ID_STOP = 1008;
    static final int ID_LOW = 1009;
    static final int ID_OFFLINE = 1010;
    static final int ID_TIRE = 1011;
    static final int ID_CAPTURE_WAIT = 1012;
    static final int ID_CAPTURE_DONE = 1013;

    static final String CH_SERVICE = "service";
    static final String CH_MSG = "messages";
    static final String CH_CHARGE = "charging";
    // 重要事件渠道升级为 v2（HIGH）：Android 渠道重要性创建后不可改，必须换 ID 才能让老用户生效。
    static final String CH_CHARGE_EVENT = "charge_events_v2";
    static final String CH_TASK = "tasks_v2";
    static final String CH_ACTION = "actions";
    static final String CH_ALERTS = "alerts_v2";
    static final String CH_CAPTURE_WAIT = "capture_wait";
    static final String CH_CAPTURE_DONE = "capture_done_v2";

    private Notify() {
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        channel(nm, CH_SERVICE, c.getString(R.string.channel_service),
            c.getString(R.string.channel_service_desc), NotificationManager.IMPORTANCE_MIN, false);
        channel(nm, CH_MSG, c.getString(R.string.channel_messages),
            c.getString(R.string.channel_messages_desc), NotificationManager.IMPORTANCE_DEFAULT, true);
        channel(nm, CH_CHARGE, c.getString(R.string.channel_charging),
            c.getString(R.string.channel_charging_desc), NotificationManager.IMPORTANCE_LOW, true);
        channel(nm, CH_CHARGE_EVENT, c.getString(R.string.channel_charge_events),
            c.getString(R.string.channel_charge_events_desc), NotificationManager.IMPORTANCE_HIGH, true);
        channel(nm, CH_TASK, c.getString(R.string.channel_tasks),
            c.getString(R.string.channel_tasks_desc), NotificationManager.IMPORTANCE_HIGH, true);
        channel(nm, CH_ACTION, c.getString(R.string.channel_actions),
            c.getString(R.string.channel_actions_desc), NotificationManager.IMPORTANCE_LOW, false);
        channel(nm, CH_ALERTS, c.getString(R.string.channel_alerts),
            c.getString(R.string.channel_alerts_desc), NotificationManager.IMPORTANCE_HIGH, true);
        channel(nm, CH_CAPTURE_WAIT, c.getString(R.string.channel_capture_wait),
            c.getString(R.string.channel_capture_wait_desc), NotificationManager.IMPORTANCE_LOW, false);
        channel(nm, CH_CAPTURE_DONE, c.getString(R.string.channel_capture_done),
            c.getString(R.string.channel_capture_done_desc), NotificationManager.IMPORTANCE_HIGH, true);
        // 旧渠道（DEFAULT，没有横幅）已经不再使用；删掉，避免设置里留下两个同名开关。
        delete(nm, "charge_events");
        delete(nm, "tasks");
        delete(nm, "alerts");
        delete(nm, "capture_done");
    }

    private static void delete(NotificationManager nm, String id) {
        try { nm.deleteNotificationChannel(id); } catch (Exception ignored) { }
    }

    private static void channel(NotificationManager nm, String id, String name, String desc,
                                int importance, boolean sound) {
        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.setDescription(desc);
        if (!sound) ch.setSound(null, null);
        ch.setShowBadge(importance >= NotificationManager.IMPORTANCE_DEFAULT);
        if (sound || importance >= NotificationManager.IMPORTANCE_HIGH
            || CH_MSG.equals(id) || CH_CHARGE.equals(id)) {
            ch.enableVibration(true);
        }
        nm.createNotificationChannel(ch);
    }

    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33
            && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != 0) {
            return false;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    static Notification service(Context c) {
        return base(c, CH_SERVICE, R.string.monitor_running, R.string.monitor_running_text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build();
    }

    static boolean message(Context c, NativeStore.Status s) {
        if (!allowed(c) || s.noRead <= 0) return false;
        String title = s.msgTitle == null || s.msgTitle.isEmpty()
            ? c.getString(R.string.notify_message_title) : s.msgTitle;
        String text = c.getString(R.string.notify_message_text, s.noRead, title);
        Notification.Builder b = base(c, CH_MSG, R.string.notify_message_title, 0)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setNumber(s.noRead)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(s.updatedAt > 0 ? s.updatedAt : System.currentTimeMillis());
        return notify(c, ID_MSG, b.build());
    }

    static void cancelMessage(Context c) {
        cancel(c, ID_MSG);
    }

    static void chargeStart(Context c, NativeStore.Status s) {
        if (!allowed(c)) return;
        Notification.Builder b = base(c, CH_CHARGE, R.string.notify_charge_start_title, 0)
            .setContentTitle(c.getString(R.string.notify_charge_start_title))
            .setContentText(s.soc >= 0
                ? c.getString(R.string.notify_charge_start_text, s.soc)
                : c.getString(R.string.notify_charge_start_text_unknown))
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH);
        notify(c, ID_START, b.build());
    }

    static void chargeProgress(Context c, NativeStore.Status s) {
        if (!allowed(c) || s.charge != 1) return;
        String power = powerText(s.power);
        String time = timeText(c, s);
        String text;
        if (!power.isEmpty() && !time.isEmpty()) text = power + " · " + time;
        else if (!power.isEmpty()) text = power;
        else if (!time.isEmpty()) text = time;
        else text = c.getString(R.string.notify_charge_unknown);

        Notification.Builder b = base(c, CH_CHARGE, R.string.notify_charge_title_unknown, 0)
            .setContentTitle(s.soc >= 0
                ? c.getString(R.string.notify_charge_title, s.soc)
                : c.getString(R.string.notify_charge_title_unknown))
            .setContentText(text)
            .setSubText(NativeStore.name(c))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(s.updatedAt > 0 ? s.updatedAt : System.currentTimeMillis());
        if (s.soc >= 0 && s.soc <= 100) {
            b.setProgress(100, s.soc, false);
        }
        notify(c, ID_CHARGE, b.build());
    }

    static boolean chargeFull(Context c, NativeStore.Status s) {
        if (!allowed(c)) return false;
        Notification.Builder b = base(c, CH_CHARGE_EVENT, R.string.notify_charge_full_title, 0)
            .setContentTitle(c.getString(R.string.notify_charge_full_title))
            .setContentText(s.soc >= 0
                ? c.getString(R.string.notify_charge_full_text, s.soc)
                : c.getString(R.string.notify_charge_full_text_unknown))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOnlyAlertOnce(false);
        return notify(c, ID_FULL, b.build());
    }

    /** 充电中途结束（未充满）：和「已充满」分开一条通知，避免用户以为已经充满。 */
    static boolean chargeStopped(Context c, NativeStore.Status s) {
        if (!allowed(c)) return false;
        Notification.Builder b = base(c, CH_CHARGE_EVENT, R.string.notify_charge_stop_title, 0)
            .setContentTitle(c.getString(R.string.notify_charge_stop_title))
            .setContentText(s.soc >= 0
                ? c.getString(R.string.notify_charge_stop_text, s.soc)
                : c.getString(R.string.notify_charge_stop_text_unknown))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOnlyAlertOnce(false);
        return notify(c, ID_STOP, b.build());
    }

    /** 低电量（默认关闭，用户自己开）：同一次放电只提醒一次，充电或回到 25% 以上后复位。 */
    static boolean lowBattery(Context c, NativeStore.Status s) {
        if (!allowed(c) || s.soc < 0) return false;
        Notification.Builder b = base(c, CH_ALERTS, R.string.notify_low_title, 0)
            .setContentTitle(c.getString(R.string.notify_low_title))
            .setContentText(c.getString(R.string.notify_low_text, s.soc))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH);
        return notify(c, ID_LOW, b.build());
    }

    /** 在线 → 离线的那一次跃迁（默认关闭）。 */
    static boolean offline(Context c, NativeStore.Status s) {
        if (!allowed(c)) return false;
        Notification.Builder b = base(c, CH_ALERTS, R.string.notify_offline_title, 0)
            .setContentTitle(c.getString(R.string.notify_offline_title))
            .setContentText(c.getString(R.string.notify_offline_text,
                NativeStore.name(c).isEmpty() ? c.getString(R.string.app_name) : NativeStore.name(c)))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH);
        return notify(c, ID_OFFLINE, b.build());
    }

    /** 胎压异常（默认关闭，15 分钟兜底任务里检查）。 */
    static boolean tire(Context c, String pos, double bar, int warn) {
        if (!allowed(c)) return false;
        String value = String.format(Locale.CHINA, "%.1f", bar);
        Notification.Builder b = base(c, CH_ALERTS, R.string.notify_tire_title, 0)
            .setContentTitle(c.getString(R.string.notify_tire_title))
            .setContentText(c.getString(R.string.notify_tire_text, pos, value,
                c.getString(warn > 0 ? R.string.tire_reason_warn : R.string.tire_reason_low)))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH);
        return notify(c, ID_TIRE, b.build());
    }

    /** 自动抓取的常驻“等待中”通知（前台服务用，不响铃）。 */
    static Notification captureWaiting(Context c) {
        Notification.Builder b = base(c, CH_CAPTURE_WAIT, R.string.capture_wait_title, 0)
            .setContentTitle(c.getString(R.string.capture_wait_title))
            .setContentText(c.getString(R.string.capture_wait_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false);
        // 页面打不开/卡住时，用户仍能从通知栏一键停 VPN，恢复网络。
        Intent stop = new Intent(c, AutoCaptureService.class).setAction(AutoCaptureService.ACTION_STOP);
        PendingIntent sp = PendingIntent.getService(c, 501, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(
            Icon.createWithResource(c, R.drawable.ic_notify),
            c.getString(R.string.capture_cancel), sp).build());
        return b.build();
    }

    static void captureSuccess(Context c, String uid) {
        if (!allowed(c)) return;
        String masked = uid == null ? "" : uid.trim();
        if (masked.length() > 10) masked = masked.substring(0, 8) + "…";
        String text = masked.isEmpty()
            ? c.getString(R.string.capture_success_text_unknown)
            : c.getString(R.string.capture_success_text, masked);
        Notification.Builder b = base(c, CH_CAPTURE_DONE, R.string.capture_success_title, 0)
            .setContentTitle(c.getString(R.string.capture_success_title))
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PRIVATE);
        notify(c, ID_CAPTURE_DONE, b.build());
    }

    static void captureFailed(Context c, String message) {
        if (!allowed(c)) return;
        String text = message == null || message.isEmpty()
            ? c.getString(R.string.capture_failed_text) : message;
        Notification.Builder b = base(c, CH_CAPTURE_DONE, R.string.capture_failed_title, 0)
            .setContentTitle(c.getString(R.string.capture_failed_title))
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(Notification.PRIORITY_HIGH);
        notify(c, ID_CAPTURE_DONE, b.build());
    }

    static void cancelChargeProgress(Context c) {
        cancel(c, ID_CHARGE);
        cancel(c, ID_START);
    }

    static void cancelChargeStop(Context c) {
        cancel(c, ID_STOP);
    }

    static void cancelAllCharge(Context c) {
        cancel(c, ID_CHARGE);
        cancel(c, ID_START);
        cancel(c, ID_FULL);
        cancel(c, ID_STOP);
    }

    static void auth(Context c) {
        if (!allowed(c)) return;
        Notification.Builder b = base(c, CH_MSG, R.string.notify_auth_title, 0)
            .setContentTitle(c.getString(R.string.notify_auth_title))
            .setContentText(c.getString(R.string.notify_auth_text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ERROR);
        notify(c, ID_AUTH, b.build());
    }

    static void autoTask(Context c, boolean ok, String result) {
        if (!allowed(c)) return;
        Notification.Builder b = base(c, CH_TASK,
            ok ? R.string.notify_task_ok_title : R.string.notify_task_fail_title, 0)
            .setContentTitle(c.getString(ok ? R.string.notify_task_ok_title : R.string.notify_task_fail_title))
            .setContentText(result)
            .setStyle(new Notification.BigTextStyle().bigText(result))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(Notification.PRIORITY_HIGH);
        notify(c, ID_TASK, b.build());
    }

    static void action(Context c, boolean ok, String text) {
        if (!allowed(c)) return;
        Notification.Builder b = base(c, CH_ACTION, R.string.app_name, 0)
            .setContentTitle(c.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true);
        notify(c, ID_ACTION, b.build());
    }

    static void cancelService(Context c) {
        cancel(c, ID_SERVICE);
    }

    static void cancelAll(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancelAll();
    }

    // ---------------- 文案 ----------------

    private static String powerText(int w) {
        if (w <= 0) return "";
        if (w >= 1000) return String.format(Locale.CHINA, "功率 %.1f kW", w / 1000.0);
        return String.format(Locale.CHINA, "功率 %d W", w);
    }

    private static String timeText(Context c, NativeStore.Status s) {
        if (s.eta != null && !s.eta.isEmpty()) return s.eta;
        int min = s.fullMin;
        if (min <= 0) min = ChargeLog.estimateMinutes(c, NativeStore.vin(c), s.soc);
        if (min > 0 && min <= 12 * 60) {
            String clock = new SimpleDateFormat("HH:mm", Locale.CHINA)
                .format(new Date(System.currentTimeMillis() + min * 60_000L));
            return "预计 " + clock + " 充满";
        }
        if (s.charged != null && !s.charged.isEmpty()) {
            return "已充 " + s.charged;
        }
        if (s.chargedMin > 0) {
            int h = s.chargedMin / 60;
            int m = s.chargedMin % 60;
            return "已充 " + (h > 0 ? h + " 小时 " : "") + m + " 分";
        }
        return "";
    }

    private static Notification.Builder base(Context c, String channel, int titleRes, int textRes) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(c, channel)
            : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF2F7BF6)
            .setContentTitle(c.getString(titleRes))
            .setContentIntent(openApp(c));
        if (textRes != 0) b.setContentText(c.getString(textRes));
        return b;
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean notify(Context c, int id, Notification n) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        try {
            nm.notify(id, n);
            return true;
        } catch (SecurityException ignored) {
            // 没给通知权限：后台照常跑，用户开权限后下次事件会补上
            return false;
        }
    }

    private static void cancel(Context c, int id) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(id);
    }

    /** 给「后台设置」按钮用：直接跳到本应用的通知/电池设置。 */
    static Intent appSettingsIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(android.net.Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
