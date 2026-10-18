package io.github.lswlc33.zeehowebnext;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;

import java.util.Locale;

/**
 * 壳的导航策略：页面只允许停在面板根路径。
 *
 * 外部网页一律拦下（用户要求“跳到别的网页要跳回来”）；高德那类 intent:// / 地图 scheme
 * 是用户主动点的导航，交给系统 App 处理，失败再用 intent 自带的 browser_fallback_url 开浏览器。
 */
final class UrlPolicy {

    private UrlPolicy() {
    }

    /** 是否是“面板自己的页面”：同 scheme/host/port，且路径是根路径、index.html 或 vehicle-info.html。 */
    static boolean isAllowedPage(String url, String baseUrl) {
        if (url == null) return false;
        try {
            Uri u = Uri.parse(url);
            Uri b = Uri.parse(baseUrl);
            if (!eq(u.getScheme(), b.getScheme())) return false;
            if (!eq(u.getHost(), b.getHost())) return false;
            if (port(u) != port(b)) return false;
            String bp = b.getPath() == null ? "/" : b.getPath();
            if (!bp.endsWith("/")) bp += "/";
            String p = u.getPath() == null ? "/" : u.getPath();
            return p.equals(bp)
                || p.equals(bp + "index.html")
                // 投屏资料页：车辆设置页的「投屏」入口会带 ?auto=1&vin=... 进来。
                // 它是壳自己的功能页，不能像外链一样被拦掉。
                || p.equals(bp + "vehicle-info.html")
                || p.equals(bp.substring(0, bp.length() - 1));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * shouldOverrideUrlLoading 的返回语义：true = 已消费，WebView 不要导航。
     */
    static boolean handleNavigation(Activity activity, WebView webView, String url, String baseUrl) {
        if (url == null) return true;
        if (url.startsWith("about:") || url.startsWith("data:")) return false;
        if (isAllowedPage(url, baseUrl)) return false;

        String scheme;
        try {
            scheme = Uri.parse(url).getScheme();
        } catch (Exception e) {
            return true;
        }
        if (scheme == null) return true;
        scheme = scheme.toLowerCase(Locale.US);
        switch (scheme) {
            case "intent":
                launchIntentUri(activity, url);
                return true;
            case "androidamap":
            case "iosamap":
            case "geo":
            case "mailto":
            case "tel":
                startExternal(activity, Uri.parse(url));
                return true;
            default:
                // http(s) 外链、javascript: 以及其它未知 scheme：留在面板
                return true;
        }
    }

    private static void launchIntentUri(Activity activity, String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            // 只放行"可浏览"的外部 intent：剥掉 component/selector，防止页面里被注入的
            // intent://#Intent;component=...;end 直接拉起任意导出组件（含带任意 data 的 VIEW）。
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            try {
                activity.startActivity(intent);
                return;
            } catch (ActivityNotFoundException e) {
                // 高德没装：intent 里带了 S.browser_fallback_url，用系统浏览器打开网页版
                String fallback = intent.getStringExtra("browser_fallback_url");
                if (fallback != null && !fallback.isEmpty()) {
                    startExternal(activity, Uri.parse(fallback));
                }
            }
        } catch (Exception ignored) {
            // 坏 intent 直接吞掉，页面保持不动
        }
    }

    private static void startExternal(Activity activity, Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception ignored) {
            // 没有对应 App：保持页面不动
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static int port(Uri u) {
        if (u.getPort() >= 0) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }
}
