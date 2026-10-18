package io.github.lswlc33.zeehowebnext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

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
        MonitorService.start(c);
        WidgetProviders.updateAll(c);
    }
}
