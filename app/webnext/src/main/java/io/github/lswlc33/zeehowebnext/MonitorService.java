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
import java.util.concurrent.atomic.AtomicBoolean;

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
    /** 给「后台自检」用：前台服务是否真的活着。 */
    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private ExecutorService io;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (io == null || io.isShutdown()) return;
            // 上一轮还没回来就跳过这一拍：三个接口最坏各 25 秒，60 秒的 tick 会越堆越多。
            if (inFlight.compareAndSet(false, true)) {
                io.execute(() -> {
                    try {
                        Poller.poll(MonitorService.this);
                    } catch (Exception e) {
                        Log.w(TAG, "monitor poll failed", e);
                    } finally {
                        inFlight.set(false);
                    }
                });
            }
            // 后台静默更新面板：每拍都调一次，内部 30 分钟节流（检查→下载→用户空闲时自动应用）。
            // 服务里没有现成的 WebCache 实例（那是 MainActivity 的），这里新建一个——
            // 构造只是读两个 prefs 键，不碰网络；面板源固定走线上生产地址（debug base 只影响主进程）。
            try {
                WebCache wc = new WebCache(MonitorService.this, MainActivity.PROD_BASE_FOR_CACHE);
                wc.backgroundCheck();
            } catch (Exception e) {
                Log.w(TAG, "background panel check failed", e);
            }
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
            // startForegroundService 之后必须在 5 秒内 startForeground。用户恰好在启动窗口里
            // 关掉后台同步/退出登录时，直接 stopSelf 会触发 "did not then call startForeground"
            // 崩溃（Android 8~10 与部分 OEM 上仍会判）；先挂一条瞬态通知再停，代价可以忽略。
            startForeground(Notify.ID_SERVICE, Notify.service(this));
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(Notify.ID_SERVICE, Notify.service(this));
        running = true;
        main.removeCallbacks(tick);
        main.post(tick);
        return START_STICKY;
    }

    /**
     * Android 15+（targetSdk 35）对 dataSync 前台服务有 6 小时/24 小时配额：超时后系统回调这里，
     * 不在宽限期内停下会抛 ForegroundServiceDidNotStopInTimeException 直接崩溃。
     * 配额耗尽后不能立刻再起 dataSync 前台服务，所以这里只停自己——15 分钟周期的
     * MonitorJobService 仍会兜底轮询，用户下次打开 App 也会重新拉起。
     */
    @Override
    public void onTimeout(int startId) {
        Log.w(TAG, "monitor dataSync timeout, fall back to periodic job");
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacks(tick);
        if (io != null) io.shutdownNow();
        BatteryHistory.flush(this);
        Notify.cancelService(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
