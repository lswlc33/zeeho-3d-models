package io.github.lswlc33.zeehowebnext;

import android.app.Application;

/** 进程级初始化：通知渠道、动态快捷方式、后台周期任务的兜底排程、WebView 缓存上限。 */
public final class WebNextApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 必须在任何 WebView 创建之前发起；只在超过 32MB 时删，且一周最多扫一次。
        WebViewCache.trimAsync(this);
        Notify.ensureChannels(this);
        // 快捷方式与两个 Job 的兜底排程都不是首帧需要的，别占 Application.onCreate 的主线程；
        // 它们在后台线程跑，和 WebView/页面 JS 争一点 CPU 也比阻塞启动强。
        new Thread(() -> {
            ShortcutHelper.ensure(this);
            MonitorScheduler.ensure(this);
            AutoTaskScheduler.ensure(this);
            // 进程刚起来时先把微件按本地摘要刷新一次，避免页面还没同步时桌面停在旧数据。
            WidgetProviders.updateAll(this);
        }, "app-init").start();
    }
}
