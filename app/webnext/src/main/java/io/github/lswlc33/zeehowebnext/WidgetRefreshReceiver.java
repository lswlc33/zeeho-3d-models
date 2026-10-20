package io.github.lswlc33.zeehowebnext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 微件上的「刷新」按钮。
 *
 * 以前 WIDGET_REFRESH 挂在三个 widget receiver 的 intent-filter 上，而它们必须 exported
 * （APPWIDGET_UPDATE 由系统投递）——任何第三方应用都能照着 action 发广播，逼我们起服务/重绘。
 * 现在 PendingIntent 显式指向这个不导出的 receiver，action 也不再出现在任何导出的过滤器里。
 */
public final class WidgetRefreshReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || !BaseWidget.ACTION_REFRESH.equals(intent.getAction())) return;
        final Context app = c.getApplicationContext();
        if (NativeStore.token(app).isEmpty() || NativeStore.vin(app).isEmpty()) {
            toast(app, R.string.widget_refresh_no_token);
            return;
        }
        // 以前只调 MonitorService.start：后台同步关着/登录失效时它直接 return，按钮点下去毫无反应。
        // 现在无论开关状态都真打一次接口；后台同步没开时顺带告诉用户这只是手动的一次。
        if (!NativeStore.backgroundEnabled(app)) toast(app, R.string.widget_refresh_manual);
        else toast(app, R.string.widget_refresh_running);
        // **先**发强制刷新，"拉起服务"放后面：反过来的话服务 tick 会先抢到 Poller 的 RUNNING，
        // 我们这次 pollNow 只能排队等（见 Poller 的 PENDING_FORCE），用户看到的就是"点了没反应"。
        new Thread(() -> {
            try {
                Poller.pollNow(app);
            } finally {
                WidgetProviders.updateAll(app);
            }
        }, "widget-refresh").start();
        MonitorService.start(app);
    }

    private static void toast(Context c, int res) {
        try {
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(c, res, Toast.LENGTH_SHORT).show());
        } catch (Exception ignored) {
        }
    }
}
