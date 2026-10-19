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

    /**
     * 异步手动检查 APK 更新：起后台任务立即返回，页面用 {@link #pollAppUpdate()} 轮询取果。
     * 同步版（上面那个）会让 JS 主线程冻在网络超时上，新页面不再调用。
     */
    @JavascriptInterface
    public void beginAppUpdate() {
        UpdateChecker.beginQuery(activity);
    }

    /** 取一次异步 APK 检查的结果：{done, json?}。 */
    @JavascriptInterface
    public String pollAppUpdate() {
        return UpdateChecker.pollQuery();
    }

    /** 页面点了「下载软件」：走原生下载/校验/安装进度。 */
    @JavascriptInterface
    public void downloadAppUpdate() {
        UpdateChecker.downloadLatest(activity);
    }

    /** 手动检查页面版本；返回当前/远端 build，不下载。旧页面兜底用（同步网络，会冻 JS）。 */
    @JavascriptInterface
    public String checkPageUpdate() {
        return activity.checkPageUpdate();
    }

    /** 异步手动检查页面版本：起后台任务立即返回，页面用 {@link #pollPageUpdate()} 轮询取果。 */
    @JavascriptInterface
    public void beginPageUpdate() {
        activity.beginPageUpdate();
    }

    /** 取一次异步面板检查的结果：{done, json?}。 */
    @JavascriptInterface
    public String pollPageUpdate() {
        return activity.pollPageUpdate();
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

    /** 车辆附近检测：只扫描该 MAC 的 BLE 广播，不连接、不发 GATT 命令。 */
    @JavascriptInterface
    public String proximityStart(String address) {
        return Proximity.start(activity, address);
    }

    @JavascriptInterface
    public String proximityStatus() {
        return Proximity.status(activity);
    }

    @JavascriptInterface
    public void requestProximity() {
        activity.postRequestProximity();
    }

    @JavascriptInterface
    public void proximityStop() {
        Proximity.stop(activity);
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

    /** 后台自检：开关、前台服务、15 分钟任务、通知权限、电池优化、上次同步时间、鉴权状态。 */
    @JavascriptInterface
    public String backgroundStatus() {
        return activity.backgroundStatusJson();
    }

    /** 一键修复后台：重排兜底任务并拉起前台服务。 */
    @JavascriptInterface
    public void restartBackground() {
        activity.postRestartBackground();
    }

    // ---------------- 方案 3：VPN 自动抓取 ----------------

    /** 请求 VPN 授权并启动自动抓取；页面轮询 captureStatus() 看进度。 */
    @JavascriptInterface
    public void startCapture() {
        activity.postStartCapture();
    }

    @JavascriptInterface
    public void stopCapture() {
        activity.postStopCapture();
    }

    /** 只打开官方极核 App（不开 VPN、不启动抓取），先停在「我的」页。 */
    @JavascriptInterface
    public void openOfficialApp() {
        activity.postOpenOfficialApp();
    }

    /** 开启 VPN 并跳官方极核 App。 */
    @JavascriptInterface
    public void launchOfficialApp() {
        activity.postLaunchOfficialApp();
    }

    /** {state, token, uid, error, at, installed}。 */
    @JavascriptInterface
    public String captureStatus() {
        return activity.captureJson();
    }

    /** 页面用掉/放弃抓取结果后清掉。 */
    @JavascriptInterface
    public void clearCapture() {
        activity.postClearCapture();
    }

    /** 方案三诊断日志（不含 token 原文）；页面在失败弹层里展示/复制。 */
    @JavascriptInterface
    public String captureDiag() {
        return activity.captureDiag();
    }

    @JavascriptInterface
    public void copyCaptureDiag() {
        activity.postCopyCaptureDiag();
    }

    @JavascriptInterface
    public void clearCaptureDiag() {
        activity.postClearCaptureDiag();
    }

    /** 页面第一次 render 完成：原生据此把启动图淡出（比 onPageFinished 早得多）。 */
    @JavascriptInterface
    public void pageReady() {
        activity.postHideSplash();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
