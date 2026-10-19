package io.github.lswlc33.zeehowebnext;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Bundle;

/**
 * 微件的尺寸分档与主题（2026-10-20 微件重做）。
 *
 * 八个「车辆状态」= 4 个固定 provider（1×1/2×2/4×2/4×4）× 黑/白两套配色：
 *   · 同一个 provider 的实例允许用户拖拽改格子（resizeMode 双向），拖到哪一档就换哪一档的布局；
 *   · 黑/白是两个独立 provider（名字都叫「车辆状态」，选择器里各有一个预览图），
 *     用户装哪个就是哪个主题，不用在微件里放一个"切换主题"的入口占空间；
 *   · 布局分档按 onAppWidgetOptionsChanged 给到的实际 CELL 数（OPTIONS_APPWIDGET_*_CELLS，
 *     API 31+ 才有；更早的桌面拿不到就按 provider 自己的 target 档）。
 *
 * 分档（横向×纵向，格子数四舍五入）：
 *   1×1：soc + 电压，正方形紧凑
 *   2×2：soc + 电压 + 充电状态/锁车/坐垫，正方形
 *   4×2：完整车况行 + 车辆立绘 + 车控按钮
 *   4×4：4×2 的全部 + 最近骑行/上一次骑行/积分，最大化利用
 */
final class WidgetTheme {

    /** 微件主题：黑（深底浅字）/ 白（浅底深字）。 */
    enum Mode { DARK, LIGHT }

    /** 布局档位。 */
    enum Size { S1, S2, W4x2, W4x4 }

    private WidgetTheme() {
    }

    /** 黑主题 provider 用的深色配色；白主题用浅色。颜色 id 全部经 resolveColor 查表。 */
    static boolean isDark(Class<?> provider) {
        return provider == Widget1x1Dark.class || provider == Widget2x2Dark.class
            || provider == Widget4x2Dark.class || provider == Widget4x4Dark.class;
    }

    /**
     * 按当前实例的实际格子数决定布局档。
     * API < 31 桌面不报 cell 数（bundle 里没有），此时用 provider 声明的 target 档——
     * 和挑选 provider 时用户看到的尺寸一致。
     */
    static Size sizeOf(Context c, AppWidgetManager m, int id, Size fallback) {
        Bundle o = m == null ? null : m.getAppWidgetOptions(id);
        if (o == null) return fallback;
        int cw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, -1);
        int ch = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, -1);
        if (cw <= 0 || ch <= 0) return fallback;
        // Launcher 侧 1 格 ≈ 64~70dp（K90 实测 ~67dp）。用 60dp 当下限，避免第三方程式把格子算小。
        final int CELL = 60;
        int cols = Math.max(1, Math.round(cw / (float) CELL));
        int rows = Math.max(1, Math.round(ch / (float) CELL));
        return classify(cols, rows, fallback);
    }

    /** provider 的标称档（onUpdate 第一次画、以及旧桌面拿不到 cell 数时用）。 */
    static Size fallbackOf(Class<?> provider) {
        if (provider == Widget1x1Dark.class || provider == Widget1x1Light.class) return Size.S1;
        if (provider == Widget4x2Dark.class || provider == Widget4x2Light.class) return Size.W4x2;
        if (provider == Widget4x4Dark.class || provider == Widget4x4Light.class) return Size.W4x4;
        return Size.S2;
    }

    /** 实际格子数 → 布局档。容差按"最近档"走，2x3/3x2 这类拉伸形状都能落到一个合理布局。 */
    static Size classify(int cols, int rows, Size fallback) {
        int area = cols * rows;
        if (cols <= 1 && rows <= 1) return Size.S1;
        if (cols >= 3 && rows >= 3) return Size.W4x4;
        if (cols >= 3) return Size.W4x2;
        if (area <= 4) return Size.S2;
        // 2x3/3x2：面积比 2x2 大但不是横向长条——2x2 的正方形内容仍然最合适。
        return fallback == Size.S1 ? Size.S1 : Size.S2;
    }

    /** 档位 → RemoteViews 布局（黑白共用同一份布局文件，颜色在 bind 里按主题涂）。 */
    static int layoutFor(Size s, boolean dark) {
        switch (s) {
            case S1:   return R.layout.widget_1x1;
            case W4x2: return R.layout.widget_4x2;
            case W4x4: return R.layout.widget_4x4;
            default:   return R.layout.widget_2x2;
        }
    }

    /** 档位 → 预览（选择器）布局。 */
    static int previewLayoutFor(Size s, boolean dark) {
        switch (s) {
            case S1:   return dark ? R.layout.widget_1x1_preview_dark : R.layout.widget_1x1_preview_light;
            case W4x2: return dark ? R.layout.widget_4x2_preview_dark : R.layout.widget_4x2_preview_light;
            case W4x4: return dark ? R.layout.widget_4x4_preview_dark : R.layout.widget_4x4_preview_light;
            default:   return dark ? R.layout.widget_2x2_preview_dark : R.layout.widget_2x2_preview_light;
        }
    }

    /**
     * 主题配色。布局里的颜色不再用 values/colors.xml 的资源（那套跟随系统深浅模式，
     * 用户在白主题桌面挂"黑"微件会被系统夜间模式劫持成别的颜色）——直接给 id 对。
     */
    static int color(boolean dark, String role) {
        switch (role) {
            case "bg":      return dark ? 0xFF101318 : 0xFFF7F9FD;
            case "text":    return dark ? 0xFFF5F6F8 : 0xFF131720;
            case "muted":   return dark ? 0xFF9AA0A6 : 0xFF5F6B7A;
            case "chipBg":  return dark ? 0xFF1B2028 : 0xFFFFFFFF;
            case "line":    return dark ? 0xFF262C36 : 0xFFE2E8F2;
            case "accent":  return dark ? 0xFF7FB0FF : 0xFF2F6FE0;
            case "good":    return dark ? 0xFF8CE99A : 0xFF0E8A5F;
            case "warn":    return dark ? 0xFFFFB4A2 : 0xFFC2410C;
            default:        return dark ? 0xFFF5F6F8 : 0xFF131720;
        }
    }
}
