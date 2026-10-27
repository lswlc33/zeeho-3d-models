package io.github.lswlc33.zeehowebnext.cast;

import java.util.HashMap;
import java.util.Map;

/**
 * 投屏协议的**错误码词表**。
 *
 * <p>为什么单独一个类：错误码是 JS 页面（{@code sdk/vehicle-cast.js} 的
 * {@code CAST_ERROR_TEXT}）与原生桥之间唯一的约定，散落在各个类里必然漂移。
 * 码值与文案逐条对应 {@code sdk/vehicle-cast.js}；本类多出来的几条是本仓库原生实现
 * 自身需要的（页面对未知码会原样显示，不会崩）。
 *
 * <p>协议来源：{@code docs/CAST.md} §2（qr_*）、§3（wifi_*）、§4（vehicle_status_*）、
 * §9（状态机 reason）；反编译侧 {@code d0.c(Exception)} 有同样的 {code} 映射表。
 */
public final class CastErrors {

    private CastErrors() {
    }

    /* ---------------- 二维码（docs/CAST.md §2，d0.g / d0.c） ---------------- */

    /** 文本为空或超过 4096 字符。 */
    public static final String QR_INPUT_INVALID = "qr_input_invalid";
    /** v/version/ver 不是数字。 */
    public static final String QR_VERSION_INVALID = "qr_version_invalid";
    /** 版本存在但不是 1。 */
    public static final String QR_VERSION_UNSUPPORTED = "qr_version_unsupported";
    /** action/mode 不等于 9。 */
    public static final String CONNECTION_MODE_UNSUPPORTED = "connection_mode_unsupported";
    /** modelid/ssid/pwd/mac 有缺失。 */
    public static final String PAIRING_FIELD_MISSING = "pairing_field_missing";
    /** 车型不匹配 ^[A-Za-z0-9_.-]{1,64}$。 */
    public static final String MODEL_ID_INVALID = "model_id_invalid";
    /** SSID 超过 32 字符或含 NUL。 */
    public static final String NETWORK_NAME_INVALID = "network_name_invalid";
    /** 口令长度不在 8..63。 */
    public static final String NETWORK_CREDENTIAL_INVALID = "network_credential_invalid";
    /** 缺少 mac 字段（d0 有该码，但 d0.g 里会先被判成 pairing_field_missing；保留以对齐词表）。 */
    public static final String DEVICE_ADDRESS_MISSING = "device_address_missing";
    /** mac 不是 xx:xx:xx:xx:xx:xx。 */
    public static final String DEVICE_ADDRESS_INVALID = "device_address_invalid";
    /** SN 超过 128 字符。 */
    public static final String SERIAL_INVALID = "serial_invalid";
    /** 兜底：无法识别的二维码。 */
    public static final String QR_INVALID = "qr_invalid";

    /* ---------------- 桥本身 ---------------- */

    /** 没有原生桥（浏览器里跑页面）。 */
    public static final String NATIVE_BRIDGE_MISSING = "native_bridge_missing";
    /** 原生桥回了非 JSON。 */
    public static final String BRIDGE_BAD_JSON = "bridge_bad_json";
    /** 桥缺少方法（旧版本）。 */
    public static final String BRIDGE_METHOD_MISSING = "bridge_method_missing";
    /** 桥无响应。 */
    public static final String BRIDGE_TIMEOUT = "bridge_timeout";

    /* ---------------- 组网（docs/CAST.md §3） ---------------- */

    /** 缺少 NEARBY_WIFI_DEVICES / ACCESS_FINE_LOCATION。 */
    public static final String WIFI_PERMISSION_DENIED = "wifi_permission_denied";
    /** Wi-Fi 没开。 */
    public static final String WIFI_DISABLED = "wifi_disabled";
    /** 机型没有 android.hardware.wifi.direct。 */
    public static final String WIFI_DIRECT_UNSUPPORTED = "wifi_direct_unsupported";
    /** 手机同时开了热点。 */
    public static final String HOTSPOT_ACTIVE = "hotspot_active";
    /** connect() 失败。 */
    public static final String P2P_CONNECT_FAILED = "p2p_connect_failed";
    /** 组网/配对超时。 */
    public static final String P2P_TIMEOUT = "p2p_timeout";
    /** 扫描结果里没有二维码里的 MAC。 */
    public static final String VEHICLE_NOT_FOUND = "vehicle_not_found";
    /** Wi-Fi Direct 被系统关掉。 */
    public static final String WIFI_DIRECT_DISABLED = "wifi_direct_disabled";

    /* ---------------- 屏幕采集 ---------------- */

    /** 没授权屏幕录制。 */
    public static final String PROJECTION_PERMISSION_REQUIRED = "projection_permission_required";
    /** getMediaProjection 返回 null。 */
    public static final String PROJECTION_UNAVAILABLE = "projection_unavailable";
    /** 用户/系统中途收回授权。 */
    public static final String PROJECTION_STOPPED = "projection_stopped";

    /* ---------------- 车机侧 ---------------- */

    /** 10930 探测连不上或读超时。 */
    public static final String VEHICLE_STATUS_UNAVAILABLE = "vehicle_status_unavailable";
    /** 10930 回了，但 status 不是 true。 */
    public static final String VEHICLE_STATUS_REJECTED = "vehicle_status_rejected";
    /** 媒体/数据通道断开。 */
    public static final String VEHICLE_DISCONNECTED = "vehicle_disconnected";
    /** 10920/10921/10922 有一个起不来。 */
    public static final String LISTENER_PORT_UNAVAILABLE = "listener_port_unavailable";

    /* ---------------- 编码 ---------------- */

    /** 没有可用 H.264 编码器，或 configure/start 抛异常。 */
    public static final String ENCODER_UNAVAILABLE = "encoder_unavailable";
    /** 降到最后一档仍失败。 */
    public static final String MEDIA_INCOMPATIBLE_AFTER_FALLBACK = "media_incompatible_after_fallback";

    /* ---------------- 参数 ---------------- */

    /** frameFormat 不是 traditional/extended。 */
    public static final String UNSUPPORTED_FRAME_FORMAT = "unsupported_frame_format";
    /** captureSource 目前只支持 screen（theme 渲染器不在本实现范围内）。 */
    public static final String CAPTURE_SOURCE_UNSUPPORTED = "capture_source_unsupported";
    /** 命令 16 的采集配置字节数不足或宽高非法。 */
    public static final String MEDIA_CONFIG_INVALID = "media_config_invalid";
    /** 已有会话。 */
    public static final String SESSION_ACTIVE = "session_active";
    /** 系统抛 SecurityException（Wi-Fi / 悬浮窗 / 前台服务）。 */
    public static final String SECURITY_ERROR = "security_error";
    /** 未分类内部错误（不吞，原样上报，便于真机定位）。 */
    public static final String INTERNAL_ERROR = "internal_error";

    /** 文案表：与 {@code sdk/vehicle-cast.js} 的 CAST_ERROR_TEXT 逐条一致。 */
    private static final Map<String, String> TEXT = new HashMap<String, String>();

    static {
        TEXT.put(QR_INPUT_INVALID, "二维码内容为空或过长");
        TEXT.put(QR_VERSION_INVALID, "二维码版本号不是数字");
        TEXT.put(QR_VERSION_UNSUPPORTED, "二维码版本不受支持（只支持 v=1）");
        TEXT.put(CONNECTION_MODE_UNSUPPORTED, "二维码连接模式不受支持（需要 action=9）");
        TEXT.put(PAIRING_FIELD_MISSING, "二维码缺少必填字段");
        TEXT.put(MODEL_ID_INVALID, "二维码里的车型不合法");
        TEXT.put(NETWORK_NAME_INVALID, "二维码里的 Wi-Fi 名称不合法");
        TEXT.put(NETWORK_CREDENTIAL_INVALID, "二维码里的 Wi-Fi 密码不合法（8~63 位）");
        TEXT.put(DEVICE_ADDRESS_MISSING, "二维码缺少车机 P2P 地址");
        TEXT.put(DEVICE_ADDRESS_INVALID, "车机 P2P 地址格式不对");
        TEXT.put(SERIAL_INVALID, "二维码里的 SN 过长");
        TEXT.put(QR_INVALID, "无法识别的投屏二维码");
        TEXT.put(NATIVE_BRIDGE_MISSING, "当前环境没有投屏原生桥，需在极核面板 App 内使用");
        TEXT.put(BRIDGE_METHOD_MISSING, "原生桥版本过旧，缺少所需方法");
        TEXT.put(BRIDGE_BAD_JSON, "原生桥返回的数据无法解析");
        TEXT.put(BRIDGE_TIMEOUT, "原生桥无响应");
        TEXT.put(WIFI_PERMISSION_DENIED, "缺少 Wi-Fi / 附近设备权限");
        TEXT.put(WIFI_DISABLED, "Wi-Fi 未开启");
        TEXT.put(WIFI_DIRECT_UNSUPPORTED, "本机不支持 Wi-Fi Direct");
        TEXT.put(HOTSPOT_ACTIVE, "手机热点已开启，请先关闭");
        TEXT.put(PROJECTION_PERMISSION_REQUIRED, "需要先授权屏幕录制");
        TEXT.put(PROJECTION_UNAVAILABLE, "无法获取屏幕录制会话");
        TEXT.put(PROJECTION_STOPPED, "屏幕录制授权已被收回");
        TEXT.put(VEHICLE_STATUS_UNAVAILABLE, "车机没有响应（检查车机是否停在投屏页）");
        TEXT.put(VEHICLE_STATUS_REJECTED, "车机拒绝了握手");
        TEXT.put(VEHICLE_DISCONNECTED, "车机断开连接");
        TEXT.put(LISTENER_PORT_UNAVAILABLE, "端口 10920~10922 被占用");
        TEXT.put(ENCODER_UNAVAILABLE, "本机没有可用的 H.264 编码器");
        TEXT.put(MEDIA_INCOMPATIBLE_AFTER_FALLBACK, "车机解码不兼容，已降档仍失败");
        TEXT.put(UNSUPPORTED_FRAME_FORMAT, "数据帧格式不受支持");
        TEXT.put(CAPTURE_SOURCE_UNSUPPORTED, "只支持采集整个屏幕");
        TEXT.put(MEDIA_CONFIG_INVALID, "车机下发的采集配置不合法");
        TEXT.put(P2P_CONNECT_FAILED, "Wi-Fi Direct 连接车机失败");
        TEXT.put(P2P_TIMEOUT, "Wi-Fi Direct 组网超时");
        TEXT.put(VEHICLE_NOT_FOUND, "附近没有找到车机的 Wi-Fi Direct 设备");
        TEXT.put(WIFI_DIRECT_DISABLED, "Wi-Fi Direct 已被系统关闭");
        TEXT.put(SESSION_ACTIVE, "已有投屏会话在进行");
        TEXT.put(SECURITY_ERROR, "系统拒绝该操作");
        TEXT.put(INTERNAL_ERROR, "投屏内部错误");
    }

    /** 错误码 → 中文；未知码原样返回（页面自己决定怎么显示）。 */
    public static String text(String code) {
        if (code == null || code.length() == 0) {
            return "";
        }
        String value = TEXT.get(code);
        return value == null ? code : value;
    }
}
