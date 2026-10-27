# 合进壳工程（3 步）

本仓库不含壳工程源码（面板网页与 Android 壳在私有工作区构建），所以投屏以
**drop-in 源码 + 三个接入点**交付。壳工程（`MainActivity` 所在模块）照下面做即可。

产物依赖：`cast/` 的 11 个类 + 本目录的 `ZeehoCastShell.java`（同一个包）。

---

## 1. 拷源码

```
app/src/main/java/io/github/lswlc33/zeehowebnext/cast/
├── CastErrors.java          CastProfile.java        CastWire.java
├── CastControlChannel.java  CastMediaChannel.java   CastFramePuller.java
├── CastP2pLink.java         CastEncoder.java        CastTiers.java
├── CastService.java         ZeehoCastBridge.java    ZeehoCastShell.java
```

`ZeehoCastShell.java` 就在本目录，与上面 11 个放同一目录（同包，不要新建子包）。
壳的包名不同时，改每个文件首行的 `package` 即可；类之间没有跨包引用。

### build.gradle 要求

```gradle
android {
    compileSdk 34                 // 30 也能编译（模块已按 API 30 校验过），
                                  // 但 targetSdk 34 必须能解析 FOREGROUND_SERVICE_MEDIA_PROJECTION
    defaultConfig { minSdk 26 }   // Wi-Fi Direct + MediaProjection 的底线
}
```

不需要额外依赖（只用 Android SDK + `org.json`）。

## 2. 贴 Manifest

把 [`android-manifest-additions.xml`](android-manifest-additions.xml) 里的权限与 `<service>` 合并进
`app/src/main/AndroidManifest.xml`。最容易漏的两条：

- `FOREGROUND_SERVICE_MEDIA_PROJECTION`（targetSdk 34 缺了会 `startForeground` 抛异常）；
- `android:foregroundServiceType="mediaProjection"`。

## 3. 改 MainActivity（4 处）

```java
import io.github.lswlc33.zeehowebnext.cast.ZeehoCastBridge;
import io.github.lswlc33.zeehowebnext.cast.ZeehoCastShell;

public class MainActivity extends Activity {          // 现存类，只加标了 + 的行
    private ZeehoCastBridge zeehoCast;               // + 强引用，别用局部变量

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // ...原有 WebView 初始化...
        zeehoCast = ZeehoCastShell.install(this, webView);     // + 挂 window.ZeehoCast
        ZeehoCastShell.requestPermissions(this);               // + 首次进来就申请（附近设备/位置/通知）
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (ZeehoCastShell.forwardActivityResult(zeehoCast, requestCode, resultCode, data)) {
            return;                                            // + 录屏授权结果，必须转发
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override protected void onDestroy() {
        ZeehoCastShell.release(zeehoCast);                     // + 解绑事件监听
        zeehoCast = null;
        super.onDestroy();
    }
}
```

> `onActivityResult` 是最容易漏的一处。漏了的现象：系统“开始录制”点允许后，页面 `start()` 仍一直回
> `projection_permission_required`，因为 `MediaProjection` 只从这条回调进来。

---

## 合入后的自检（不装车也能做）

1. `adb install -r app-debug.apk`，页面打开任一带 `#car-cast` 的入口；
2. Chrome DevTools 连 WebView（`chrome://inspect`）执行：
   ```js
   window.ZeehoCast.available()      // {"ok":true,"available":true,...}
   window.ZeehoCast.getStatus()      // phase=idle, projectionReady=false
   window.ZeehoCast.requestProjection()  // 弹系统授权；允许后 getStatus().projectionReady=true
   ```
3. 没连车机时点「开始投屏」，应看到阶段 `provisioning → failed`，错误 `p2p_timeout` 或
   `wifi_permission_denied`，而不是崩溃或一直卡在 `provisioning`；
4. 完整真机清单在 [`../README.md`](../README.md) §4（20 步）。

## 和 `ApiActivity` 补丁的关系

两者独立：只投屏 → 只合 `cast/`；只要调试台 → 只合 `../ApiActivity.java`。
两个都合时，`ApiActivity` 也建议照第 3 步挂一次桥（它的 WebView 同样能打开面板页），
但**不要**在 `ApiActivity.onDestroy` 里 `CastService` 相关清理——服务生命周期由桥自己管。
