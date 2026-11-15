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

车型与官方立绘/车模的对应关系靠用户问卷收集（vehicleType / vehicleTypeName / vehiclePicUrl / vehiclePic3dUrl）。
**官方车模与立绘统一按 `车型-整车型号[-配色]` 命名**，例如 `AE5i-Pro-TDQ0201Z-silver`：
空格转连字符（URL 里不用编码），配色只在同型号有多个配色时加，颜色是看图识别的、不是官方配色名。
文件名只用字母数字和 `. _ -`，前端清单加载器会拒掉其它字符。

## 添加车模

1. 车模放进 `models/pic3d/`，静态立绘放进 `models/pic/`，按上面的命名规则取名；
2. 在 `pic3d.json` / `pic.json` 增加对应条目，填写文件名、字节数和版本号；官方源链接放 `source`，仓库副本为回退；
3. 单个文件必须小于 **20MB**（jsDelivr 对 GitHub 源文件的限制，超限仅 GitHub Pages / 代理线路可用）；
4. 立绘先裁掉全透明边再入库（面板按 `object-fit: contain` 缩放，留白会白白缩小车体）；
5. 第三方模型的作者和许可证写入本 README「第三方模型致谢」，`note` 只描述模型。

## 第三方模型致谢

- 咕咕嘎嘎（企鹅装）：Sketchfab「Gugugaga」，planktonfun，CC-BY-4.0。
- DeepSeek 玩偶（女仆装）：Sketchfab「Deepseek Fumo」，xunbile，CC-BY-4.0。
- 巫女立绘（半身）：图像转 3D 角色模型。


提交推送后，jsDelivr 通常会在 15 分钟内刷新。
