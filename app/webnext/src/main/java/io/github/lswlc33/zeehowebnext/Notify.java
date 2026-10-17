package io.github.lswlc33.zeehowebnext;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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

    static final String CH_SERVICE = "service";
    static final String CH_MSG = "messages";
    static final String CH_CHARGE = "charging";
    static final String CH_TASK = "tasks";
    static final String CH_ACTION = "actions";

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
        channel(nm, CH_TASK, c.getString(R.string.channel_tasks),
            c.getString(R.string.channel_tasks_desc), NotificationManager.IMPORTANCE_DEFAULT, true);
        channel(nm, CH_ACTION, c.getString(R.string.channel_actions),
            c.getString(R.string.channel_actions_desc), NotificationManager.IMPORTANCE_LOW, false);
    }

    private static void channel(NotificationManager nm, String id, String name, String desc,
                                int importance, boolean sound) {
        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.setDescription(desc);
        if (!sound) ch.setSound(null, null);
        ch.setShowBadge(importance >= NotificationManager.IMPORTANCE_DEFAULT);
        if (CH_MSG.equals(id) || CH_CHARGE.equals(id)) {
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

    static void message(Context c, NativeStore.Status s) {
        if (!allowed(c) || s.noRead <= 0) return;
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
        notify(c, ID_MSG, b.build());
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
            .setVisibility(Notification.VISIBILITY_PUBLIC);
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

    static void chargeFull(Context c, NativeStore.Status s) {
        if (!allowed(c)) return;
        Notification.Builder b = base(c, CH_CHARGE, R.string.notify_charge_full_title, 0)
            .setContentTitle(c.getString(R.string.notify_charge_full_title))
            .setContentText(s.soc >= 0
                ? c.getString(R.string.notify_charge_full_text, s.soc)
                : c.getString(R.string.notify_charge_full_text_unknown))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false);
        notify(c, ID_FULL, b.build());
    }

    static void cancelChargeProgress(Context c) {
        cancel(c, ID_CHARGE);
        cancel(c, ID_START);
    }

    static void cancelAllCharge(Context c) {
        cancel(c, ID_CHARGE);
        cancel(c, ID_START);
        cancel(c, ID_FULL);
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
            .setCategory(Notification.CATEGORY_STATUS);
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

    private static void notify(Context c, int id, Notification n) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(id, n);
            } catch (SecurityException ignored) {
                // 没给通知权限：后台照常跑，用户开权限后下次事件会补上
            }
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
