package io.github.lswlc33.zeehowebnext;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 极核面板的纯 WebView 壳。
 *
 * 不打包任何网页资源，只做四件事：
 *   1. 首次启动把线上单文件页面缓存到 files/webcache；
 *   2. 后续启动从本地快照加载，后台静默检查 version.json 并下载新快照；
 *   3. 新快照只在下次冷启动或用户空闲 5 秒时启用；
 *   4. 把系统栏/刘海/键盘 insets 和深浅色通过 ZeehoShell 桥告诉网页。
 */
public final class MainActivity extends Activity {

    private static final String TAG = "ZeehoWebNext";
    static final String PROD_BASE_FOR_CACHE = "https://lswlc33.github.io/zeeho-3d-models/";
    private static final String PREFS = "webnext";
    private static final String K_DARK = "last_dark";
    private static final String K_CHECK = "last_check_at";
    private static final long IDLE_MS = 5000;
    private static final long BACK_EXIT_MS = 2000;
    private static final long UPDATE_CHECK_GAP_MS = 30000;
    private static final int REQ_LOCATION = 1002;
    private static final int REQ_PROXIMITY = 1003;
    private static final int REQ_VPN = 1004;
    /** 网页 <input type=file>（抓取账号 id 选二维码图）的请求码；不能与上面重复。 */
    private static final int REQ_FILE_CHOOSER = 1005;

    private static final String BACK_JS =
        "(function(){try{"
            + "if(window.APP&&typeof window.APP.back==='function'){var r=window.APP.back();if(r)return r;}"
            + "var s=document.querySelector('.sheet.show');"
            + "if(s){window.UI.back();return 'sheet';}"
            + "if(window.APP&&window.APP.state&&window.APP.state.tab!=='home'){"
            + "var t=document.querySelector('.tab[data-tab=home]');"
            + "if(t){t.click();return 'home';}}"
            + "return 'exit';}catch(e){return 'exit';}})()";

    /**
     * 空闲判定：**有弹层就算忙**（用户正看着一层东西，别在这时候整页 reload 把内容抽走），
     * 或者**焦点在任何一个可编辑元素上**。
     *
     * 以前只认 INPUT / TEXTAREA 两个 tagName：contenteditable、<select>、以及"输入法开着但
     * activeElement 已经不是 input"的情况都会被判成 idle，于是新快照会在用户正在填东西时
     * 提为 active 并 reload——表单内容直接丢掉。现在用 isContentEditable + tagName 一起判。
     */
    private static final String IDLE_JS =
        "(function(){var a=document.activeElement;var t=a&&a.tagName;"
            + "var edit=!!a&&(a.isContentEditable===true||t==='INPUT'||t==='TEXTAREA'||t==='SELECT');"
            + "return (document.querySelector('.sheet.show')||document.querySelector('dialog[open]')||edit)?'busy':'idle';})()";

    private FrameLayout root;
    private WebView webView;
    private WebCache cache;
    private ShellBridge bridge;
    private String baseUrl = PROD_BASE_FOR_CACHE;
    private String versionName = "1.0";
    private int versionCode;
    private boolean debugBuild;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int insetTop;
    private volatile int insetBottom;
    private volatile int insetLeft;
    private volatile int insetRight;
    private volatile boolean dark = true;
    private boolean resumed;
    private boolean pageLoaded;
    private boolean initRunning;
    private boolean updateRunning;
    private boolean destroyed;
    private long lastInteractionAt;
    private long startAt;
    private boolean backInFlight;
    /** 渲染进程崩过、还没等到新的 page commit（见 onRenderProcessGone / onBackPressed）。 */
    private boolean rendererDied;
    private long lastBackAt;
    private boolean idleApplyInFlight;
    /** onShowFileChooser 挂起的回调：选完图（或取消）必须回调一次，否则网页的 file input 会永久卡死。 */
    private ValueCallback<Uri[]> pendingFileUpload;
    private long lastCheckAt;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;

    private View overlay;
    private View splash;
    private TextView overlayTitle;
    private TextView overlayStatus;
    private TextView overlayDetail;
    private ProgressBar overlayProgress;
    private Button overlayRetry;
    private boolean overlayError;

    private int lastInsetTop = -1;
    private int lastInsetBottom = -1;
    private int lastInsetLeft = -1;
    private int lastInsetRight = -1;
    /**
     * 回到前台后的"杂活"：快捷方式/Job 排程/前台服务/更新检查/BLE。
     * 这些都不是首帧需要的，放在首帧之后跑，别和 WebView 启动、页面 JS 抢主线程。
     */
    private final Runnable backgroundWork = () -> {
        if (destroyed) return;
        ShortcutHelper.ensure(this);
        MonitorScheduler.ensure(this);
        AutoTaskScheduler.ensure(this);
        MonitorService.start(this);
        UpdateChecker.onResume(this);
        Proximity.resume(this);
    };

    private final Runnable idleTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !resumed) return;
            if (cache != null && cache.hasPending() && !idleApplyInFlight
                && SystemClock.elapsedRealtime() - lastInteractionAt >= IDLE_MS) {
                checkPageIdleAndApply();
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        debugBuild = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        versionName = readVersionName();
        readDebugBaseUrl();

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        dark = sp.contains(K_DARK) ? sp.getBoolean(K_DARK, true) : isSystemDark();
        lastCheckAt = sp.getLong(K_CHECK, 0L);
        lastInteractionAt = SystemClock.elapsedRealtime();
        startAt = lastInteractionAt;

        setupWindow();
        root = new FrameLayout(this);
        root.setBackgroundColor(bgColor());
        setContentView(root);

        if (!createWebView()) return;
        // 冷启动到页面首帧之间（约 300~600ms）原来是一块纯色。放一张极轻的原生启动图，
        // 首帧就有东西可看；页面 commit 时淡出。真正的数据仍由页面/缓存负责。
        if (savedInstanceState == null) showSplash();

        cache = new WebCache(this, baseUrl);
        bridge = new ShellBridge(this);
        webView.addJavascriptInterface(bridge, "ZeehoShell");
        applyBarAppearance(dark);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(insets);
            return insets;
        });
        root.requestApplyInsets();

        cache.promotePendingAtStartup();
        cache.ensureOptionalAssetsAsync();
        // 进程重启后如果还停在 vpn/waiting，但抓取服务已经不在了（同一进程，跟着一起死了），
        // 说明这一轮已经中断；留成 error 让页面提示，别让用户以为还在等。
        String capState = NativeStore.captureState(this);
        if (("vpn".equals(capState) || "waiting".equals(capState)) && !AutoCaptureService.isRunning()) {
            NativeStore.setCaptureState(this, "error", "抓取被中断（App 进程重启）");
        }
        if (cache.hasActive()) {
            loadPage();
        } else {
            startInitialization();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        lastInteractionAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(idleTick);
        main.post(idleTick);
        applyBarAppearance(dark);
        AppState.foreground = true;
        // 面板自己的 WebView 不在 VPN 允许列表里，回到前台不会受代理影响。
        // 早先这里一回到面板就停抓取，会把官方 App 正在返回的 baseInfo 请求一起掐掉；
        // 现在让服务继续跑到成功/120s 超时，用户可随时点「取消抓取」。
        if (AutoCaptureService.isRunning() && "waiting".equals(NativeStore.captureState(this))) {
            CaptureLog.add(this, "ui", "panel resumed while waiting; keep capture running");
        }
        // 首帧优先：这些系统调用（快捷方式、JobScheduler、前台服务、更新检查、BLE）全部后移。
        main.removeCallbacks(backgroundWork);
        main.postDelayed(backgroundWork, 350);
        if (pageLoaded) main.postDelayed(() -> UpdateChecker.check(this), 1200);
        if (pageLoaded) scheduleUpdateCheck(1500);
    }

    @Override
    protected void onPause() {
        resumed = false;
        AppState.foreground = false;
        main.removeCallbacks(idleTick);
        super.onPause();
        // 页面刚同步过状态：退到后台立刻把充电进度/未读通知补上，不用等下一轮网络。
        StatusFeed.reconcileBackground(this);
        // 电量历史缓存里可能还有不足 8 条的尾块，退后台时落盘，进程被杀也不会丢。
        BatteryHistory.flush(this);
        // 附近检测只在 App 前台做；退后台立刻停 BLE 扫描，避免耗电。
        Proximity.stop(this);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        // 页面没了还挂着的选图回调必须还掉，否则 WebView 端那个 file input 永远卡在"等待中"
        if (pendingFileUpload != null) {
            pendingFileUpload.onReceiveValue(null);
            pendingFileUpload = null;
        }
        if (cache != null) cache.shutdown();
        if (webView != null) {
            if (root != null) root.removeView(webView);
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        // **只在"渲染进程崩过、新 WebView 还没 commit"时把页面重新载起来**：那种情况下
        // pageLoaded 被置成 false，直接走 super 会让用户以为"点一下 App 就没了"。
        // 不能对所有 !pageLoaded 都 reload：离线且没有快照时页面永远 commit 不了，
        // 那样每按一次返回都在重试，用户根本退不出去。
        if (rendererDied && !pageLoaded) {
            rendererDied = false;
            backInFlight = false;
            loadPage();
            return;
        }
        if (!pageLoaded) {
            super.onBackPressed();
            return;
        }
        // 从面板进过 vehicle-info.html（投屏资料页）时，先退回面板；
        // 主面板本身是单页，没有历史记录，不会影响原来的"连按两次退出"。
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (backInFlight) return;
        backInFlight = true;
        webView.evaluateJavascript(BACK_JS, value -> main.post(() -> {
            backInFlight = false;
            if (destroyed) return;
            String r = value == null ? "" : value.replace("\"", "");
            if (!"exit".equals(r)) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastBackAt <= BACK_EXIT_MS) {
                finish();
            } else {
                lastBackAt = now;
                Toast.makeText(this, R.string.back_again, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        lastInteractionAt = SystemClock.elapsedRealtime();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        lastInteractionAt = SystemClock.elapsedRealtime();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyBarAppearance(dark);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (root != null) root.setBackgroundColor(bgColor());
        if (webView != null) webView.setBackgroundColor(bgColor());
        applyBarAppearance(dark);
    }

    // ---------------- 网页桥触发的原生动作 ----------------

    void postRequestNotifications() {
        main.post(this::requestNotificationsOnce);
    }

    private void requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.getBoolean("notify_asked", false)) return;
        sp.edit().putBoolean("notify_asked", true).apply();
        try {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        } catch (Exception ignored) {
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void postRequestProximity() {
        main.post(() -> {
            if (Proximity.hasPermission(this)) {
                Proximity.resume(this);
                return;
            }
            try {
                requestPermissions(Proximity.permissions(), REQ_PROXIMITY);
            } catch (Exception e) {
                Log.w(TAG, "request proximity permission failed", e);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PROXIMITY) {
            boolean granted = Proximity.hasPermission(this);
            if (granted) Proximity.resume(this);
            else Toast.makeText(this, R.string.nearby_permission_denied, Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode != REQ_LOCATION) return;
        boolean granted = hasLocationPermission();
        String origin = pendingGeoOrigin;
        GeolocationPermissions.Callback cb = pendingGeoCallback;
        pendingGeoOrigin = null;
        pendingGeoCallback = null;
        if (cb != null) cb.invoke(origin, granted, false);
        if (!granted) {
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            ValueCallback<Uri[]> cb = pendingFileUpload;
            pendingFileUpload = null;
            if (cb == null) return;
            Uri[] uris = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                uris = new Uri[]{ data.getData() };
            }
            // 取消（resultCode != OK）也要回调 null：WebView 在等这一次回话才恢复 file input
            cb.onReceiveValue(uris);
            return;
        }
        if (requestCode != REQ_VPN) return;
        CaptureLog.add(this, "ui", "vpn permission result="
            + (resultCode == RESULT_OK ? "ok" : "denied/cancel"));
        if (resultCode == RESULT_OK) {
            // 只记“已授权”；VPN 要等用户确认指引、点“启动极核 App”时才建立——
            // 避免本应用自己的 WebView 在用户眼前被 VPN 代理影响。
            NativeStore.setCaptureState(this, "ready", null);
        } else {
            NativeStore.setCaptureState(this, "denied", "你拒绝了 VPN 授权");
            Notify.captureFailed(this, "你拒绝了 VPN 授权");
        }
    }

    // ---------------- 方案 3：VPN 自动抓取 ----------------

    /** 页面按钮：先弹系统 VPN 授权；授权后进入 ready，等指引确认再起 VPN。 */
    void postStartCapture() {
        main.post(() -> {
            if (destroyed || isFinishing()) return;
            CaptureLog.begin(this, "start capture clicked");
            CaptureLog.add(this, "ui", "start capture clicked");
            NativeStore.setCaptureState(this, "vpn", null);
            Intent prep = VpnService.prepare(this);
            if (prep != null) {
                try {
                    startActivityForResult(prep, REQ_VPN);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "vpn permission intent failed", e);
                    CaptureLog.add(this, "ui", "vpn permission intent failed: " + e.getMessage());
                    NativeStore.setCaptureState(this, "error", "无法打开 VPN 授权界面");
                    Notify.captureFailed(this, "无法打开 VPN 授权界面");
                    return;
                }
            }
            CaptureLog.add(this, "ui", "vpn already authorized");
            NativeStore.setCaptureState(this, "ready", null);
        });
    }

    private void startCaptureService() {
        if (getPackageManager().getLaunchIntentForPackage("com.cfmoto") == null) {
            CaptureLog.add(this, "ui", "com.cfmoto launch intent missing");
            NativeStore.setCaptureState(this, "error", "未安装极核 App（com.cfmoto）");
            Notify.captureFailed(this, "未安装极核 App（com.cfmoto）");
            return;
        }
        CaptureLog.add(this, "ui", "starting capture service");
        NativeStore.setCaptureState(this, "waiting", null);
        AutoCaptureService.start(this);
    }

    void postStopCapture() {
        main.post(() -> {
            CaptureLog.add(this, "ui", "stop capture clicked running="
                + AutoCaptureService.isRunning());
            if (AutoCaptureService.isRunning()) AutoCaptureService.stop(this);
            NativeStore.setCaptureState(this, "idle", null);
        });
    }

    /** 只打开官方 App：不开 VPN、不启动抓取，让用户先停在「我的」页。 */
    void postOpenOfficialApp() {
        main.post(this::launchOfficialNow);
    }

    /** 指引确认后：先起 VPN 让代理就绪，再跳官方 App。 */
    void postLaunchOfficialApp() {
        main.post(() -> {
            String state = NativeStore.captureState(this);
            CaptureLog.add(this, "ui", "launch official clicked state=" + state);
            if ("ready".equals(state) || "vpn".equals(state)) {
                startCaptureService();
                if ("error".equals(NativeStore.captureState(this))) return;
                main.postDelayed(this::launchOfficialNow, 700);
            } else {
                launchOfficialNow();
            }
        });
    }

    private void launchOfficialNow() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.cfmoto");
        if (i == null) {
            CaptureLog.add(this, "ui", "launch official: intent null");
            NativeStore.setCaptureState(this, "error", "未安装极核 App（com.cfmoto）");
            Notify.captureFailed(this, "未安装极核 App（com.cfmoto）");
            return;
        }
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            CaptureLog.add(this, "ui", "official app launched");
        } catch (Exception e) {
            Log.w(TAG, "launch cfmoto failed", e);
            CaptureLog.add(this, "ui", "launch official failed: " + e.getMessage());
            NativeStore.setCaptureState(this, "error", "无法启动极核 App");
        }
    }

    /** 页面取走抓取结果后清掉，避免下次开屏又弹一次。 */
    void postClearCapture() {
        main.post(() -> NativeStore.clearCapture(this));
    }

    private boolean captureActive() {
        String s = NativeStore.captureState(this);
        return "vpn".equals(s) || "ready".equals(s) || "waiting".equals(s)
            || "error".equals(s) || "denied".equals(s);
    }

    /** 给页面轮询的状态：state/token/uid/error + 官方 App 是否已安装。 */
    String captureJson() {
        try {
            JSONObject j = new JSONObject(NativeStore.captureJson(this));
            j.put("installed", getPackageManager().getLaunchIntentForPackage("com.cfmoto") != null);
            return j.toString();
        } catch (Exception e) {
            return NativeStore.captureJson(this);
        }
    }

    /**
     * 方案三的诊断日志：只包含连接主机、请求路径（query 值已打码）、响应结构和提取结果，
     * 不包含 token 原文。用户复制后发给开发者即可定位卡在哪一步。
     */
    String captureDiag() {
        String log = CaptureLog.read(this);
        if (log == null || log.trim().isEmpty()) {
            return "state=" + NativeStore.captureState(this)
                + "\n（还没有抓取日志。先点「开启自动抓取」，按指引进入极核「我的 → 签到」；"
                + "失败后回来点这个按钮复制。）";
        }
        return "state=" + NativeStore.captureState(this) + "\n" + log;
    }

    void postCopyCaptureDiag() {
        main.post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(
                    ClipData.newPlainText("Zeeho capture diag", captureDiag()));
                Toast.makeText(this, "诊断日志已复制", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "复制失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    void postClearCaptureDiag() {
        main.post(() -> {
            CaptureLog.clear(this);
            Toast.makeText(this, "诊断日志已清空", Toast.LENGTH_SHORT).show();
        });
    }

    /** 网页任务面板的 App 专属设置：自动执行开关/时间/任务参数。 */
    void postSetAutoTask(String json) {
        main.post(() -> {
            try {
                JSONObject j = new JSONObject(json);
                boolean enabled = j.optBoolean("enabled", false);
                String time = j.optString("time", "09:00");
                String content = j.optString("postContent", "开心的一天");
                boolean delete = j.optBoolean("deleteAfterPost", true);
                int gap = j.optInt("gapMs", 2000);
                // 提醒开关跟自动任务同一份 JSON：老页面不带这三个键时保留原值，别把它们重置成 false。
                NativeStore.setAlerts(this,
                    j.has("alertsLow") ? j.optBoolean("alertsLow") : NativeStore.alertLow(this),
                    j.has("alertsOffline") ? j.optBoolean("alertsOffline") : NativeStore.alertOffline(this),
                    j.has("alertsTire") ? j.optBoolean("alertsTire") : NativeStore.alertTire(this));
                NativeStore.setAuto(this, enabled, time, content, delete, gap,
                    NativeStore.autoLastResult(this));
                if (enabled) {
                    AutoTaskScheduler.schedule(this);
                    requestNotificationsOnce();
                } else {
                    AutoTaskScheduler.cancel(this);
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** 网页任务面板的后台同步开关。 */
    void postSetBackground(boolean on) {
        main.post(() -> {
            NativeStore.setBackgroundEnabled(this, on);
            if (on) {
                MonitorScheduler.ensure(this);
                MonitorService.start(this);
                requestNotificationsOnce();
            } else {
                MonitorScheduler.cancel(this);
                MonitorService.stop(this);
                Notify.cancelAll(this);
            }
        });
    }

    /** 打开通知/电池/自启动设置。HyperOS 的自启动入口不是标准 API，失败就退回应用详情。 */
    void postOpenSettings(String kind) {
        main.post(() -> {
            Intent i;
            if ("autostart".equals(kind)) {
                i = new Intent("miui.intent.action.OP_AUTO_START")
                    .putExtra("extra_pkgname", getPackageName());
            } else if ("notifications".equals(kind) && Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else if ("battery".equals(kind)) {
                // 已经加入白名单就打开系统列表（没有标准的"单应用详情"页）；否则直接弹本应用的请求框。
                if (batteryIgnoring()) {
                    i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                } else {
                    i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                }
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception e) {
                if ("battery".equals(kind)) {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    } catch (Exception ignored) {
                        // 继续退到应用详情
                    }
                }
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ---------------- 提供给 ShellBridge ----------------

    /** 是否已加入电池优化白名单（HyperOS 上后台存活的关键一项）。 */
    boolean batteryIgnoring() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** 「后台自检」卡的数据：让用户看到后台到底还活着没有，而不是靠微件时间猜。 */
    String backgroundStatusJson() {
        JSONObject j = new JSONObject();
        try {
            NativeStore.Status s = NativeStore.status(this);
            j.put("enabled", NativeStore.backgroundEnabled(this));
            j.put("service", MonitorService.isRunning());
            j.put("job", MonitorScheduler.isScheduled(this));
            j.put("notifications", Notify.allowed(this));
            j.put("batteryIgnoring", batteryIgnoring());
            j.put("lastPoll", NativeStore.lastPoll(this));
            j.put("lastPage", NativeStore.lastPageSync(this));
            j.put("updatedAt", s.updatedAt);
            j.put("authFailed", NativeStore.authFailed(this));
            j.put("hasToken", !NativeStore.token(this).isEmpty());
            j.put("configured", NativeStore.isConfigured(this));
        } catch (Exception e) {
            Log.w(TAG, "backgroundStatus failed", e);
        }
        return j.toString();
    }

    /** 页面第一次 render 完成（桥调用，可能在 JavaBridge 线程）：回主线程淡出启动图。 */
    void postHideSplash() {
        main.post(() -> {
            Log.i(TAG, "page ready in " + (SystemClock.elapsedRealtime() - startAt) + "ms");
            hideSplash();
        });
    }

    /** 一键修复后台：重排 15 分钟兜底任务并拉起前台服务（已有实例时只会重开 tick）。 */
    void postRestartBackground() {
        main.post(() -> {
            try {
                MonitorScheduler.ensure(this);
                MonitorService.start(this);
                Toast.makeText(this, R.string.bg_restarted, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.w(TAG, "restart background failed", e);
            }
        });
    }

    String insetsJson() {
        return "{\"top\":" + insetTop + ",\"bottom\":" + insetBottom
            + ",\"left\":" + insetLeft + ",\"right\":" + insetRight + "}";
    }

    String appVersionName() {
        return versionCode > 0 ? versionName + " (" + versionCode + ")" : versionName;
    }

    /**
     * 页面「面板版本」的手动检查：**异步版**（2026-10-19）。
     * 旧的 checkPageUpdate() 在 JavaBridge 线程同步抓远端 version.json（20 秒读超时）——
     * JS 主线程会停在调用行等返回，弱网下整页点击最长冻 20 秒。现在改成：
     * beginPageUpdate() 起后台任务（只查 build，不下载），pollPageUpdate() 轮询取果。
     */
    private volatile String pageQueryResult = "";
    private final java.util.concurrent.atomic.AtomicInteger pageQueryState =
        new java.util.concurrent.atomic.AtomicInteger(0);   // 0=空闲 1=进行中 2=完成

    void beginPageUpdate() {
        if (!pageQueryState.compareAndSet(0, 1)) return;    // 已有一轮在跑
        pageQueryResult = "";
        if (cache == null) {
            pageQueryResult = "{\"ok\":false,\"error\":\"缓存未就绪\"}";
            pageQueryState.set(2);
            return;
        }
        cache.checkForUpdateAsync(new WebCache.UpdateCallback() {
            @Override public void onUpdateReady(String build) {
                pageQueryResult = resultJson(true, build);
                pageQueryState.set(2);
            }
            @Override public void onNoUpdate() {
                pageQueryResult = resultJson(true, currentBuild());
                pageQueryState.set(2);
            }
            @Override public void onError(String message) {
                pageQueryResult = "{\"ok\":false,\"error\":"
                    + JSONObject.quote(message == null ? "网络不可用" : message) + "}";
                pageQueryState.set(2);
            }
        });
    }

    private String resultJson(boolean ok, String remote) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", ok);
            out.put("current", currentBuild());
            out.put("remote", remote == null ? "" : remote);
            out.put("pending", cache != null && cache.pendingBuild() != null ? cache.pendingBuild() : "");
            out.put("hasUpdate", remote != null && !remote.isEmpty() && !remote.equals(currentBuild()));
        } catch (Exception ignored) { }
        return out.toString();
    }

    private String currentBuild() {
        return cache != null && cache.active() != null ? cache.active().build : "";
    }

    /** 取一次异步面板检查的结果：done=true 时带 json，取走即复位。 */
    String pollPageUpdate() {
        JSONObject out = new JSONObject();
        try {
            int s = pageQueryState.get();
            out.put("done", s == 2);
            if (s == 2) {
                pageQueryState.set(0);
                out.put("json", pageQueryResult);
            }
        } catch (Exception ignored) { }
        return out.toString();
    }

    /**
     * 同步版面板检查：只留给旧页面兜底，新页面不再调用（会冻 JS 主线程最长 20 秒）。
     */
    @Deprecated
    String checkPageUpdate() {
        JSONObject out = new JSONObject();
        try {
            String current = cache != null && cache.active() != null ? cache.active().build : "";
            String remote = cache == null ? "" : cache.remoteBuild();
            String pending = cache == null ? "" : cache.pendingBuild();
            out.put("ok", true);
            out.put("current", current);
            out.put("remote", remote == null ? "" : remote);
            out.put("pending", pending);
            out.put("hasUpdate", !remote.isEmpty() && !remote.equals(current));
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    /** 手动「重启页面」：有 pending 就应用；否则先检查/下载远端页面，完成后再 reload。 */
    void postRestartPage() {
        main.post(() -> {
            if (destroyed || webView == null) return;
            if (cache != null && cache.hasPending()) {
                cache.promotePendingNow();
                webView.reload();
                return;
            }
            if (cache == null || !cache.hasActive() || updateRunning) {
                webView.reload();
                return;
            }
            updateRunning = true;
            Toast.makeText(this, R.string.page_update_checking, Toast.LENGTH_SHORT).show();
            cache.checkForUpdateAsync(new WebCache.UpdateCallback() {
                @Override
                public void onUpdateReady(String build) {
                    main.post(() -> {
                        updateRunning = false;
                        if (destroyed || webView == null) return;
                        if (cache != null && cache.hasPending()) cache.promotePendingNow();
                        Toast.makeText(MainActivity.this, R.string.updated, Toast.LENGTH_SHORT).show();
                        webView.reload();
                    });
                }

                @Override
                public void onNoUpdate() {
                    main.post(() -> {
                        updateRunning = false;
                        if (!destroyed && webView != null) webView.reload();
                    });
                }

                @Override
                public void onError(String message) {
                    main.post(() -> {
                        updateRunning = false;
                        if (!destroyed && webView != null) {
                            Toast.makeText(MainActivity.this,
                                getString(R.string.page_update_failed, message),
                                Toast.LENGTH_LONG).show();
                            webView.reload();
                        }
                    });
                }
            });
        });
    }

    void postSetDark(boolean d) {
        main.post(() -> {
            if (destroyed) return;
            dark = d;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(K_DARK, d).apply();
            if (root != null) root.setBackgroundColor(bgColor());
            if (webView != null) webView.setBackgroundColor(bgColor());
            applyBarAppearance(d);
        });
    }

    // ---------------- 窗口 / 系统栏 ----------------

    private void setupWindow() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            w.setNavigationBarDividerColor(Color.TRANSPARENT);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
        }
    }

    private void applyBarAppearance(boolean isDark) {
        boolean light = !isDark;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
    }

    private void applyInsets(WindowInsets insets) {
        int top;
        int bottom;
        int left;
        int right;
        int ime;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = bars.top;
            bottom = bars.bottom;
            left = bars.left;
            right = bars.right;
            ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
        } else {
            top = insets.getSystemWindowInsetTop();
            bottom = insets.getSystemWindowInsetBottom();
            left = insets.getSystemWindowInsetLeft();
            right = insets.getSystemWindowInsetRight();
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                top = Math.max(top, cutout.getSafeInsetTop());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                left = Math.max(left, cutout.getSafeInsetLeft());
                right = Math.max(right, cutout.getSafeInsetRight());
            }
            int stable = insets.getStableInsetBottom();
            ime = Math.max(0, insets.getSystemWindowInsetBottom() - stable);
            if (ime > 0) bottom = stable;
        }

        float density = getResources().getDisplayMetrics().density;
        int topDp = Math.round(top / density);
        int bottomDp = Math.round(bottom / density);
        int leftDp = Math.round(left / density);
        int rightDp = Math.round(right / density);
        boolean changed = topDp != lastInsetTop || bottomDp != lastInsetBottom
            || leftDp != lastInsetLeft || rightDp != lastInsetRight;
        insetTop = topDp;
        insetBottom = bottomDp;
        insetLeft = leftDp;
        insetRight = rightDp;
        lastInsetTop = topDp;
        lastInsetBottom = bottomDp;
        lastInsetLeft = leftDp;
        lastInsetRight = rightDp;
        if (changed) {
            Log.i(TAG, "insets dp=" + topDp + "," + bottomDp + " imePx=" + ime);
        }

        setWebViewBottomMargin(ime);

        if (changed && webView != null) {
            webView.post(() -> webView.evaluateJavascript(
                "window.dispatchEvent(new Event('resize'))", null));
        }
    }

    private void setWebViewBottomMargin(int px) {
        if (webView == null) return;
        ViewGroup.LayoutParams lp = webView.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams f = (FrameLayout.LayoutParams) lp;
        if (f.bottomMargin != px) {
            f.bottomMargin = px;
            webView.setLayoutParams(f);
        }
    }

    private boolean isSystemDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // ---------------- WebView ----------------

    private boolean createWebView() {
        try {
            // 等启动时的缓存修剪跑完（最多 400ms）：它必须在 WebView 创建前完成，
            // 否则可能删到 Chromium 正在打开的 HTTP Cache。
            long t0 = SystemClock.elapsedRealtime();
            // 一周才扫一次（见 WebViewCache.trimAsync）；绝大多数启动这里立即返回。
            WebViewCache.awaitTrim(400);
            long t1 = SystemClock.elapsedRealtime();
            webView = new WebView(this);
            Log.i(TAG, "webview ready: trimWait=" + (t1 - t0) + "ms create="
                + (SystemClock.elapsedRealtime() - t1) + "ms total=" + (SystemClock.elapsedRealtime() - startAt) + "ms");
        } catch (RuntimeException | LinkageError t) {
            Log.e(TAG, "webview unavailable", t);
            showFatal(getString(R.string.webview_unavailable), false);
            return false;
        }

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSaveFormData(false);
        s.setSavePassword(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " ZeehoWebNext/" + versionName);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        if (debugBuild) WebView.setWebContentsDebuggingEnabled(true);

        webView.setBackgroundColor(bgColor());
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setWebViewClient(new Client());
        webView.setWebChromeClient(new Chrome());
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
            Log.i(TAG, "download ignored in shell: " + url));
        root.addView(webView);
        return true;
    }

    private void loadPage() {
        if (webView == null) return;
        pageLoaded = false;
        Log.i(TAG, "load page, active=" + (cache == null || cache.active() == null
            ? "none" : cache.active().build));
        long t = SystemClock.elapsedRealtime();
        // URL 带上快照 build：WebView 会按 URL 缓存主文档，快照更新后 URL 不变会继续用旧页。
        // 带 build 查询后，新快照就是一个新 URL，页面更新才真的能生效（查询不影响拦截/同源）。
        String url = baseUrl;
        if (cache != null && cache.active() != null) {
            String build = cache.active().build;
            if (build != null && !build.isEmpty()) {
                url += (url.contains("?") ? "&" : "?") + "b=" + Uri.encode(build);
            }
        }
        webView.loadUrl(url);
        // 兜底：旧版页面没有 pageReady 桥，onPageFinished 又可能被外链字体拖到 1s+。
        // 650ms 后无论如何收掉启动图，最多挡住"本来就要显示的内容"一小会儿。
        main.postDelayed(this::hideSplash, 650);
        Log.i(TAG, "loadUrl returned in " + (SystemClock.elapsedRealtime() - t) + "ms, since start="
            + (SystemClock.elapsedRealtime() - startAt) + "ms");
    }

    private void startInitialization() {
        if (initRunning || cache == null) return;
        initRunning = true;
        showOverlayForInit();
        cache.initializeAsync(
            (percent, done, total) -> main.post(() -> {
                if (!destroyed) updateOverlayProgress(percent, done, total);
            }),
            new WebCache.Callback() {
                @Override
                public void onSuccess() {
                    main.post(() -> {
                        if (destroyed) return;
                        initRunning = false;
                        lastCheckAt = SystemClock.elapsedRealtime();
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putLong(K_CHECK, lastCheckAt).apply();
                        updateOverlayOpening();
                        loadPage();
                    });
                }

                @Override
                public void onError(String message) {
                    main.post(() -> {
                        if (destroyed) return;
                        initRunning = false;
                        // 没有可用快照时别把用户钉在"页面校验失败"上：WebCache 在无 active
                        // 快照时会让请求走网络，直接打开在线版至少还有机会用；真断网时
                        // WebView 自己的 onReceivedError 会再给一次明确提示与重试。
                        Log.w(TAG, "cache init failed, fall back to online: " + message);
                        updateOverlayOpening();
                        loadPage();
                    });
                }
            });
    }

    private void scheduleUpdateCheck(long delay) {
        if (cache == null || !cache.hasActive() || updateRunning) return;
        if (SystemClock.elapsedRealtime() - lastCheckAt < UPDATE_CHECK_GAP_MS) return;
        main.postDelayed(() -> {
            if (destroyed || !resumed || updateRunning || cache == null || !cache.hasActive()) return;
            updateRunning = true;
            lastCheckAt = SystemClock.elapsedRealtime();
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putLong(K_CHECK, lastCheckAt).apply();
            cache.checkForUpdateAsync(new WebCache.UpdateCallback() {
                @Override
                public void onUpdateReady(String build) {
                    main.post(() -> {
                        updateRunning = false;
                        if (!destroyed) Log.i(TAG, "update pending: " + build);
                    });
                }

                @Override
                public void onNoUpdate() {
                    main.post(() -> updateRunning = false);
                }

                @Override
                public void onError(String message) {
                    main.post(() -> {
                        updateRunning = false;
                        if (!destroyed) Log.w(TAG, "update check failed: " + message);
                    });
                }
            });
        }, delay);
    }

    private void checkPageIdleAndApply() {
        if (webView == null || cache == null || !cache.hasPending()) return;
        if (webView.getUrl() == null) return;
        idleApplyInFlight = true;
        webView.evaluateJavascript(IDLE_JS, value -> main.post(() -> {
            idleApplyInFlight = false;
            if (destroyed || !"\"idle\"".equals(value)) return;
            if (!resumed || cache == null || !cache.hasPending()) return;
            if (SystemClock.elapsedRealtime() - lastInteractionAt < IDLE_MS) return;
            WebCache.Snapshot s = cache.promotePendingNow();
            if (s == null) return;
            Toast.makeText(this, R.string.updated, Toast.LENGTH_SHORT).show();
            lastInteractionAt = SystemClock.elapsedRealtime();
            webView.reload();
        }));
    }

    // ---------------- 启动图 ----------------

    /**
     * 极轻的原生启动图：只画图标 + 名称，不做动画、不读网络/缓存。
     * 目的是把"WebView 还没 commit"的那 300~600ms 从纯色块变成品牌画面；
     * 页面 commit / 首启遮罩 / 错误页出现时立刻淡出。
     */
    private void showSplash() {
        if (splash != null || destroyed || root == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(bgColor());

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        int size = dp(76);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.bottomMargin = dp(14);
        box.addView(icon, ip);

        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextSize(16);
        name.setTextColor(dark ? 0xFFE6E0E9 : 0xFF1D1B20);
        name.setGravity(Gravity.CENTER);
        box.addView(name);

        root.addView(box, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        splash = box;
    }

    private void hideSplash() {
        final View s = splash;
        if (s == null) return;
        splash = null;
        s.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            if (s.getParent() != null) root.removeView(s);
        }).start();
    }

    // ---------------- 原生遮罩 ----------------

    private void showOverlayForInit() {
        hideSplash();
        ensureOverlay();
        overlayError = false;
        overlay.setAlpha(1f);
        overlayStatus.setText(R.string.init_preparing);
        overlayDetail.setText(R.string.init_hint);
        overlayProgress.setVisibility(View.VISIBLE);
        overlayProgress.setProgress(0);
        overlayRetry.setVisibility(View.GONE);
        overlayRetry.setText(R.string.init_retry);
    }

    private void updateOverlayProgress(int percent, long done, long total) {
        if (overlay == null || overlayError) return;
        overlayStatus.setText(R.string.init_downloading);
        int p = Math.max(0, Math.min(100, percent));
        overlayProgress.setProgress(p * 10);
        if (total > 0) {
            overlayDetail.setText(getString(R.string.init_bytes, mb(done), mb(total)));
        } else {
            overlayDetail.setText(getString(R.string.init_hint));
        }
    }

    private void updateOverlayOpening() {
        if (overlay == null || overlayError) return;
        overlayStatus.setText(R.string.init_opening);
        overlayDetail.setText("");
        overlayProgress.setProgress(1000);
    }

    private void showOverlayError(String message) {
        hideSplash();
        ensureOverlay();
        overlayError = true;
        overlay.setAlpha(1f);
        overlayStatus.setText(message);
        overlayDetail.setText(cache != null && cache.hasActive()
            ? R.string.network_hint_cached : R.string.network_hint);
        overlayProgress.setVisibility(View.GONE);
        overlayRetry.setVisibility(View.VISIBLE);
        overlayRetry.setText(captureActive() ? R.string.capture_stop_retry
            : (cache != null && cache.hasActive() ? R.string.reload : R.string.init_retry));
    }

    private void showFatal(String message, boolean retry) {
        hideSplash();
        ensureOverlay();
        overlayError = true;
        overlay.setAlpha(1f);
        overlayStatus.setText(message);
        overlayDetail.setText("");
        overlayProgress.setVisibility(View.GONE);
        overlayRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    private void hideOverlay() {
        final View o = overlay;
        if (o == null) return;
        overlay = null;
        o.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (o.getParent() != null) root.removeView(o);
        }).start();
    }

    private void ensureOverlay() {
        if (overlay != null) return;
        buildOverlay();
    }

    private void buildOverlay() {
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor(bgColor());
        o.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(28);
        box.setPadding(pad, pad, pad, pad);
        box.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(72), dp(72));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(icon, ilp);

        overlayTitle = text(getString(R.string.app_name), 26, textColor(), true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        tlp.topMargin = dp(18);
        box.addView(overlayTitle, tlp);

        overlayStatus = text(getString(R.string.init_preparing), 14, textColor(), false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.topMargin = dp(10);
        box.addView(overlayStatus, slp);

        overlayProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        overlayProgress.setMax(1000);
        overlayProgress.setProgress(0);
        overlayProgress.setProgressTintList(ColorStateList.valueOf(accentColor()));
        overlayProgress.setProgressBackgroundTintList(ColorStateList.valueOf(
            dark ? 0x22FFFFFF : 0x22000000));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(240), dp(4));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(20);
        box.addView(overlayProgress, plp);

        overlayDetail = text(getString(R.string.init_hint), 12, mutedColor(), false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        dlp.topMargin = dp(10);
        box.addView(overlayDetail, dlp);

        overlayRetry = new Button(this);
        overlayRetry.setText(R.string.init_retry);
        overlayRetry.setAllCaps(false);
        overlayRetry.setBackgroundTintList(ColorStateList.valueOf(accentColor()));
        overlayRetry.setTextColor(bgColor());
        overlayRetry.setVisibility(View.GONE);
        overlayRetry.setOnClickListener(v -> {
            // 页面打不开又刚好有抓取/VPN 挂着：先停 VPN 再重载，别让用户被困在代理里。
            if (captureActive()) {
                if (AutoCaptureService.isRunning()) AutoCaptureService.stop(this);
                NativeStore.setCaptureState(this, "idle", null);
                hideOverlay();
                main.postDelayed(this::loadPage, 800);
                return;
            }
            if (cache != null && cache.hasActive()) {
                hideOverlay();
                loadPage();
            } else if (webView == null) {
                recreate();
            } else {
                startInitialization();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.topMargin = dp(18);
        box.addView(overlayRetry, blp);

        o.addView(box);
        root.addView(o, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = o;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ---------------- 颜色 / 尺寸 ----------------

    private int bgColor() {
        return getColor(dark ? R.color.page_dark : R.color.page_light);
    }

    private int textColor() {
        return getColor(dark ? R.color.text_dark : R.color.text_light);
    }

    private int mutedColor() {
        return getColor(dark ? R.color.muted_dark : R.color.muted_light);
    }

    private int accentColor() {
        return getColor(dark ? R.color.accent_dark : R.color.accent_light);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private String readVersionName() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                .getPackageInfo(getPackageName(), 0);
            versionCode = pi.versionCode;
            String v = pi.versionName;
            return v == null ? "1.0" : v;
        } catch (Exception e) {
            return "1.0";
        }
    }

    private void readDebugBaseUrl() {
        if (!debugBuild) return;
        Intent intent = getIntent();
        String extra = intent == null ? null : intent.getStringExtra("base_url");
        if (extra == null) return;
        if (!extra.startsWith("http://") && !extra.startsWith("https://")) return;
        if (!extra.endsWith("/")) extra += "/";
        baseUrl = extra;
        Log.i(TAG, "debug base url: " + baseUrl);
    }

    // ---------------- WebView 回调 ----------------

    private final class Client extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            WebCache c = cache;
            return c == null ? null : c.intercept(request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request == null ? null : request.getUrl();
            return UrlPolicy.handleNavigation(MainActivity.this, view,
                u == null ? null : u.toString(), baseUrl);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return UrlPolicy.handleNavigation(MainActivity.this, view, url, baseUrl);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (url != null && !UrlPolicy.isAllowedPage(url, baseUrl)
                && !url.startsWith("about:") && !url.startsWith("data:")) {
                // 万一有重定向绕过 shouldOverrideUrlLoading，也把页面拉回来
                view.stopLoading();
                view.loadUrl(baseUrl);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            // 启动图别压到页面已经画出来之后：onPageCommitVisible 在主线程忙时可能晚 300ms+，
            // 而 load 事件（脚本已执行）通常更早。这里只负责把启动图淡出，错误/首启遮罩另有逻辑。
            Log.i(TAG, "page finished in " + (SystemClock.elapsedRealtime() - startAt) + "ms");
            hideSplash();
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (destroyed) return;
            pageLoaded = true;
            rendererDied = false;
            lastInteractionAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "page commit in " + (lastInteractionAt - startAt) + "ms");
            hideSplash();
            // 首屏性能时间线：DOMContentLoaded / load / FCP / 首数据 mark。留着，改启动链路时对比用。
            view.evaluateJavascript("(function(){try{var n=(performance.getEntriesByType('navigation')||[])[0]||{};"
                + "var p=(performance.getEntriesByType('paint')||[]).map(function(x){return x.name+'='+Math.round(x.startTime)});"
                + "var f=performance.getEntriesByName('zeeho-first-data')[0];"
                + "return JSON.stringify({dcl:Math.round(n.domContentLoadedEventEnd||0),load:Math.round(n.loadEventEnd||0),"
                + "paint:p,firstData:f?Math.round(f.startTime):null,now:Math.round(performance.now())});}catch(e){return ''}})()",
                v -> Log.i(TAG, "page timing " + v));
            // 首数据通常晚于 FCP：2.5 秒后再取一次 mark，才知道"数据真正上屏"的时刻。
            main.postDelayed(() -> {
                if (destroyed || webView == null) return;
                webView.evaluateJavascript(
                    "(function(){try{var f=performance.getEntriesByName('zeeho-first-data')[0];"
                        + "var c=performance.getEntriesByName('zeeho-cached-data')[0];"
                        + "return JSON.stringify({cached:c?Math.round(c.startTime):null,"
                        + "firstData:f?Math.round(f.startTime):null,now:Math.round(performance.now())});"
                        + "}catch(e){return ''}})()",
                    v -> Log.i(TAG, "page timing late " + v));
            }, 2500);
            hideOverlay();
            scheduleUpdateCheck(3000);
            main.postDelayed(() -> UpdateChecker.check(MainActivity.this), 3000);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()
                && error != null && error.getErrorCode() != ERROR_UNKNOWN) {
                Log.w(TAG, "main frame error: " + error.getDescription());
                showOverlayError(getString(R.string.load_failed));
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse response) {
            if (request != null && request.isForMainFrame()
                && response != null && response.getStatusCode() >= 400) {
                Log.w(TAG, "main frame http " + response.getStatusCode());
                showOverlayError(getString(R.string.load_failed)
                    + " (HTTP " + response.getStatusCode() + ")");
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // 绝不放过证书错误；主文档失败才弹原生提示，子资源失败让页面自己兜底
            handler.cancel();
            String url = error == null ? null : error.getUrl();
            if (url != null && UrlPolicy.isAllowedPage(url, baseUrl)) {
                showOverlayError(getString(R.string.load_failed));
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.e(TAG, "render process gone, crashed=" + (detail != null && detail.didCrash()));
            if (root != null) root.removeView(view);
            view.destroy();
            webView = null;
            pageLoaded = false;
            rendererDied = true;   // 让返回键知道"该重载而不是退出"（见 onBackPressed）
            if (createWebView()) {
                webView.addJavascriptInterface(bridge, "ZeehoShell");
                loadPage();
            } else {
                showFatal(getString(R.string.webview_unavailable), false);
            }
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {

        /**
         * 网页 <input type=file> 的落地：抓取账号 id 要从相册选二维码图。
         * 不实现这个回调时点击无反应（WebView 默认丢弃）；返回 false = "不支持"，
         * 网页的 change 事件永远不会来。
         */
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (pendingFileUpload != null) {
                // 上一次还没回来又点了一次：先把旧的回调干净地还掉（回调一次是契约，不能扣着）
                pendingFileUpload.onReceiveValue(null);
            }
            pendingFileUpload = callback;
            try {
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                Intent chooser = Intent.createChooser(pick, null);
                startActivityForResult(chooser, REQ_FILE_CHOOSER);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "file chooser failed", e);
                pendingFileUpload = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, R.string.file_chooser_failed, Toast.LENGTH_LONG).show();
                return true;
            }
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            return false;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            // 只有页面真的调用 navigator.geolocation（点「我的位置」/「两个都看」）才会走到这里，
            // 不在启动时申请定位，避免一打开 App 就弹权限。
            if (hasLocationPermission()) {
                callback.invoke(origin, true, false);
                return;
            }
            if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) {
                callback.invoke(origin, false, false);
                return;
            }
            pendingGeoOrigin = origin;
            pendingGeoCallback = callback;
            requestPermissions(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            if (debugBuild && m != null) {
                Log.d(TAG, "console: " + m.message() + " @"
                    + m.sourceId() + ":" + m.lineNumber());
            }
            return true;
        }
    }
}
