package io.github.lswlc33.zeehowebnext.cast;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/**
 * 壳工程唯一需要接触的投屏入口：把「挂桥 / 权限 / 录屏结果 / 释放」收敛成四个静态方法，
 * {@code MainActivity} 里每处最多一行。
 *
 * <p>用法（完整示例见 {@code shell/README.md}）：
 * <pre>
 * private ZeehoCastBridge zeehoCast;
 *
 * onCreate:  zeehoCast = ZeehoCastShell.install(this, webView);
 *            ZeehoCastShell.requestPermissions(this);            // 首次进页面就申请，别等到点“开始投屏”
 * onActivityResult: if (ZeehoCastShell.forwardActivityResult(zeehoCast, requestCode, resultCode, data)) return;
 * onDestroy: ZeehoCastShell.release(zeehoCast); zeehoCast = null;
 * </pre>
 *
 * <p>为什么要有这层：{@link ZeehoCastBridge} 的方法都是页面契约（返回 JSON 字符串），
 * 而壳要处理的是 Android 生命周期（权限、resultCode、WebView 销毁）。两者混在 MainActivity 里
 * 很容易漏 {@code onActivityResult}（漏了的表现是“授权弹窗点允许了但 start 一直报
 * projection_permission_required”，很难查）。
 */
public final class ZeehoCastShell {

    private ZeehoCastShell() {
    }

    /** 权限申请码；{@code onRequestPermissionsResult} 不需要处理，桥每次启动会自己复查。 */
    public static final int REQUEST_CAST_PERMISSIONS = 0x5C02;

    /**
     * 挂桥：内部 {@code addJavascriptInterface(bridge, "ZeehoCast")}。
     *
     * <p>返回值必须由壳用字段强引用住（WebView 侧不保证强引用），否则可能被 GC，
     * 页面调用 {@code window.ZeehoCast} 时报 “object has no method”。
     */
    public static ZeehoCastBridge install(Activity activity, WebView webView) {
        return ZeehoCastBridge.attach(activity, webView);
    }

    /** 投屏需要的运行时权限（按系统版本裁剪）。 */
    public static String[] requiredPermissions() {
        List<String> permissions = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add("android.permission.NEARBY_WIFI_DEVICES");
            permissions.add("android.permission.POST_NOTIFICATIONS");
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return permissions.toArray(new String[0]);
    }

    /** 是否已经拿到全部投屏权限（缺权限时桥会返回 {@code wifi_permission_denied}）。 */
    public static boolean hasPermissions(Activity activity) {
        if (activity == null) {
            return false;
        }
        for (String permission : requiredPermissions()) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 申请投屏权限：只申请缺的那些；已全部授权时什么都不做。
     *
     * <p>建议在 WebView 建好后立刻调用一次：Wi-Fi Direct 扫描、前台服务通知都要这些权限，
     * 等到用户点“开始投屏”再弹窗，会让第一次投屏必失败一轮。
     */
    public static void requestPermissions(Activity activity) {
        if (activity == null) {
            return;
        }
        List<String> missing = new ArrayList<String>();
        for (String permission : requiredPermissions()) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        activity.requestPermissions(missing.toArray(new String[0]), REQUEST_CAST_PERMISSIONS);
    }

    /**
     * 转发 {@code onActivityResult}。返回 true 表示这次结果是投屏录屏授权，已经被消费，
     * 壳不要再往 super 传。
     */
    public static boolean forwardActivityResult(ZeehoCastBridge bridge, int requestCode, int resultCode, Intent data) {
        return bridge != null && bridge.onProjectionResult(requestCode, resultCode, data);
    }

    /** Activity 销毁时调用：解绑事件监听（不会停掉正在跑的投屏服务）。 */
    public static void release(ZeehoCastBridge bridge) {
        if (bridge != null) {
            bridge.release();
        }
    }
}
