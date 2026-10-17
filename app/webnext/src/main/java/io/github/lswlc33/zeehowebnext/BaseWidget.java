package io.github.lswlc33.zeehowebnext;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            MonitorService.start(c);
            WidgetProviders.updateAll(c);
            return;
        }
        super.onReceive(c, intent);
    }

    static void bind(Context c, AppWidgetManager m, int id, int layout) {
        NativeStore.Status s = NativeStore.status(c);
        RemoteViews rv = new RemoteViews(c.getPackageName(), layout);
        boolean two = layout == R.layout.widget_2x2;
        boolean four = layout == R.layout.widget_4x4;
        // 只有消息同步、没有车况时不要显示成"未充电 0%"；至少要有电量/充电/锁状态之一。
        boolean hasData = s.soc >= 0 || s.charge != 0 || s.lock >= 0 || s.seat >= 0;

        String name = NativeStore.name(c);
        if (name.isEmpty()) name = c.getString(R.string.widget_title);
        rv.setTextViewText(R.id.w_title, name);
        rv.setTextViewText(R.id.w_soc, s.soc >= 0 ? s.soc + "%" : c.getString(R.string.widget_soc_unknown));
        rv.setTextViewText(R.id.w_charge, state(c, s, hasData));
        rv.setTextViewText(R.id.w_updated, hasData ? updated(c, s) : "");

        if (s.soc >= 0) {
            rv.setViewVisibility(R.id.w_progress, View.VISIBLE);
            rv.setProgressBar(R.id.w_progress, 100, Math.max(0, Math.min(100, s.soc)), false);
        } else {
            rv.setViewVisibility(R.id.w_progress, View.GONE);
        }

        if (two) {
            rv.setTextViewText(R.id.w_sub, sub(c, s, hasData));
        } else {
            rv.setTextViewText(R.id.w_lock, lock(c, s));
            rv.setTextViewText(R.id.w_seat, seat(c, s));
            rv.setTextViewText(R.id.w_online, online(c, s));
            rv.setTextViewText(R.id.w_power, power(s));
            rv.setTextViewText(R.id.w_time, time(c, s));
            rv.setOnClickPendingIntent(R.id.w_refresh, refresh(c));
        }

        if (four) {
            rv.setOnClickPendingIntent(R.id.w_btn_unlock, action(c, "unlock", 301));
            rv.setOnClickPendingIntent(R.id.w_btn_lock, action(c, "lock", 302));
            rv.setOnClickPendingIntent(R.id.w_btn_horn, action(c, "horn", 303));
            rv.setOnClickPendingIntent(R.id.w_btn_seat, action(c, "seat", 304));
        }

        rv.setOnClickPendingIntent(R.id.w_root, openApp(c));
        try {
            m.updateAppWidget(id, rv);
        } catch (Exception ignored) {
        }
    }

    private static String state(Context c, NativeStore.Status s, boolean hasData) {
        if (!hasData) return c.getString(R.string.widget_no_data);
        if (s.charge == 1) return c.getString(R.string.widget_charging);
        if (s.charge == 2) return c.getString(R.string.widget_full);
        return c.getString(R.string.widget_idle);
    }

    private static String sub(Context c, NativeStore.Status s, boolean hasData) {
        if (!hasData) return c.getString(R.string.widget_open);
        if (s.charge == 1) {
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
        String lk = lock(c, s);
        String on = s.online == 1 ? c.getString(R.string.widget_online) : "";
        return on.isEmpty() ? lk : lk + " · " + on;
    }

    private static String lock(Context c, NativeStore.Status s) {
        if (s.lock == 1) return c.getString(R.string.widget_locked);
        if (s.lock == 0) return c.getString(R.string.widget_unlocked);
        return "—";
    }

    private static String seat(Context c, NativeStore.Status s) {
        if (s.seat == 1) return c.getString(R.string.widget_seat_locked);
        if (s.seat == 0) return c.getString(R.string.widget_seat_unlocked);
        return "—";
    }

    private static String online(Context c, NativeStore.Status s) {
        if (s.online == 1) return c.getString(R.string.widget_online);
        if (s.online == 0) return c.getString(R.string.widget_offline);
        return "—";
    }

    private static String power(NativeStore.Status s) {
        if (s.power <= 0) return "";
        if (s.power >= 1000) return String.format(Locale.CHINA, "%.1f kW", s.power / 1000.0);
        return s.power + " W";
    }

    private static String time(Context c, NativeStore.Status s) {
        if (s.eta != null && !s.eta.isEmpty()) return s.eta;
        int min = s.fullMin;
        if (min <= 0) min = ChargeLog.estimateMinutes(c, NativeStore.vin(c), s.soc);
        if (min > 0 && min <= 12 * 60) {
            String clock = new SimpleDateFormat("HH:mm", Locale.CHINA)
                .format(new Date(System.currentTimeMillis() + min * 60_000L));
            return c.getString(R.string.widget_eta, clock);
        }
        if (s.charged != null && !s.charged.isEmpty()) {
            return c.getString(R.string.widget_charged, s.charged);
        }
        if (s.chargedMin > 0) {
            return c.getString(R.string.widget_charged,
                (s.chargedMin / 60 > 0 ? s.chargedMin / 60 + " 小时 " : "") + (s.chargedMin % 60) + " 分");
        }
        return "";
    }

    private static String updated(Context c, NativeStore.Status s) {
        if (s.updatedAt <= 0) return "";
        String hhmm = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(s.updatedAt));
        return c.getString(R.string.widget_updated, hhmm);
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 200, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent refresh(Context c) {
        Intent i = new Intent(c, Widget4x2.class).setAction(ACTION_REFRESH);
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
