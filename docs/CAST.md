# 极核车机投屏协议（逆向记录）

> 目标：让极核面板自己把手机画面投到车机，不再依赖第三方 App（Motexa）。
>
> 事实来源：`Motexa 2.7.0` 安装包的反编译结果（`com.motexaqixing.app.vehiclecast.*`，JADX 输出，
> 见文末「证据索引」）。Swift/Java 名称、行号均指反编译产物，不是原始源码。
>
> 适用车型：**具备投屏能力的极核车机**（Motexa 只列出 AE5 / AE5i 等兼容项）。
> 未在真机上验证的条目在文中标 `〔未验证〕`。

## 1. 角色与结论

| 角色 | 谁 | 做什么 |
| --- | --- | --- |
| 发送端 | 手机（本仓的面板 App / 网页 + 原生桥） | 摄像头扫码 → 加入车机 Wi-Fi Direct 组 → 监听 3 个端口 → 等车机连回来 → H.264 编码屏幕/画面 → 按车机“拉帧”请求逐帧发送 |
| 接收端 | 极核车机（Android，Amlogic S905L3AB 一类） | 显示投屏二维码；作为 Wi-Fi Direct **GO**（网关 `192.168.0.1`）开 `10930` 状态口；握手通过后主动连手机的 `10920/10921/10922` |
| 配对媒介 | 车机屏幕上的二维码 | 携带 SSID / 密码 / 车机 P2P MAC / 车型 / SN |

关键点：**视频不是手机推流，而是车机拉帧**（request/response），所以 NAT、缓冲、丢帧都由车机节奏决定；
协议本身不加密（控制帧只有一条 JSON+RSA 签名握手），只在同一 Wi-Fi Direct 组内可见。

## 2. 配对二维码

车机屏幕上显示的二维码，文本是 URL 或 `key=value` 串；解析规则（`d0.h` / `d0.g`）：

1. 文本 `trim()`，长度必须 `1..4096`，否则 `qr_input_invalid`；
2. 若含 `?`，只取 `?` 之后的部分；
3. 按 `[&;\n]` 切段，每段按第一个 `=` 切 `key/value`，key 与 value 都做 URL 解码（`+` → 空格），key 转小写；
4. 识别字段：

| 字段（别名） | 必填 | 约束 | 含义 |
| --- | --- | --- | --- |
| `v` / `version` / `ver` | 否（缺省 `1`） | 必须能被 `Integer.parseInt` 解析，且 **只支持 1** | 二维码版本 |
| `action` / `mode` | 是 | 必须等于 `9` | 连接模式（`9` = Wi-Fi Direct 投屏） |
| `modelid` / `model_id` / `channel` | 是 | `^[A-Za-z0-9_.-]{1,64}$` | 车型/渠道 |
| `ssid` | 是 | 长度 `1..32`，不含 `\0` | 车机 P2P 组 SSID |
| `pwd` / `password` | 是 | 长度 `8..63` | 组口令 |
| `mac` / `p2pmac` / `p2p_mac` | 是 | `xx:xx:xx:xx:xx:xx`（`-` 自动换 `:`，转大写） | 车机 Wi-Fi Direct 设备地址 |
| `sn` / `serial` | 否 | 长度 ≤ 128 | 车机 SN |

示例（字段值为占位）：

```
https://cast.example/?v=1&action=9&modelid=AE5I&ssid=ZEEHO-CAST-XXXX&pwd=XXXXXXXX&mac=AA:BB:CC:DD:EE:FF&sn=TESTVIN0000000001
```

UI 上 `mac` / `ssid` / `sn` 需要打码展示（Motexa 只露 MAC 后 5 位：`**:**:**:<末5>`）。

## 3. 组网（Wi-Fi Direct）与三路连接

流程（`r1.l0` / `r1.r1` / `r1.d1` / `r1.k1`）：

1. 申请权限：Android 13+ `NEARBY_WIFI_DEVICES`，13 以下 `ACCESS_FINE_LOCATION`；
   前置条件：`PackageManager.hasSystemFeature("android.hardware.wifi.direct")` 且 Wi-Fi 已开启，
   若手机同时开了热点则先阻塞（`hotspot_active`）。
2. `WifiP2pManager.initialize` → `discoverPeers` → `connect`（`WifiP2pConfig.deviceAddress = mac`）。
3. 等 `WIFI_P2P_CONNECTION_CHANGED`，拿到 `WifiP2pInfo`：`groupFormed`、`groupOwnerAddress`。
   车机是 GO，地址一般 `192.168.0.1`（代码里的兜底常量）。
4. 组形成后，手机在 **10920 / 10921 / 10922** 三个端口 `ServerSocket` 监听，三个都必须起来；
   只接受 **源 IP == GO 地址** 的连接（`r1.G0`），其它一律关掉。
5. 手机主动连 `GO:10930` 做状态探测（§4），通过后车机会来连上面三个端口。

### 3.1 三路 socket 的分工与超时

| 端口 | 方向 | 谁发起 | 帧格式 | SO_TIMEOUT |
| --- | --- | --- | --- | --- |
| 10922 | 车机 → 手机 | 车机连手机 | 控制帧（16B 头 + JSON） | **0（不超时）** |
| 10921 | 车机 → 手机 | 车机连手机 | 媒体帧（8B 头） | 15000 ms |
| 10920 | 车机 → 手机 | 车机连手机 | 车机发媒体帧（`114`），手机回裸包 | 推流前 15000 ms，推流开始后 **2000 ms** |

> 10922 加读超时会莫名 `socket-ended`；10920 不加 2 s 会在车机卡顿时把线程挂死。
> 10920 **只服务最新一条连接**：新连接进来会关掉旧的，旧连接上收到的 `114` 不回任何字节。

## 4. 握手与状态探测

手机主动连 `车机IP:10930`，发一条控制帧：`type = 0x70000010`，body：

```json
{"phoneType":"Android","packageName":"<本 App 包名>"}
```

- 连接超时 4 s、读超时 4 s；
- 应答必须 `type == 0x70000011` **且** body 的 `status` 为 true，才算车机在线；
- 连上了但状态不对 → `vehicle_status_rejected`；连接/IO 异常 → `vehicle_status_unavailable`；
- 10930 这条连接是短连接，确认后立刻关闭。

## 5. 帧格式

所有整数都是 **小端**。

### 5.1 控制帧（端口 10922，以及 10930 状态口）

固定 16 字节头 + body（UTF-8 JSON 或空）：

| 偏移 | 类型 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | int32 | `type` | 消息号，见 §6 |
| 4 | uint32 | `length` | **总长**（16 + body 长度），合法区间 `16..262144` |
| 8 | int32 | `checksum` | `length ^ type` |
| 12 | int32 | `code` | 保留，恒为 0 |
| 16 | bytes | `body` | UTF-8 JSON（可为空） |

校验失败（`checksum` 不符 / `length` 越界）→ 直接断链。

### 5.2 媒体帧（端口 10921，也用于 10920 上的请求）

8 字节头 + body：

| 偏移 | 类型 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | uint16 | `command` | 见 §7 |
| 2 | uint16 | `bodyLength` | body 字节数（≤ 65535） |
| 4 | int32 | `serial` | 请求序号；回复必须**原样回填** |
| 8 | bytes | `body` | |

### 5.3 数据帧（端口 10920）

**请求**是标准媒体帧：`command = 114`、`bodyLength = 0`、`serial = 车机给的序号`。
它**不是**裸包，先按 §5.2 解 8 字节头再判断。

**应答**才是裸包：

```
[int32 length][仅 extended 且有帧时：int32 frameIndex][H.264 Annex-B 帧]
```

- `length` = **length 字段之后的全部字节数**：传统模式等于帧长；extended 模式等于「帧长 + 4」；
- 没有新帧时固定写 **4 字节 0**（`int32 0`），extended 模式也**不带** frameIndex；
- 单帧长度没有 65535 上限，`int32` 直接给；
- `frameIndex` 从 0 开始，**每次重连/降档重试后归零**。

## 6. 控制消息表（端口 10922）

车机发起，手机应答（`u.b`）：

| 车机 → 手机 | 手机 → 车机 | body | 说明 |
| --- | --- | --- | --- |
| `0x70000000` | `0x70000001` | 空 | 通用 ping |
| `0x00010000` | `0x00010001` | 空 | 通用 ack |
| `0x000106D0` | `0x000106D1` | 空 | 通用 ack |
| `0x00020000` | `0x00020001` | 空 | 通用 ack |
| `0x00010010` | `0x00010011` | 见下 | 客户端信息（HUID 签名） |
| `0x000103E0` | 先 `0x000103E1`（空）再 `0x000201C0`（JSON） | 见下 | SN 校验 |
| `0x00010780` | `0x00010781` | 空 | running-log |
| `0x00030020` | 无 | 空 | start-signal（单向下发） |
| `0x80000010` | 无 | — | 不支持的命令，记录并忽略 |

### 6.1 `0x00010010` 客户端信息

收到 body：`{"HUID":"...", ...}`（HUID 为空 → 直接断链）。

回包 `0x00010011`（UTF-8 JSON）：

```json
{
  "pxcVersion": "1.0.2",
  "phoneUUID": "<随机 UUID>",
  "phoneBrand": "<Build.BRAND>",
  "phoneModel": "<Build.MODEL>",
  "phoneOsVersion": "<Build.VERSION.RELEASE>",
  "phoneOs": "Android",
  "package": "<包名>",
  "packageName": "<包名>",
  "versionCode": 1,
  "token": 0,
  "pubkey": "<RSA-1024 公钥 DER，Base64>",
  "encryptedHUID": "<HUID 用同一密钥对做 RSA/ECB/PKCS1Padding“私钥加密”，Base64>",
  "bluetoothName": "Motexa VehicleCast",
  "supportH264IFrame": true,
  "supportScreenTouch": false,
  "supportHID": false,
  "supportFunction": 0,
  "appVersionFingerPrint": "<Build.FINGERPRINT>"
}
```

要点：

- 密钥对 **RSA 1024**，`Cipher.ENCRYPT_MODE` + **私钥**（等价于签名），车机用回传的 `pubkey` 校验；
- UUID 与密钥对都挂在「客户端信息应答器」实例上：**每次重试/降档会重建实例**，所以 UUID 和密钥对会变
  （对车机无影响，重连本来就是新会话）；
- `bluetoothName` 是品牌串，可以换成自己的；
- 触摸/HID 能力这里报 `false`，所以本协议**没有反向触控**。

### 6.2 `0x000103E0` SN 校验

收到 body：`{"sn":"...", "client_set":"vehicle_cast"}`（`client_set` 缺省 `vehicle_cast`）。

应答分两包：

1. `0x000103E1`，空 body；
2. `0x000201C0`，JSON：`{"isOk":true,"errCode":0,"errMsg":"","id":"<sn>","sn":"<sn>","client_set":"<client_set>"}`。

## 7. 媒体命令表（端口 10921）

车机发起，手机应答，`serial` 原样回填（`r1.Q0`）：

| 命令 | 方向 | body | 说明 |
| --- | --- | --- | --- |
| `16` | 车机 → 手机 | 采集配置，≥30 字节 | 见 §7.1 |
| `17` | 手机 → 车机 | 9 字节 | 编码器确认，见 §7.2 |
| `48` | 车机 → 手机 | 空 | 心跳 |
| `49` | 手机 → 车机 | 8 字节：`int32 5002, int32 1` | 心跳应答（常量） |
| `64` | 车机 → 手机 | 空 | 心跳 |
| `65` | 手机 → 车机 | 空 | 心跳应答 |
| `96` | 车机 → 手机 | 空 | 查询视图配置 |
| `97` | 手机 → 车机 | JSON：`{"viewAreaConfig":{"state":0},"supportFunction":0}` | 应答 |
| `112` | 车机 → 手机 | 空 | **开始投屏**：手机起编码器后回 113 |
| `113` | 手机 → 车机 | 空 | 已就绪 |
| `114` | 车机 → 手机 | 空 | **拉一帧**（只在 10920 上） |

### 7.1 采集配置（命令 16）

小端结构（`w.a`，长度必须 ≥ 30）：

| 偏移 | 类型 | 字段 | 备注 |
| --- | --- | --- | --- |
| 0 | uint16 | `width` | ≥ 16 |
| 2 | uint16 | `height` | ≥ 16 |
| 4 | int32 | `fps` | **0 表示由手机按档位决定**；其他值原样使用 |
| 8 | int32 | `encoder` | `0` 归一成 `2`（H.264）；**其他值原样透传、不校验、不影响编码** |
| 12 | int32 | 保留 | 未使用 |
| 16 | int32 | 保留 | 未使用 |
| 20 | int32 | `bitrate` | bps，缺省 2097152 |
| 24 | int32 | 保留 | 未使用 |
| 28 | uint8 | 保留 | 未使用 |
| 29 | uint8 | `extended` | ≠0 表示车机支持 extended 数据帧 |

**兜底**：如果车机没发 16 就发 112，用 `1024×600, fps 0, encoder 2, bitrate 2097152, extended false`（`r1.A0`）。

`fps = 0` 在各档的落点：performance/bitrate-safe 30、compatibility 24、balanced 20、
resolution-safe 20、decoder-safe 15。

### 7.2 编码器确认（命令 17）

9 字节小端：`int32 codec, uint16 width, uint16 height, uint8 extendedAck`。

- `codec` = 采集配置里回填的编码器号，**缺省/取值 2 = H.264**（`p.b.f7894f` 就是 `p.c.f7902e`，
  也就是命令 16 偏移 8 的 `encoder`；`w.b(encoder, width, height, flag)`）。不是 level，别发 512；
- `width/height` 是**实际**采用的编码尺寸（16 对齐、可能被档位降分辨率）；
- `extendedAck` = App 侧设置 `extended` **且** 采集配置 offset 29 ≠ 0；只有它为 1，数据帧才带 `frameIndex`。

### 7.3 连接诊断（connection test）分支

不申请录屏权限、不启动编码器，只验证「组网 + 三端口 + 车机会来拉帧」这条链路：

1. 进入诊断：`testConnection` → 标记 `connectionTest`；
2. 收到 `112`：**只回 `113`**，不配置编码器、不请求 MediaProjection；
3. 收到第一个 `114`：写 4 字节 0 并结束诊断（成功）；
4. 35 s 内没收到 `114` → `frame_pull_timeout`（失败）。

## 8. 编码与档位

Motexa 的编码器（`h2.o` / `h2.s`）：

- `MediaFormat.createVideoFormat("video/avc", w, h)`；
- `color-format = 2130708361`（`COLOR_FormatSurface`），输入面来自 `MediaProjection` 的 `VirtualDisplay`；
- `bitrate`、`frame-rate`、`i-frame-interval = 1`；
- **第一次 configure 一定带** `profile = 1`（Baseline）+ `level`；configure 抛异常才 `reset()`
  并按 `codec-default` 重配（不带 profile/level）；
- 另一组「高性能键」（`bitrate-mode = 2` CBR、`priority = 0`、`operating-rate = fps`、
  API 26+ `latency = 1`、API 29+ `max-fps-to-encoder` + `prepend-sps-pps-to-idr-frames`）
  只在**发送端本机** `Build.MODEL/DEVICE/PRODUCT/BOARD/HARDWARE` 命中 `s905l3a` 时加（`v.d()`），
  与档位无关，也和「车机是不是 S905L3AB」无关；
- 每帧处理：关键帧判定 `(bufferInfo.flags & 1) != 0 || 含 NAL 5`；若已有 IDR 但同时缺 SPS/PPS，
  才把 `csd-0 + csd-1` 拼到帧前；若缺 AUD（NAL 9），**每帧**前置 `00 00 00 01 09 10`；
- 队列拥塞清队列或丢帧后，用 `request-sync` 请求关键帧（节流 300 ms）。

尺寸规则：宽高都按 16 对齐（`max(16, v & -16)`）。

档位表（`p.e`，依次降级；默认 `performance`）：

| 档位 | 分辨率 | fps | 码率 | level |
| --- | --- | --- | --- | --- |
| `performance` | 请求值 | 30 | 发送端是 S905L3AB → `min(2 MiB, 请求值)`；否则固定 **2621440** | 3.1（写死 512） |
| `bitrate-safe` | 请求值 | 30 | `min(2 MiB, 请求值)`（请求值不在 256 k~8 M 时用 2 MiB） | 3.1（写死 512） |
| `compatibility` | 请求值 | `fps>=15 ? min(24, fps) : 24` | `min(2 MiB, 请求值)` | 按宏块 / 宏块×fps 查表 |
| `balanced` | 请求值 | 20 | 1572864 | 3.1（写死 512） |
| `resolution-safe` | 等比缩到 ≤ 800×480 且宏块 ≤ 1620 | 20 | 1572864 | 3.0（写死 256） |
| `decoder-safe` | 等比缩到 ≤ 800×480 且宏块 ≤ 1620 | 15 | 1048576 | 3.0（写死 256） |

`compatibility` 档的 level 查表（`p.d`）：命中条件
`macroblocks <= 宏块上限 && macroblocks * fps <= 像素上限`，全不命中取最后一档：

| level id | 名字 | 宏块上限 | 宏块×fps 上限 |
| --- | --- | --- | --- |
| 1 | 1.0 | 99 | 1485 |
| 4 | 1.1 | 396 | 3000 |
| 8 | 1.2 | 396 | 6000 |
| 16 | 1.3 | 396 | 11880 |
| 64 | 2.1 | 792 | 19800 |
| 128 | 2.2 | 1620 | 20250 |
| 256 | 3.0 | 1620 | 40500 |
| 512 | 3.1 | 3600 | 108000 |
| 1024 | 3.2 | 5120 | 216000 |
| 2048 | 4.0 | 8192 | 245760 |
| 8192 | 4.2 | 8704 | 522240 |
| 16384 | 5.0 | 22080 | 589824 |
| 32768 | 5.1 | 36864 | 983040 |
| 65536 | 5.2 | 36864 | 2073600 |

> 这些 id 是 Motexa 自己定的档位号，直接塞进 `MediaFormat("level")`；
> Android 常见写法是 level_idc（3.1 → 31），这里照抄 Motexa 取值，`〔未验证〕` 是否被具体 codec 接受。

### 8.1 自动降级 vs 档位学习（两件事，别混）

| | 触发 | 条件 | 效果 |
| --- | --- | --- | --- |
| 降级 | 媒体连接提前断开 | **15 s 内**失败且 `sentFrames > 0`，还有下一档，重试次数 < **5** | 换下一档重连（phase `adapting`） |
| 学习 | 会话正常跑完 | 已跑满 **15 s** 且 ≥ **150 帧** | 记为这台车的首选档位（`compatibility-learned`），不改变当前会话 |

重试次数上限 `p.f7884a = 6 - 1 = 5`。

## 9. 会话状态机

Motexa 的 phase 名字（`r1.r2`）：

| phase | 何时出现 |
| --- | --- |
| `idle` | 初始 / 取消后 |
| `saved` | 保存了扫码 profile |
| `connecting` | 起监听之前 |
| `provisioning` | Wi-Fi Direct 组网中 |
| `hotspot-monitor` | 诊断对象里的热点监控态 |
| `handshaking` | 三路 socket 就绪 + 10930 探测通过，等车机握手 |
| `waiting-projection` | 缺 MediaProjection 授权 |
| `ready` | **只在连接诊断成功时**出现（不是正常投屏路径） |
| `casting` | 收到 112 并起编码器 |
| `adapting` | 降档重连中 |
| `stopped` | 用户停止（**没有 `stopping` 这个名字**） |
| `failed` | 失败 |

**停止不发任何协议报文**：只关监听/连接、停编码器；车机从数据流中断感知。

本仓 `sdk/vehicle-cast.js` 的词表更小（`idle/provisioning/handshaking/ready/casting/adapting/stopping/failed/unavailable`），
原生桥可以直接用这套；从旧实现或日志里读到别的名字时用 `normalizeCastPhase()` 归一
（`connecting/hotspot-monitor/waiting-projection → provisioning`，`stopped/saved → idle`）。

## 10. 安全与限制

- **无鉴权、无加密**：一次性二维码 `ssid/pwd` 就是准入券；握手只证明“手机持有 HUID 的签名”，
  不防同组内嗅探。H.264 裸流可被同组设备抓到。
- RSA 1024 + PKCS#1 v1.5 仅用于兼容车机固件，**不要**当作安全机制。
- 触摸/HID 能力声明为 `false`，投上去只能看，不能反向操作车机。
- 屏幕内容会整屏外发（通知、验证码），UI 上必须给明确提示。
- 二维码里带 Wi-Fi 口令，属于一次性凭证：日志/诊断导出里必须打码（本仓 `redactDiagnostic`）。

## 11. 本仓实现映射

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 协议模型（纯 JS，可测） | `sdk/vehicle-cast.js` | 二维码解析、帧编解码、采集配置解析、档位规划、状态机、诊断打码、原生桥适配 |
| 页面 UI | `tools/car-ui/api-car-ui.{html,js,css}` | 「车机投屏」卡片与弹层：扫码 / 连接 / 开始 / 停止 / 画质 / 诊断 |
| 注入 | `tools/patch-panel-entry.mjs` | 把 `sdk/vehicle-cast.js` 内联进面板 `index.html` |
| 原生参考实现 | `android/cast/*.java` | Wi-Fi Direct + 三路 socket + 控制/媒体/数据通道 + MediaProjection H.264 |
| 测试 | `tests/vehicle-cast.test.mjs` | 二维码、帧格式、档位、状态机、打码 |

JS ↔ 原生桥契约见 `android/cast/README.md`（`window.ZeehoCast`）。

## 12. 未验证项（真机才能确认）

1. 车机是否接受 `bluetoothName` / `pxcVersion` 之外的实现（Motexa 是本协议唯一已知客户端）；
2. `0x00030020` start-signal 与 `112` 的先后顺序是否固定（Motexa 两种都收到，未区分处理）；
3. 不同车机固件的采集配置默认值（宽高/fps/码率）——Motexa 的兜底值是 1024×600 / fps 0 / 2 MiB；
4. AE5 / AE5i 之外车型是否使用同一套协议；
5. `MediaFormat("level")` 传 Motexa 的档位号（512/256）而非 level_idc 时，各厂商编码器的实际行为；
6. 车机侧对 `request-sync` 关键帧请求的响应延迟（影响弱网首帧时间）。

## 13. 证据索引

反编译目录：`_ref/motexa270-src/sources/com/motexaqixing/app/vehiclecast/`

| 主题 | 类 |
| --- | --- |
| 二维码解析 | `d0.java` |
| 帧格式 | `c0.java` |
| 单端口循环 / 状态 | `r1.java`（`Q0` 媒体、`N0` 控制、`R0` 数据、`n2` 状态探测、`k1` 监听、`d1` 起监听） |
| 控制通道应答 | `u.java` |
| 采集配置 / 档位 | `w.java`、`p.java`、`r1.g`（`p.b.f7894f`=编码器号、`f7896h`=level id） |
| 编码器 | `h2.java`（`o` 组 MediaFormat、`s` 取帧） |
| 屏幕采集服务 | `VehicleCastProjectionService.java` |
| 插件 API / 选项 | `NativeVehicleCastPlugin.java`（`startSession` 选项：`profileId`、`captureSource`、`initialCompatibilityTier`） |
| 扫码器 | `z0.java` |
| 通道超时 / 连接只服务最新一条 | `r1.k1`、`r1.c1`、`r1.R0` |
| 连接诊断分支 | `r1.g2`/`r1.h2`、`r1.Q0` 的 112/114 分支、`r1.g1` |
