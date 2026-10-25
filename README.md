# zeeho-3d-models

极核（Zeeho）车型的 **3D 车模库**：收集各车型的 `.glb` 车模与配套静态图，
经 jsDelivr / GitHub Pages 分发，供客户端按清单加载使用。

## 内容

```
models/                 .glb 车模文件
index.json              车模清单，客户端按清单读取列表和文件地址
art/                    车辆静态图
icons/                  图标资源
```

## 添加车模

1. 把车模文件放进 `models/`，用车型命名；
2. 在 `index.json` 增加对应条目，填写文件名、字节数和版本号；
3. 单个文件必须小于 **20MB**（jsDelivr 对 GitHub 源文件的限制）；
4. 第三方模型请在 `note` 中注明作者、协议和原始链接。

提交推送后，jsDelivr 通常会在 15 分钟内刷新。

## 车辆工具（面板页 + 接口调试台）

三块功能（固件版本 / 车辆设置 / 车机壁纸）同时长在两个已有页面里，**不新增页面**：

- **面板 `index.html`**（分开路由）：`车辆设置` 页底部「车机壁纸」→ `#car-wallpaper`；
  `我的 → 固件版本` 卡片右上角「OTA 升级」→ `#car-ota`；
- **调试台 `api.html`**：车况概览下方三块卡片。

- 固件版本：仪表/App 版本、升级状态、`fileInfo` 固件包（地址打码）；「检查更新」在卡片右上角；
- 车辆设置：服务端 `itemsV2` 的 9 行 14 磁贴（+「其他设置项」兜底），开关/下拉/滑杆写入
  `vehicleSet/addVehicleSet`，写前二次确认；
- 车机壁纸：整屏壁纸 + 中间速度卡**两步**（官方 `BigWallPaperEditActivity` / `SmallWallPaperEditActivity`），
  详情分块预览，新建向导两步各传一张（`wallpaper/save` 两次 multipart，`type=1/2`），支持应用/删除/改名。

实现：`tools/car-ui/` 共用源码，`tools/patch-api-car-ui.mjs` 注入 `api.html`，
`tools/patch-panel-entry.mjs` 注入 `index.html`（卡片平铺注入、运行时搬进 sheet）；
数据模型在注入时内联成 `__carModels`，产物仍是自包含单文件；面板侧由 `panel-host.js`
借 `window.ZEEHO_SIGN` 补一层请求。上游重建产物后重跑对应脚本即可（幂等）。

离线测试：`npm test`（`node --test tests/*.test.mjs`）。
