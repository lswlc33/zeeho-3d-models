package io.github.lswlc33.zeehowebnext;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 八个「车辆状态」微件共用的绘制逻辑（2026-10-20 重做）。
 *
 * 结构：4 个尺寸（1×1/2×2/4×2/4×4）× 黑/白两主题 = 8 个 provider；
 * 每个实例允许拖拽调整格子，onAppWidgetOptionsChanged 后按实际格子数换布局档，
 * 颜色不再走资源表（那套跟随系统夜间模式，会劫持用户挑好的黑白主题），全部代码着色。
 * 数据只读 NativeStore.Status；刷新按钮触发一次真实轮询（WidgetRefreshReceiver）。
 */
abstract class BaseWidget extends AppWidgetProvider {

    static final String ACTION_REFRESH = "io.github.lswlc33.zeehowebnext.WIDGET_REFRESH";

    /** 黑主题还是白主题（provider 声明，一个类一个主题）。 */
    abstract boolean dark();

    /** provider 的标称档：第一次绘制 / 旧桌面拿不到实际格子数时用。 */
    abstract WidgetTheme.Size nominal();

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) bind(c, m, id, dark(), nominal());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle newOptions) {
        bind(c, m, id, dark(), nominal());
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        // WIDGET_REFRESH 已改由不导出的 WidgetRefreshReceiver 处理；这里不再接这个 action，
        // 否则第三方仍可显式发广播给 exported 的 widget receiver。
        super.onReceive(c, intent);
    }

    /** 按 provider 类把它的全部实例刷一遍（WidgetProviders 走这里）。 */
    static void updateProvider(Context c, AppWidgetManager m, Class<? extends BaseWidget> cls) {
        BaseWidget probe = newInstance(cls);
        if (probe == null) return;
        try {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, cls));
            for (int id : ids) bind(c, m, id, probe.dark(), probe.nominal());
        } catch (Exception ignored) {
        }
    }

    static boolean anyBound(Context c, AppWidgetManager m, Class<? extends BaseWidget> cls) {
        try {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, cls));
            return ids != null && ids.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static BaseWidget newInstance(Class<? extends BaseWidget> cls) {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 绘制 ----------------

    static void bind(Context c, AppWidgetManager m, int id, boolean dark, WidgetTheme.Size nominal) {
        NativeStore.Status s = NativeStore.status(c);
        WidgetTheme.Size size = WidgetTheme.sizeOf(c, m, id, nominal);
        RemoteViews rv = new RemoteViews(c.getPackageName(), WidgetTheme.layoutFor(size, dark));
        T t = new T(c, dark);
        boolean hasData = s.soc >= 0 || s.chargeKnown || s.lock >= 0 || s.seat >= 0 || s.online >= 0;

        // 底色与控件底全部按主题重涂：布局 XML 里的 @color 引用只是预览兜底，
        // 那套资源跟随系统夜间模式，会把用户挑好的黑白主题劫持掉。
        applyRootColor(c, rv, t);

        String name = NativeStore.name(c);
        if (name.isEmpty()) name = c.getString(R.string.widget_title);
        text(rv, R.id.w_title, name, t.muted, 10);
        text(rv, R.id.w_updated, hasData ? updated(c, s, size == WidgetTheme.Size.S1 || size == WidgetTheme.Size.S2) : "", t.muted, 8);
        if (has(R.id.w_refresh)) {
            text(rv, R.id.w_refresh, c.getString(R.string.widget_refresh), t.accent, 8);
            rv.setOnClickPendingIntent(R.id.w_refresh, refresh(c));
        }

        // ---- 电量（所有档位都有）----
        text(rv, R.id.w_soc, s.soc >= 0 ? String.valueOf(s.soc) : c.getString(R.string.widget_soc_unknown), t.text, socSp(size));
        if (s.soc >= 0) {
            rv.setViewVisibility(R.id.w_progress, View.VISIBLE);
            rv.setProgressBar(R.id.w_progress, 100, Math.max(0, Math.min(100, s.soc)), false);
        } else {
            rv.setViewVisibility(R.id.w_progress, View.GONE);
        }
        // 进度条颜色沿用布局 XML 里的 accent tint（两套主题 accent 都是蓝系，无需按主题换）。
        rv.setTextViewText(R.id.w_unit, "%");

        // ---- 电压（1×1 只显示电量和电压，其余档位也都有）----
        String voltage = voltage(c, s);
        if (has(R.id.w_voltage)) {
            text(rv, R.id.w_voltage, voltage, t.muted, 9);
            rv.setViewVisibility(R.id.w_voltage, voltage.isEmpty() ? View.GONE : View.VISIBLE);
        }

        // ---- 状态行 / 芯片 ----
        if (size == WidgetTheme.Size.S1) {
            // 1×1：状态就用一行小字（充电中/已锁车/离线），空间只够它
            text(rv, R.id.w_state, state(c, s, hasData), stateColor(t, s, hasData), 9);
        } else {
            if (has(R.id.w_charge)) {
                text(rv, R.id.w_charge, state(c, s, hasData), stateColor(t, s, hasData), 9);
            }
            boolean charging = s.chargeKnown && s.charge == 1 && s.online != 0;
            if (has(R.id.w_power)) text(rv, R.id.w_power, charging ? power(s) : "", t.text, 9);
            if (has(R.id.w_time)) text(rv, R.id.w_time, charging ? time(c, s) : "", t.muted, 8);
            if (has(R.id.w_lock)) {
                String lk = lock(c, s);
                text(rv, R.id.w_lock, lk, t.text, 8);
                rv.setViewVisibility(R.id.w_lock, lk.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (has(R.id.w_seat)) {
                String st = seat(c, s);
                text(rv, R.id.w_seat, st, t.text, 8);
                rv.setViewVisibility(R.id.w_seat, st.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (has(R.id.w_tire)) {
                String tire = tire(c, s);
                text(rv, R.id.w_tire, tire, t.text, 8);
                rv.setViewVisibility(R.id.w_tire, tire.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }

        // ---- 4×4 扩展区：最近骑行 / 上一次骑行 / 积分 ----
        if (size == WidgetTheme.Size.W4x4) {
            boolean charging = s.chargeKnown && s.charge == 1 && s.online != 0;
            bind4x4Extra(c, rv, t, s, hasData, charging);
            rv.setOnClickPendingIntent(R.id.w_btn_unlock, action(c, "unlock", 301));
            rv.setOnClickPendingIntent(R.id.w_btn_lock, action(c, "lock", 302));
            rv.setOnClickPendingIntent(R.id.w_btn_horn, action(c, "horn", 303));
            rv.setOnClickPendingIntent(R.id.w_btn_seat, action(c, "seat", 304));
        } else if (size == WidgetTheme.Size.W4x2) {
            rv.setOnClickPendingIntent(R.id.w_btn_unlock, action(c, "unlock", 301));
            rv.setOnClickPendingIntent(R.id.w_btn_lock, action(c, "lock", 302));
            rv.setOnClickPendingIntent(R.id.w_btn_horn, action(c, "horn", 303));
            rv.setOnClickPendingIntent(R.id.w_btn_seat, action(c, "seat", 304));
        }

        // 车图：2×2/1×1 不放立绘（空间不够，用户指定 2×2 只展示数据）；4×2/4×4 放透明立绘。
        if (has(R.id.w_car_box)) {
            if (size == WidgetTheme.Size.W4x2 || size == WidgetTheme.Size.W4x4) {
                Bitmap car = WidgetCar.load(c, NativeStore.vin(c));
                if (car != null) {
                    rv.setImageViewBitmap(R.id.w_car, car);
                    rv.setViewVisibility(R.id.w_car_box, View.VISIBLE);
                } else {
                    rv.setViewVisibility(R.id.w_car_box, View.GONE);
                }
            } else {
                rv.setViewVisibility(R.id.w_car_box, View.GONE);
            }
        }

        rv.setOnClickPendingIntent(R.id.w_root, openApp(c));
        try {
            m.updateAppWidget(id, rv);
        } catch (Exception ignored) {
        }
        // 桌面已经有微件时，充电进度在桌面就能看到；持续性的进度通知不再重复挂在通知栏。
        if (WidgetProviders.anyBound(c)) Notify.cancelChargeProgress(c);
    }

    /** 4×4 底部扩展：最近骑行 / 上一次骑行 / 积分，三格统计。 */
    private static void bind4x4Extra(Context c, RemoteViews rv, T t, NativeStore.Status s,
                                     boolean hasData, boolean charging) {
        // 最近骑行 = 今日里程；接口没给就显示 0.0（"今天没骑"是真实状态，不算缺数据）。
        text(rv, R.id.w_today_mile, hasData && s.todayMile >= 0 ? km(s.todayMile) : "0.0", t.text, 13);
        text(rv, R.id.w_today_unit, "km", t.muted, 8);
        boolean hasLast = hasData && s.lastRideMile >= 0 && s.lastRideMile > 0;
        text(rv, R.id.w_last_mile, hasLast ? km(s.lastRideMile) : "—", t.text, 13);
        text(rv, R.id.w_last_unit, hasLast ? "km" : "", t.muted, 8);
        String lastTime = hasLast && s.lastRideMin > 0 ? minutes(c, s.lastRideMin) : "";
        text(rv, R.id.w_last_time, hasLast ? lastTime : c.getString(R.string.widget_no_ride), t.muted, 8);
        text(rv, R.id.w_integral, hasData && s.integral >= 0 ? String.valueOf(s.integral) : "—", t.text, 13);
        text(rv, R.id.w_integral_unit, c.getString(R.string.widget_integral_unit), t.muted, 8);
        // 充电时那一格显示功率/预计，跟 4×2 的状态行互补
        if (has(R.id.w_extra_hint)) {
            String hint = charging
                ? (power(s).isEmpty() ? "" : power(s) + (time(c, s).isEmpty() ? "" : " · " + time(c, s)))
                : "";
            text(rv, R.id.w_extra_hint, hint, t.accent, 8);
            rv.setViewVisibility(R.id.w_extra_hint, hint.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ---------------- 主题着色 ----------------

    /** 布局 XML 里的 @color 引用在绑定时不被 RemoteViews 直接替换，逐个 view 显式涂色。 */
    private static void applyRootColor(Context c, RemoteViews rv, T t) {
        // 背景：RemoteViews 没有 setDrawableTint，用布局里两个现成的 bg drawable + 颜色区分。
        // 布局根节点的 background 只写了浅色；黑主题这里换成深色圆角。
        rv.setInt(R.id.w_root, "setBackgroundResource",
            t.dark ? R.drawable.widget_card_dark : R.drawable.widget_card);
        // 芯片/按钮的底色跟主题走（widget_chip/widget_btn 各有一份深色变体）
        if (has(R.id.w_lock)) rv.setInt(R.id.w_lock, "setBackgroundResource", t.dark ? R.drawable.widget_chip_dark : R.drawable.widget_chip);
        if (has(R.id.w_seat)) rv.setInt(R.id.w_seat, "setBackgroundResource", t.dark ? R.drawable.widget_chip_dark : R.drawable.widget_chip);
        int btnBg = t.dark ? R.drawable.widget_btn_dark : R.drawable.widget_btn;
        for (int btn : new int[]{R.id.w_refresh, R.id.w_btn_unlock, R.id.w_btn_lock, R.id.w_btn_horn, R.id.w_btn_seat}) {
            if (has(btn)) rv.setInt(btn, "setBackgroundResource", btnBg);
        }
    }

    /** 一次性把颜色对打包，bind 里少写重复代码。 */
    private static final class T {
        final boolean dark;
        final int text;
        final int muted;
        final int accent;
        final int good;
        final int warn;

        T(Context c, boolean dark) {
            this.dark = dark;
            text = WidgetTheme.color(dark, "text");
            muted = WidgetTheme.color(dark, "muted");
            accent = WidgetTheme.color(dark, "accent");
            good = WidgetTheme.color(dark, "good");
            warn = WidgetTheme.color(dark, "warn");
        }
    }

    private static int socSp(WidgetTheme.Size s) {
        switch (s) {
            case S1: return 34;
            case S2: return 34;
            case W4x2: return 28;
            default: return 30;
        }
    }

    private static int stateColor(T t, NativeStore.Status s, boolean hasData) {
        if (!hasData) return t.muted;
        if (s.chargeKnown && s.charge == 1) return t.accent;
        if (s.chargeKnown && s.charge == 2) return t.good;
        if (s.online == 0) return t.warn;
        return t.muted;
    }

    /** RemoteViews 没有 view存在性查询；各档布局共用同一套 id，缺的就当没有。 */
    private static boolean has(int viewId) {
        return viewId != 0;
    }

    private static void text(RemoteViews rv, int id, String v, int color, int sp) {
        rv.setTextViewText(id, v == null ? "" : v);
        rv.setTextColor(id, color);
        // RemoteViews 的字号要用 setFloat(viewId, "setTextSize", sp) 反射形式；
        // setViewTextSize 在部分 API 层不存在。
        rv.setFloat(id, "setTextSize", sp);
    }

    // ---------------- 文案组装 ----------------

    private static String state(Context c, NativeStore.Status s, boolean hasData) {
        // 登录态失效时先说实话：否则微件会一直停在"未充电/已锁车"这种看起来正常的状态。
        if (NativeStore.authFailed(c)) return c.getString(R.string.widget_auth_failed);
        if (!hasData) return c.getString(R.string.widget_no_data);
        if (s.online == 0) return c.getString(R.string.widget_offline);
        if (s.chargeKnown) {
            if (s.charge == 1) return c.getString(R.string.widget_charging);
            if (s.charge == 2) return c.getString(R.string.widget_full);
            return c.getString(R.string.widget_idle);
        }
        if (s.lock == 1) return c.getString(R.string.widget_locked);
        if (s.lock == 0) return c.getString(R.string.widget_unlocked);
        return c.getString(R.string.widget_state_unknown);
    }

    private static String voltage(Context c, NativeStore.Status s) {
        String v = s.voltageText == null ? "" : s.voltageText.trim();
        if (v.isEmpty() && s.voltage > 0) v = String.valueOf(s.voltage);
        if (v.isEmpty()) return "";
        // 车机偶尔会报 "0" / "0.0" / "--"：那不是电压，别显示成"电压 0 V"。
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

    private static String tire(Context c, NativeStore.Status s) {
        if (s.tireFront < 0 && s.tireRear < 0) return "";
        String f = s.tireFront >= 0 ? trim1(s.tireFront) : "--";
        String r = s.tireRear >= 0 ? trim1(s.tireRear) : "--";
        return c.getString(R.string.widget_tire, f, r);
    }

    private static String trim1(double v) {
        float r = Math.round(v * 10) / 10f;
        return (r == (long) r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    static String km(int meters) {
        double v = meters / 1000.0;
        String one = String.format(Locale.CHINA, "%.1f", v);
        return one.endsWith(".0") ? one.substring(0, one.length() - 2) : one;
    }

    private static String minutes(Context c, int min) {
        if (min <= 0) return "";
        if (min < 60) return c.getString(R.string.widget_minutes, min);
        return c.getString(R.string.widget_minutes_h, min / 60, min % 60);
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

    /** 1×1/2×2 空间小，只给短时间；4×2/4×4 可以带"可能已过期"提示。 */
    private static String updated(Context c, NativeStore.Status s, boolean compact) {
        if (s.updatedAt <= 0) return "";
        long age = System.currentTimeMillis() - s.updatedAt;
        if (age > 30 * 60_000L) {
            String stamp = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(s.updatedAt));
            return compact ? stamp : c.getString(R.string.widget_updated_stale, stamp);
        }
        return c.getString(R.string.widget_updated,
            new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(s.updatedAt)));
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
