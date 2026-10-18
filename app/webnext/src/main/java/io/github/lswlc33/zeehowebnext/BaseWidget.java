package io.github.lswlc33.zeehowebnext;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 三个尺寸共用的绘制逻辑。数据只读 NativeStore，+ 刷新按钮触发一次后台轮询。 */
abstract class BaseWidget extends AppWidgetProvider {

    static final String ACTION_REFRESH = "io.github.lswlc33.zeehowebnext.WIDGET_REFRESH";

    abstract int layoutId();

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) bind(c, m, id, layoutId());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, android.os.Bundle newOptions) {
        bind(c, m, id, layoutId());
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        // WIDGET_REFRESH 已改由不导出的 WidgetRefreshReceiver 处理；这里不再接这个 action，
        // 否则第三方仍可显式发广播给 exported 的 widget receiver。
        super.onReceive(c, intent);
    }

    static void bind(Context c, AppWidgetManager m, int id, int layout) {
        NativeStore.Status s = NativeStore.status(c);
        RemoteViews rv = new RemoteViews(c.getPackageName(), layout);
        boolean two = layout == R.layout.widget_2x2;
        boolean four = layout == R.layout.widget_4x4;
        // 只有消息同步、没有车况时不要显示成"未充电 0%"；充电状态没报过也不能当成"未充电"。
        boolean hasData = s.soc >= 0 || s.chargeKnown || s.lock >= 0 || s.seat >= 0 || s.online >= 0;

        String name = NativeStore.name(c);
        if (name.isEmpty()) name = c.getString(R.string.widget_title);
        rv.setTextViewText(R.id.w_title, name);
        rv.setTextViewText(R.id.w_soc, s.soc >= 0 ? String.valueOf(s.soc) : c.getString(R.string.widget_soc_unknown));
        rv.setTextViewText(R.id.w_charge, state(c, s, hasData));
        rv.setTextColor(R.id.w_charge, c.getColor(
            !hasData ? R.color.widget_text_secondary
                : s.chargeKnown && s.charge == 1 ? R.color.widget_accent
                : s.chargeKnown && s.charge == 2 ? R.color.widget_good : R.color.widget_text_secondary));
        rv.setTextViewText(R.id.w_updated, hasData ? (two ? updatedShort(c, s) : updated(c, s)) : "");
        String voltage = voltage(c, s);
        rv.setTextViewText(R.id.w_voltage, voltage);
        rv.setViewVisibility(R.id.w_voltage, voltage.isEmpty() ? View.GONE : View.VISIBLE);

        if (two) {
            boolean lockKnown = s.lock >= 0;
            rv.setTextViewText(R.id.w_lock, lockKnown ? lock(c, s) : "");
            rv.setViewVisibility(R.id.w_lock, lockKnown ? View.VISIBLE : View.GONE);
        } else {
            String lockLine = lockLine(c, s);
            rv.setTextViewText(R.id.w_lock, lockLine);
            rv.setViewVisibility(R.id.w_lock, lockLine.isEmpty() ? View.GONE : View.VISIBLE);
            rv.setTextViewText(R.id.w_seat, seat(c, s));
            rv.setViewVisibility(R.id.w_seat, s.seat >= 0 ? View.VISIBLE : View.GONE);
        }
        if (s.soc >= 0) {
            rv.setViewVisibility(R.id.w_progress, View.VISIBLE);
            rv.setProgressBar(R.id.w_progress, 100, Math.max(0, Math.min(100, s.soc)), false);
        } else {
            rv.setViewVisibility(R.id.w_progress, View.GONE);
        }

        if (two) {
            rv.setTextViewText(R.id.w_sub, sub(c, s, hasData));
        } else {
            boolean charging = s.chargeKnown && s.charge == 1 && s.online != 0;
            rv.setTextViewText(R.id.w_power, charging ? power(s) : "");
            rv.setTextViewText(R.id.w_time, charging ? time(c, s) : "");
            rv.setOnClickPendingIntent(R.id.w_refresh, refresh(c));
        }

        // 车图对所有尺寸都一样：透明 PNG 直接铺在卡片上，没有 stage 背景；没图就整块收起。
        Bitmap car = WidgetCar.load(c, NativeStore.vin(c));
        if (car != null) {
            rv.setImageViewBitmap(R.id.w_car, car);
            rv.setViewVisibility(R.id.w_car_box, View.VISIBLE);
        } else {
            rv.setViewVisibility(R.id.w_car_box, View.GONE);
        }

        if (four) {
            rv.setOnClickPendingIntent(R.id.w_btn_unlock, action(c, "unlock", 301));
            rv.setOnClickPendingIntent(R.id.w_btn_lock, action(c, "lock", 302));
            rv.setOnClickPendingIntent(R.id.w_btn_horn, action(c, "horn", 303));
            rv.setOnClickPendingIntent(R.id.w_btn_seat, action(c, "seat", 304));
        } else if (!two) {
            rv.setOnClickPendingIntent(R.id.w_btn_unlock, action(c, "unlock", 301));
            rv.setOnClickPendingIntent(R.id.w_btn_horn, action(c, "horn", 303));
        }

        rv.setOnClickPendingIntent(R.id.w_root, openApp(c));
        try {
            m.updateAppWidget(id, rv);
        } catch (Exception ignored) {
        }
        // 桌面已经有微件时，充电进度在桌面就能看到；持续性的进度通知不再重复挂在通知栏。
        if (WidgetProviders.anyBound(c)) Notify.cancelChargeProgress(c);
    }

    private static String state(Context c, NativeStore.Status s, boolean hasData) {
        // 登录态失效时先说实话：否则微件会一直停在"未充电/已锁车"这种看起来正常的状态。
        if (NativeStore.authFailed(c)) return c.getString(R.string.widget_auth_failed);
        if (!hasData) return c.getString(R.string.widget_no_data);
        if (s.online == 0) return c.getString(R.string.widget_offline);
        // 充电状态没上报过就别说"未充电"：数据缺一块时宁可显示真实的车锁状态或"状态未知"。
        if (s.chargeKnown) {
            if (s.charge == 1) return c.getString(R.string.widget_charging);
            if (s.charge == 2) return c.getString(R.string.widget_full);
            return c.getString(R.string.widget_idle);
        }
        if (s.lock == 1) return c.getString(R.string.widget_locked);
        if (s.lock == 0) return c.getString(R.string.widget_unlocked);
        return c.getString(R.string.widget_state_unknown);
    }

    private static String sub(Context c, NativeStore.Status s, boolean hasData) {
        if (!hasData) return c.getString(R.string.widget_open);
        if (s.online == 0) return c.getString(R.string.widget_offline);
        if (s.chargeKnown && s.charge == 1) {
            StringBuilder sb = new StringBuilder();
            String p = power(s);
            String t = time(c, s);
            if (!p.isEmpty()) sb.append(p);
            if (!t.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(t);
            }
            return sb.length() == 0 ? c.getString(R.string.widget_charging) : sb.toString();
        }
        String on = online(c, s);
        if (!on.isEmpty()) return on;
        if (s.lock >= 0) return lock(c, s);
        return c.getString(R.string.widget_state_unknown);
    }

    private static String lockLine(Context c, NativeStore.Status s) {
        String lk = lock(c, s);
        if (s.online == 1) return lk.isEmpty() ? c.getString(R.string.widget_online)
            : lk + " · " + c.getString(R.string.widget_online);
        if (s.online == 0) return lk.isEmpty() ? c.getString(R.string.widget_offline)
            : lk + " · " + c.getString(R.string.widget_offline);
        return lk;
    }

    private static String voltage(Context c, NativeStore.Status s) {
        String v = s.voltageText == null ? "" : s.voltageText.trim();
        if (v.isEmpty() && s.voltage > 0) v = String.valueOf(s.voltage);
        if (v.isEmpty()) return "";
        // 车机偶尔会报 "0" / "0.0" / "--"：那不是电压，别显示成"电压 0 V"。
        // 也把可能带的 "V" 去掉，避免拼成 "82.8VV"。
        String num = v.replaceAll("[^0-9.]", "");
        try {
            if (Double.parseDouble(num) <= 0) return "";
        } catch (Exception e) {
            return "";
        }
        return c.getString(R.string.widget_voltage, num);
    }

    private static String lock(Context c, NativeStore.Status s) {
        if (s.lock == 1) return c.getString(R.string.widget_locked);
        if (s.lock == 0) return c.getString(R.string.widget_unlocked);
        return "";
    }

    private static String seat(Context c, NativeStore.Status s) {
        if (s.seat == 1) return c.getString(R.string.widget_seat_locked);
        if (s.seat == 0) return c.getString(R.string.widget_seat_unlocked);
        return "";
    }

    private static String online(Context c, NativeStore.Status s) {
        if (s.online == 1) return c.getString(R.string.widget_online);
        if (s.online == 0) return c.getString(R.string.widget_offline);
        return "";
    }

    private static String power(NativeStore.Status s) {
        if (s.power <= 0) return "";
        if (s.power >= 1000) return String.format(Locale.CHINA, "%.1f kW", s.power / 1000.0);
        return s.power + " W";
    }

    private static String time(Context c, NativeStore.Status s) {
        if (s.eta != null && !s.eta.isEmpty()) return s.eta;
        int min = s.fullMin;
        // 只用接口真正给了的值；历史充电曲线外推是 App 内的估算，不放到桌面冒充"预计"。
        if (min > 0 && min <= 12 * 60) {
            String clock = new SimpleDateFormat("HH:mm", Locale.CHINA)
                .format(new Date(System.currentTimeMillis() + min * 60_000L));
            return c.getString(R.string.widget_eta, clock);
        }
        if (s.charged != null && !s.charged.isEmpty()
            && !"-".equals(s.charged) && !"--".equals(s.charged)) {
            return c.getString(R.string.widget_charged, s.charged);
        }
        if (s.chargedMin > 0) {
            return c.getString(R.string.widget_charged,
                (s.chargedMin / 60 > 0 ? s.chargedMin / 60 + " 小时 " : "") + (s.chargedMin % 60) + " 分");
        }
        return "";
    }

    /** 2x2 的头部空间小，只给短时间；超过 30 分钟显示日期，不加长文案。 */
    private static String updatedShort(Context c, NativeStore.Status s) {
        if (s.updatedAt <= 0) return "";
        long age = System.currentTimeMillis() - s.updatedAt;
        if (age > 30 * 60_000L) {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(s.updatedAt));
        }
        return c.getString(R.string.widget_updated,
            new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(s.updatedAt)));
    }

    private static String updated(Context c, NativeStore.Status s) {
        if (s.updatedAt <= 0) return "";
        long age = System.currentTimeMillis() - s.updatedAt;
        // 超过 30 分钟只显示 HH:mm 会让人以为"刚刚还在更新"（跨天更明显）。
        if (age > 30 * 60_000L) {
            String stamp = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(s.updatedAt));
            return c.getString(R.string.widget_updated_stale, stamp);
        }
        String hhmm = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(s.updatedAt));
        return c.getString(R.string.widget_updated, hhmm);
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
            // 没有 SINGLE_TOP 时，standard 启动模式 + CLEAR_TOP 会把已有的 MainActivity 销毁重建，
            // 点一下微件就等于把 WebView 重新加载一遍（滚动位置/弹层全丢）。
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 200, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent refresh(Context c) {
        Intent i = new Intent(c, WidgetRefreshReceiver.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(c, 400, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent action(Context c, String action, int req) {
        Intent i = new Intent(c, ActionActivity.class)
            .putExtra(ActionActivity.EXTRA_ACTION, action)
            .putExtra(ActionActivity.EXTRA_SECRET, NativeStore.shortcutSecret(c))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
