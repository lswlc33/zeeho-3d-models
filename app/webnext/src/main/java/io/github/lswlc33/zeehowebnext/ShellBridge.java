package io.github.lswlc33.zeehowebnext;

import android.webkit.JavascriptInterface;

/**
 * 注入给网页的原生桥。
 *
 * 页面是我们自己的构建（线上快照与仓库产物同一份），桥只暴露这几件事：
 *   - 安全区/主题/版本（原有）；
 *   - 把登录态和车辆摘要推给原生，供通知/小组件/自动任务使用；
 *   - App 专属的自动任务与后台同步设置；
 *   - 请求通知权限、打开系统设置。
 *
 * 不提供任意文件、任意 Intent、任意 URL 能力；同步下来的 token 只写应用私有 SharedPreferences。
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

    /** 手动检查 APK 更新；返回 JSON（不弹原生框），由页面展示结果。 */
    @JavascriptInterface
    public String checkAppUpdate() {
        return UpdateChecker.query(activity);
    }

    /** 页面点了「下载软件」：走原生下载/校验/安装进度。 */
    @JavascriptInterface
    public void downloadAppUpdate() {
        UpdateChecker.downloadLatest(activity);
    }

    /** 手动检查页面版本；返回当前/远端 build，不下载。 */
    @JavascriptInterface
    public String checkPageUpdate() {
        return activity.checkPageUpdate();
    }

    /** 重启页面：有已下载快照就应用，否则先检查/下载再 reload。 */
    @JavascriptInterface
    public void restartPage() {
        activity.postRestartPage();
    }

    /**
     * App 专属电量历史。返回降采样后的 JSON（不会把整月原始记录塞进 JS）；
     * 浏览器没有这个桥，页面整块不渲染。range: 24h / 3d / 7d / 30d。
     */
    @JavascriptInterface
    public String batteryHistory(String range) {
        return BatteryHistory.report(activity, NativeStore.vin(activity), range);
    }

    /**
     * 网页每次 load 后同步登录态。token/vin 任一为空都会让后台服务停下；
     * 换 token（重新登录）时 NativeStore 会清掉旧车的摘要和登录失效标记。
     */
    @JavascriptInterface
    public void syncSession(String token, String uid, String vin, String name) {
        String t = safe(token), u = safe(uid), v = safe(vin), n = safe(name);
        NativeStore.updateSession(activity, t, u, v, n);
        final boolean configured = !t.isEmpty() && !v.isEmpty();
        activity.runOnUiThread(() -> {
            if (configured) {
                MonitorScheduler.ensure(activity);
                MonitorService.start(activity);
                activity.postRequestNotifications();
                ShortcutHelper.ensure(activity);
            } else {
                MonitorScheduler.cancel(activity);
                MonitorService.stop(activity);
                Notify.cancelAll(activity);
            }
            WidgetProviders.updateAll(activity);
        });
    }

    /** 网页每轮刷新后的车辆摘要（JSON）。原生只做展示/通知，不反向改页面。 */
    @JavascriptInterface
    public void syncStatus(String json) {
        if (!NativeStore.isConfigured(activity)) return;
        NativeStore.setLastPageSync(activity);
        StatusFeed.pageAsync(activity, json);
    }

    @JavascriptInterface
    public String autoTask() {
        return NativeStore.autoJson(activity);
    }

    @JavascriptInterface
    public void setAutoTask(String json) {
        activity.postSetAutoTask(json);
    }

    @JavascriptInterface
    public boolean background() {
        return NativeStore.backgroundEnabled(activity);
    }

    @JavascriptInterface
    public void setBackground(boolean on) {
        activity.postSetBackground(on);
    }

    @JavascriptInterface
    public void requestNotifications() {
        activity.postRequestNotifications();
    }

    @JavascriptInterface
    public void openSettings(String kind) {
        activity.postOpenSettings(kind);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
