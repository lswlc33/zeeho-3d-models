# zeeho-3d-models

极核（Zeeho）车型的 **3D 车模库 + 极核面板网页产物与发布物**。

本仓库只放**静态资源与发布产物**，不含任何 Android 工程源码
（面板的网页源码与 Android 壳已随 2026-10-20 重构搬进私有工作区
`E:\AI_Program\zeeho-unlocker\dashboard\`）。

## 内容

```
index.html              极核面板网页（单文件自包含，由 dashboard/ 构建）
manifest.webmanifest    PWA 清单
sw.js                   service worker（断网可开）
version.json            网页构建号（页面靠它发现新版本）
vehicle-info.html       车辆信息页
api.html                极核接口调试台（App 3.0.6 接口目录 + 车况/OTA）
sdk/
  zeeho-api.js          零依赖接口 SDK（签名 / 客户端 / 便捷方法）
  endpoints.js          接口目录元数据（面板 39 项 + 3.0.6 补充，共 69 项）
tools/zeeho-cli.mjs     命令行：登录 / 车况 / OTA 固件 / 车模导入
docs/API-3.0.6.md       接口文档（签名、端点、错误码、坑位）
tests/api.test.mjs      签名与客户端离线测试
icons/                  PWA 图标
art/                    页面用到的静态图
models/                 .glb 车模文件
index.json              车模清单，客户端按清单读取列表和文件地址
app/
  zeehowebnext.apk      「几何面板」Android 壳（固定下载地址，勿改名）
  version.json          APK 自更新清单
```

## 发布流程

1. 在私有工作区 `dashboard/` 下构建网页：
   `node build-static.mjs E:/AI_Program/zeeho-unlocker/zeeho-3d-models/index.html --obfuscate`
   （公网产物必须带 `--obfuscate`，脚本会拒绝把明文密钥写进本仓库）；
2. 在 `dashboard/app-shell` 下构建 APK：
   `./gradlew :webnext:exportApk`，产物直接写到这里（`app/zeehowebnext.apk`）；
3. 改 `app/version.json` 的 `versionCode/versionName`，与网页产物一起提交推送。

固定地址（不要改）：

- 页面：<https://lswlc33.github.io/zeeho-3d-models/>
- APK：<https://lswlc33.github.io/zeeho-3d-models/app/zeehowebnext.apk>
- APK 清单：<https://lswlc33.github.io/zeeho-3d-models/app/version.json>

## 接口调试台（App 3.0.6）

<https://lswlc33.github.io/zeeho-3d-models/api.html>

这是一个纯静态的接口调试台，和面板共用同一套接口与登录态：

- **默认复用面板签名**：在隐藏 iframe 里加载 `index.html`，直接调用面板的
  `ZEEHO_SIGN` / `API.call`，面板模式不保存任何密钥；
- **登录态复用**：读取 `localStorage['zeeho.token']`。先在面板用手机号登录一次，
  回到调试台刷新即可；也可以手动粘贴 token；
- **接口目录**：面板来源的 39 个端点 + 从 App 3.0.6 补的 OTA / 版本 / 蓝牙能力码 / 壁纸主题等（共 69 个），
  按「登录 / 车辆 / 车机壁纸 / 车机主题 / 骑行 / OTA / 消息 / 我的 / 社区 / 车控」分组；
- **车况概览**：车型、SOC、续航、在线状态、OTA 版本、`vehiclePic3dUrl`；
- **投屏资料**：从 `vehicleHomePageV2` 的 `encryptInfo` 取 key/iv/encryptValue，默认打码；
- **OTA**：`checkVersion`、`ota/mmi/info/v2`（含 `fileUrl` + `sign`）、任务列表/记录/红点内容；
- **车机壁纸**：壁纸列表/数量/详情/适用车型；**车机主题**按车型判断，只有 `themeShopFlag=1` 才显示主题组（AE5i 当前为 0，不显示）；
- **自填凭据**：没有面板时，可在「设置」里填 App 3.0.6 的 appId/appSecret/Basic，
  或直接粘贴 token。appSecret 只放当前标签页的 sessionStorage（关页即失效），
  appId / token 存 localStorage；都不随仓库分发。

接口细节、签名算法、错误码与两个网关坑（UA / Content-Type）见
[docs/API-3.0.6.md](docs/API-3.0.6.md)。

### 命令行

`tools/zeeho-cli.mjs` 与页面共用 `sdk/zeeho-api.js`，适合服务器/CI：

```bash
# 登录（验证码会发到手机；也可加 --code 非交互）
node tools/zeeho-cli.mjs login --phone 10000000000

# 车况 / OTA
node tools/zeeho-cli.mjs vehicle --vin TESTVIN0000000001
node tools/zeeho-cli.mjs ota --vin TESTVIN0000000001
node tools/zeeho-cli.mjs wallpaper --vin TESTVIN0000000001
node tools/zeeho-cli.mjs watch --vin TESTVIN0000000001 --every 600 --hours 2

# 从 vehiclePic3dUrl 导入官方 3D 车模（自动更新 index.json）
node tools/zeeho-cli.mjs models --vin TESTVIN0000000001 --out models

# 任意接口（--json 默认打码 encryptInfo / location / wifi 密码 / OTA fileUrl；需要原值加 --show-secrets）
node tools/zeeho-cli.mjs call ota_info --query vinNo=TESTVIN0000000001 --json
```

凭据通过环境变量或**未跟踪**的 `config.local.json` 注入，两者都不要提交：

```bash
export ZEEHO_APP_ID=...
export ZEEHO_APP_SECRET=...
export ZEEHO_BASIC='Basic ...'
# 或者先 login，token 会写到 ~/.zeeho_token.json
```

### 本地运行与测试

页面用了 ES module，不能直接双击 `file://` 打开；在本目录起静态服务：

```bash
python3 -m http.server 8787
# 打开 http://127.0.0.1:8787/api.html
```

离线测试（签名 KAT / query 规范化 / 客户端行为）：

```bash
node --test tests/*.test.mjs
```

## Android 壳补丁

- `android/` 提供轻量 `ApiActivity` 和「接口调试台」快捷方式源码；集成后打开 `api.html`，不等待首页首次缓存。
- 调试台里的壁纸上传 `<input type="file">` 走系统图片选择器；`ApiActivity` 已实现 `onShowFileChooser`，不需要额外权限。
- 主题组按车型显示：`vehicleHomePageV2.themeShopFlag=1` 才出现；AE5i 当前为 0，只显示车机壁纸。
- 仓库里的 `app/zeehowebnext.apk` 仍是上游 1.5.43 原包，没有合入 Android 补丁；需要按 `android/README.md` 集成，并用原 `CN=Zeeho Unlocker` keystore 重签后发布。
- 网页功能更新仍不需要重装 APK；`api.html` 从同一 Pages 地址加载。

## 添加车模

1. 把车模文件放进 `models/`，用车型命名；
2. 在 `index.json` 增加对应条目，填写文件名、字节数和版本号；
3. 单个文件必须小于 **20MB**（jsDelivr 对 GitHub 源文件的限制）；
4. 第三方模型请在 `note` 中注明作者、协议和原始链接。

提交推送后，jsDelivr 通常会在 15 分钟内刷新。
