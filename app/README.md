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
      MainActivity.java         # WebView 宿主、edge-to-edge、返回键、更新调度
      WebCache.java             # 快照下载/校验/激活/拦截
      ShellBridge.java          # @JavascriptInterface: insets()/setDark()/appVersion()
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
files/webcache/.tmp-<build-id>/
```

只缓存页面壳，**不缓存**接口数据、车辆图片、地图瓦片；字体是外部非阻塞资源，离线时
回退系统字体。页面 HTML 用 **gzip** 传输（约 639KB → 约 200KB），首次安装必须联网下载
约 0.2 MB，之后才是本地秒开。

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
| `appVersion()` | 当前壳版本，调试用。 |

页面侧必须保留这些分支（改 `app.js` 时别删）：

- `inShell()` = `!!window.ZeehoShell`；`isStandalone()` 在壳内直接返回 true。
- `fitViewport()` 先走壳分支：写 `--sat`/`--sab`，清掉 iOS 撑高。
- `applyTheme()` 调 `ZeehoShell.setDark()`。
- `checkBuild()` 在壳内直接 return（更新由原生接管，页面绝不整页重载）。
- `registerSw()` 在壳内直接 return（SW 网络优先且绕过 `shouldInterceptRequest`）。
- 「我的」页的「几何面板 / 下载 App」卡片只在 `!inShell()` 时渲染。

壳的 `WebViewClient.shouldInterceptRequest` 对同源路径的处理：

| 路径 | 行为 |
|---|---|
| `/`、`/index.html` | active 快照的 index.html（`text/html`，无 charset 参数） |
| `/version.json` | active 快照的 version.json（绝不放行到网络） |
| `/manifest.webmanifest`、`/icons/*` | 快照文件；缺失则放行网络 |
| `/sw.js` | 固定 404，阻止 Service Worker 注册 |
| 其他同源子资源 | 放行网络（站点以后加文件时不至于白屏） |

顶层导航只允许面板根路径/`index.html`（可带 query/hash）。外部 http(s) 网页直接拦截，
页面留在面板；`intent://` / `androidamap://` 等地图 scheme 交给系统 App，失败时用
`browser_fallback_url` 开浏览器。

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
- 返回键：先退弹层，再回爱车，最后双击退出。
- 外部网页被拦截；地图 intent 放行。
- 壳内「我的」没有下载入口；普通浏览器有。

## 已知边界

- 首次安装必须联网下载页面壳；断网首启只能提示重试。
- 只缓存单文件 `index.html` + manifest + 图标。若站点以后改成多文件产物，需要给壳补资源清单。
- GitHub Pages CDN 有约 10 分钟缓存；更新检查用时间戳 query 绕过，但刚发布的几分钟内可能仍看到旧版。
- 壳的 WebView localStorage 与手机浏览器互相独立，首次使用需在「数据与调试」里重新填 token。
- `versionCode` 每次发布必须 +1；`zeehowebnext.apk` 是提交进仓库的构建产物，发版时一起更新。
