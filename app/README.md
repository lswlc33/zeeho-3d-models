# 几何面板 Android 壳（zeehowebnext）

纯**系统 WebView** 壳：APK 内不打包任何网页静态资源，也不内置 WebView 引擎，只打开

```
https://lswlc33.github.io/zeeho-3d-models/
```

首次启动把线上单文件页面缓存到应用私有目录，之后从本地快照秒开；后台静默检查
`version.json`，下载完成后旧页面继续用到下次启动或用户空闲 5 秒。

固定下载地址：

```
https://lswlc33.github.io/zeeho-3d-models/app/zeehowebnext.apk
```

- 包名：`io.github.lswlc33.zeehowebnext`
- 显示名：**几何面板**（网页本身仍叫「极核面板」，两者是同一套页面）
- 版本：`gradle.properties` 里的 `webnext.versionCode` / `webnext.versionName`
- 签名：复用私有模块仓库的 `zeeho-unlocker.jks`；**密钥文件不提交到本仓库**

## 目录

```
app/
  settings.gradle.kts / build.gradle.kts / gradle.properties
  gradlew / gradlew.bat / gradle/wrapper/
  keystore.properties.example   # 复制成 keystore.properties（gitignore）
  local.properties              # sdk.dir，gitignore
  zeehowebnext.apk              # exportApk 产物，固定文件名
  webnext/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/io/github/lswlc33/zeehowebnext/
      MainActivity.java         # WebView 宿主、edge-to-edge、返回键、页面更新调度
      WebCache.java             # 快照下载/校验/激活/拦截
      ShellBridge.java          # @JavascriptInterface: insets()/setDark()/appVersion()/batteryHistory()
      UpdateChecker.java        # APK 自更新：清单检查、下载、包名/签名校验、安装
      UpdateProvider.java       # 只读更新 APK 的 content:// 出口
      BatteryHistory.java       # 电量历史：采样、Deflate+AES-GCM、31 天、统计
      WebViewCache.java         # WebView HTTP Cache 超 32MB 时在进程启动前清理
      UrlPolicy.java             # 只允许面板页面；外部网页拦截，地图 intent 放行
    src/main/res/...            # 主题、图标、原生文案
    src/debug/AndroidManifest.xml # 仅 debug 允许 cleartext（本机测试服）
```

## 构建

前置：JDK 21、Android SDK 37（`local.properties` 里 `sdk.dir=E:/Android/Sdk`）、
Gradle wrapper 9.6.1（已随仓库提供）。

```bash
export JAVA_HOME='E:\Java\jdk-21.0.12.1+1'
cd E:/AI_Program/zeeho-3d-models/app

# 日常调试
./gradlew :webnext:assembleDebug
# -> webnext/build/outputs/apk/debug/webnext-debug.apk

# 正式包（固定名）
./gradlew :webnext:exportApk
# -> app/zeehowebnext.apk
```

`exportApk` 会先检查 `keystore.properties` 四项齐全，缺任何一项直接失败——不会悄悄
产出未签名包。正式包构建后建议核对：

```bash
E:/Android/Sdk/build-tools/37.0.0/apksigner.bat verify --print-certs zeehowebnext.apk
E:/Android/Sdk/build-tools/37.0.0/aapt2.exe dump badging zeehowebnext.apk
```

证书 SHA-256 应与私有模块仓库的 `zeeho-unlocker.jks` 一致
（`98:EC:53:4D:...:EA`）。

## 签名

```bash
cp keystore.properties.example keystore.properties
# 填 storeFile / storePassword / keyAlias / keyPassword
# 本机可以直接指向私有模块仓库里的密钥，例如：
# storeFile=../../zeeho-unlocker/keystore/zeeho-unlocker.jks
```

`keystore.properties`、`*.jks`、`local.properties` 都在 `.gitignore` 里。**不要**把密钥
提交到这个公开仓库；已安装用户靠同一把密钥覆盖升级，换密钥会导致无法覆盖安装。

> **关于 `ApiClient.java` 里的 `APP_SECRET` / `H5_SECRET` / `CAR_AES_KEY`**：这三个常量是从
> 官方客户端逆向出的硬编码值，与私有模块仓库 `SignUtil.java` 同源，**刻意**留在这个仓库里
> （壳要自己发请求/云解锁就得带它们）。它们不是用户凭据，也不是本项目需要保护的秘密；
> 网页版混淆的意义是"不让陌生人复制粘贴就能用"，不是保护这三个值。

## 启动与更新机制

```
首次启动（无快照）
  原生遮罩 + 进度条
  -> GET version.json?_=ts 拿 build
  -> 下载 index.html?build=...（gzip；校验标题 + <meta name="build"> 与 version.json 一致）
  -> 写 meta.json，临时目录 rename 成正式快照
  -> 立即 loadUrl(BASE_URL)，onPageCommitVisible 时淡出遮罩
  -> manifest + 4 个图标**不挡首屏**，在同一个 worker 里接着补（写 .part 再改名）

后续启动
  1. 若有上次下载好的 pending，先提为 active
  2. 立即从本地快照加载
  3. 首帧后 3 秒检查 version.json（resume 时再按 30 秒防抖检查）
  4. 有新 build -> 静默下载到 .tmp-<build> -> 校验 -> 标记 pending
  5. 旧页面继续用；直到：
     a) 下次冷启动：启动时提为 active
     b) 当前会话空闲 5 秒：且没有 .sheet.show / INPUT / TEXTAREA 聚焦
        -> 提为 active + reload + Toast「已更新到新版本」
```

快照目录：

```
files/webcache/snapshots/<build-id>/
  index.html
  manifest.webmanifest
  version.json
  icons/*.png
  meta.json          # 原始 build / createdAt，最后写
  vehicle-info.html  # 投屏资料页（可选资源，失败不影响主页面）
files/webcache/.tmp-<build-id>/
```

只缓存页面壳，**不缓存**接口数据、车辆图片、地图瓦片；字体是外部非阻塞资源，离线时
回退系统字体。页面 HTML 用 **gzip** 传输（约 639KB → 约 200KB），首次安装必须联网下载
约 0.2 MB，之后才是本地秒开。快照目录只保留 **active + pending**，旧版本在切换完成后删除
（2026-10-18 起不再保留一份旧快照回退，省约 0.7MB；代价是 active 损坏时只能重新下载）。

### APK 体积（1.4.1）

AGP 9 的内置 Kotlin 支持会给纯 Java 工程自动挂 `kotlin-stdlib`，旧版 APK 里 90% 的 dex
都是它（755KB APK / 2.2MB dex）。`webnext/build.gradle.kts` 只对 `RuntimeClasspath`
排除 `org.jetbrains.kotlin:kotlin-stdlib`，不动 lint 自己的 Kotlin 依赖：

```kotlin
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
```

1.4.1 实测 APK **755KB → 123KB**，dex **2.23MB → 155KB**，lint 通过。以后若真的写
Kotlin 代码，必须删掉这个排除，否则运行时会缺 kotlin 类。

### APK 自更新（1.4.0）

APK 和页面是两条独立的版本线：页面用 `version.json` 的 `build`，APK 用固定路径
`app/version.json`：

```json
{
  "versionCode": 13,
  "versionName": "1.4.7",
  "url": "https://lswlc33.github.io/zeeho-3d-models/app/zeehowebnext.apk",
  "notes": "更新说明"
}
```

- 启动后页面首帧再检查（冷启动/回前台有防抖）；远端 `versionCode` 大于当前才弹窗，
  不会因为 `versionName` 字符串比较误判。
- 点「立即更新」后下载到 `cache/update/`，先验证**包名、versionCode、签名证书集合**与当前
  安装包一致；不一致直接删包报错，不把任意 APK 递给安装器。
- 安装走 `UpdateProvider`（只读 `content://<applicationId>.update/…`，工程没有 AndroidX，
  不用 FileProvider）；Android 8+ 没允许「安装未知应用」时先跳系统设置，回来后继续安装。
- 下载的 APK 在**下次进程启动检查更新时**清掉 `cache/update`；等待未知来源授权的那一份会保留，
  避免点完设置回来文件没了。正常安装会替换 APK 并重启进程，所以不会长期占着 0.75MB。
- 发版顺序：先 `exportApk` 产出新 APK，再把 `app/version.json` 的 `versionCode/versionName`
  改成这次的值，二者一起提交推送。线上 `version.json` 是更新弹窗的唯一开关。
- debug 包可用 `adb shell am start … --es update_manifest http://127.0.0.1:8899/version.json`
  指向本机清单测试；release 忽略该 extra，只认固定 HTTPS 地址。
- 「数据与调试」两行版本可点击：**软件版本**走 `checkAppUpdate()` 返回 APK 当前/最新版本，有更新时
  只提供「下载更新」→ `downloadAppUpdate()`；**面板版本**先走 `checkPageUpdate()` 只查远端 build
  （V5.93 起页面侧：没更新只显示状态，有更新才出现「更新并重启」→ `restartPage()`），
  原生检查/下载 pending 后 reload，网页显示不确定进度条。两者的动作刻意不同，不要合并。

### WebView HTTP 缓存上限（1.4.1）

WebView 没有公开的缓存大小 API。`WebViewCache.trim()` 在 `Application.onCreate`（还没创建
WebView 时）统计 `cache/WebView/**/HTTP Cache`，**超过 32MB 才整块删除**，不是每次启动清空；
否则地图瓦片/车图/字体每次都要重新下载。页面快照在 `files/webcache`，不受影响。
安装后真正占空间的大头是 WebView 自己预分配的 4MB `app_webview/BrowserMetrics-spare.pma`
（Chromium 固定开销，应用无法控制）和 HTTP Cache；APK 本身优化后只有约 123KB。

### 车辆附近检测（1.4.5）

位置卡遮罩不再只写「位置已隐藏」：原生在首页、前台时扫描车况里的 `bluetoothAddress`，
只收该 MAC 的 BLE 广播并取 RSSI，遮罩显示「车在附近 / -62 dBm · 约 3 米」或「没检测到车辆」。

- **只扫描，不连接**：代码没有 `connectGatt`、配对或 GATT 命令；用 `ScanFilter.setDeviceAddress()`
  收广播。Android 12+ 只申请 `BLUETOOTH_SCAN`（过滤条件已限定 MAC，不读设备地址、不连接）；
  Android 11 及以下用 legacy Bluetooth + `ACCESS_FINE_LOCATION`。
- 权限只在用户点位置遮罩/眼睛、状态是 `needperm` 时申请，不在启动时弹；拒绝会 Toast。
- 扫描只在首页 + App 前台进行：离开首页 `proximityStop()`，`onPause` 也停止；`onResume` 恢复。
- RSSI 做 0.6/0.4 平滑；距离按 log-distance（n=2，默认 1m 处 -59dBm）估算，只作量级参考。
- 状态：`nearby`（20 秒内收到广播）、`searching`（启动后 10 秒内）、`far`（没收到）、
  `needperm`、`unavailable`（蓝牙关闭/无适配器）、`noaddr`（车况没蓝牙地址）。

### 电量历史（1.4.0，App 专属）

后台 `StatusFeed` 每处理一份车况就调 `BatteryHistory.record()`，**50 秒去重**，所以页面
每 30 秒同步、后台每 60 秒轮询时都最多一分钟一条。保留 31 天，按 VIN 分文件：
`files/battery/hist-<sha256(vin) 前 16 位>.bin`（文件名不含 VIN）。

存储格式（不是明文 JSON）：

```
[magic "ZBAT"][version]
每个 chunk：[plainLen][compLen][ivLen][iv 12B][AES-GCM 密文]
chunk 明文：每 16 字节一条（long 时间 / short 电压×10 / short 电流×10
           / byte soc / byte 温度(未知 -128) / byte 充电状态 / byte flags）
```

- **压缩**：每个 chunk 先 `Deflater.BEST_SPEED`；**加密**：AES-256-GCM，密钥在 Android
  Keystore（不可导出），IV 由 Keystore 随机生成；每 8 条一个 chunk，最多丢 8 分钟。
- 读取时只把最近区间降采样到约 18~360 个点交给页面（24h 5 分钟一桶 / 3d 15 分钟 /
  7d 30 分钟 / 30d 2 小时），整月原始记录不会过 JS 桥。
- 页面通过 `ZeehoShell.batteryHistory(range)` 取 JSON：`points` + `summary` +
  `lastCharge` + `lastDischarge`。充电=`state>0`；放电=未充电且 `|current| ≥ 0.3A`。
  统计里的能量按采样间隔积分（单段最多按 5 分钟算），是趋势值不是电表读数。
- 1.4.3 起「我的」里只放一张入口卡，点「查看记录」打开 `U.sheet(full:true, key:'batt')`
  全屏弹层（与骑行记录同一套）；范围 chips、图表、上一次充电/放电都在弹层里，切范围只重画这一层。
- 这块**只在 `inShell()` 时渲染**；浏览器没有桥，整块不出现。页面侧的卡片在「我的」页
  骑行分析下方，范围切换只重新拉数据，不整页刷新。

页面侧也配合做了三件事（改 `public/js/app.js` 时别退回去）：`init()` 先把车况接口发出去
再画骨架；车辆列表和 VIN 落 localStorage，冷启动不再串行等 `vehicle/list`；账号档案与
车辆设置只在后台补，不挡爱车页。MuMu + 本机测试服实测：进程冷启动约 0.65 秒到首帧、
约 1.1 秒数据上屏；同一 WebView 里重载页面数据约 0.36 秒（接口热连接）。

## 壳与网页的约定

网页源码在模块仓库 `tools/dashboard/public/`，发布前用
`node tools/dashboard/build-static.mjs E:/AI_Program/zeeho-3d-models/index.html --obfuscate`
出单文件产物。壳通过原生注入的 `window.ZeehoShell` 与页面交互：

| 方法 | 作用 |
|---|---|
| `insets()` | 返回 `{"top":24,"bottom":0,"left":0,"right":0}`（CSS px）。WebView 的 `env(safe-area-inset-*)` 不报系统栏高度，页面 `fitViewport()` 在壳模式必须用它。 |
| `setDark(boolean)` | 页面主题变化时同步状态栏/导航栏图标明暗。 |
| `appVersion()` | 当前壳版本，形如 `1.4.7 (13)`；页面「数据与调试 → 软件版本」用，浏览器不显示。 |
| `checkAppUpdate()` / `downloadAppUpdate()` | 手动检查 APK 更新（返回 JSON）/ 下载并交给安装器。 |
| `checkPageUpdate()` / `restartPage()` | 手动检查页面远端 build（返回 JSON）/ 重启页面（有 pending 先应用，否则下载后再 reload）。 |
| `batteryHistory(range)` | App 专属电量历史，`range` = `24h`/`3d`/`7d`/`30d`；返回降采样 JSON（见下节）。浏览器没有这个桥。 |
| `proximityStart(address)` / `proximityStatus()` | 车辆附近检测：只扫描该 MAC 的 BLE 广播，返回 `state/rssi/meters`；不连接、不配对、不发 GATT 命令。 |
| `requestProximity()` / `proximityStop()` | 用户点眼睛/遮罩时申请「附近的设备」权限；离开首页或退后台停止扫描。 |
| `syncSession(token, uid, vin, name)` | 页面每次加载后把登录态推给原生；换 token/车辆会清旧摘要。token 为空会停后台。 |
| `syncStatus(json)` | 页面每轮刷新后的车辆摘要（电量/充电/锁/坐垫/在线/功率/消息未读）。原生只读它做通知和小组件，不反向改页面。 |
| `autoTask()` / `setAutoTask(json)` | 读写 App 专属自动任务（开关、执行时间、发帖文案、间隔、上次结果）与三个车况提醒开关（`alertsLow`/`alertsOffline`/`alertsTire`）。 |
| `background()` / `setBackground(on)` | 读写后台同步开关；关闭会停前台服务、取消 15 分钟兜底任务并清通知。 |
| `backgroundStatus()` | 后台自检：返回开关/前台服务/15 分钟任务/通知权限/电池优化/上次同步/鉴权状态（JSON）。 |
| `restartBackground()` | 一键修复后台：重排兜底任务并拉起前台服务。 |
| `pageReady()` | 页面第一次 `render()` 完成时调用，原生据此淡出启动图（不要用 `onPageFinished`，它会等外链字体 CSS）。 |
| `requestNotifications()` | 请求 Android 13+ 通知权限（已问过就不再弹）。 |
| `openSettings(kind)` | 打开通知/自启动/**电池优化**/应用详情设置（HyperOS 的自启动入口失败时退回应用详情）。 |

页面侧必须保留这些分支（改 `app.js` 时别删）：

- `inShell()` = `!!window.ZeehoShell`；`isStandalone()` 在壳内直接返回 true。
- `fitViewport()` 先走壳分支：写 `--sat`/`--sab`，清掉 iOS 撑高。
- `applyTheme()` 调 `ZeehoShell.setDark()`。
- `checkBuild()` 在壳内直接 return（更新由原生接管，页面绝不整页重载）。
- `registerSw()` 在壳内直接 return（SW 网络优先且绕过 `shouldInterceptRequest`）。
- 大地图「我的位置」用 `navigator.geolocation`：壳必须开 `setGeolocationEnabled(true)`，并在
  `onGeolocationPermissionsShowPrompt` 里申请 `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`
  （任一授予即放行，拒绝回 false）。权限只在用户点「我的位置」/「两个都看」时弹，不在启动时申请。
- 「我的」页的「几何面板 / 下载 App」卡片只在 `!inShell()` 时渲染。
- `syncNative()` 在每次 `doLoad` 末尾把登录态与车辆摘要推给原生；**别删**，否则通知/小组件/
  自动任务拿不到数据。

壳的 `WebViewClient.shouldInterceptRequest` 对同源路径的处理：

| 路径 | 行为 |
|---|---|
| `/`、`/index.html` | active 快照的 index.html（`text/html`，无 charset 参数） |
| `/version.json` | active 快照的 version.json（绝不放行到网络） |
| `/manifest.webmanifest`、`/icons/*` | 快照文件；缺失则放行网络 |
| `/vehicle-info.html` | 投屏资料页；老快照里没有就放行网络（页面小，不占下载进度） |
| `/sw.js` | 固定 404，阻止 Service Worker 注册 |
| 其他同源子资源 | 放行网络（站点以后加文件时不至于白屏） |

顶层导航只允许面板根路径/`index.html`、`vehicle-info.html`（可带 query/hash）。外部 http(s)
网页直接拦截，页面留在面板；`intent://` 解析后会剥掉 `component`/`selector` 并强制
`CATEGORY_BROWSABLE`，`androidamap://` 等地图 scheme 交给系统 App，失败时用
`browser_fallback_url` 开浏览器。

`vehicle-info.html` 是「车辆设置 → 投屏」的落点：面板 `openVehicleInfo()` 同源时把
`{token, vin}` 写进 `sessionStorage['zeeho.vi.handoff']`（token 不进 URL），跨源退化到 hash
且页面读完立刻 `replaceState` 清掉；页面只认 `?auto=1`。壳里按返回键先 `webView.canGoBack()`
回面板，再走原来的 `BACK_JS`。

## 通知、后台与自动化（1.2 / 1.3）

### 通知

| 渠道 ID | 用途 | 何时出现 |
|---|---|---|
| `service` | 后台同步前台服务（低优先级、常驻） | 登录后开启后台同步时 |
| `messages` | 未读车辆消息 | 后台收到新的未读消息（或未读数增加） |
| `charging` | 充电开始 / 充电进度 | 后台检测到充电状态变化；进度通知优先预计充满时间，没有就用 `ChargeLog` 线性外推，再退回已充时长 |
| `charge_events` | 已充满 / 充电已停止 | 两个事件分开：`已充满`（100%）与`充电已停止`（未充满断开）使用不同通知 ID，不会互相覆盖 |
| `tasks` | 自动任务执行结果 | 每日自动任务跑完 |
| `actions` | 快捷方式/小组件车控结果 | 解锁/锁车/鸣笛/开坐垫执行后 |
| `auth` | 登录态失效 | 后台接口返回 401 |

- 消息通知依赖页面同步的**内部用户 id**（`S.profileUid`，即「数据与调试」里手填的账号 id）。
  没填账号 id 时页面自己都查不了消息，原生也不会推；这不是壳的缺陷。
- Android 13+ 首次登录/开启自动任务时请求 `POST_NOTIFICATIONS`；拒绝后只能去系统设置开。
- 前台不弹消息通知：页面里已经能看到红点和充电卡；退回后台时才补通知。
- **100% 兜底**：车机在满电时可能仍短暂报 `charging=1`；原生和网页都把 `soc >= 100` 归一成"已充满"，
  收掉进度通知，避免满电后进度条一直挂着。
- **桌面有微件时自动隐藏持续性充电通知**：检测到任意尺寸的本应用微件被绑定后，不再推
  "开始充电/充电中"进度通知（微件上已经能看到）；"已充满/充电已停止"这类一次性事件仍会提醒。
  微件被移除后，下一次轮询会恢复进度通知。

### 后台

- `MonitorService` 是 `dataSync` 类型前台服务，登录后启动，60 秒轮询车况/电池/消息。
  1.4.9 起后台只拉 **1 条**消息（页面要列表才拉 20 条）——后台只需要 `noRead` 与首条标题，
  而 `noRead` 与页大小无关；这是后台流量最大的一笔。
- 车况没变化时**不写 SharedPreferences、不重绘小组件**（`StatusFeed.sameData`，`updatedAt` 不参与比较），
  只在关键字段变了或距上次写盘超过 15 分钟时落一次。以前每 60 秒整份覆写一次状态，还要让桌面进程
  重绘三套 RemoteViews 并重新解码车图，是后台最贵的一段。
- 被杀或重启后由 `MonitorJobService`（JobScheduler，15 分钟周期）兜底；两者都受系统省电策略影响。
- `ACCESS_NETWORK_STATE` 是 Job 网络约束的硬前提：少了它 `JobScheduler.schedule()` 直接抛
  `SecurityException`，而且以前被 `catch` 吞掉，表现就是"任务永远不跑"。**别删这个权限**。
- 充电记录 `ChargeLog` 按 VIN 存 JSON 采样，只在后台采样；用于没有预计充满时间时的外推。
- 「后台同步」开关关闭时：停服务、取消周期任务、清掉充电/消息通知；自动任务不受它影响。

### 自动任务（App 专属）

- 任务面板只在 `window.ZeehoShell` 存在时渲染「App 专属 · 后台增强」块；普通浏览器网页端不显示。
- 开关 + 每日执行时间（默认 `09:00`）+ 保活提示；时间改动会立刻重排 JobScheduler 2102。
- Job 用 `setMinimumLatency` 排到下一个执行时刻，`setPersisted(true)` 跨重启保留；执行完
  `AutoTaskJobService` 写 `auto_last_result` 并排下一天。
- 执行内容：h5 签到、积分任务（发帖/点赞/分享，按接口返回的 `completeFlag` 判断），发帖后按设置删除动态。
  自动任务**绝不能**改走原生 `signin`（见模块仓库 AGENTS：有效 token 也会 401 并触发全局登录失效）。
- 保活：允许自启动/后台运行、加桌面小组件；HyperOS 等系统仍可能延迟到下次唤醒。

### 桌面小组件

- 三种：2×2（车名/电量/电压/充电状态/功率/时间/锁车/更新时间）、4×2（加车图、坐垫锁、在线、刷新、
  解锁/鸣笛图标）、4×4（再加锁车/坐垫按钮和更大的车图）。
- 信息来自 `NativeStore` 缓存。电压取接口的 `voltage`（保留一位小数）；**不再显示续航和定位**；
  车图取 `vehiclePicUrl`，由 `WidgetCar` 在后台缩图缓存到 `filesDir`，URL 变化才重新下载。
- 颜色是**浅/深色两套**（`values/colors.xml` + `values-night/colors.xml`）：浅色模式白卡片、深色模式
  近黑卡片，强调色跟随系统。小组件布局用 RemoteViews 支持的控件，不用自定义 View。
- 尺寸用 `targetCellWidth/Height` + `minWidth/minHeight`。HyperOS 这类桌面会按 minHeight 决定行数：
  2×2 / 4×2 的 minHeight 提到 180dp，避免被放成 2×1 / 4×1 导致内容裁切；4×4 用 300dp 保证
  能在微件列表里出现。部分第三方桌面（如 MuMu 的 Lawnchair）换算不同，可手动拉伸。
- 刷新按钮触发一次 `MonitorService` 轮询，不直接发网络；动作按钮的 Intent 带本机随机 secret，
  `ActionActivity` 校验后才发车控，错误 secret 只回主界面。
- 检测到任意微件存在时，持续性充电进度通知自动隐藏（见上节）；事件通知保留。

### App Shortcuts

- 动态注册四个：解锁车辆 / 锁定车辆 / 寻车鸣笛 / 打开坐垫。
- 每个 Intent 带 `NativeStore.shortcutSecret()` 生成的随机 secret；`ActionActivity` 是 exported，
  没有 secret 或 secret 不匹配时直接回主界面，**不会发任何请求**。
- secret 存在应用私有 prefs 和桌面保存的 shortcut intent 里；卸载重装会换新 secret，桌面旧快捷方式失效。

### 图标

- 自适应图标：`drawable-nodpi/ic_launcher_foreground.png`（原图裁切后的 Z 字标）+
  `ic_launcher_monochrome.png`（单色层），背景 `drawable/ic_launcher_background.xml`。
- 源图与裁切中间产物在 `app/icon-work/`；改图标时从这里重新导出，别直接改 PNG 后忘了 monochrome。

## 1.5.0：鉴权恢复、提醒与后台自检

这一版修的是几个"会静默坏"的场景，并补上用户能自己看的后台状态：

- **鉴权误判不再永久锁死**：`ApiClient.read()` 只认 HTTP 401 或**失败响应**里的明确文案，
  不再扫成功响应的 raw 正文（一条正文写着"请重新登录"的普通消息以前能让后台永久停更）。
  `Poller` 的 `authFailed` 改成 30 分钟冷却重试，任何一条接口成功都会立刻清除；通知只在第一次判定时发。
- **充电状态未知时不写 `charging=0`**：车况超时、电池也没报状态时，旧代码会把正在充电的车写死，
  触发假的"充电已停止"。现在只有真的拿到 `chargeState`/`whetherChargeState` 才落盘。
- **旧响应不覆盖新状态**：`StatusFeed.apply` 丢弃 `at` 早于已存 `updatedAt` 的迟到 payload。
- **轮询去重**：`Poller` 全局 `RUNNING` + 30 秒最小间隔；`MonitorService` 上一轮没回来就跳过这一拍。
- **自动任务失败重试**：20 分钟一次、最多 3 次再排到明天；`onStopJob` 让系统接管时不再抢排，
  避免一次断网丢掉当天签到。
- **微件**：车图按 mtime 缓存解码（三套微件不再各自解一张 420×280 PNG）；超过 30 分钟显示
  「MM-dd HH:mm 更新 · 可能已过期」；登录失效直接显示「登录已失效，打开 App」；
  刷新按钮无论后台开关都会真打一次接口并 Toast 反馈；点微件加 `SINGLE_TOP`，不再重建 Activity/重载页面。
- **更新流程**：下载框可取消（取消即删半截包），完成/失败前都判 Activity 存活。
- **首启不再因标题卡死**：快照校验去掉 `<title>极核面板</title>` 精确匹配（保留 build meta + 长度下限）；
  校验失败时直接打开在线版，而不是把用户钉在"页面校验失败"。
- **电池优化**：`openSettings('battery')` 走 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  （失败退回系统列表）；页面「后台自检」卡显示是否已加入白名单。
- **后台自检桥**：`backgroundStatus()` / `restartBackground()`（见上表），页面显示开关/服务/任务/
  通知/电池优化/上次同步，并给一键重排。
- **可选车况提醒**（默认全关，任务面板逐项打开）：低电量（≤20% 且未充电，一次放电只提醒一次）、
  车辆离线（在线→离线跃迁）、胎压异常（15 分钟兜底任务里查，<1.9 bar 或 warningType 非 0）。
  没做「未锁车」：`headLockState` 是整车开关机，骑行途中就是"未锁"，按它提醒会每次骑车误报。
- 微件正文最小字号从 8sp 提到 9sp；版本 **1.5.0 / versionCode 16**。

## 1.5.1：开屏性能

真机 Redmi K90、冷启动（`am start -W` + 壳自己的日志）：

- WebView 创建 164~250ms（系统波动，占大头）；`loadUrl` 后 DCL 305~410ms；
  页面 commit 575~780ms；页面首数据（缓存命中）`cached` mark 238~303ms。
- 1.5.0 之前同口径：page commit 715ms、DCL 234~867ms、`firstData` 796~1202ms。

改动：

- **启动图**：`MainActivity.showSplash()` 画图标 + 名称，第一帧就有内容；
  页面第一次 `render()` 调 `ZeehoShell.pageReady()` 淡出（比 `onPageFinished` 早，
  后者会等 jsDelivr 的字体 CSS）。首启下载遮罩/错误页出现时也会收掉。
- **前台杂活后移**：`onResume` 里的快捷方式、两个 Job 排程、前台服务、APK 更新检查、
  BLE 扫描统一挪到 350ms 后（`backgroundWork`）；`Application.onCreate` 里的快捷方式/Job 也改到后台线程。
- **缓存扫描一周一次**：`WebViewCache.trimAsync` 用 `webnext` prefs 的 `last_trim_at` 做 7 天闸门；
  绝大多数启动 `awaitTrim()` 立即返回，只有每周那一次最多等 400ms。
- **快捷方式不重复注册**：`ShortcutHelper.ensure` 用 `版本:secret` 签名 + `getDynamicShortcuts().size()` 判断，
  同一进程只跑一次；以前 Application 和 onResume 每次启动都重建 4 个 ShortcutInfo 并 binder 写入。
- **页面侧配套**（V5.96）：上一轮车况缓存先画、快慢两批接口、`pageReady()` 桥；详见网页 README。
- **性能日志**（保留）：`webview ready: trimWait/create/total`、`loadUrl returned`、
  `page ready`、`page finished`、`page commit`、`page timing {dcl,load,paint,firstData,cached}`。
  改启动链路时直接 `adb logcat -s ZeehoWebNext` 对比。
- **1.5.1 / versionCode 17**。

## 1.5.2：桌面微件重做（2026-10-19）

**尺寸**
- 只有 2×2、4×2、4×4 三种：`resizeMode="none"`，`maxResizeWidth/Height` 与 `min` 相等，桌面不能拉成别的形状。
- `res/xml/` 是 API <31 的标准尺寸（110×110 / 250×110 / 250×250dp）；
  `res/xml-v31/` 覆盖 API 31+。v31 里的 2×2 故意写成 `258×55dp`、4×2 `250×55dp`、4×4 `250×165dp`：
  Lawnchair 把 minWidth/minHeight 当 **px** 算，标准 110dp 会被它放成 1×3；这组值让它算出 2×2 / 2×2 / 2×4。
  现代桌面认 `targetCellWidth/Height`（2×2 / 4×2 / 4×4），会盖掉这些 min 值。
  ⚠️ Lawnchair 的 4 列还受它自己的屏幕宽度算法限制，4×2/4×4 在它上面仍可能显示成 2 列；这是桌面自身的问题，
  不是 provider 配置能解决的（标准 250dp 也会被算成 2 列，写 546dp 又会被它当成超出屏幕而隐藏）。
  别再为它改标准值；改之前先看 `dumpsys appwidget` 的 min/resizeMode 和桌面实际 span。
- **预览**：`previewLayout` 指向静态样例布局（`widget_*_preview.xml`，跟随深/浅色），
  `previewImage` 指向 `drawable-nodpi`/`drawable-night-nodpi` 的 PNG（老桌面用，不再是 App 图标）。
- **布局**：所有尺寸都改用 `0dp + weight` 吸收桌面给的宽高差；2×2 也加了车图。
  车图是 OSS 的透明 PNG，直接铺在卡片上；`widget_stage` 背景板已删除。
- **数据准确性**：`NativeStore.Status` 新增 `chargeKnown`（页面/Poller 明确报了充电状态才写）；
  没报过时不再显示"未充电"，而是车锁状态或"状态未知"。电压为 0/`--` 时隐藏；锁/坐垫未知时隐藏对应 chip；
  微件不再用 `ChargeLog` 历史曲线外推"预计充满"（只显示接口真给的 eta/fullMin/已充时长）。
  2×2 头部只显示短时间（`HH:mm 更新`，过期显示日期）。
- **同步/刷新**：数据变化时 `StatusFeed` 重绘三套微件；无变化每 15 分钟补一次时间；开机/覆盖安装、
  进程启动、页面 `syncSession` 都会先按本地摘要重绘一次，避免桌面停在旧数据。
- **1.5.2 / versionCode 18**。

## 1.5.9：抓取流程文案/交互优化（2026-10-19）

- 方案三从「自动抓取」彻底改成两步式：**1. 触发签到页 → 2. 粘贴地址**；
  删除旧的“VPN 自动抓取/抓到登录信息后会发通知/conn=0/mitmFail”文案。
- 粘贴框支持“从剪贴板粘贴”；从极核回来会自动弹粘贴面板（visibilitychange 已接）。
- 超时提示改成“没有等到签到页，请确认点过我的→签到”，不再显示诊断统计。
- 抓取卡只在进行中显示“停止并恢复网络”；诊断日志降为次要入口。
- **1.5.9 / versionCode 25**。

## 1.5.8：免 root 抓取改为「错误页 + 手动粘贴」（2026-10-19）

K90 真机确认官方签到页是 **HTTPS**，URL 里带 `token`；自签证书/用户 CA 都被 WebView 拒绝，
被动 TUN 只能看到 SNI，抓不到 path/query。于是改成免 root、免证书的两段式：

- 壳用 catch-all TUN（WebView 在 isolated sandbox UID，必须全量路由）+ 自签证书只 MITM
  `h5.zeehoev.com:443`；官方 WebView 拒绝证书后会显示「网页无法打开」，错误页里带**完整 URL**。
- `TunCapture` 的回调新增 `onManualPage()`；`AutoCaptureService` 收到后写 state=`manual`、
  通知「请复制签到页地址」，2 秒后自动拆 VPN；错误页不会消失。
- 页面新增「粘贴签到页地址」：用户长按复制错误页 URL（或 token）粘贴，前端解析
  `token`/`userId`（strip `bearer `、URL decode），复用原有 `capApply` 保存并刷新。
- 不做证书安装、不需要 root；代价是用户要手动复制一次地址，且签到页会短暂失败。
- **1.5.8 / versionCode 24**。

## 1.5.7：方案三诊断的最终构建（2026-10-19）

1.5.6 的探针在设备上验证后补了四处：

- 超时/失败文案直接带代理统计，并区分 `conn=0`（流量没进代理）与只有 `mitmFail`
  （h5 走了 HTTPS、官方 App 拒绝本地证书）。
- 诊断弹层的「复制全部」只保留原生 Toast，不再和网页 Toast 各弹一个。
- 多条 `Set-Cookie` 逐条转发（1.5.6 解析时保留全部，但回给客户端只写了最后一条）。
- 抓取诊断日志保留从点击「开启自动抓取」开始的 UI 事件（原先服务启动时清空会把它们抹掉）。
- **1.5.7 / versionCode 23**。

## 1.5.6：方案三探针日志（2026-10-19）

用户反馈 1.5.4 修完明文转发后，官方 H5 页能打开但抓不到结果。现有信息无法区分是
「流量没进代理 / 进了但 h5 走 HTTPS 且证书被拒 / 命中 baseInfo 但字段不在我们找的位置 /
状态没回传」，所以先加可导出的诊断日志，不靠猜。

- 新增 `CaptureLog`：应用私有文件 `files/capture-diag.log`（192KB 环形，每轮抓取先清空），
  同时写 logcat。**token 原文不落盘**，只记录来源与长度；URL query 值、Authorization、Cookie
  值全部打码，响应体只留打码后的短预览和 JSON 键名。
- 记录内容：
  - 每个连接的首行（CONNECT 主机、明文绝对 URL 的路径与 query 键）；
  - h5 的 CONNECT 是 MITM 成功还是证书被拒，失败时带异常类型；
  - baseInfo 的请求头名字/关键头存在性/cookie 名、响应码/类型/长度/Location、
    响应体 JSON 键名与 token/uid 键是否存在；
  - token/uid 的提取来源（query/header/cookie/Location/body）与长度。
- 数据调试的方案三区块新增「查看抓取诊断日志」：弹层展示日志，可「复制全部」到剪贴板
  （原生 `ClipboardManager`，不依赖网页剪贴板权限）或清空。失败弹层里也有入口。
- 提取逻辑补强：301 `Location` 的 query、响应 `Set-Cookie`、响应体里不带引号的
  `userId:123` / 表单 `token=...` 都能识别；`data.id` 优先于其他层级的 `id`。
  **uid 缺失不再直接判失败**：token 有效时先成功（页面会从 `vehicle_home.bindingUserId` 补账号 id），
  日志里记 `uid missing, accept token-only`。
- 超时错误文案附带代理统计（`conn/plain/connect/mitmOk/mitmFail`），用户不复制日志也能先看出
  代理到底有没有收到流量。
- **回到面板不再自动停抓取**：面板自己的 WebView 不在 VPN 允许列表里，回前台不受影响；
  继续等到成功/120 秒超时（或用户点「取消抓取」），避免把官方 App 正在返回的 baseInfo 掐掉。
- **1.5.6 / versionCode 22**。

## 1.5.5：重要通知升级为横幅（2026-10-19）

- 充电完成/中途停止、自动抓取成功/失败、低电量/离线/胎压提醒、自动任务结果
  四个渠道改为 `IMPORTANCE_HIGH`：默认有**横幅（悬浮）+ 声音 + 震动**。
- **Android 渠道重要性创建后不能修改**，所以这四个渠道换了新 ID：
  `charge_events_v2` / `capture_done_v2` / `alerts_v2` / `tasks_v2`；
  启动时删除旧的 `charge_events` / `capture_done` / `alerts` / `tasks`，避免设置里出现两份。
- 通知 Builder 同时带 `PRIORITY_HIGH`，兼容部分 OEM 的判断。
- 充电进度（`charging`，LOW）、车辆消息（`messages`，DEFAULT）、快捷操作（LOW）、
  后台常驻（MIN）、抓取等待（LOW）保持原级别，不抢占横幅。
- 用户仍可在系统通知设置里单独把某个渠道调成静音/关横幅——这是系统权限，App 不能强制。
- **1.5.5 / versionCode 21**。

## 1.5.4：方案三修明文网页抓取（2026-10-19）

用户抓包确认：官方 App 的 `h5.zeehoev.com/cfmotoservermine/H5/baseInfo` 是 **WebView 网页**，
先走**明文 HTTP**（token/userId 在 URL/请求头里），服务端再 301 到 HTTPS；所以 HttpCanary
这类工具**不需要 CA**也能看到第一跳。1.5.3 的代理只处理 CONNECT，把明文 HTTP 直接关掉，
于是既抓不到、官方 H5 页也打不开。

1.5.4 改动：

- `CaptureProxy.handlePlain()`：接收代理形式的绝对 URL（`GET http://host/path HTTP/1.1`），
  转发到目标 80 端口，把 301/页面原样回给 WebView；从请求 **URL query / 自定义头 / cookie**
  提取 `token` 和 `userId/user_id/uid/access_token`，命中后置 captured。
- 抓到后 h5 的 HTTPS CONNECT **盲转发**（不再 MITM）；如果第一轮没抓到、MITM 又被官方 App
  拒绝证书，则置 `mitmFailed`，后续也盲转发——页面至少能打开，错误文案会说明 CA 边界。
- 抓到后 VPN 再保留 **15 秒**让 WebView 完成 301→HTTPS 加载，然后自动拆 VPN/停止代理。
- 流程改为：点“开启自动抓取”只申请 VPN 权限（state=ready），指引里点“启动极核 App”才真正
  建立 VPN；提前回到面板会自动停 VPN；等待通知加“停止抓取”动作；原生错误页的按钮改成
  “关闭 VPN 并重试”。这样面板自己的 WebView 不会在 VPN 生效期间被代理影响。
- **1.5.4 / versionCode 20**。

## 1.5.3：方案 3 · VPN 自动抓取（2026-10-19）

数据调试里新增“方案三、VPN 自动抓取（无需 root）”：点按钮 → 系统 VPN 授权 → 指引 →
自动打开极核 App；用户在极核里点「我的 → 签到」后，本地代理从
`https://h5.zeehoev.com/cfmotoservermine/H5/baseInfo` 拿到 token 和 userId，
写入 NativeStore 并发通知；点通知回到面板，页面弹“使用并刷新”。

实现：

- `AutoCaptureService`（VpnService）：`addAllowedApplication("com.cfmoto")` + 只加一条 dummy 路由，
  `setHttpProxy(127.0.0.1:<本地端口>)`。只代理极核 App，其他 App 完全不走这个 VPN。
- `CaptureProxy`：本地 CONNECT 代理；对 `h5.zeehoev.com` 用 `res/raw/zeeho_capture.p12`
  （自签名，SAN=DNS:h5.zeehoev.com）做 TLS 中间人，其他 HTTPS 盲转发；
  强制 HTTP/1.1、`Accept-Encoding: identity`，从请求 Authorization 拿 token，
  从响应 JSON/HTML 里找 userId（兼容 userId/user_id/uid/userNo/memberId）。
  抓到后立刻停服务；120 秒没抓到就报错。
- `ShellBridge`：`startCapture()` / `stopCapture()` / `launchOfficialApp()` /
  `captureStatus()` / `clearCapture()`。状态：idle / vpn / waiting / success / error / denied。
- 通知：`capture_wait`（LOW，前台服务）+ `capture_done`（DEFAULT，成功/失败）；
  成功通知只显示打码后的账号 ID，不带 token，点通知回 MainActivity。
- 页面（V5.97）：数据调试里的方案三区块、VPN 授权后弹指引、启动极核 App、
  轮询状态、成功后弹“使用并刷新 / 复制 token / 忽略”；冷启动或回前台若已有成功结果也会弹。
- **前提**：官方 App 对 h5 这条链路不校验系统 CA（用户已验证）。如果它改成校验证书，
  代理会在握手时报“官方 App 拒绝了本地证书，可能需要安装 CA”，不会静默失败。
- `autocapture` 的 VPN 需要用户每次授权（Android 会弹系统框）；抓取结束即停 VPN。
- **1.5.3 / versionCode 19**。

## 测试

### 本机测试服 + debug 包

正式包只认固定 URL；debug 包可以用 intent extra 覆盖 base URL。网页源码在主仓库
`zeeho-unlocker`，下面的第 1 步在主仓库根目录执行；第 2 步起在本仓库根目录执行：

```bash
# 1) 出两版网页到临时目录（非混淆即可；在 zeeho-unlocker 根目录）
node tools/dashboard/build-static.mjs <tmp>/v1/index.html
node tools/dashboard/build-static.mjs <tmp>/v2/index.html

# 2) 起本机静态服务（本仓库自带的 devserver；THROTTLE=40000 可限速看首启进度条）
node app/tools/devserver.mjs <tmp>/v1 8787

# 3) 让模拟器/真机的 localhost:8787 指向 PC
adb reverse tcp:8787 tcp:8787

# 4) 启动 debug 包
adb shell am start -n io.github.lswlc33.zeehowebnext/.MainActivity \
  --es base_url http://127.0.0.1:8787/
```

debug 包的 `base_url` 只在 `ApplicationInfo.FLAG_DEBUGGABLE` 时读取；release 忽略。
debug manifest 允许 cleartext，release 是 `usesCleartextTraffic=false`。

### MuMu 模拟器注意

- 本机 MuMu Nx 15 / Android 15 的系统 WebView 是 **110**。页面用了 `color-mix()`（Chrome 111+），
  在 110 上部分半透明/描边会降级；功能和布局验证可用，最终视觉以真机（K90 / Android 17）为准。
- MuMu 自带搜狗输入法的窗口高度是 0（`dumpsys window` 里 `InputMethod Requested h=0`），
  软键盘无法显示；IME 缩 WebView 的逻辑无法在该模拟器上目测，真机需复测输入框不被键盘遮挡。
- 建议 `wm size 720x1280` / `wm density 320`（MuMu 默认即可），页面宽度 360 CSS px，不会进入
  桌面预览布局；App 锁竖屏。

### 验收点

- 首启有进度条；`files/webcache/snapshots/<id>` 里 `index.html`/`version.json`/`meta.json`
  先齐，manifest 和图标随后在后台补齐。
- 二次启动从快照加载（MuMu 实测约 0.65 秒到首帧；页面内重载数据约 0.36 秒）。
- 停掉测试服/断网后冷启动仍能显示页面壳（API 数据失败是预期的）。
- 新 build 下载后旧页不变；空闲 5 秒或下次启动才切新页。
- 有弹层/输入框聚焦时推迟空闲刷新。
- 底栏悬浮在系统手势条上方，顶部不被状态栏压住；切系统深浅色状态栏图标跟随。
- 返回键：`BACK_JS` 先调 `APP.back()`。骑行记录内部先退单条 → 单日 → 年视图 → 月视图，再关弹层
  （多个弹层只退一层，不一次性全关）；没有弹层时回爱车，最后双击退出。
- 外部网页被拦截；地图 intent 放行。
- 从面板「车辆设置 → 投屏」进入 `vehicle-info.html` 后，按系统返回键回到面板（不是退出 App）。
- 壳内「我的」没有下载入口；普通浏览器有。
- 登录后后台出现 `service` 常驻通知；按 Home 后 60 秒内 `last_poll`/电量会更新；充电时出现
  「充电中 · xx%」进度通知，充满后出现「已充满」。
- 任务面板出现「App 专属 · 后台增强」；开关/时间改动后 `cmd jobscheduler get-job-state <pkg> 2102`
  返回 `waiting`；普通浏览器任务面板没有这块。
- 三个小组件能在微件列表预览并拖到桌面；4×4 的四个按钮可点（错误 secret 不发请求）。
- 长按桌面图标能看到四个动态快捷方式（部分桌面只显示前两个，`dumpsys shortcut` 里应有四个）。
- 图标为新的 Z 字标自适应图标，单色层在主题图标模式下可用。

## 已知边界

- 首次安装必须联网下载页面壳；断网首启只能提示重试。
- 只缓存单文件 `index.html` + manifest + 图标。若站点以后改成多文件产物，需要给壳补资源清单。
- GitHub Pages CDN 有约 10 分钟缓存；更新检查用时间戳 query 绕过，但刚发布的几分钟内可能仍看到旧版。
- 壳的 WebView localStorage 与手机浏览器互相独立，首次使用需在「数据与调试」里重新填 token。
- `versionCode` 每次发布必须 +1；`zeehowebnext.apk` 是提交进仓库的构建产物，发版时一起更新。
