# zeeho-3d-models

Zeeho Unlocker 的 3D 车模库：存放从极核 App 抓取的官方车模（glb），以及自制/改造的
模型，供模块内「编辑 3D 车模 → 高级 → 从车模库选择」下载使用——让没有下发 3D 模型的
车型（如 AE5i Pro）也能挂上别的车型的 3D 车模。

## 结构

```
models/<车型名>.glb     车模文件
index.json              清单：模块按它拉列表
```

**两类文件**：官方原包（从设备 `Android/data/com.cfmoto/files/models/<md5(url)>.glb`
直接拷出，未改写）与改造包（自制或对原包做过处理）。改造包要在 `note` 里注明，
例如半身像模型是把原始 glb 裁掉下半身、按 App 相机朝向旋转、并把包围盒中心补白
到原点（App 会按包围盒自动缩放/居中，所以尺寸与偏心都靠改 accessor 的 min/max 控制）。

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
- 直连回退：`https://raw.githubusercontent.com/lswlc33/zeeho-3d-models/main/...`

## 上架新车模

1. 从装有该车型的设备上取 `Android/data/com.cfmoto/files/models/<md5(url)>.glb`
   （或模块日志里的 vehiclePic3dUrl 下载）；
2. 按车型命名放进 `models/`；
3. 在 `index.json` 加一条（size 用实际字节数）；
4. 提交推送，jsDelivr 15 分钟内刷新。
