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

## 添加车模

1. 把车模文件放进 `models/`，用车型命名；
2. 在 `index.json` 增加对应条目，填写文件名、字节数和版本号；
3. 单个文件必须小于 **20MB**（jsDelivr 对 GitHub 源文件的限制）；
4. 第三方模型请在 `note` 中注明作者、协议和原始链接。

提交推送后，jsDelivr 通常会在 15 分钟内刷新。
