# zeeho-3d-models

极核（Zeeho）车型的 **3D 车模库**：收集各车型的 `.glb` 车模与配套静态立绘，
经 jsDelivr / GitHub Pages 分发，供客户端按清单加载使用。

## 内容

```
models/
  pic/                   车辆静态立绘（png/jpg/webp）
  pic3d/                 3D 车模（glb）
  pic.json               静态立绘清单，key 对应车型（vehicleType），file 相对 models/pic/
  pic3d.json             3D 车模清单，file 相对 models/pic3d/
index.json               旧版车模清单（兼容老客户端，file 已指向 pic3d/ 新路径）
art/                     按 VIN 命名的车辆静态图（面板首页专用，见 art/README.md）
icons/                   图标资源
```

车型与官方立绘/车模的对应关系（vehicleType → 文件命名）还在收集，
用户通过问卷提交 vehicleType / vehiclePicUrl / vehiclePic3dUrl 后再补齐命名。

## 添加车模

1. 车模放进 `models/pic3d/`，静态立绘放进 `models/pic/`，用车型命名；
2. 在 `pic3d.json` / `pic.json` 增加对应条目，填写文件名、字节数和版本号；
3. 单个文件必须小于 **20MB**（jsDelivr 对 GitHub 源文件的限制，超限仅 GitHub Pages / 代理线路可用）；
4. 第三方模型请在 `note` 中注明作者、协议和原始链接。

提交推送后，jsDelivr 通常会在 15 分钟内刷新。
