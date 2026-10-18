package io.github.lswlc33.zeehowebnext;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

/** 统一刷新三个尺寸的小组件；数据每次变化（网页同步/后台轮询）都会走这里。 */
final class WidgetProviders {

    private WidgetProviders() {
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        update(c, m, Widget2x2.class, R.layout.widget_2x2);
        update(c, m, Widget4x2.class, R.layout.widget_4x2);
        update(c, m, Widget4x4.class, R.layout.widget_4x4);
    }

    /** 桌面上是否还挂着本应用的任意尺寸微件。挂件存在时不再推持续性的充电进度通知。 */
    static boolean anyBound(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return bound(c, m, Widget2x2.class) || bound(c, m, Widget4x2.class) || bound(c, m, Widget4x4.class);
    }

    private static boolean bound(Context c, AppWidgetManager m, Class<?> cls) {
        try {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, cls));
            return ids != null && ids.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void update(Context c, AppWidgetManager m, Class<?> cls, int layout) {
        try {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, cls));
            for (int id : ids) BaseWidget.bind(c, m, id, layout);
        } catch (Exception ignored) {
        }
    }
}
