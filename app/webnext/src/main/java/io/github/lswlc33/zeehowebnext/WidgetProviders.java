package io.github.lswlc33.zeehowebnext;

import android.appwidget.AppWidgetManager;
import android.content.Context;

/** 统一刷新八个「车辆状态」微件；数据每次变化（网页同步/后台轮询）都会走这里。 */
final class WidgetProviders {

    private static final Class<? extends BaseWidget>[] ALL = new Class[]{
        Widget1x1Dark.class, Widget1x1Light.class,
        Widget2x2Dark.class, Widget2x2Light.class,
        Widget4x2Dark.class, Widget4x2Light.class,
        Widget4x4Dark.class, Widget4x4Light.class,
    };

    private WidgetProviders() {
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (Class<? extends BaseWidget> cls : ALL) BaseWidget.updateProvider(c, m, cls);
    }

    /** 桌面上是否还挂着本应用的任意微件。挂件存在时不再推持续性的充电进度通知。 */
    static boolean anyBound(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (Class<? extends BaseWidget> cls : ALL) {
            if (BaseWidget.anyBound(c, m, cls)) return true;
        }
        return false;
    }
}
