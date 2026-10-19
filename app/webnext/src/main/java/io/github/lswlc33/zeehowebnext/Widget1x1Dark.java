package io.github.lswlc33.zeehowebnext;

/**
 * 八个「车辆状态」微件的 provider：4 个尺寸（1×1 / 2×2 / 4×2 / 4×4）× 黑 / 白两主题。
 * 名字都叫「车辆状态」，选择器里按预览图区分；每个实例仍允许拖拽改格子（分档换布局）。
 * 全部逻辑在 BaseWidget，这里只声明标称档与主题。
 */
public final class Widget1x1Dark extends BaseWidget {
    @Override boolean dark() { return true; }
    @Override WidgetTheme.Size nominal() { return WidgetTheme.Size.S1; }
}
