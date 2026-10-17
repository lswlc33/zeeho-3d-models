package io.github.lswlc33.zeehowebnext;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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
    private static final String PROD_BASE = "https://lswlc33.github.io/zeeho-3d-models/";
    private static final String PREFS = "webnext";
    private static final String K_DARK = "last_dark";
    private static final String K_CHECK = "last_check_at";
    private static final long IDLE_MS = 5000;
    private static final long BACK_EXIT_MS = 2000;
    private static final long UPDATE_CHECK_GAP_MS = 30000;

    private static final String BACK_JS =
        "(function(){try{"
            + "var s=document.querySelector('.sheet.show');"
            + "if(s){window.UI.back();return 'sheet';}"
            + "if(window.APP&&window.APP.state&&window.APP.state.tab!=='home'){"
            + "var t=document.querySelector('.tab[data-tab=home]');"
            + "if(t){t.click();return 'home';}}"
            + "return 'exit';}catch(e){return 'exit';}})()";

    private static final String IDLE_JS =
        "(function(){var a=document.activeElement;var t=a&&a.tagName;"
            + "return (document.querySelector('.sheet.show')||t==='INPUT'||t==='TEXTAREA')?'busy':'idle';})()";

    private FrameLayout root;
    private WebView webView;
    private WebCache cache;
    private ShellBridge bridge;
    private String baseUrl = PROD_BASE;
    private String versionName = "1.0";
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
    private long lastBackAt;
    private boolean idleApplyInFlight;
    private long lastCheckAt;

    private View overlay;
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
        if (pageLoaded) scheduleUpdateCheck(1500);
    }

    @Override
    protected void onPause() {
        resumed = false;
        main.removeCallbacks(idleTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
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
        if (webView == null || !pageLoaded) {
            super.onBackPressed();
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

    // ---------------- 提供给 ShellBridge ----------------

    String insetsJson() {
        return "{\"top\":" + insetTop + ",\"bottom\":" + insetBottom
            + ",\"left\":" + insetLeft + ",\"right\":" + insetRight + "}";
    }

    String appVersionName() {
        return versionName;
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
            webView = new WebView(this);
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
        s.setGeolocationEnabled(false);
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
        webView.loadUrl(baseUrl);
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
                        showOverlayError(getString(R.string.init_failed, message));
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

    // ---------------- 原生遮罩 ----------------

    private void showOverlayForInit() {
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
        ensureOverlay();
        overlayError = true;
        overlay.setAlpha(1f);
        overlayStatus.setText(message);
        overlayDetail.setText(cache != null && cache.hasActive()
            ? R.string.network_hint_cached : R.string.network_hint);
        overlayProgress.setVisibility(View.GONE);
        overlayRetry.setVisibility(View.VISIBLE);
        overlayRetry.setText(cache != null && cache.hasActive()
            ? R.string.reload : R.string.init_retry);
    }

    private void showFatal(String message, boolean retry) {
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
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
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
        public void onPageCommitVisible(WebView view, String url) {
            if (destroyed) return;
            pageLoaded = true;
            lastInteractionAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "page commit in " + (lastInteractionAt - startAt) + "ms");
            hideOverlay();
            scheduleUpdateCheck(3000);
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

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            return false;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
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
