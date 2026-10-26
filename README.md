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

## 面板页的车辆工具（车辆设置 / 车机壁纸 / OTA）

三块功能只挂在**面板 `index.html`**，调试台 `api.html` 保持上游原样。入口分开路由：

| 路由 | 入口 | 视图 |
| --- | --- | --- |
| `#car-wallpaper` | 「车辆设置」页底部一行「车机壁纸」 | 只显示壁纸卡片 |
| `#car-ota` | 「我的 → 固件版本」卡片右上角「OTA 升级」 | 只显示固件版本卡片 |

- 固件版本：仪表/App 版本、升级状态、`fileInfo` 固件包（地址打码）；「检查更新」在卡片右上角；
- 车辆设置：服务端 `itemsV2` 的 9 行 14 磁贴（+「其他设置项」兜底），开关/下拉/滑杆写入
  `vehicleSet/addVehicleSet`，写前二次确认；
- 车机壁纸：**先本地调位置、点保存才上传**（跟官方 `BigWallPaperEditActivity` /
  `SmallWallPaperEditActivity` 的顺序一致）：
  - 新建弹窗里是两个缩略图：整屏（横）与速度卡（竖长方形、居中，比例固定不给用户改）；
  - 点缩略图选图后直接进预览框，拖动 / 滚轮 / 双指缩放调整位置，双击复位；
  - 整屏预览只显示整屏图；速度卡预览用整屏图当背景、手势只作用于中间那块速度卡；
  - 全程不联网，只有「保存并上传」才按当前裁切导出两张图
    （`wallpaper/save` 两次 multipart，`type=1/2`）；
  - 裁切几何在 `sdk/vehicle-media.js`（`wallpaperFitTransform` / `wallpaperSpeedCardBox`），
    预览与导出共用同一份参数，`tests/wallpaper-fit.test.mjs` 固定「不出黑边 / 速度卡居中竖长」等不变量。

> **壁纸功能尚未完善**：速度卡的尺寸比例（`tools/car-ui/api-car-ui.js` 的 `CAR_SPEED_CARD_SPEC`）
> 目前是按面板比例估算的占位值，`wallpaper/save` 的 multipart 字段（`file / wallpaperName / vinNo /
> type / wallpaperContent`）与 `wallpaperContent` 的语义也没有真机验证过；已知在部分车型上可能
> 需要按实际仪表布局调整速度卡参数或字段。其余两块（车辆设置 / OTA）走的是已抓包验证过的接口。

实现：`tools/patch-panel-entry.mjs` 幂等注入 `index.html`（卡片与弹窗平铺注入、运行时再搬进 sheet），
UI 源码在 `tools/car-ui/`，数据模型在注入时内联成 `__carModels`；
面板侧请求由 `tools/car-ui/panel-host.js` 借 `window.ZEEHO_SIGN` 补一层。离线测试：`npm test`。
