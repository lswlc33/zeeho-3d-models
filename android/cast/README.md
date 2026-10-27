# 极核投屏 · 原生发送端（Java 参考实现）

把手机屏幕投到极核车机的**发送端**参考实现，供 Android WebView 壳（`MainActivity` / `ApiActivity`）
集成，替换第三方 App（Motexa）。协议依据：[`docs/CAST.md`](../../docs/CAST.md)；
行为基准：[`sdk/vehicle-cast.js`](../../sdk/vehicle-cast.js)（页面的状态机与解析逻辑就以它为准）。

- 目标：`minSdk 26`，无第三方依赖（只用 Android SDK + `org.json`）。
- 语言级别：Java 8（没有 `var` / `record` / `switch` 表达式），所有 API 29/33/34 分支都写了显式判断。
- **已用 `android-30/android.jar` 全量编译通过**（`javac -source 8 -target 8`，含 `shell/ZeehoCastShell.java`，
  只剩 deprecated 提示）：所以壳的 `compileSdk` 只要 ≥ 30 就能编；`targetSdk 34` 时前台服务类型必须写对。
  未做的是**打包进 APK 与真机验证**（本仓库没有壳工程与签名 keystore）。

## 1. 文件清单

| 文件 | 职责 |
| --- | --- |
| `CastErrors.java` | 错误码词表 + 中文文案（与 JS `CAST_ERROR_TEXT` 一致） |
| `CastProfile.java` | 二维码解析（规则同 `d0.g`）、打码、JSON |
| `CastWire.java` | 协议常量 + 控制帧/媒体帧/数据帧编解码 + 采集配置解析 |
| `CastControlChannel.java` | 10922：客户端信息（RSA-1024）、SN 校验、start-signal |
| `CastMediaChannel.java` | 10921：命令 16/48/64/96/112 的应答（17/49/65/97/113） |
| `CastFramePuller.java` | 10920：命令 114 → 回 `[len][可选 index][Annex-B]`，空帧 4 字节 0 |
| `CastP2pLink.java` | Wi-Fi Direct 组网、三个监听端口、GO 来源校验、10930 探测 |
| `CastEncoder.java` | MediaProjection → VirtualDisplay → `video/avc` Surface 编码 |
| `CastTiers.java` | 档位表、`planCapture`、level 查表、降级/学习判定 |
| `CastService.java` | 前台服务：串起 P2P → 三通道 → 编码器，状态机/事件/诊断 |
| `ZeehoCastBridge.java` | `window.ZeehoCast` 桥（`@JavascriptInterface`） |
| `shell/ZeehoCastShell.java` | 壳工程唯一入口：挂桥 / 权限 / 录屏结果 / 释放（4 个静态方法） |
| `shell/android-manifest-additions.xml` | 直接合并的权限与 `<service>` 片段 |
| `shell/README.md` | **合进壳工程的 3 步**（拷源码 → 贴 Manifest → 改 MainActivity 4 处） |

依赖方向（改代码前先看这张图，避免循环依赖）：
`Bridge → Service → {P2pLink, ControlChannel, MediaChannel, FramePuller, Encoder} → Wire/Tiers/Profile/Errors`。

## 2. 集成步骤

> 只想尽快合入壳工程？直接看 **[`shell/README.md`](shell/README.md)**（3 步、MainActivity 只改 4 处）。
> 下面 §2.2~§2.5 是每个接入点的原因和契约，排错时再看。

### 2.1 拷贝与包名

1. 把本目录 11 个 `.java` 连同 `shell/ZeehoCastShell.java` 拷进壳工程，包名保持
   `io.github.lswlc33.zeehowebnext.cast`（与 `ApiActivity` 同前缀）；
   若壳的包名不同，改**所有文件的 `package` 行**即可（类之间没有跨包引用）。

### 2.2 `AndroidManifest.xml`

```xml
<!-- 组网 -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
<!-- Android 12 及以下读 P2P 扫描结果 -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="32" />
<!-- Android 13+ 的附近设备权限（别加 usesPermissionFlags="neverForLocation"，
     P2P 扫描在某些 ROM 上会因此被判成无权限） -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
<!-- 前台服务 + 屏幕采集（Android 14 必需） -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<uses-feature android:name="android.hardware.wifi.direct" android:required="false" />

<application ...>
    <service
        android:name="io.github.lswlc33.zeehowebnext.cast.CastService"
        android:exported="false"
        android:foregroundServiceType="mediaProjection"
        android:stopWithTask="false" />
</application>
```

`targetSdk 34` 时 `mediaProjection` 类型 + `FOREGROUND_SERVICE_MEDIA_PROJECTION` 缺一不可，
否则 `startForeground()` 抛异常（代码里已经兜住，但会直接失败）。

### 2.3 运行时权限

```java
// 组网前请求：Android 13+ 用 NEARBY_WIFI_DEVICES，12 及以下用 ACCESS_FINE_LOCATION
String[] permissions = Build.VERSION.SDK_INT >= 33
        ? new String[]{"android.permission.NEARBY_WIFI_DEVICES"}
        : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
// Android 13+ 还要 POST_NOTIFICATIONS（前台服务通知）
```

`CastP2pLink.checkEnvironment()` 会再查一次；缺权限时桥返回 `wifi_permission_denied`。

### 2.4 注册桥 + 转发录屏授权结果

```java
public class ApiActivity extends Activity {
    private ZeehoCastBridge zeehoCast;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // ... 建好 WebView ...
        zeehoCast = ZeehoCastBridge.attach(this, webView);   // 内部已 addJavascriptInterface(bridge, "ZeehoCast")
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (zeehoCast != null && zeehoCast.onProjectionResult(requestCode, resultCode, data)) {
            return;   // 是投屏授权，已消费
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override protected void onDestroy() {
        if (zeehoCast != null) { zeehoCast.release(); zeehoCast = null; }
        super.onDestroy();
    }
}
```

要点：

- `ZeehoCastBridge` 实例必须用字段强引用住；
- 页面通过 `ZeehoCast.requestProjection()` 拉起系统弹窗，结果**只**从 `onActivityResult` 回来，
  壳必须转发（否则服务永远没有 `MediaProjection`，`start()` 会回 `projection_permission_required`）；
- 服务已启动时重复 `attach` 会替换事件监听（单 WebView 场景无影响）。

### 2.5 页面侧契约

页面用 `window.ZeehoCast`（`sdk/vehicle-cast.js` 的 `createCastClient` 已按这套写好）：

| 方法 | 入参 | 返回（JSON 字符串） |
| --- | --- | --- |
| `available()` | — | `{"ok":true,"available":bool,"version":1,"apiLevel":34,"reason":""}` |
| `getStatus()` | — | `{"ok":true,"phase":"idle","reason":"","casting":false,"vehicleConnected":false,"projectionReady":false,"captureSource":"screen","frameFormat":"traditional","compatibilityTier":"performance","compatibilityRetryCount":0,"learnedCompatibilityTier":"performance","stats":{"sentFrames":0,"sentBytes":0,"keyframes":0,"emptyPulls":0,"droppedFrames":0,"fps":0},"encoder":{...},"p2p":{"groupOwnerAddress":""},"sessionActive":false,"elapsedMs":0}` |
| `getDiagnostics(limit)` | 数字或字符串 | `{"ok":true,"items":[{at,event,payload}],"count":n}`（凭证已打码） |
| `pair(profileJson)` | profile JSON 或二维码原文 | `{"ok":true,"profile":{打码}}` / `{"ok":false,"error":"qr_*","message":"..."}` |
| `start(optionsJson)` | 见下 | `{"ok":true,"phase":"provisioning"}` / `{"ok":false,"error":"..."}` |
| `stop()` | — | `{"ok":true,"phase":"stopping"}` |
| `requestProjection()` | — | `{"ok":true,"requested":true,"requestCode":23553}` |

`start(optionsJson)` 字段：

```json
{
  "captureSource": "screen",              // 只支持 screen；其它值 → capture_source_unsupported
  "tier": "performance",                  // 省略则用“这台车学到的档位”
  "frameFormat": "traditional",           // traditional | extended
  "width": 1280, "height": 720,           // 可选：**上限**覆盖（只会把负载往下压）
  "fps": 24, "bitrate": 1500000,          // 可选：上限
  "profile": { "version":1, "mode":"9", "modelId":"AE5I", "ssid":"...", "password":"...", "p2pMac":"AA:BB:CC:DD:EE:FF", "serial":"..." }
}
```

事件（`window.__zeehoCastEvent('<json>')`，`attach` 之后自动注册到服务）：

```json
{"type":"status","status":{ ... 同 getStatus() ... }}
{"type":"error","error":"vehicle_status_unavailable","message":"车机没有响应（检查车机是否停在投屏页）","detail":"..."}
```

页面侧只要实现 `window.__zeehoCastEvent = (json) => client.handleEvent(json)`（`installCastEventSink` 已做）。

### 2.6 推荐调用顺序

1. `pair('<二维码文本>')` —— 校验并记住最近一次车机；
2. `requestProjection()` → 用户在系统弹窗点“开始录制” → 壳 `onActivityResult` 转发；
3. `getStatus().projectionReady === true` 后再 `start({tier:'performance'})`；
4. 车机进投屏页并点“开始”，状态走到 `casting`；
5. `stop()`。

> 顺序是硬的：没有录屏授权时 `start()` 直接返回 `projection_permission_required`（与 Motexa 的
> waiting-projection 分支一致，不会白起组网）。

## 3. 线程模型

| 线程 | 干什么 | 约束 |
| --- | --- | --- |
| 主线程 | 状态机、通知、事件回调（`mainHandler`） | 只有这里改 `phase`、发事件 |
| `CastService.workers`（cached pool） | 每个被接受的 socket 一个阻塞任务（三路通道） | 只读会话/统计，不碰 UI |
| `zeeho-cast-encoder` | `MediaCodec` 出队 + 组帧 | 只写帧队列（`frameLock`） |
| `zeeho-cast-probe` | 10930 探测 | 一次性 |
| 系统回调（P2P 广播 / `MediaProjection.Callback`） | 转成事件 | 内部都 post 回主线程 |

断开/停止时关 socket 是**幂等**的：三个通道 + `CastP2pLink` 各自持 `AtomicBoolean closed`，
重复 `close()` 只关一次、只回调一次；`CastEncoder.stop()` 也有 `running` 守卫，可从任意线程调用。

## 4. 真机验证清单

按顺序做，每步都看 `getDiagnostics`：

1. **装机**：`available()` 返回 `available:true`；`adb shell pm list features | grep wifi.direct` 有输出。
2. **权限**：首次 `pair` 后 `start` 前，系统弹出“附近设备/位置”授权；拒绝后再 `start` 应得
   `wifi_permission_denied`（不是崩溃）。
3. **热点阻断**：打开手机热点再 `start` → `hotspot_active`。
4. **扫码**：`pair` 合法码返回打码 profile；把 `pwd` 改短 → `network_credential_invalid`；
   `mac` 写错 → `device_address_invalid`；`action=8` → `connection_mode_unsupported`。
5. **组网**：车机进投屏页 → `start` → 阶段 `provisioning → handshaking`。
   若 30 s 内没扫到车机 → `p2p_timeout`；扫到但 connect 失败 → `p2p_connect_failed`。
6. **探测**：日志里 `status-probe` 的 `host` 应是 `192.168.0.x`；失败 → `vehicle_status_unavailable`
   （最常见原因：车机不在投屏页，或手机把 192.168.0.1 路由到了别的网络）。
7. **三端口**：`adb shell ss -lntp | grep -E '1092[0-2]'` 能看到三个监听；被占 → `listener_port_unavailable`。
8. **非 GO 来源**：用同一组里的另一台设备 `nc <手机IP> 10922`，应被立刻关闭（日志 `socket-rejected`）。
9. **握手**：车机连上后 `client-info-answered{ok:true}`、`serial-checked`；HUID 为空会直接断链。
10. **开始投屏**：车机点“开始” → 命令 112 → 回 113 → 阶段 `casting`，车机出画面。
    黑屏时依次查：`encoder-format`（实际宽高/编码器）、`encoder.parameterSetsReady`、
    `stats.sentFrames` 是否在涨、`stats.emptyPulls` 是否一直涨（涨说明编码器没出帧）。
11. **10920 超时切换**：推流前后抓 `adb shell cat /proc/<pid>/fdinfo` 不直观，改为看行为：
    车机卡 3~5 s 后恢复，画面能追上（说明推流后 2 s 超时生效，没把线程挂死）。
12. **只服务最新连接**：车机退出投屏页再进（会新建 10920 连接），旧连接应被关闭且**不**结束会话
    （日志 `data-closed{reason:"replaced"}`）。
13. **extended**：`frameFormat:"extended"` 且车机支持时，`media-capture-configured` 里
    `extendedAck:true`；数据包长度 = 帧长 + 4。
14. **降档**：故意让车机解码崩（例如持续拉高分辨率）→ 15 s 内断开且发过帧 → 阶段 `adapting`
    → 1 s 后回到 `casting`，`compatibilityTier` 变成下一档，`compatibilityRetryCount` +1。
    跑满 15 s / 150 帧后再断**不应**降档（那是学习条件），而是 `vehicle_disconnected`。
15. **档位学习**：稳定投屏 1 分钟后 `compatibility-learned` 出现；`getStatus().learnedCompatibilityTier`
    与本地偏好一致；下次 `start` 不带 `tier` 时应直接用它。
16. **停止**：`stop()` → `stopping → idle`，通知消失，三端口释放；连续 start/stop 5 次端口不残留。
17. **授权收回**：投屏中在系统通知里停止录屏 → `projection_stopped`，会话结束、通知消失。
18. **组网中断**：关手机 Wi-Fi → `wifi_direct_disabled`；关车机 → `vehicle_disconnected`。
19. **锁屏/切后台**：锁屏后车机画面黑属正常（系统遮罩）；解锁应恢复（不需要重启会话）。
20. **敏感信息**：`getDiagnostics()` 里不应出现完整 SSID/口令/SN/HUID（只露末尾几位）。

## 5. 与 `docs/CAST.md` 的对应与差异

实现严格按修订后的 CAST.md 与 `sdk/vehicle-cast.js`。以下几处是**读代码时补出来、文档原先没写**的细节，
代码里都留了注释，也同步提给了文档维护者：

1. **数据帧 `length` 含 frameIndex**：extended 带帧时 `length = 帧长 + 4`（`r1.R0`）。
   空帧永远 4 字节 0，不带 index；`frameIndex` 每次重连/降档归零。
2. **10920 的请求是媒体帧**（8 字节头、`command=114`），只有**应答**是裸包。
3. **命令 17 的首 int32 = 编码器号**（2 = H.264），level 只出现在 `MediaFormat`。
4. **`profile=1 + level` 第一次 configure 就带**，与档位无关；被 codec 拒绝时 `reset()` 后按
   codec-default 重配一次（本实现实现了两段式 configure）。
5. **高性能编码键**（CBR/priority/operating-rate/latency/max-fps/prepend-sps-pps）只在
   **发送端本机** `MODEL/DEVICE/PRODUCT/BOARD/HARDWARE` 命中 `s905l3a` 时才加（`v.d()`）。
6. **每帧补 AUD**：缺 NAL 9 时前置 `00 00 00 01 09 10`；只有“有 IDR 但缺 SPS/PPS”才拼
   `csd-0 + csd-1`；队列丢帧后主动 `request-sync`（节流 300 ms）。
7. **降级 ≠ 学习**：降级 = 15 s 内断且 `sentFrames > 0`（最多 5 次）；学习 = 跑满 15 s 且 ≥150 帧，
   只写本地偏好（按 P2P MAC 存），不改当前会话。
8. **10920 只服务最新连接**；旧连接被关闭，且**不**触发会话结束（`replaced`）。
9. **命令 16 缺失时的兜底**：`1024×600 / fps 0 / encoder 2 / 2 MiB / extended false`。
10. **SO_TIMEOUT**：10922 = 0；10921 = 15000；10920 推流前 15000、收到 113 后 2000。
11. **UUID / RSA 密钥对是通道实例级**（降档重试会重建应答器，等于换新密钥）。
12. **命令 16 的 `encoder` 透传**：`0 → 2`，其它值原样进命令 17，不校验。
13. **phase 词表映射**：`stopped → idle`、`connecting / hotspot-monitor → provisioning`、
    `waiting-projection → provisioning`（本实现直接失败为 `projection_permission_required`）。

## 6. 未验证项

来自 CAST.md §12（仍需真机确认）：
真实车机是否接受非 Motexa 的 `bluetoothName`/`pxcVersion`；命令 17 的 codec 字段是否被校验；
`start-signal(0x00030020)` 与 `112` 的先后；`frameIndex` 是否严格要求从 0；不同固件的采集默认值；
AE5/AE5i 之外车型是否同协议。

本实现额外引入、同样要真机确认的：

- **level id 非标准**：Motexa 把 512/256 这类“档位号”直接塞 `MediaFormat("level")`，
  本实现照抄；若某机型 codec 拒绝，会走 codec-default 回退（日志 `configure with profile/level rejected`）。
- **AUD 补齐**对车机是否有害（Motexa 会插，我们跟着插；理论上多余 AUD 无害）。
- **热点检测**（反射 `getWifiApState`）在部分 ROM 上可能永远返回“未开”。
- **10921 空闲 60 s 判死**：车机心跳间隔未知，60 s 是保守猜测。
- **学习档位的持久化键**按 P2P MAC 存，同 MAC 换车会继承档位（实际不会出现，但没做失效策略）。
- **明文口令落盘**：`pair()` 把最近一次 profile（含 Wi-Fi 口令）写进应用私有 `SharedPreferences`
  以便“重连上次车机”；UI 需要提供“忘记”入口（`forgetProfile()`）。诊断/日志一律打码。
- **连接诊断分支（`testConnection`）未实现**：Motexa 有“只验链路不投屏”的分支
  （112 只回 113、首个 114 写 4 字节 0、35 s 无 114 报 `frame_pull_timeout`），本实现只覆盖正式投屏。
- **theme 渲染器未实现**：`captureSource` 只支持 `screen`（Motexa 的 theme 是它自有渲染管线）。

## 7. 排错速查

| 现象 / 错误码 | 先查什么 |
| --- | --- |
| `wifi_direct_unsupported` | `hasSystemFeature(FEATURE_WIFI_DIRECT)`；模拟器一定没有 |
| `wifi_disabled` / `wifi_direct_disabled` | Wi-Fi 开关；部分机型开热点会顶掉 P2P |
| `hotspot_active` | 关热点；或反射检测误判（见 §6） |
| `p2p_timeout` | 车机是否在投屏页（二维码页）；扫描结果里有没有那个 MAC |
| `vehicle_status_unavailable` | 10930 探测：GO 地址是否 192.168.0.x；socket 是否绑到 P2P 网络 |
| `listener_port_unavailable` | `ss -lntp` 看 10920~10922 是否被别的进程占 |
| `projection_permission_required` | 是否先 `requestProjection()` 且壳转发 `onActivityResult` |
| `encoder_unavailable` | 分辨率/level 是否被 codec 拒；看 `configure with profile/level rejected` 日志 |
| 一直 `emptyPulls` 涨、`sentFrames` 不涨 | 编码器没出帧：VirtualDisplay 是否起来、Surface 是否绑对 |
| `media_incompatible_after_fallback` | 已降到最后一档仍失败：车机解码能力到顶，考虑降分辨率/fps |
| `session_active` | 上一次会话没停：先 `stop()`，或进程重启 |

## 8. 不做的事

- 不做反向触控/HID（协议里 `supportScreenTouch/supportHID` 恒 false，CAST.md §10）；
- 不做加密/鉴权增强（协议本身没有，RSA 只用于兼容握手）；
- 不在本目录引入 Gradle 工程或第三方库——壳按现有工程编译。
