# zeeho-3d-models

Zeeho Unlocker 的 3D 车模库：存放从极核 App 抓取的官方车模（glb），以及自制/改造的
模型，供模块内「编辑 3D 车模 → 高级 → 从车模库选择」下载使用——让没有下发 3D 模型的
车型（如 AE5i Pro）也能挂上别的车型的 3D 车模。

## 极核面板网页 / 几何面板 App

这个仓库的 GitHub Pages 根路径同时托管「极核面板」网页和它的安卓壳（App 显示名「几何面板」）：

- 网页：<https://lswlc33.github.io/zeeho-3d-models/>
- 安卓 App：<https://lswlc33.github.io/zeeho-3d-models/app/zeehowebnext.apk>
- App 工程：`app/`（纯系统 WebView 壳，APK 内不含网页静态资源；构建、签名、更新机制见
  [`app/README.md`](app/README.md)）

App 1.2 起在壳内做了网页做不到的事：未读消息/充电开始/充电进度/充满/充电停止通知、后台 60 秒同步
（15 分钟 JobScheduler 兜底）、2×2 / 4×2 / 4×4 桌面小组件、App 专属的每日自动任务
（默认 09:00，普通浏览器任务面板不显示这一块），以及解锁/锁车/鸣笛/打开坐垫四个 App Shortcuts。
消息通知需要在网页「数据与调试」里填内部账号 ID（页面查消息本来就依赖它）。

1.3 的小组件补上了电压、车图、锁/坐垫/在线状态和浅/深色两套配色（不显示续航和定位）；
检测到桌面微件存在时，持续性的充电进度通知会自动隐藏，只保留「已充满 / 充电已停止」这类一次性事件。

1.4 增加 APK 自更新（`app/version.json`，本地 versionCode 低于远端时弹窗）与 App 专属电量历史
（最近 24 小时 / 3 天 / 7 天 / 1 个月，以及上一次充电/放电；本机 Keystore 加密 + 压缩存储）。
「数据与调试」显示软件版本和面板版本，账号 ID 与 token 一样只露前几位。

网页源码在主仓库 `tools/dashboard/`，发布时用 `build-static.mjs --obfuscate` 输出到本仓库根的
`index.html`、`manifest.webmanifest`、`sw.js`、`version.json`、`icons/`。`models/` 和 `index.json`
仍是车模库，两者互不影响。

## 结构

```
models/<车型名>.glb     车模文件
index.json              清单：模块按它拉列表
```

**三类文件**：

1. **官方原包**——从设备 `Android/data/com.cfmoto/files/models/<md5(url)>.glb` 直接拷出，未改写；
2. **改造包**——对官方原包做过处理（改色 / 换贴图 / 裁切）；
3. **第三方模型**——从 Sketchfab 等处拿来的角色模型，多数是 **CC-BY**：`note` 里必须署名
   （作者 + 协议 + 原始链接），否则不要收进这个库。

改造包与第三方模型都要在 `note` 里注明。App 会按「POSITION accessor 的 min/max 包围盒」
做 contain-fit 并把盒中心对准屏幕中心，所以**尺寸与偏心都靠改 min/max 控制**，
跟顶点实际多大无关——平移顶点对显示位置没有任何影响。

## 适配第三方模型（真机标定，K90 / 1156×2510）

App 的相机是固定的：方位角 35°、俯仰 20°、站在 +Z 一侧，且对 min/max 包围盒做
contain-fit。所以只需要定三件事（工具见主仓库 `tools/3d-model/`，附 README）：

| 参数 | 取值 | 说明 |
|---|---|---|
| `--rotY` | 正面 = -Z 的模型取 **-35** | 让正面正对镜头。判断正面用 `tools/3d-model/zbuf_render.py` 的 -Z/+Z 视图，但**结论要以真机截图为准**：PC 端预览的方位角符号与真机相反（同一次判断会差 180°） |
| `--fit` | **2.12** | 包围盒 X/Z 半宽 = 2.12 × 几何最大半尺寸（Y 不补白）；K90 上模型高约 500px，与库内其它模型观感一致 |
| `--bias` | **0.41 × 包围盒半宽** | App 把模型画在屏幕中心右侧约 0.17×屏宽处，用这个顶回正中（正 = 模型在屏幕上偏左） |

脚本会把节点世界矩阵烘进顶点（节点变换清空）、绕 Y 旋转、重新居中，再写 min/max。
上下位置用 `--yoff`（正 = 模型在屏幕上偏下）；K90 上模型底边落在 y≈1100 就不会挡
胎压行（胎压行在 y≈1207）。


## index.json 字段

| 字段 | 说明 |
|---|---|
| `ver` | 清单版本（结构变更时 +1） |
| `models[].id` | 稳定 id（英文/数字），模块缓存键的一部分 |
| `models[].name` | 展示名（列表里给用户看的） |
| `models[].file` | `models/` 下的文件名 |
| `models[].size` | 字节数（下载校验用） |
| `models[].ver` | 模型版本（更新模型时 +1，让客户端重新下载） |
| `models[].note` | 一句话备注 |

## 下载地址（模块内写死的基址）

- 列表：`https://cdn.jsdelivr.net/gh/lswlc33/zeeho-3d-models@main/index.json`
- 模型：`https://cdn.jsdelivr.net/gh/lswlc33/zeeho-3d-models@main/models/<file>`
- 模块目前**只走 jsDelivr**（代码里没有 raw 回退），所以上面的 20MB 限制是硬约束。

## 上架新车模

1. 从装有该车型的设备上取 `Android/data/com.cfmoto/files/models/<md5(url)>.glb`
   （或模块日志里的 vehiclePic3dUrl 下载）；
2. 按车型命名放进 `models/`；
3. 在 `index.json` 加一条（size 用实际字节数）；
4. **单个文件必须 < 20MB**——jsDelivr 对 GitHub 源文件是硬限制，超了直接返回
   403 `File size exceeded the configured limit of 20 MB`，用户点下载就是失败。
   超了先瘦身（贴图转 JPEG、减面）再传；
5. 提交推送，jsDelivr 15 分钟内刷新（`index.json` 也一样，改完立刻拉可能还是旧清单）。

## 真机手工替换缓存文件时的坑

目标 App 把「缓存文件比 URL 对应的文件**小**」当成下载没下完，会直接重新下载覆盖掉。
所以手工 `cp` 一个模型到 `files/models/<md5(版本化URL)>.glb` 调试时，只要它比母本
`files/models/<md5(URL)>.glb` 小，就会被静默覆盖（表现为模型不换、或变回官方车模），
日志里只有一行 `model3d: load3d invoked` 反复出现。调试时把文件撑到比母本大
（`tools/3d-model/pad_glb.py`）即可。

走模块的车模库流程没有这个问题：配方没命中任何材质时 GlbArt 是**逐字节原样输出**，
生成的版本化文件和母本一样大，App 的大小校验自然过。

