package io.github.lswlc33.zeehowebnext;

import android.app.Application;

/** 进程级初始化：通知渠道、动态快捷方式、后台周期任务的兜底排程、WebView 缓存上限。 */
public final class WebNextApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 必须在任何 WebView 创建之前；只在超过 32MB 时删，不是每次清空。
        WebViewCache.trim(this);
        Notify.ensureChannels(this);
        ShortcutHelper.ensure(this);
        MonitorScheduler.ensure(this);
        AutoTaskScheduler.ensure(this);
    }
}
