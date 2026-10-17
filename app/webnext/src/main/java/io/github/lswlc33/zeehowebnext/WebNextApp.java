package io.github.lswlc33.zeehowebnext;

import android.app.Application;

/** 进程级初始化：通知渠道、动态快捷方式、后台周期任务的兜底排程。 */
public final class WebNextApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.ensureChannels(this);
        ShortcutHelper.ensure(this);
        MonitorScheduler.ensure(this);
        AutoTaskScheduler.ensure(this);
    }
}
