package io.github.lswlc33.zeehowebnext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机/覆盖安装后恢复周期任务与自动任务（JobScheduler 自己也会保留 persisted job，
 * 这里再 ensure 一次是兜底）。前台服务在 Android 12+ 从 BOOT 启动可能被拒，失败就只靠周期任务，
 * 用户下次打开 App 会重新拉起。
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "ZeehoWebNext";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            MonitorScheduler.ensure(context);
            AutoTaskScheduler.ensure(context);
            MonitorService.start(context);
        } catch (Exception e) {
            Log.w(TAG, "boot restore failed", e);
        }
    }
}
