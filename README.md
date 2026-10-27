# zeeho-3d-models

极核（Zeeho）车型的 **3D 车模库**：收集各车型的 `.glb` 车模与配套静态图，
经 jsDelivr / GitHub Pages 分发，供客户端按清单加载使用。

## 内容

```
models/                 .glb 车模文件
index.json              车模清单，客户端按清单读取列表和文件地址
art/                    车辆静态图
icons/                  图标资源

sdk/vehicle-cast.js     车机投屏协议模型（二维码解析、帧编解码、档位规划、状态机、诊断打码）
tools/patch-cast-entry.mjs  给面板 index.html 注入「车机投屏」卡片（可重放，幂等）
tools/car-ui/cast-ui.*  投屏卡片与扫码弹层的 CSS / HTML / JS 源
docs/CAST.md            车机投屏协议文档（逆向自 Motexa 2.7.0）
android/cast/           车机投屏 Android 原生发送端（含合入壳工程的 shell/ 说明）
tests/vehicle-cast.test.mjs  投屏协议离线测试（node --test）
package.json            只用来跑 `npm test`
```

## 车机投屏（内置，不依赖第三方）

极核车机自带的投屏接收端靠「车机屏幕二维码 + Wi-Fi Direct + 手机反向监听」工作；以前只能装第三方 App
（Motexa）来投。本仓把协议逆向出来重写了一份：面板页新增「车机投屏」卡片
（车辆设置页底部 → 车机投屏，或直接打开 `#car-cast`），协议细节见 **[docs/CAST.md](docs/CAST.md)**。

| 层 | 文件 | 说明 |
| --- | --- | --- |
| 协议模型 | `sdk/vehicle-cast.js` | 二维码解析/校验/打码、控制帧(16B)/媒体帧(8B)/数据帧编解码、30 字节采集配置、命令 17 应答、档位规划与自动降档、Annex-B 拆分、会话状态机、诊断打码。纯函数，`tests/vehicle-cast.test.mjs` 固定协议字节 |
| 面板卡片 | `tools/car-ui/cast-ui.*` + `tools/patch-cast-entry.mjs` | 扫码（相机 + BarcodeDetector，失败可粘贴）、画质档位、数据帧格式、开始/停止、复制诊断；注入块自包含，与其它面板注入互不依赖 |
| 原生桥 | `android/cast/` | `window.ZeehoCast`：Wi-Fi Direct 组网 → 监听 10920/10921/10922 → 10930 状态探测 → 控制通道（含 RSA-1024 HUID 签名）→ MediaProjection H.264 → 按车机拉帧逐帧发送。接口契约见 `android/cast/README.md`；**合进壳工程**看 `android/cast/shell/README.md`（3 步） |

### 发布流程里要重放一次

`index.html` 是混淆构建产物，上游每次 release 都会整份覆盖，所以投屏入口是注入块：

```bash
node tools/patch-cast-entry.mjs           # 就地注入（幂等，重跑逐字节一致）
node tools/patch-cast-entry.mjs --check   # 只检查
```

改过 `sdk/vehicle-cast.js` 或 `tools/car-ui/cast-ui.*` 也要重跑一次。

### 能力边界

- **纯网页（GitHub Pages / 桌面浏览器直开）开不了投屏**：Wi-Fi Direct、MediaProjection、10920~10922
  监听都需要 App 的原生桥。页面会自动进入「只读模式」：能扫码解析、能看诊断，投屏按钮禁用；
- 投屏是**手机整屏外发**，通知和验证码也会上屏；协议里 `supportScreenTouch/supportHID` 都是 false，
  **没有反向触控**；
- 车机的「投屏」开关（`itemsV2` 的 `MOTOPLAY`）要处于「扫码投屏」，否则车机不出二维码；
- Android 侧未在本仓构建（`app/zeehowebnext.apk` 未改），需要按 `android/cast/shell/README.md`
  合进壳工程、用原 keystore 重签；真机验证清单见 `android/cast/README.md` §4；
- 仍需真机确认的点列在 `docs/CAST.md` §12。

### 测试

```bash
npm test          # node --test tests/*.test.mjs
```

## 添加车模

1. 把车模文件放进 `models/`，用车型命名；
2. 在 `index.json` 增加对应条目，填写文件名、字节数和版本号；
3. 单个文件必须小于 **20MB**（jsDelivr 对 GitHub 源文件的限制）；
4. 第三方模型请在 `note` 中注明作者、协议和原始链接。

提交推送后，jsDelivr 通常会在 15 分钟内刷新。
