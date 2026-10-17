package io.github.lswlc33.zeehowebnext;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台常驻同步服务：页面打开时由 ZeehoShell 启动，60 秒一轮车况/消息。
 * 页面在前台且刚推过数据时 Poller 会自己跳过网络，不会和页面重复请求。
 *
 * 常驻通知单独用一条 MIN 优先级渠道；充电进度/消息是另外的通知。
 * 系统把服务杀掉后，MonitorJobService 的 15 分钟周期任务仍会兜底轮询。
 */
public final class MonitorService extends Service {

    private static final String TAG = "ZeehoWebNext";
    private static final String ACTION_START = "io.github.lswlc33.zeehowebnext.MONITOR_START";
    private static final String ACTION_STOP = "io.github.lswlc33.zeehowebnext.MONITOR_STOP";
    private static final long TICK_MS = 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (io == null || io.isShutdown()) return;
            io.execute(() -> {
                try {
                    Poller.poll(MonitorService.this);
                } catch (Exception e) {
                    Log.w(TAG, "monitor poll failed", e);
                }
            });
            main.postDelayed(this, TICK_MS);
        }
    };

    static void start(Context context) {
        if (!NativeStore.backgroundEnabled(context) || NativeStore.token(context).isEmpty()) return;
        Intent i = new Intent(context, MonitorService.class).setAction(ACTION_START);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Exception e) {
            // Android 12+ 从后台启动前台服务可能被拒；周期任务仍会轮询，下次打开 App 再拉起。
            Log.w(TAG, "start monitor service failed", e);
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, MonitorService.class).setAction(ACTION_STOP));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.ensureChannels(this);
        io = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!NativeStore.backgroundEnabled(this) || NativeStore.token(this).isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(Notify.ID_SERVICE, Notify.service(this));
        main.removeCallbacks(tick);
        main.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        if (io != null) io.shutdownNow();
        Notify.cancelService(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
