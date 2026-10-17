package io.github.lswlc33.zeehowebnext;

import android.webkit.JavascriptInterface;

/**
 * 注入给网页的原生桥。只提供 insets / 主题 / 版本三件事，没有任何文件、Intent、
 * 网络或凭据能力——即使页面未来被注入脚本，能拿到的也只有这些。
 */
final class ShellBridge {

    private final MainActivity activity;

    ShellBridge(MainActivity activity) {
        this.activity = activity;
    }

    /** CSS px 的四边安全区（WebView 里 env(safe-area-inset-*) 不报系统栏高度）。 */
    @JavascriptInterface
    public String insets() {
        return activity.insetsJson();
    }

    /** 页面主题变深/变浅：原生同步状态栏、导航栏图标明暗。 */
    @JavascriptInterface
    public void setDark(boolean dark) {
        activity.postSetDark(dark);
    }

    @JavascriptInterface
    public String appVersion() {
        return activity.appVersionName();
    }
}
