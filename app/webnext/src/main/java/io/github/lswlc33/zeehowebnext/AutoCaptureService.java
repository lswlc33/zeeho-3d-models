package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 方案 3 的 VPN 载体：把官方 App 的 HTTP(S) 流量交给本机 CaptureProxy，
 * 抓到 h5.zeehoev.com 的 baseInfo 后把 token/userId 写入 NativeStore 并发通知。
 *
 * 只允许 com.cfmoto 走这个 VPN；本应用（代理自己）不在允许列表里，代理的上游连接直接走网络。
 */
public final class AutoCaptureService extends VpnService {

    private static final String TAG = "ZeehoWebNext";
    private static final String ACTION_START = "io.github.lswlc33.zeehowebnext.CAPTURE_START";
    static final String ACTION_STOP = "io.github.lswlc33.zeehowebnext.CAPTURE_STOP";
    private static final long TIMEOUT_MS = 120_000L;
    /** 抓到后别立刻拆 VPN：WebView 还要跟着 301 走完 HTTPS，代理已标记 captured 会盲转发。 */
    private static final long KEEP_ALIVE_MS = 15_000L;
    /** 给 Activity/页面做“是否还有活动抓取”的判断；shutdown 会清掉。 */
    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean done = new AtomicBoolean();
    private TunCapture tunCapture;
    private boolean foreground;

    private final Runnable timeout = () ->
        fail("没有等到签到页。请确认在极核里点了「我的」→「签到」；"
            + "如果签到页没有出现「网页无法打开」，可以重试一次。");

    static void start(Context c) {
        Intent i = new Intent(c, AutoCaptureService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "start capture service failed", e);
            NativeStore.setCaptureState(c, "error", "无法启动抓取服务：" + e.getMessage());
        }
    }

    static void stop(Context c) {
        try {
            c.startService(new Intent(c, AutoCaptureService.class).setAction(ACTION_STOP));
        } catch (Exception e) {
            try { c.stopService(new Intent(c, AutoCaptureService.class)); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            CaptureLog.add(this, "service", "stop requested");
            done.set(true);
            NativeStore.setCaptureState(this, "idle", null);
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tunCapture != null) return START_NOT_STICKY;
        // 上一轮 success/error 后 VpnService 可能还被系统 bind 着没销毁；再来一轮要允许重新开始。
        done.set(false);

        CaptureLog.add(this, "session", "capture service start (log 从点击按钮开始保留)");
        try {
            startForeground(Notify.ID_CAPTURE_WAIT, Notify.captureWaiting(this));
            foreground = true;
            CaptureLog.add(this, "service", "foreground waiting notification shown");
        } catch (Exception e) {
            Log.w(TAG, "capture foreground failed", e);
            CaptureLog.add(this, "service", "foreground failed: " + e.getMessage());
            fail("无法显示抓取通知：" + e.getMessage());
            return START_NOT_STICKY;
        }
        NativeStore.setCaptureState(this, "waiting", null);

        try {
            tunCapture = new TunCapture(this, new TunCapture.Callback() {
                @Override
                public void onCaptured(String token, String uid) {
                    main.post(() -> success(token, uid));
                }

                @Override
                public void onError(String message) {
                    main.post(() -> fail(message));
                }

                @Override
                public void onManualPage() {
                    main.post(() -> manualPage());
                }
            });
            final TunCapture capture = tunCapture;
            // getAllByName / ServerSocket / establish 都可能在主线程抛 NetworkOnMainThread，
            // 整段放后台线程；startForeground 已经在主线程先做过。
            new Thread(() -> {
                try {
                    CaptureLog.add(this, "vpn", "establish tun allowed=com.cfmoto");
                    capture.start();
                    if (done.get()) {
                        // 建立过程中用户已取消：别再挂超时/保留 VPN。
                        capture.stop();
                        return;
                    }
                    running = true;
                    main.postDelayed(timeout, TIMEOUT_MS);
                    Log.i(TAG, "capture tun established " + captureStats());
                    CaptureLog.add(this, "vpn", "established; waiting up to "
                        + (TIMEOUT_MS / 1000) + "s " + captureStats());
                } catch (Exception e) {
                    Log.w(TAG, "capture tun setup failed", e);
                    CaptureLog.add(this, "vpn", "setup failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                    String msg = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                    if (msg.contains("NameNotFoundException") || msg.contains("com.cfmoto")) {
                        msg = "未安装极核 App（com.cfmoto）";
                    }
                    final String failMsg = msg;
                    main.post(() -> fail("无法建立 VPN：" + failMsg));
                }
            }, "capture-start").start();
        } catch (Exception e) {
            Log.w(TAG, "capture tun setup failed", e);
            CaptureLog.add(this, "vpn", "setup failed: " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.contains("NameNotFoundException") || msg.contains("com.cfmoto")) {
                msg = "未安装极核 App（com.cfmoto）";
            }
            fail("无法建立 VPN：" + msg);
        }
        return START_NOT_STICKY;
    }

    private String captureStats() {
        TunCapture t = tunCapture;
        return t == null ? "tun=null" : t.stats();
    }

    private void success(String token, String uid) {
        if (!done.compareAndSet(false, true)) return;
        main.removeCallbacks(timeout);
        CaptureLog.add(this, "service", "success uid=" + (uid == null || uid.isEmpty() ? "?" : "yes")
            + " tokenLen=" + (token == null ? 0 : token.length()) + " " + captureStats());
        NativeStore.setCaptureSuccess(this, token, uid);
        Notify.captureSuccess(this, uid);
        Log.i(TAG, "capture success, uid=" + (uid == null || uid.isEmpty() ? "?" : uid));
        // 先留着 VPN 让 H5 页面完成 301 -> HTTPS 的加载，10 秒后再拆。
        main.postDelayed(() -> {
            shutdown();
            stopSelf();
        }, KEEP_ALIVE_MS);
    }

    /** 证书被官方 WebView 拒绝：错误页已显示带 token 的 URL，转人工复制。 */
    private void manualPage() {
        if (!done.compareAndSet(false, true)) return;
        main.removeCallbacks(timeout);
        CaptureLog.add(this, "service", "manual page triggered");
        NativeStore.setCaptureState(this, "manual",
            "签到页已触发错误提示，请复制错误页里的网址回面板粘贴");
        Notify.captureManual(this);
        // 留 2 秒让 WebView 把错误页画出来，再拆 VPN；错误页本身不会消失。
        main.postDelayed(() -> {
            shutdown();
            stopSelf();
        }, 2000);
    }

    private void fail(String message) {
        if (!done.compareAndSet(false, true)) return;
        main.removeCallbacks(timeout);
        CaptureLog.add(this, "service", "fail: " + message + " " + captureStats());
        NativeStore.setCaptureState(this, "error", message);
        Notify.captureFailed(this, message);
        Log.w(TAG, "capture failed: " + message);
        shutdown();
        stopSelf();
    }

    /**
     * 抓完/失败/取消都要立刻拆掉 VPN 和等待通知。
     * VpnService 被系统 bind 着，只调 stopSelf 时 onDestroy 可能迟迟不来——
     * 隧道不关，状态栏的 VPN 图标和“等待中”通知就会一直挂着。
     */
    private void shutdown() {
        running = false;
        CaptureLog.add(this, "service", "shutdown " + captureStats());
        if (tunCapture != null) {
            tunCapture.stop();
            tunCapture = null;
        }
        if (foreground) {
            try { stopForeground(true); } catch (Exception ignored) { }
            foreground = false;
        }
    }

    /** 用户在系统里关掉 VPN，或系统撤销：本轮结束。 */
    @Override
    public void onRevoke() {
        CaptureLog.add(this, "service", "vpn revoked by system");
        if (!done.get()) fail("VPN 被系统关闭");
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        CaptureLog.add(this, "service", "onDestroy done=" + done.get() + " " + captureStats());
        main.removeCallbacks(timeout);
        shutdown();
        if (!done.get()) {
            NativeStore.setCaptureState(this, "error", "抓取被中断");
        }
        super.onDestroy();
    }
}
