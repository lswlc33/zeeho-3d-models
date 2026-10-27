package io.github.lswlc33.zeehowebnext.cast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JavaScript 桥：{@code window.ZeehoCast}。
 *
 * <p>契约（页面侧 {@code sdk/vehicle-cast.js} 的 {@code createCastClient} 直接按这套方法名调用）：
 * <pre>
 *   available()            → {"ok":true,"available":bool,"version":1,"reason":""}
 *   getStatus()            → {"ok":true,"phase":"...","reason":"",...}
 *   getDiagnostics(limit)  → {"ok":true,"items":[...],"count":n}   （limit 传字符串也接受）
 *   pair(profileJson)      → {"ok":true,"profile":{打码}} | {"ok":false,"error":"qr_*"}
 *   start(optionsJson)     → {"ok":true,"phase":"provisioning"} | {"ok":false,"error":"..."}
 *   stop()                 → {"ok":true,"phase":"stopping"}
 *   requestProjection()    → {"ok":true,"requested":true}
 * </pre>
 *
 * <p>事件：{@code window.__zeehoCastEvent('<json>')}，payload 形如
 * {@code {"type":"status","status":{...}}} / {@code {"type":"error","error":"..."}}。
 * JSON 字符串用 {@link JSONObject#quote(String)} 转义后再注入 JS，避免手写转义漏字符。
 *
 * <p>所有方法**只返回 JSON 字符串、绝不抛异常**：WebView 里一个未捕获的 Java 异常会直接
 * 变成页面上的 {@code Error: Java bridge method threw}，页面的状态机会被卡死。
 */
public final class ZeehoCastBridge {

    private static final String TAG = "ZeehoCastBridge";

    /** 注入到 WebView 的全局对象名。 */
    public static final String JS_NAME = "ZeehoCast";
    /** 页面注册的事件入口（与 sdk/vehicle-cast.js 的 CAST_EVENT_SINK 一致）。 */
    public static final String EVENT_SINK = "__zeehoCastEvent";
    /** 桥版本：方法集合变化时递增，页面可用它判断兼容性。 */
    public static final int VERSION = 1;
    /** 屏幕录制授权的 requestCode（壳在 onActivityResult 里转给 {@link #onProjectionResult}）。 */
    public static final int REQUEST_PROJECTION = 0x5C01;

    /** 上次配对的车机（含口令，一次性凭证，仅应用私有存储）。 */
    public static final String PREFS_NAME = "zeeho_cast";
    public static final String KEY_LAST_PROFILE = "last_profile";
    /** getDiagnostics 的默认条数（与 JS 侧默认 50 对齐）。 */
    public static final int DEFAULT_DIAGNOSTIC_LIMIT = 50;

    private final Activity activity;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CastService.EventListener sink;

    /**
     * @param activity 用于 {@code startActivityForResult} 申请屏幕录制；可为 null（只读场景）
     * @param webView  事件回调用；可为 null（不推事件）
     */
    public ZeehoCastBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.sink = new CastService.EventListener() {
            @Override
            public void onCastEvent(JSONObject event) {
                emit(event == null ? null : event.toString());
            }
        };
        CastService.setEventListener(sink);
    }

    /**
     * 一步完成注入：{@code ZeehoCastBridge.attach(this, webView)}。
     *
     * <p>返回值请保存在 Activity 字段里，否则可能被 GC（WebView 只持有弱引用之外的
     * JavaBridge 引用，保险起见留一个强引用）。
     */
    public static ZeehoCastBridge attach(Activity activity, WebView webView) {
        ZeehoCastBridge bridge = new ZeehoCastBridge(activity, webView);
        if (webView != null) {
            webView.addJavascriptInterface(bridge, JS_NAME);
        }
        return bridge;
    }

    /** 页面卸载时解除事件监听，避免持有已销毁的 WebView。 */
    public void release() {
        CastService.setEventListener(null);
    }

    /* ================= @JavascriptInterface 方法 ================= */

    /** 当前环境能不能投屏（机型没有 Wi-Fi Direct 就直接给 unavailable）。 */
    @JavascriptInterface
    public String available() {
        try {
            Context context = appContext();
            boolean wifiDirect = context != null && CastP2pLink.hasWifiDirect(context);
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("available", wifiDirect);
            out.put("version", VERSION);
            out.put("apiLevel", Build.VERSION.SDK_INT);
            out.put("reason", wifiDirect ? "" : CastErrors.WIFI_DIRECT_UNSUPPORTED);
            return out.toString();
        } catch (Throwable error) {
            return error(CastErrors.INTERNAL_ERROR, error);
        }
    }

    /** 会话状态快照（页面每次打开/定时刷新都会调）。 */
    @JavascriptInterface
    public String getStatus() {
        try {
            return CastService.statusSnapshot().toString();
        } catch (Throwable error) {
            return error(CastErrors.INTERNAL_ERROR, error);
        }
    }

    /** 最近诊断（含打码）；{@code limit} 允许字符串（JS 侧就是这么传的）。 */
    @JavascriptInterface
    public String getDiagnostics(String limit) {
        try {
            int max = parseLimit(limit);
            JSONArray items = CastService.diagnostics(max);
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("items", items);
            out.put("count", items.length());
            return out.toString();
        } catch (Throwable error) {
            return error(CastErrors.INTERNAL_ERROR, error);
        }
    }

    /**
     * 校验并记住二维码 profile。
     *
     * <p>入参两种都接受：页面解析后的 profile JSON，或者二维码原文（便于贴文本调试）。
     */
    @JavascriptInterface
    public String pair(String profileJson) {
        try {
            String raw = profileJson == null ? "" : profileJson.trim();
            if (raw.length() == 0) {
                return error(CastErrors.QR_INPUT_INVALID, null);
            }
            CastProfile parsed;
            if (raw.charAt(0) == '{') {
                // 页面传对象：profile JSON 已经校验过一遍，这里再做一次“原生侧自己的校验”
                try {
                    parsed = CastProfile.fromJson(new JSONObject(raw));
                } catch (Exception invalid) {
                    return error(CastErrors.QR_INVALID, invalid);
                }
            } else {
                CastProfile.Result result = CastProfile.parse(raw);
                if (!result.ok || result.profile == null) {
                    return error(result.firstError(), null);
                }
                parsed = result.profile;
            }
            persistProfile(parsed);
            CastService.record("paired", parsed.toMaskedJson());
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("profile", parsed.toMaskedJson());
            return out.toString();
        } catch (Throwable error) {
            return error(CastErrors.INTERNAL_ERROR, error);
        }
    }

    /**
     * 开始投屏。
     *
     * <p>顺序是硬的：先 {@code requestProjection()} 拿到授权，再 {@code start()}。
     * 和 Motexa 一样，没有屏幕录制授权就不起传输（CAST.md §9 的 waiting-projection）。
     */
    @JavascriptInterface
    public String start(String optionsJson) {
        try {
            Context context = appContext();
            if (context == null) {
                return error(CastErrors.NATIVE_BRIDGE_MISSING, null);
            }
            JSONObject options;
            try {
                options = optionsJson == null || optionsJson.trim().length() == 0
                        ? new JSONObject() : new JSONObject(optionsJson);
            } catch (Exception badJson) {
                return error(CastErrors.BRIDGE_BAD_JSON, badJson);
            }

            String captureSource = options.optString("captureSource", "screen");
            if (!"screen".equals(captureSource)) {
                return error(CastErrors.CAPTURE_SOURCE_UNSUPPORTED, null);
            }
            String frameFormat = options.optString("frameFormat", "traditional");
            if (!"traditional".equals(frameFormat) && !"extended".equals(frameFormat)) {
                return error(CastErrors.UNSUPPORTED_FRAME_FORMAT, null);
            }
            String tier = options.optString("tier", "");
            if (CastTiers.isKnownTier(tier)) {
                options.put("tier", tier);
            } else {
                // 不传 tier：让服务用“这台车学到的档位”兜底（CAST.md §8.1）
                options.remove("tier");
            }

            JSONObject profileJson = options.optJSONObject("profile");
            if (profileJson == null) {
                // 页面上“重连上次车机”可以直接不带 profile，这里回落到上次配对的
                profileJson = loadLastProfile();
            }
            if (profileJson == null) {
                return errorWithDetail(CastErrors.QR_INVALID, "no profile");
            }
            CastProfile profile;
            try {
                profile = CastProfile.fromJson(profileJson);
            } catch (Exception invalid) {
                return error(CastErrors.QR_INVALID, invalid);
            }

            if (CastService.isSessionActive()) {
                return error(CastErrors.SESSION_ACTIVE, null);
            }
            if (!CastService.isProjectionReady()) {
                return error(CastErrors.PROJECTION_PERMISSION_REQUIRED, null);
            }

            options.put("captureSource", captureSource);
            options.put("frameFormat", frameFormat);
            options.put("profile", profile.toJson());
            CastService.startSession(context, options.toString());

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("phase", CastService.PHASE_PROVISIONING);
            return out.toString();
        } catch (Throwable error) {
            return error(CastErrors.INTERNAL_ERROR, error);
        }
    }

    /** 停止会话（幂等；服务没在跑也返回 ok）。 */
    @JavascriptInterface
    public String stop() {
        try {
            Context context = appContext();
            if (context != null) {
                CastService.stopSession(context);
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("phase", CastService.PHASE_STOPPING);
            return out.toString();
        } catch (Throwable error) {
            return error(CastErrors.INTERNAL_ERROR, error);
        }
    }

    /** 触发系统“屏幕录制”授权弹窗；结果要壳在 onActivityResult 里转给本桥。 */
    @JavascriptInterface
    public String requestProjection() {
        try {
            if (activity == null) {
                return errorWithDetail(CastErrors.PROJECTION_PERMISSION_REQUIRED, "no activity");
            }
            MediaProjectionManager manager = (MediaProjectionManager)
                    activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                return errorWithDetail(CastErrors.PROJECTION_UNAVAILABLE, "no MediaProjectionManager");
            }
            Intent intent = manager.createScreenCaptureIntent();
            activity.startActivityForResult(intent, REQUEST_PROJECTION);
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("requested", true);
            out.put("requestCode", REQUEST_PROJECTION);
            return out.toString();
        } catch (Throwable error) {
            return error(CastErrors.PROJECTION_UNAVAILABLE, error);
        }
    }

    /* ================= 壳侧回调 ================= */

    /**
     * 屏幕录制授权结果（壳的 {@code onActivityResult} 里调用）。
     *
     * @return true 表示这个 requestCode 是投屏的，已消费
     */
    public boolean onProjectionResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PROJECTION) {
            return false;
        }
        Context context = appContext();
        if (context == null) {
            return true;
        }
        CastService.record("projection-result",
                json("resultCode", resultCode, "hasData", data != null));
        CastService.deliverProjectionResult(context, resultCode, data);
        return true;
    }

    /* ================= 内部 ================= */

    private Context appContext() {
        if (activity != null) {
            return activity.getApplicationContext();
        }
        if (webView != null) {
            return webView.getContext().getApplicationContext();
        }
        return null;
    }

    private SharedPreferences prefs() {
        Context context = appContext();
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 记住最近一次配对。
     *
     * <p>存的是**明文**（含 Wi-Fi 口令），因为重连必须用到；它落在应用私有目录
     * （{@code MODE_PRIVATE}），不会出现在诊断/日志里（诊断一律走打码）。
     * 页面“忘记车机”时应调用 {@link #forgetProfile()}。
     */
    private void persistProfile(CastProfile profile) {
        SharedPreferences preferences = prefs();
        if (preferences == null) {
            return;
        }
        try {
            preferences.edit().putString(KEY_LAST_PROFILE, profile.toJson().toString()).apply();
        } catch (RuntimeException error) {
            Log.w(TAG, "persist profile failed: " + error);
        }
    }

    private JSONObject loadLastProfile() {
        SharedPreferences preferences = prefs();
        if (preferences == null) {
            return null;
        }
        String raw = preferences.getString(KEY_LAST_PROFILE, null);
        if (raw == null || raw.length() == 0) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Exception error) {
            return null;
        }
    }

    /** 清掉记住的车机（页面“忘记”按钮用）。不是 @JavascriptInterface，按需在壳里调用。 */
    public void forgetProfile() {
        SharedPreferences preferences = prefs();
        if (preferences != null) {
            preferences.edit().remove(KEY_LAST_PROFILE).apply();
        }
    }

    private static int parseLimit(String limit) {
        if (limit == null || limit.length() == 0) {
            return DEFAULT_DIAGNOSTIC_LIMIT;
        }
        try {
            int value = Integer.parseInt(limit.trim());
            return value <= 0 ? DEFAULT_DIAGNOSTIC_LIMIT : value;
        } catch (NumberFormatException error) {
            return DEFAULT_DIAGNOSTIC_LIMIT;
        }
    }

    /** 把事件推给页面；WebView 已销毁时静默忽略。 */
    private void emit(final String json) {
        if (json == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                WebView view = webView;
                if (view == null) {
                    return;
                }
                String script = "window." + EVENT_SINK + " && window." + EVENT_SINK + "("
                        + JSONObject.quote(json) + ");";
                try {
                    view.evaluateJavascript(script, null);
                } catch (RuntimeException error) {
                    Log.w(TAG, "evaluateJavascript failed: " + error);
                }
            }
        });
    }

    /** 字符串细节目重载（壳/服务里大量“no activity”这类原因，不需要造异常）。 */
    private static String errorWithDetail(String code, String detail) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", code == null ? CastErrors.INTERNAL_ERROR : code);
            out.put("message", CastErrors.text(code));
            if (detail != null) {
                out.put("detail", detail);
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    private static String error(String code, Throwable detail) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", code == null ? CastErrors.INTERNAL_ERROR : code);
            out.put("message", CastErrors.text(code));
            if (detail != null) {
                out.put("detail", detail.getClass().getSimpleName() + ": " + detail.getMessage());
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    private static JSONObject json(String key, Object value, String key2, Object value2) {
        JSONObject out = new JSONObject();
        try {
            out.put(key, value);
            out.put(key2, value2);
        } catch (Exception ignored) {
        }
        return out;
    }
}
