# 极核 App 3.0.6 接口文档

> **最后验证：2026-10-24**
>
> 适用对象：极核（ZEEHO）App 3.0.6 的 `com.cfmoto.network.service.*` 接口层，实测环境为生产网关。
> 读者：能看懂 HTTP / JSON 的开发者。
> 约定：`{VIN}` `{uid}` `{phone}` `{postId}` 是占位符；`{APPID}` `{APPSECRET}` `{BASIC}` 是凭据占位符，
> 本文不写任何真实凭据、token、手机号、VIN。凡标「未验证」的条目都是尚未在生产网关单独确认的行为。

## 1. 基础信息

### 1.1 网关与业务前缀

| 项 | 值 |
| --- | --- |
| 生产网关 | `https://tapi.zeehoev.com` |
| 车辆 / 骑行 / OTA / 车控 | `/v1.0/app/cfmotoserverapp/` |
| 登录 / 消息 / 我的 / 版本 | `/v1.0/mine/cfmotoservermine/` |
| 社区 | `/v1.0/social/cfmotoserversocial/` |
| 商城 | `/v1.0/mall/cfmotoservermall/` |

- 完整 URL = 网关 + 前缀 + 端点路径，例如 `https://tapi.zeehoev.com/v1.0/app/cfmotoserverapp/vehicle/list`。
- `mall` 前缀已在 SDK 登记（`PATHS.mall`），但本仓库未接入任何端点，因此**商城业务端点：未验证**。
- 下文表格的路径列用前缀缩写：`app:` / `mine:` / `social:`，分别对应上表三行；少数历史端点不带 `/v1.0/`
  前缀（如 `signin/info`），表格里给出完整路径。

### 1.2 凭据

| 凭据 | 用途 | 来源 |
| --- | --- | --- |
| appId | 签名串里的 `appId`、请求头 `appId` | App 3.0.6 `com.zeeho.constant.AppConfig`（`isTest=false`） |
| appSecret | 参与签名计算 | 同上 AppConfig；**从 App 获取，不随仓库分发** |
| Basic | 登录接口的 `Authorization` | 同上，登录专用固定值；**不随仓库分发** |
| access_token | 业务接口 `Authorization: Bearer` | 登录响应 `data.tokenInfo.access_token` |

- appId 是固定常量，不等于会话令牌，但为保持文档可公开，本文统一写 `{APPID}`；appSecret / Basic 属于密钥，
  任何场景都不要写进仓库、页面产物或 Issue。

### 1.3 本仓库的四个组成部分

| 文件 | 角色 |
| --- | --- |
| `sdk/zeeho-api.js` | **零依赖 SDK**：纯 JS SHA-1 / MD5、签名、`ZeehoClient`、便捷方法与 `pickVehicleSummary`。浏览器与 Node 通用，不依赖 DOM |
| `sdk/endpoints.js` | 接口目录元数据（面板来源 39 项，其中 36 项有 panelId；另含 3.0.6 补充端点，共 69 项），调试台表单与 CLI 共用 |
| `api.html` | **调试台**：分组端点列表、参数表单、JSON 响应、车况 / OTA / 壁纸主题概览 |
| `tools/zeeho-cli.mjs` | **CLI**：`login` / `vehicle` / `ota` / `watch` / `models` / `call` |

## 2. 签名算法

签名逆向自 App 3.0.6 的请求拦截器（`RequestSignInterceptor`），并已对生产网关验证。

### 2.1 公式

```text
param = "appId=<APPID>&nonce=<16位随机+毫秒>&timestamp=<毫秒>"
GET   pre = scheme://host + path (+ "?" + 规范化 query)
POST  pre = 规范化 query + body（不含 scheme/host/path）
sign  = md5( sha1( pre + param + APPSECRET ) 的十六进制小写串 )
```

三个必须记住的点：

1. **两层哈希**：先 SHA-1，取小写十六进制串（40 字符），再对「该串 + 后续拼接内容」做 MD5，再取小写十六进制（32 字符）。
2. **GET 与写请求的 preimage 不同**：GET 的 `pre` 含 `scheme://host/path`；POST / PUT / DELETE 的 `pre`
   **只有**规范化 query 与 body，**不含** scheme / host / path。
3. `body` 必须是实际发送的字节（`JSON.stringify` 的结果），签名与发送必须完全一致，缩进/空格差异都会导致验签失败。

### 2.2 规范化 query

- 解析成 `(key, value)` 键值对；
- 按 `(key, value)` 二元组排序（先比 key，再比 value，`<` 比较，即字典序）；
- value 用 RFC 3986 编码（unreserved = `A-Za-z0-9-._~`），其余按 UTF-8 百分号编码；
- **空值保留**（`empty=` 也要出现在签名串里）；
- 重复键保留为多组，例如 `a=1&a=2`。

| 原始 query | 规范化结果 |
| --- | --- |
| `b=2&a=1&empty=&sp=a%20b&cn=%E4%B8%AD%E6%96%87` | `a=1&b=2&cn=%E4%B8%AD%E6%96%87&empty=&sp=a%20b` |
| `a=2&a=1` | `a=1&a=2` |

注意：签名始终基于**重新规范化**后的 query，而不是调用方拼出来的原始字符串。

### 2.3 请求头

| 头 | 值 | 说明 |
| --- | --- | --- |
| `appId` | `{APPID}` | 与签名串中的 appId 一致 |
| `nonce` | 16 位随机串 + 毫秒时间戳 | 见 2.4 |
| `timestamp` | 毫秒时间戳（字符串） | 与 `param` 中一致 |
| `signature` | 32 位小写十六进制 | 签名结果 |
| `Cfmoto-X-Param` | `appId=...&nonce=...&timestamp=...` | 与 `param` 完全相同 |
| `Cfmoto-X-Sign` | 同上签名 | 与 `signature` 相同 |
| `Cfmoto-X-Sign-Type` | `0` | 固定值 |
| `Authorization` | `Bearer <access_token>` | 登录接口换成 `Basic {BASIC}` |
| `Content-Type` | `application/json`（SDK）或 `application/json;charset=UTF-8`（面板签名） | 面板签名请求见 3.2，**不要简写** |
| `User-Agent` | `okhttp/4.9.2` | 见 3.1，Node / CLI 必须显式设置 |

- 面板产物在请求里还带 `interfaceversion: 2`、`Accept-Language: zh-CN`（见 `api.html` 的 `panelHeaders`）；
  是否必需**未验证**，带上不影响。HTTP 头大小写不敏感：SDK 发 `Cfmoto-X-Sign`、面板模式发 `cfmoto-x-sign`，都能通。

### 2.4 nonce 与 timestamp

- `nonce` = **16 位随机字母数字 + 毫秒时间戳**，合计 29 字符；实现见 `randomNonce()`（字母表 `A-Za-z0-9`，
  优先 `crypto.getRandomValues`），拼接：`` `${randomNonce()}${Date.now()}` ``。
- 部分网关接口会校验 nonce 长度/结构，别把时间戳省掉；`timestamp` 与 nonce 尾部是同一毫秒值。

### 2.5 为什么必须自带 SHA-1 / MD5

浏览器的 WebCrypto（`crypto.subtle`）**不提供 MD5**，而极核网关的签名外层恰好是 MD5。因此
`sdk/zeeho-api.js` 用纯 JS 实现 `sha1Hex` / `md5Hex`（UTF-8 输入、返回小写十六进制），浏览器里可直接用。

### 2.6 可复现的签名向量（KAT）

下面这组向量用**虚构凭据**即可独立复现（`tests/api.test.mjs` 用自己的 fixture 断言同一套算法，可交叉核对）：

```text
appId     = test-app-id（虚构）
appSecret = test-secret
nonce     = ABCDEFGHIJKLMNOP
timestamp = 1792770000000
param     = appId=test-app-id&nonce=ABCDEFGHIJKLMNOP&timestamp=1792770000000
```

| 方法 | URL | body | 期望 sign |
| --- | --- | --- | --- |
| GET | `https://tapi.zeehoev.com/v1.0/app/cfmotoserverapp/vehicle/list` | — | `d7a31b4e438521b96186d6d67e99c85b` |
| GET | `.../v1.0/app/cfmotoserverapp/ota/mmi/info/v2?vinNo=TESTVIN0000000001` | — | `6c3fdd757be5e2e7b67c6563cfca6534` |
| POST | `.../v1.0/mine/cfmotoservermine/user/loginByPhone` | `{"phone":"10000000000","authCode":"123456"}` | `2e8d503f39ae095fbe06c64f7d33c5ac` |

三行都用虚构测试值（`TESTVIN0000000001` / `10000000000`），可原样复现；换成真实 VIN 或手机号签名必然不同。

### 2.7 最小实现

```js
import { buildHeaders, ZeehoClient } from './sdk/zeeho-api.js';

// 方式一：自己造头（接自己的 fetch 封装时用）
const headers = buildHeaders({
  url: 'https://tapi.zeehoev.com/v1.0/app/cfmotoserverapp/vehicle/list',
  method: 'GET', body: '',
  appId: process.env.ZEEHO_APP_ID,
  appSecret: process.env.ZEEHO_APP_SECRET,
  token: process.env.ZEEHO_TOKEN,
});

// 方式二：直接用客户端（URL 拼接、query 规范化、Bearer/Basic、超时都在里面）
const client = new ZeehoClient({
  appId: process.env.ZEEHO_APP_ID, appSecret: process.env.ZEEHO_APP_SECRET,
  basic: process.env.ZEEHO_BASIC, token: process.env.ZEEHO_TOKEN,
});
const result = await client.vehicleList();
```

`ZeehoClient.request()` 返回**原始结果对象**，不抛业务异常：
`{ status, ok, url, method, headers, requestHeaders, text, data, code, message, elapsed, authFailed? }`
（`requestHeaders` 含 `Authorization`，不要原样写日志），
`code !== '10000'` 需要调用方自己判断。

## 3. 网关行为与前端限制

这三条都是实测结论，写代码前先看完，能省掉大半调试时间。

### 3.1 UA 坑：`430 permit error`

App 3.0.6 的 appId 在请求 `vehicleHomePageV2`、`batteryInfo` 等接口时，网关会按 `User-Agent` 放行：

```http
HTTP/1.1 430
{"code":"30000","message":"permit error"}
```

- `User-Agent: okhttp/4.9.2`（App 原值）→ 正常返回；Node 默认 UA（`node`）、浏览器默认 UA、`curl` 默认 UA → 全部 430。
- 浏览器 `fetch` **不允许**脚本设置 `User-Agent`（forbidden header），所以**纯前端自填凭据模式下
  `vehicleHomePageV2` 不可用**，这不是签名问题。规避：面板模式走 `API.call`，或 Node / CLI 侧显式设置该 UA。
- `api.html` 自填模式请求车况失败时，会用 `carSet` + `ota/mmi/info/v2` 兜底；`carSet` 不要求 okhttp UA，
  但必须带 `carUserId`（`vehicle/list` 不返回该字段），所以只有面板模式读到过 uid 时才能补全 SOC / 续航。

### 3.2 Content-Type 坑：`30121 permit error`

面板签名请求必须保留 `Content-Type: application/json;charset=UTF-8`；只发 `application/json`（去掉 `charset`）
时，`vehicleHomePageV2` 会返回 `30121 permit error`。

```http
HTTP/1.1 200
{"code":"30121","message":"permit error"}
```

### 3.3 CORS

网关对浏览器调试是友好的：

- 会回显 `Origin`，允许 `authorization`、`cfmoto-x-sign`、`cfmoto-x-param` 等自定义头；
- 已验证来源：`http://127.0.0.1:*`、`http://localhost:*`、GitHub Pages 域（`https://lswlc33.github.io`）；
- `credentials: include` 可用（token 走 header，不依赖 Cookie）。

### 3.4 401 返回的是 XML

token 失效 / 未带 token 时返回 `401`，响应体是 XML 而非 JSON：
`<InvalidTokenException><error>invalid_token</error></InvalidTokenException>`。
`ZeehoClient` 对此容错：`data` 为 `null`、`text` 保留原文、`authFailed` 置 `true`。

## 4. 登录流程

### 4.1 无图形码路径（App 3.0.6）

| 步骤 | 请求 | 说明 |
| --- | --- | --- |
| 1 | `GET mine:authCode/{phone}` | 直接给手机号发短信验证码；**App 3.0.6 没有图形验证码** |
| 2 | `POST mine:user/loginByPhone` | body `{"phone":"{phone}","authCode":"123456"}`，`Authorization: Basic {BASIC}` |
| 3 | 取 token | 响应 `data.tokenInfo.access_token` |
| 4 | 业务请求 | `Authorization: Bearer <access_token>` |

- 第 1 步用面板的 appId 会返回 `30121 permit error`：**该接口只对 3.0.6 的 appId 开放**。
- 第 2 步虽然是「登录」请求也**需要签名**，只是 `Authorization` 从 Bearer 换成 Basic；token 有效期未公开，
  实测失效后返回 401 + `InvalidTokenException`，重新登录即可。

### 4.2 带图形码路径（面板使用）

| 步骤 | 请求 | 说明 |
| --- | --- | --- |
| 1 | `POST mine:authCode/code/generate` | 取图形验证码（返回 captchaId + 图片） |
| 2 | `POST mine:authCode/V2` | body 含 `phone` / `captcha` / `captchaId`，发短信 |
| 3 | `POST mine:user/loginByPhone` | 同上，换 token |

字段名以实际返回为准；3.0.6 登录页不需要这条路径，保留用于兼容面板老逻辑。

### 4.3 实测错误码

| 场景 | 返回 |
| --- | --- |
| 手机号格式非法 | `3006CM0002001` |
| 验证码错误或已过期 | `3006CM0002002` |
| 未带 token / token 失效 | HTTP `401` + `<InvalidTokenException>` |

## 5. 接口清单

以下端点全部来自 `sdk/endpoints.js`（`source: panel` 为面板已用，`source: app306` 为从 3.0.6 补齐）；
「返回要点」只列关键字段，实际响应字段更多。

### 5.1 登录与版本（`mine:`）

| 方法 | 路径 | 关键参数 | 返回要点 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `authCode/{phone}` | path: phone | 发送结果 | 3.0.6 无图形码；面板 appId 会 30121 |
| POST | `user/loginByPhone` | body: phone, authCode | `data.tokenInfo.access_token` | Basic 认证 + 签名 |
| POST | `authCode/code/generate` | body: `{}` | captchaId / 图片 | 面板图形码登录用 |
| POST | `authCode/V2` | body: phone, captcha, captchaId | 发送结果 | 需先拿图形码 |
| GET | `app/versions/newest` | query: `systemType=1` | `version` / `versionCode` / `name` / `content` | **`systemType` 必填**，缺了返回 40000 |

### 5.2 车辆（`app:`）

| 方法 | 路径 | 关键参数 | 返回要点 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `vehicle/list` | — | `vinNo` / `vehicleName` / `vehicleType` / `virtualFlag` / `sharedFlag` | 实测未返回 `bindingUserId`；要拿 `{uid}` 以 `vehicleHomePageV2` 的 `bindingUserId` 为准 |
| GET | `vehicleHomePageV2/{vin}` | path: vin | 140+ 字段：`bmssoc`、`hmiRidableMile`、`onlineStatus`、`vehiclePic3dUrl`、`encryptInfo`、`wifiAddress` | **必须 okhttp UA**，否则 430 |
| GET | `vehicleHomePage/{vin}` | path: vin | 实测与 V2 同为 140 个 key | 旧版路径，字段集合一致；新代码优先 V2 |
| GET | `app/vehicle/tire/monitoring` | query: vinNo, sensorType=`EXTERNAL` | 胎压 / 胎温 | 路径里的 `app/` 不能省；旧的 `/vehicle/tire/monitoring` 返回 401 |
| GET | `vehicleSet/items` | query: vinNo, homepageInvoke=`1` | 47 个功能项 + 蓝牙 serviceCode | 「这台车支持哪些功能」看它 |
| GET | `vehicleSet/vehicleSetInfo/{vin}` | path: vin | 设置项当前值 | 与 `items` 配合渲染开关 |
| GET | `vehicleSet/blueToothServiceCode` | query: vinNo | `OPEN_STORAGE_BOX` / `LOCK_VCU` / `UNLOCK_VCU` / `OPEN_CUSHION` 等 | 蓝牙车控的能力码 |
| GET | `carSet` | query: vinNo, carUserId, `virtualFlag=0` | `bmssocStr`、`hmiRidableMileStr`、`vehicleName`、`vehicleType` | **`virtualFlag` 必填**，缺了 40004 |
| GET | `batteryInfo/{vin}` | path: vin | 电池信息 | 同样受 UA 放行影响 |
| GET | `vehicle/navigation/address/list/0` | — | 导航地址簿 | 末尾 `0` 是固定分组号 |
| GET | `app/service/recharge/vehicle` | query: vinNo | 智能服务 / 权益 | 注意同样有 `app/` 前缀 |
| GET | `vehicleSet/vcuLockStatus/{vin}` | path: vin | 车辆锁状态 | 只读；实际开锁走 5.8 |

### 5.3 骑行（`app:`）

| 方法 | 路径 | 关键参数 | 返回要点 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `homeRideInfo` | query: vinNo | 最近约 10 天的里程 / 时长 | 单位见 `lastRidingTimeUnitMinute` |
| GET | `myRideInfo` | query: vinNo, `month=YYYY.MM` | `rideRecordList` 按天：里程、时长、最高速、急加速/急刹 | 月份用**点**分隔 |
| GET | `ridetrack_v2` | query: vinNo, startDate, endDate, page, pageSize | 轨迹点列表 | 日期为空则取默认区间 |
| GET | `analyse/analyse` | query: vinNo, month | 骑行画像 / 统计 | 与 `myRideInfo` 同月参数 |

### 5.4 OTA / 固件（`app:`，3.0.6 新增能力的大头）

| 方法 | 路径 | 关键参数 | 返回要点 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `checkVersion` | query: vinNo, targetVersion | 实测只返回 `true` / `false` | 传车机当前版本问「有没有新版」；不给包地址 |
| GET | `ota/mmi/info/v2` | query: vinNo | `upgradeStatus`、`recordId`、`softwareVersion`、`appVersion`、`otaVersion`、`wifiAddress`、`fileInfo` | **固件包地址的入口**，见 5.9 |
| GET | `ota/mmi/status` | query: recordId | 单任务进度 | recordId 来自 info / list |
| GET | `ota/list` | query: vinNo | OTA 任务列表 | |
| GET | `otaDetail` | query: id, vinNo | 记录详情 | |
| GET | `otaRecord` | query: vinNo | 升级历史记录 | 面板也在用 |
| GET | `checks` | query: vinNo | 升级前检查结果 | |
| GET | `redPoint` | query: vinNo | 数字（`0` / `1`） | 是否有 OTA 红点 |
| GET | `app/ota/v2/red-point/content` | query: vinNo | `status`、`title`、`content`、`fileInfoList`、`multiDownloadUpgradeContent` | `fileInfoList` 与 `fileInfo` 同构 |
| GET | `upstatus` | query: vinNo | `{status, errCode, errDesc}`，实测 `{status:1}` | 升级状态 |
| GET | `ota/pre/download/setting` | query: vinNo | 预下载开关 | 官方 App 同款；字段未验证 |
| POST | `ota/pre/download/setting` | body | 设置预下载 | 写 |
| GET | `app/ota/autoUpgradeSetInfo` | query: vinNo | 自动升级配置 | 官方 App 同款 |
| POST | `app/ota/autoUpgradeSetInfo` | body | 设置自动升级 | 写 |
| GET | `getOtaHelp` | — | 升级帮助文案 | 纯文案 |
| GET | `orderOtaUpgrade` | query | 预约升级 | 写；未验证 |
| POST | `reallyOtaUpgrade` | body | 真正开始升级 | 写；未验证 |
| POST | `ota/wifi/set` | body | 仪表 Wi-Fi 配网 | 写；未验证 |
| POST | `ota/popSelect` | body | 升级弹窗选择 | 写；未验证 |
| GET | `ota/redPointType` | query | 红点类型 | — |

> 这些端点来自 `out/api_inventory.csv`（从 3.0.6 客户端 dex 提取的 534 条 Retrofit 清单），
> 路径存在但**参数与返回未逐个验证**；`api.html` 的车辆工具区块只把只读的几个挂到界面上，写操作留了确认步骤。

### 5.5 消息（`mine:`）

| 方法 | 路径 | 关键参数 | 返回要点 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `app/Message/allUnRead` | — | 数字（未读数） | |
| GET | `app/Message/list_v2` | query: userId, page, pageSize, `messageKind=3` | 分页消息列表 | `messageKind` 区分消息种类 |
| GET | `/v1.0/app/cfmotoserverapp/vehicleMessageList` | query: vinNo | 历史抓包为 `[]` | **前缀是 `app:`**；当前实测 40000，属服务端不稳定 |
| GET | `app/Message/read` | query: `messageKind=3` | 全部已读 | 写操作；面板实测走 GET |
| GET | `app/Message/delete` | query: `messageKind=3` | 全部清除 | 写操作；同上 |

### 5.6 我的（`mine:`）

| 方法 | 路径 | 关键参数 | 返回要点 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `setting/other/{uid}` | path: uid | 用户资料 | `{uid}` 来自 `vehicleHomePageV2` 的 `bindingUserId` |
| GET | `user/appCode` | — | `codeMap`（0010 用车场景、0098 职业…） | 表单字典 |
| GET | `app/popwindow/v2` | — | 弹窗配置 | |
| GET | `setting/userMuteStatus/{uid}` | path: uid | 禁言 / 违规状态 | |
| GET | `integral/totalIntegral` | — | 积分总数 | |
| GET | `signin/info` | query: month=`YYYY-MM`, `server_name=SMART` | 签到日历 | 历史写法：路径不带 `/v1.0/`，为 `/cfmotoservermine/signin/info` |
| GET | `signin/count` | — | 签到状态 / 连续天数 | |
| GET | `integral/getByUserId` | query: userId | 积分任务列表 | |
| GET | `integral/adjustByShare` | — | 领取分享积分结果 | **写操作**，会真实加积分 |

### 5.7 社区（`social:`）

| 方法 | 路径 | 关键参数 | 返回要点 | 备注 |
| --- | --- | --- | --- | --- |
| GET | `community/mineArticleInfo` | query: userId | 我的动态 | |
| GET | `community/qbTzInfoNewV2` | query: postModule=`1`, slidingType=`0`, page, pageSize | 广场动态 | |
| POST | `commonArticle` | body: content, images | 发布结果 | **写操作**：真发帖 |
| POST | `socialCommu/likeFavoriteInfo` | body: articleId, `likeFlag=1` | 点赞结果 | **写操作** |
| PUT | `article/share/{postId}` | path: postId | 分享计数 | **写操作** |
| DELETE | `commonArticle/deleteArticle` | query: articleId, postType=`1` | 删除结果 | **写操作** |

### 5.8 车控（`app:`，全部是写操作）

| 方法 | 路径 | 关键参数 | 返回要点 | 备注 |
| --- | --- | --- | --- | --- |
| PUT | `vehicleInfo/control/{vin}` | path: vin；body 如 `{"controlType":"HORN"}` | 指令受理结果 | 寻车（响铃/闪灯）；body 字段随车型/固件不同，先抓一次真实请求 |
| POST | `dbpoint/send` | body 结构随功能项变化 | 指令受理结果 | 蓝牙 / 云端车控的统一下发口 |
| POST | `vehicleSet/network/unlock` | body（需 AES 加密载荷） | 解锁结果 | **云解锁，会真的开锁**；密钥来自车辆 `encryptInfo` |
| PUT | `vehicleSet/propertyOne/one` | body 属性对 | 写入结果 | 通用属性写入 |
| PUT | `vehicleSet/propertyTwo/one` | body 属性对 | 写入结果 | 通用属性写入（第二组） |
| PUT | `vehicleSet/motoPlay/one` | body 开关值 | 写入结果 | 投屏开关 |

### 5.9 重点字段：OTA `fileInfo`

`ota/mmi/info/v2` 与 `app/ota/v2/red-point/content` 的 `fileInfoList[]` 使用同一结构：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `fileName` | string | 固件文件名，落盘时取 `basename` |
| `fileSize` | number | 字节数 |
| `fileUrl` | string | **OSS 预签名直链**，直接 GET 即可下载；带时效参数，不要入库当永久地址 |
| `firmwareId` | string | 固件标识，配合 `softwareVersion` 判断是否重复下载 |
| `sign` | string | 文件签名 / 校验串；具体校验算法**未验证** |
| `fileBucket` | string | 对象存储桶名 |

另有 `wifiAddress`：**仪表 Wi-Fi 二维码链接**，形如 `...?pwd=****`，含 Wi-Fi 密码；打码只保留到 `pwd=` 之前，
其余替换为 `***`（CLI 即如此处理）。

`fileInfo` 为 `null` 表示服务端当前没有下发 OTA 包 —— 正常状态，不是错误。

### 5.10 重点字段：`vehicleHomePageV2.encryptInfo`

结构：`{ "key": "****", "iv": "****", "encryptValue": "****" }`（三个值一律打码）。

- 这是**投屏 / 敏感指令的加密资料**：`key` + `iv` 用于 AES，`encryptValue` 是待用密文。
- 属于账号级敏感材料：不要贴到公开渠道、不要提交进仓库、不要打进日志或截图。
- 浏览器自填凭据模式拿不到它（`vehicleHomePageV2` 被 UA 拦，见 3.1）；Node / CLI 带 okhttp UA 可以取到。
- CLI 文本模式只提示「投屏资料 已取到」，不打印内容；`vehicle --json` 默认也只输出
  `{present:true,keys:[...]}`，必须显式加 `--show-secrets` 才输出原值（该开关同样影响
  `location` 与 `wifiAddress` 里的 Wi-Fi 密码）。


### 5.11 壁纸与主题（`app:`，3.0.6；主题按车型显示）

`vehicleHomePageV2` 里与壁纸/主题相关的字段：

| 字段 | 说明 |
| --- | --- |
| `supportCustomWallpaper` | 是否支持自定义壁纸 |
| `wallpaperResolutionWidth` / `wallpaperResolutionHeight` | 壁纸分辨率；本车实测为 `1024×600`（部分固件/服务端返回 null） |
| `subjectWallpaperConfig` | 内置壁纸配置的 **JSON 字符串**，元素含 `name`、`type`、`url`、`subjectUrl` |
| `themeShopFlag` / `themeShopNewFlag` | 主题商店是否开放 / 是否有新品；本车实测 `0` |
| `appThemeRgb` / `appChargeRgb` | App 主题色 / 充电主题色 |
| `appThemeImage` / `appChargeImage` | App 主题图 / 充电主题图 |

壁纸接口（`/v1.0/app/cfmotoserverapp/vehicle/wallpaper/`）：

| 方法 | 路径 | 关键参数 | 返回 / 状态 |
| --- | --- | --- | --- |
| GET | `pageVehicleWallpaper` | query: vinNo, pageNum, pageSize | **实测 10000**：`total` / `list`（id、wallpaperName、useState、wallpaperContent、mmiSubjectConfigId） |
| GET | `getUserWallpaperCount` | query: vinNo | **实测 10000**：数字 |
| GET | `getWallpaperVehicle/{id}` | path: id | **实测 10000**：适用车型数组，如 `["AE5i"]` |
| GET | `vehicleWallpaperDetail/{id}` | path: id；query: vinNo | **实测 10000**：壁纸详情；不带 vinNo 返回 40000 |
| GET | `useWallpaperProgress/{recordId}` | path: recordId | 下发进度；recordId 不是壁纸 id，未验证 |
| POST | `useWallpaper` | body：id / vinNo 等 | 写操作：下发壁纸到车机，未执行 |
| DELETE | `deleteWallpaperV2/{id}` | path: id | 写操作：删壁纸，未执行 |
| PUT / POST | `update/{id}` | path: id；body：壁纸信息（`wallpaperName` 必填） | 写操作：修改壁纸信息，未执行。**方法有分歧**：早期抓包文档记 PUT，3.0.6 客户端清单（`out/api_inventory.csv`）记 POST；页面上默认 POST，可切 PUT |
| POST | `save` | multipart 图片 | 写操作：上传自定义壁纸；调试台只发 JSON，文件字段未验证 |
| POST | `bluetooth/report` | body：vinNo / id / status | 写操作：蓝牙传输状态上报，未验证 |

主题接口：

| 方法 | 路径 | 关键参数 | 状态 |
| --- | --- | --- | --- |
| GET | `vehicleTheme/list` | query: vinNo | 3.0.6 存在该路径；本车 `themeShopFlag=0`，实测 40000，参数/返回待主题商店开通后验证 |
| GET | `theme/one` | query: themeId | 3.0.6 存在该路径；themeId 来自主题列表，实测 40000，待验证 |

> 结论：**壁纸的列表/数量/适用车型/详情已经实测可用**；`useWallpaper`、删除、上传、蓝牙上报属于写操作，只登记未执行；主题列表/详情受 `themeShopFlag` 限制，当前 AE5i 的 `themeShopFlag=0`，调试台直接隐藏「车机主题」分组；只有读到 `themeShopFlag=1` 的车型才显示。

### 5.12 车辆设置（`app:`，3.0.6；结构由服务端下发）

`api.html` 车辆工具区块里的「车辆设置」就是按这一节的契约做的，**不要在客户端硬编码 47 项清单**：
官方 App 自己也是拿服务端的能力表 + 当前值渲染的，硬编码会在换车型/换固件时直接错位。

| 方法 | 路径 | 关键参数 | 返回要点 |
| --- | --- | --- | --- |
| GET | `vehicleSet/items` | query: vinNo, homepageInvoke | 47 条能力表：`functionCode`、`isSupport`、`isSupportSetting`、`isSupportBlueTooth`、`blueToothServiceCodeList[{functionName, serviceCode}]`、`functionConfig`、`restrictVersion` |
| GET | `vehicleSet/itemsV2` | query: vinNo, homepageInvoke | **官方「车辆设置主页」的磁贴布局**，见下 |
| GET | `vehicleSet/vehicleSetInfo/{vinNo}` | path: vinNo | 当前值（约 90 个 camelCase 键）+ `disabledContent[]` + `rideFunctionVOList` |
| GET | `vehicleSet/blueToothServiceCode` | query: vinNo | `[{functionName, serviceCode, isSupportSetting}]`，蓝牙下发用的服务码 |
| GET | `vehicleSet/chargePower/options` | query: vinNo | 充电功率档位 `{value,key/label}`，`chargingPower` 下拉用 |
| POST | `vehicleSet/addVehicleSet` | body: `AddVehicleSetReq` | 开关/枚举类写入的**主路径** |
| PUT | `vehicleSet/propertyOne/one`、`propertyTwo/one` | body 属性对 | 通用属性写入（未验证字段名） |
| PUT | `vehicleSet/motoPlay/one`、`lowPowerRule/one` | body | 投屏模式、低电量规则 |

`itemsV2` 的 `data.vehicleSetFunctionVOList` 是**二维数组**：外层是「行」，每行是磁贴；
每个磁贴 `{functionCode, functionName, describe, type, subFunctions[]}`，`subFunctions` 才是具体设置项。
实测 AE5i Pro：**9 行 / 14 个磁贴**（防盗与安全、音效设置、自定义按键设置、仪表设置、钥匙与共享、
骑行偏好设置、便捷骑行、辅助驾驶、伴我回家、自动驻车、电池信息与设置、智能周边、车辆信息、功能许愿池）。
另有 `vehicleTypeDetailFunctionVOList`（48 条本车型明细），可与 `items` 交叉校验。

`vehicleSetInfo` 的几个坑（都是实测）：

- **同一个值有多套键**：`tcsTractionControl` / `etcsTractionControl`、`cushionSensor` / `ecushionSensor`，
  值还可能不一致；按「精确 → 别名 → 忽略大小写」取，不要按名字猜。
- **上游拼写**：电子制动 `BRAKE_POWER` 的值键是 **`barkePower`**；`MOTOPLAY` → `motoPlay`；
  `SOUND_EFFECT` 的总开关实际叫 `soundEffectStatus`，明细在 `soundEffectSetting{...}` 里（15 个子项）。
- `disabledContent[{functionName, content}]` 是「为什么这项点不动」的官方文案（例如
  `automaticShutdown` 需要先开座垫感应），有就展示，别自己编。
- 带 `_GO` 后缀的 functionCode（`AUTO_SHUTDOWN_TIME_GO`、`AUTOMATIC_PARKING_GO`）对应的是 v1 能力码别名。

写入体（字段名来自 3.0.6 dex 的 `com.cfmoto.bean.reqest.AddVehicleSetReq`）：

```json
{ "vin": "TESTVIN0000000001", "serviceCode": "603980085", "vehicleSetEnum": "CRUISE_CONTROL_HOLD", "commandParam": "1", "flag": true, "message": "" }
```

- `serviceCode` 来自 `blueToothServiceCode` / `items.*.blueToothServiceCodeList`，**字符串化**传；
  服务端没给 serviceCode 的项，客户端下发不了（只能车机上改），这一点在 UI 上要说清楚。
- `commandParam` 一律字符串（开关 `"1"/"0"`，枚举就是枚举值，数值项给数字字符串）。
- `flag` / `message` 的语义**未验证**：仓库里的实现固定 `flag=true`、`message=""`，以车端返回为准。

## 6. 错误码

| code | HTTP | 含义 | 常见触发 / 排查方向 |
| --- | --- | --- | --- |
| `10000` | 200 | 成功 | 业务成功只认这个码，不要只看 HTTP 200 |
| `3006CM0002001` | 200 | 手机号有误 | 号码格式 / 号段不对 |
| `3006CM0002002` | 200 | 验证码有误或已过期 | 重发验证码 |
| `40000` | 200 | 网络开小差（通用业务失败） | 缺必填参数、接口本身不稳定、服务端限流。先核对参数，再重试 |
| `40004` | 200 | 必填项缺失 | 典型：`carSet` 少了 `virtualFlag` |
| `30121` | 200 / 430 | permit error | appId 无该接口权限、签名方式不对、`Content-Type` 被简化 |
| `30000` | 430 | permit error（UA 校验） | App 的 appId 对 `User-Agent` 有要求，用 `okhttp/4.9.2` |
| — | 430 | 网关放行失败 | 响应体通常带 `30121` 或 `30000`，按上面两行定位 |
| — | 401 | token 失效 / 未携带 | 响应体是 `<InvalidTokenException>` XML，重新登录 |

## 7. 安全与边界

- **token 等价于账号会话**：拿到就能读车况、拉 OTA 包、发帖、解锁车辆。不要提交进仓库，也不要贴进 Issue / 截图。
  CLI 把它存在 `~/.zeeho_token.json`（权限 `0600`），不在仓库内。
- **必须打码**：`encryptInfo`（投屏加密资料）、`wifiAddress` 里的 Wi-Fi 密码、VIN、手机号。本文示例全是占位符。
- **写操作会改真实世界**：`vehicleInfo/control`、`vehicleSet/network/unlock`、`vehicleSet/property*`、
  `dbpoint/send`、`commonArticle`、`Message/read|delete`、`integral/adjustByShare` 都属于写操作。调试台对它们标记「写」，
  必须勾选「我确认这是写操作」才发送；CLI 不会自动调用。
- **本仓库是公开静态站**：本 PR 新增文件不写死 appSecret / Basic；`sdk/zeeho-api.js` 不内置密钥。既有 `index.html` 运行时暴露的 `ZEEHO_SIGN.APP_BASIC_AUTH` 属于历史产物，需另行处理，不在本 PR 范围。
  凭证由使用者注入；`api.html` 面板模式不落任何密钥。
- 自填凭据模式的 appId 存 `localStorage`，appSecret 与 Basic 只放当前标签页 `sessionStorage`；仍有明文风险，仅限调试机使用，用完可「清空本机保存」。

## 8. 复现与工具

### 8.1 离线测试

```bash
node --test tests/*.test.mjs
```

覆盖：哈希与 Node `crypto` 对齐、RFC 3986 编码、query 规范化、签名 KAT、`buildHeaders` 头字段、
`ZeehoClient` 的 URL/Bearer/Basic/401-XML 行为、`pickVehicleSummary`、端点目录自检（14 条）。

### 8.2 CLI

```bash
node tools/zeeho-cli.mjs vehicle --vin {VIN}                        # 车辆列表 + 车况概览
node tools/zeeho-cli.mjs ota --vin {VIN}                            # 仪表 OTA / 固件包 / 历史
node tools/zeeho-cli.mjs wallpaper --vin {VIN} [--id {wallpaperId}]  # 壁纸列表 / 详情
node tools/zeeho-cli.mjs watch --vin {VIN} --every 600 --hours 2    # 轮询，发现新包即下载并打 sha256
node tools/zeeho-cli.mjs login --phone {phone} [--code 123456]      # 登录，token 写入 ~/.zeeho_token.json
node tools/zeeho-cli.mjs call ota_info --query vinNo={VIN} --json    # 按端点 id 或路径直接调
# 写操作/发短信端点必须加 --yes；--json 默认打码敏感字段，--show-secrets 才输出原文
```

### 8.3 调试台 `api.html`

- 默认 **复用面板签名**：在隐藏 iframe 里加载 `index.html`，调用面板的 `API.call` 与登录态，本页不保存密钥。
  注意面板签名是 `(tag, params, body, method)` 那一套，**preimage 与 3.0.6 的 `(url, method, body)` 不同**；
  直接拿 `ZEEHO_SIGN.buildHeaders(url, …)` 请求 `vehicleHomePageV2` 会被判 `30121`（实测），
  面板没有的端点（OTA / 版本等）优先用自填 appId/appSecret 自签名；没有 appSecret 时退回面板 signer（OTA / version 已实测可用，但不保证所有端点）。
- 设置里可切 **自填凭据**：填 appId / appSecret / Basic 或直接粘贴 token；appId / token 在 `localStorage`，appSecret / Basic 只在当前标签页 `sessionStorage`。
- 登录态优先读 `localStorage['zeeho.token']`：先在 `index.html` 登录一次，回到调试台刷新即可复用。
- 写操作需勾选确认（见 §7）。

### 8.4 `api.html` 里的车辆工具区块

车辆设置 / 车机壁纸 / 固件版本三块 UI 直接长在接口调试台页里（车况概览下方），不另建页面；
复用本页已有的 `request()`（面板签名或自填凭据）与 `state.token` / `state.vin`。

| 区块 | 内容 | 主要接口 |
| --- | --- | --- |
| 固件版本 | 版本、升级状态、`fileInfo`（地址打码）；检查更新在卡片右上角 | `ota/mmi/info/v2`、`checkVersion`、`upstatus` |
| 车辆设置 | `itemsV2` 磁贴 → 开关/下拉/滑杆；写前二次确认；底部有壁纸入口（修改壁纸信息等） | `vehicleSet/itemsV2`、`items`、`vehicleSetInfo`、`addVehicleSet` |
| 车机壁纸 | 内置/我的壁纸、应用（轮询进度）、删除、改名、上传 | `vehicle/wallpaper/*` |

实现方式：区块的 CSS/HTML/JS 在 `tools/car-ui/`，由 `tools/patch-api-car-ui.mjs` 拼进 `api.html`，
数据模型（`sdk/vehicle-settings.js`、`sdk/vehicle-media.js`）在构建时内联成 `__carModels`。
上游重建 `api.html` 后重跑该脚本即可复原（幂等）。

同一套 UI 也注入了面板 `index.html`（`tools/patch-panel-entry.mjs`），但**分开路由**：

| 路由 | 入口 | 视图 |
| --- | --- | --- |
| `#car-wallpaper` | 「车辆设置」页底部一行「车机壁纸」 | 只显示壁纸卡片 |
| `#car-ota` | 「我的 → 固件版本」卡片右上角「OTA 升级」 | 只显示固件版本卡片 |

面板里没有本页的 `request()`，由 `tools/car-ui/panel-host.js` 用面板自己的 `window.ZEEHO_SIGN` 补一层。

壁纸是**两块图**（官方 `BigWallPaperEditActivity` + `SmallWallPaperEditActivity`）：
`wallpaperContent` 里的 `WallpaperConfigItem { type, url, subjectUrl }`，`type=1` 整屏、`type=2` 速度卡；
新建时按两步向导各传一张，应用时按记录整组下发。

### 8.5 环境变量

| 变量 | 用途 |
| --- | --- |
| `ZEEHO_APP_ID` | appId |
| `ZEEHO_APP_SECRET` | appSecret |
| `ZEEHO_BASIC` | 登录接口的 Basic 凭据（可带或不带 `Basic ` 前缀） |
| `ZEEHO_TOKEN` | 已有 access_token，跳过登录 |
| `ZEEHO_BASE` | 覆盖网关地址，默认 `https://tapi.zeehoev.com` |

未跟踪的 `config.local.json`（`base` / `appId` / `appSecret` / `basic`）可替代环境变量，已在 `.gitignore` 内；优先级：环境变量 > 该文件。

## 9. 未验证事项与维护约定

### 9.1 明确标为未验证的行为

- `mall` 前缀下的具体端点（前缀存在，端点未接入）。
- `fileInfo.sign` 的校验算法与用途。
- 面板请求附带 `interfaceversion: 2` / `Accept-Language: zh-CN` 是否必需。
- query 值含空格时实际 URL 用 `+`、签名用 `%20` 的兼容性（签名侧始终重新规范化，未单独压测）。
- `checkVersion` 对其他车型是否返回包地址。
- token 的具体有效期与刷新机制（只观察到 401 后的表现）。

### 9.2 改动本文档时

1. 端点先改 `sdk/endpoints.js`（`id` 稳定、`path` 绝对路径、占位符必须在 `params` 里声明），再同步本文档表格。
2. 签名相关改动必须同步更新 `tests/api.test.mjs` 的 KAT，并跑 `node --test tests/*.test.mjs`。
3. 新错误码写清「HTTP 状态 + code + 触发条件」；示例数据只能是占位符；最后更新顶部「最后验证」日期。
