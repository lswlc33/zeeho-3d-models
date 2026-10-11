# zeeho-3d-models

Zeeho Unlocker 的 3D 车模库：存放从极核 App 抓取的官方车模（glb），供模块内
「编辑 3D 车模 → 高级 → 从车模库选择」下载使用——让没有下发 3D 模型的车型
（如 AE5i Pro）也能挂上别的车型的 3D 车模。

## 结构

```
models/<车型名>.glb     车模文件（官方原包，未改写）
index.json              清单：模块按它拉列表
```

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
