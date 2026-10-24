/**
 * 极核接口目录（App 3.0.6）。
 *
 * 两个来源：
 *   · `panel` —— 极核面板已经使用的 40 个端点（与 index.html 里的 ZEEHO_EP 同源，
 *     这里整理成可读、可渲染表单的元数据）；
 *   · `app306` —— 我们从 App 3.0.6 的 Retrofit 接口层补齐的 OTA / 版本 / 蓝牙能力码等端点。
 *
 * 字段说明：
 *   id        稳定标识（页面锚点、CLI 子命令都用它）
 *   group     分组 id，见 GROUPS
 *   method    HTTP 方法
 *   path      以 / 开头的绝对路径，支持 {vin} {uid} {phone} 等占位符
 *   params    参数列表：in=path/query；example 会填进表单
 *   body      POST/PUT/DELETE 的 JSON 示例；null 表示无 body
 *   auth      token（Bearer）/ basic（登录用 Basic）/ anonymous（不带 Authorization）
 *   source    panel | app306
 *   danger    写操作/车控，页面需要二次确认
 *   note      一句话说明（含实测返回、坑位）
 */

export const GROUPS = [
  { id: 'auth', label: '登录与版本', desc: '手机号登录、App 版本检查' },
  { id: 'vehicle', label: '车辆', desc: '车况、电池、胎压、设置能力' },
  { id: 'wallpaper', label: '车机壁纸', desc: '自定义/内置壁纸、下发与删除（3.0.6）' },
  { id: 'theme', label: '车机主题（按车型）', desc: '主题商店；仅 themeShopFlag=1 的车型显示（3.0.6）' },
  { id: 'ride', label: '骑行', desc: '最近骑行、月度统计、轨迹与画像' },
  { id: 'ota', label: 'OTA / 固件', desc: '版本比对、仪表固件包、升级状态（3.0.6 新增）' },
  { id: 'message', label: '消息', desc: '未读数、列表、已读/清除' },
  { id: 'mine', label: '我的', desc: '积分、签到、用户资料、静音状态' },
  { id: 'social', label: '社区', desc: '动态、点赞、分享' },
  { id: 'control', label: '车控（写）', desc: '寻车、云解锁、属性写入 —— 会改车，谨慎操作' },
];

export const ENDPOINTS = [
  /* ------------------------------- 登录 ------------------------------- */
  {
    id: 'auth_code', group: 'auth', label: '发送短信验证码（3.0.6 无图形码）', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/authCode/{phone}',
    params: [{ in: 'path', key: 'phone', label: '手机号', example: '10000000000', required: true }],
    auth: 'anonymous', source: 'app306', danger: true,
    note: '实测：手机号非法返回 3006CM0002001；有效手机号会直接发短信。仅 3.0.6 的 appId 有权限，面板 appId 会 30121 permit error。',
  },
  {
    id: 'auth_login', group: 'auth', label: '手机号 + 验证码换 token', method: 'POST',
    path: '/v1.0/mine/cfmotoservermine/user/loginByPhone',
    params: [], body: { phone: '10000000000', authCode: '123456' },
    auth: 'basic', source: 'panel',
    note: 'Basic 认证 + 签名。成功返回 data.tokenInfo.access_token；验证码错误返回 3006CM0002002。',
  },
  {
    id: 'auth_captcha', group: 'auth', label: '登录：图形验证码', method: 'POST',
    path: '/v1.0/mine/cfmotoservermine/authCode/code/generate',
    params: [], body: {}, auth: 'anonymous', source: 'panel',
    note: '面板登录用的图形码接口；App 3.0.6 的 authCode/{phone} 不需要它。',
  },
  {
    id: 'auth_sms_v2', group: 'auth', label: '登录：发短信验证码 V2', method: 'POST',
    path: '/v1.0/mine/cfmotoservermine/authCode/V2',
    params: [], body: { phone: '10000000000', captcha: '1234', captchaId: '' }, auth: 'anonymous', source: 'panel', danger: true,
    note: '需要先拿图形码；body 字段以 App 3.0.6 的登录页为准。',
  },
  {
    id: 'app_version', group: 'auth', label: '最新 App 版本', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/app/versions/newest',
    params: [{ in: 'query', key: 'systemType', label: '系统类型', example: '1', required: true }],
    auth: 'token', source: 'app306',
    note: 'systemType=1 才返回数据；不传会 40000「网络开小差了」。实测当前 3.0.3 / versionCode 398。',
  },

  /* ------------------------------- 车辆 ------------------------------- */
  {
    id: 'vehicle_list', panelId: 'vehicle_list', group: 'vehicle', label: '车辆列表', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicle/list', params: [], auth: 'token', source: 'panel',
    note: '至少包含 vinNo/vehicleName/vehicleType/virtualFlag。',
  },
  {
    id: 'vehicle_home', panelId: 'vehicle_home', group: 'vehicle', label: '车辆主页（车况全量）', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicleHomePageV2/{vin}',
    params: [{ in: 'path', key: 'vin', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'panel',
    note: '140+ 字段：bmssoc、hmiRidableMile、onlineStatus、vehiclePic3dUrl、encryptInfo（投屏资料）、wifiAddress（仪表 Wi-Fi 二维码链接）。网关按 UA 放行：必须带 okhttp/4.9.2，否则 430 permit error。',
  },
  {
    id: 'vehicle_home_v1', group: 'vehicle', label: '车辆主页（旧版 V1）', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicleHomePage/{vin}',
    params: [{ in: 'path', key: 'vin', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
    note: '实测字段集合与 V2 相同（140 个 key）；旧路径保留，新代码优先用 V2。',
  },
  {
    id: 'tire_monitor', panelId: 'tire_monitor', group: 'vehicle', label: '胎压监测', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/app/vehicle/tire/monitoring',
    params: [
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
      { in: 'query', key: 'sensorType', label: '传感器类型', example: 'EXTERNAL' },
    ],
    auth: 'token', source: 'panel',
    note: '路径里的 app/ 不能省；旧的 /vehicle/tire/monitoring 会 401 invalid_token。',
  },
  {
    id: 'vehicle_set_items', panelId: 'vehicle_set_items', group: 'vehicle', label: '设置项能力表', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/items',
    params: [
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
      { in: 'query', key: 'homepageInvoke', label: '首页调用', example: '1' },
    ],
    auth: 'token', source: 'panel',
    note: '47 个功能项 + 蓝牙 serviceCode；「哪些功能这台车支持」就看它。',
  },
  {
    id: 'vehicle_set_info', panelId: 'vehicle_set_info', group: 'vehicle', label: '设置项当前值', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/vehicleSetInfo/{vin}',
    params: [{ in: 'path', key: 'vin', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'panel',
  },
  {
    id: 'bt_codes', group: 'vehicle', label: '蓝牙能力码', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/blueToothServiceCode',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
    note: 'OPEN_STORAGE_BOX / LOCK_VCU / UNLOCK_VCU / OPEN_CUSHION 等 serviceCode。',
  },
  {
    id: 'car_set', panelId: 'vehicle_props', group: 'vehicle', label: '车辆属性', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/carSet',
    params: [
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
      { in: 'query', key: 'carUserId', label: '车主 userId', example: '', required: true },
      { in: 'query', key: 'virtualFlag', label: '虚拟车', example: '0' },
    ],
    auth: 'token', source: 'panel',
  },
  {
    id: 'battery_info', panelId: 'battery_info', group: 'vehicle', label: '电池信息', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/batteryInfo/{vin}',
    params: [{ in: 'path', key: 'vin', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'panel',
  },
  {
    id: 'nav_addresses', panelId: 'nav_addresses', group: 'vehicle', label: '导航地址簿', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicle/navigation/address/list/0',
    params: [], auth: 'token', source: 'panel',
  },
  {
    id: 'service_recharge', panelId: 'service_recharge', group: 'vehicle', label: '智能服务', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/app/service/recharge/vehicle',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'panel',
  },
  {
    id: 'vcu_lock_status', panelId: 'vcu_lock_status', group: 'vehicle', label: '车辆锁状态', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/vcuLockStatus/{vin}',
    params: [{ in: 'path', key: 'vin', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'panel',
  },

  /* ------------------------------- 骑行 ------------------------------- */
  {
    id: 'ride_home', panelId: 'ride_home', group: 'ride', label: '最近骑行', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/homeRideInfo',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'panel',
    note: '最近 10 天里程/时长；单位见 lastRidingTimeUnitMinute。',
  },
  {
    id: 'ride_month', panelId: 'ride_month', group: 'ride', label: '月度骑行', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/myRideInfo',
    params: [
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
      { in: 'query', key: 'month', label: '月份', example: '2026.10', required: true },
    ],
    auth: 'token', source: 'panel',
    note: 'rideRecordList 按天：里程、时长、最高速、急加速/急刹。',
  },
  {
    id: 'ride_track', panelId: 'ride_track', group: 'ride', label: '骑行轨迹（按天）', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/ridetrack_v2',
    params: [
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
      { in: 'query', key: 'startDate', label: '开始日期', example: '' },
      { in: 'query', key: 'endDate', label: '结束日期', example: '' },
      { in: 'query', key: 'page', label: '页码', example: '1' },
      { in: 'query', key: 'pageSize', label: '每页', example: '20' },
    ],
    auth: 'token', source: 'panel',
  },
  {
    id: 'analyse', panelId: 'analyse', group: 'ride', label: '骑行画像', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/analyse/analyse',
    params: [
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
      { in: 'query', key: 'month', label: '月份', example: '2026.10' },
    ],
    auth: 'token', source: 'panel',
  },

  /* -------------------------------- OTA -------------------------------- */
  {
    id: 'check_version', group: 'ota', label: '版本比对 checkVersion', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/checkVersion',
    params: [
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
      { in: 'query', key: 'targetVersion', label: '当前版本', example: '0', required: true },
    ],
    auth: 'token', source: 'app306',
    note: '传车机当前版本问服务端有没有更新；实测这台 AE5i 只返回 true/false，不给包地址。',
  },
  {
    id: 'ota_info', group: 'ota', label: '仪表 OTA 全量信息', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/ota/mmi/info/v2',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
    note: 'upgradeStatus/recordId/softwareVersion/wifiAddress/fileInfo{fileName,fileSize,fileUrl,firmwareId,sign,fileBucket}。包地址就在 fileInfo 里。',
  },
  {
    id: 'ota_status', group: 'ota', label: 'OTA 任务进度', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/ota/mmi/status',
    params: [{ in: 'query', key: 'recordId', label: '任务 id', example: '', required: true }],
    auth: 'token', source: 'app306',
  },
  {
    id: 'ota_list', group: 'ota', label: 'OTA 任务列表', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/ota/list',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
  },
  {
    id: 'ota_detail', group: 'ota', label: 'OTA 记录详情', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/otaDetail',
    params: [
      { in: 'query', key: 'id', label: '记录 id', example: '', required: true },
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
    ],
    auth: 'token', source: 'app306',
  },
  {
    id: 'ota_record', panelId: 'ota_records', group: 'ota', label: 'OTA 升级记录', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/otaRecord',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'panel',
  },
  {
    id: 'ota_checks', group: 'ota', label: 'OTA 检查结果', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/checks',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
  },
  {
    id: 'ota_redpoint', group: 'ota', label: 'OTA 红点', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/redPoint',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
    note: '实测返回数字（0 或 1）。',
  },
  {
    id: 'ota_content', group: 'ota', label: '红点内容 / 升级文案', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/app/ota/v2/red-point/content',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
    note: '返回 {status,title,content,fileInfoList,multiDownloadUpgradeContent}；有任务时 fileInfoList 与 ota/mmi/info/v2 的 fileInfo 同构。',
  },
  {
    id: 'upstatus', group: 'ota', label: '升级状态', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/upstatus',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
    note: '实测 {status:1,errCode:null,errDesc:null}。',
  },

  /* ------------------------------- 消息 ------------------------------- */
  {
    id: 'msg_unread', group: 'message', label: '未读数', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/app/Message/allUnRead', params: [], auth: 'token', source: 'app306',
    note: '实测返回数字（当前 15）。',
  },
  {
    id: 'msg_list', panelId: 'msg_list', group: 'message', label: '消息列表', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/app/Message/list_v2',
    params: [
      { in: 'query', key: 'userId', label: '用户 id', example: '', required: true },
      { in: 'query', key: 'page', label: '页码', example: '1' },
      { in: 'query', key: 'pageSize', label: '每页', example: '20' },
      { in: 'query', key: 'messageKind', label: '消息类型', example: '3' },
    ],
    auth: 'token', source: 'panel',
  },
  {
    id: 'msg_vehicle_list', group: 'message', label: '车辆消息（旧接口）', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicleMessageList',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
    note: '历史抓包返回 []；目前实测 40000「网络开小差了」，属服务端不稳定，不是签名问题。',
  },
  {
    id: 'msg_read_all', panelId: 'msg_read_all', group: 'message', label: '全部已读', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/app/Message/read',
    params: [{ in: 'query', key: 'messageKind', label: '消息类型', example: '3' }],
    auth: 'token', source: 'panel', danger: true,
  },
  {
    id: 'msg_del_all', panelId: 'msg_del_all', group: 'message', label: '全部清除', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/app/Message/delete',
    params: [{ in: 'query', key: 'messageKind', label: '消息类型', example: '3' }],
    auth: 'token', source: 'panel', danger: true,
  },

  /* -------------------------------- 我的 -------------------------------- */
  {
    id: 'user_info', panelId: 'user_info', group: 'mine', label: '用户资料', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/setting/other/{uid}',
    params: [{ in: 'path', key: 'uid', label: '用户 id', example: '10000000001', required: true }],
    auth: 'token', source: 'panel',
  },
  {
    id: 'user_app_codes', group: 'mine', label: 'App 字典（车型/职业…）', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/user/appCode', params: [], auth: 'token', source: 'app306',
    note: 'codeMap：0010 用车场景、0098 职业等。',
  },
  {
    id: 'popwindow', group: 'mine', label: '弹窗配置', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/app/popwindow/v2', params: [], auth: 'token', source: 'app306',
  },
  {
    id: 'mute_status', group: 'mine', label: '静音/违规状态', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/setting/userMuteStatus/{uid}',
    params: [{ in: 'path', key: 'uid', label: '用户 id', example: '10000000001', required: true }],
    auth: 'token', source: 'app306',
  },
  {
    id: 'integral_total', panelId: 'integral_total', group: 'mine', label: '积分', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/integral/totalIntegral', params: [], auth: 'token', source: 'panel',
  },
  {
    id: 'signin_info', panelId: 'signin_info', group: 'mine', label: '签到信息', method: 'GET',
    path: '/cfmotoservermine/signin/info',
    params: [
      { in: 'query', key: 'month', label: '月份', example: '2026-10' },
      { in: 'query', key: 'server_name', label: '服务名', example: 'SMART' },
    ],
    auth: 'token', source: 'panel',
    note: '注意路径没有 /v1.0/ 前缀，是面板里的历史写法。',
  },
  {
    id: 'signin_count', panelId: 'signin_count', group: 'mine', label: '签到状态', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/signin/count', params: [], auth: 'token', source: 'panel',
  },
  {
    id: 'integral_tasks', panelId: 'integral_tasks', group: 'mine', label: '积分任务', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/integral/getByUserId',
    params: [{ in: 'query', key: 'userId', label: '用户 id', example: '', required: true }],
    auth: 'token', source: 'panel',
  },
  {
    id: 'adjust_by_share', panelId: 'adjust_by_share', group: 'mine', label: '领取分享积分', method: 'GET',
    path: '/v1.0/mine/cfmotoservermine/integral/adjustByShare', params: [], auth: 'token', source: 'panel', danger: true,
  },

  /* -------------------------------- 社区 -------------------------------- */
  {
    id: 'post_mine', panelId: 'post_mine', group: 'social', label: '我的动态', method: 'GET',
    path: '/v1.0/social/cfmotoserversocial/community/mineArticleInfo',
    params: [{ in: 'query', key: 'userId', label: '用户 id', example: '', required: true }],
    auth: 'token', source: 'panel',
  },
  {
    id: 'post_square', panelId: 'post_square', group: 'social', label: '广场动态', method: 'GET',
    path: '/v1.0/social/cfmotoserversocial/community/qbTzInfoNewV2',
    params: [
      { in: 'query', key: 'postModule', label: '模块', example: '1' },
      { in: 'query', key: 'slidingType', label: '排序', example: '0' },
      { in: 'query', key: 'page', label: '页码', example: '1' },
      { in: 'query', key: 'pageSize', label: '每页', example: '20' },
    ],
    auth: 'token', source: 'panel',
  },
  {
    id: 'post_publish', panelId: 'post_publish', group: 'social', label: '发动态', method: 'POST',
    path: '/v1.0/social/cfmotoserversocial/commonArticle',
    params: [], body: { content: '', images: [] }, auth: 'token', source: 'panel', danger: true,
  },
  {
    id: 'post_like', panelId: 'post_like', group: 'social', label: '点赞', method: 'POST',
    path: '/v1.0/social/cfmotoserversocial/socialCommu/likeFavoriteInfo',
    params: [], body: { articleId: '', likeFlag: '1' }, auth: 'token', source: 'panel', danger: true,
  },
  {
    id: 'post_share', panelId: 'post_share', group: 'social', label: '分享', method: 'PUT',
    path: '/v1.0/social/cfmotoserversocial/article/share/{postId}',
    params: [{ in: 'path', key: 'postId', label: '动态 id', example: '', required: true }],
    auth: 'token', source: 'panel', danger: true,
  },
  {
    id: 'post_delete', panelId: 'post_delete', group: 'social', label: '删除动态', method: 'DELETE',
    path: '/v1.0/social/cfmotoserversocial/commonArticle/deleteArticle',
    params: [
      { in: 'query', key: 'articleId', label: '动态 id', example: '', required: true },
      { in: 'query', key: 'postType', label: '类型', example: '1' },
    ],
    auth: 'token', source: 'panel', danger: true,
  },

  /* -------------------------------- 车控 -------------------------------- */
  {
    id: 'car_horn', panelId: 'car_horn', group: 'control', label: '寻车（响铃/闪灯）', method: 'PUT',
    path: '/v1.0/app/cfmotoserverapp/vehicleInfo/control/{vin}',
    params: [{ in: 'path', key: 'vin', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    body: { controlType: 'HORN' }, auth: 'token', source: 'panel', danger: true,
    note: 'body 字段随车型/固件不同，先抓一次 App 或面板的请求再填。',
  },
  {
    id: 'car_cmd', panelId: 'car_cmd', group: 'control', label: '车控指令 dbpoint', method: 'POST',
    path: '/v1.0/app/cfmotoserverapp/dbpoint/send',
    params: [], body: {}, auth: 'token', source: 'panel', danger: true,
    note: '面板里所有蓝牙/云端控制的统一下发口；body 结构按功能项不同。',
  },
  {
    id: 'net_unlock', panelId: 'net_unlock', group: 'control', label: '云解锁（一次性 AES）', method: 'POST',
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/network/unlock',
    params: [], body: {}, auth: 'token', source: 'panel', danger: true,
    note: '会真的解锁车辆；需要先用车辆 encryptInfo 做 AES 加密。',
  },
  {
    id: 'set_property', panelId: 'set_property', group: 'control', label: '写：通用属性 propertyOne', method: 'PUT',
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/propertyOne/one',
    params: [], body: {}, auth: 'token', source: 'panel', danger: true,
  },
  {
    id: 'set_property2', panelId: 'set_property2', group: 'control', label: '写：通用属性 propertyTwo', method: 'PUT',
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/propertyTwo/one',
    params: [], body: {}, auth: 'token', source: 'panel', danger: true,
  },
  {
    id: 'set_motoPlay', panelId: 'set_motoPlay', group: 'control', label: '写：投屏开关', method: 'PUT',
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/motoPlay/one',
    params: [], body: {}, auth: 'token', source: 'panel', danger: true,
  },
  /* --------------------------- 壁纸与主题 --------------------------- */
  {
    id: 'wallpaper_page', group: 'wallpaper', label: '壁纸列表（分页）', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/pageVehicleWallpaper',
    params: [
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
      { in: 'query', key: 'pageNum', label: '页码', example: '1' },
      { in: 'query', key: 'pageSize', label: '每页', example: '20' },
    ],
    auth: 'token', source: 'app306',
    note: '实测返回 total/list；list 项含 id、wallpaperName、useState、displayWallpaperSet、wallpaperContent、mmiSubjectConfigId、wallpaperConfigSnapshot。',
  },
  {
    id: 'wallpaper_count', group: 'wallpaper', label: '我的壁纸数量', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/getUserWallpaperCount',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
    note: '实测返回数字（当前账号 2 张）。',
  },
  {
    id: 'wallpaper_vehicle', group: 'wallpaper', label: '壁纸适用车型', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/getWallpaperVehicle/{id}',
    params: [{ in: 'path', key: 'id', label: '壁纸 ID', example: '100000', required: true }],
    auth: 'token', source: 'app306',
    note: '实测返回车型数组，例如 ["AE5i"]。',
  },
  {
    id: 'wallpaper_detail', group: 'wallpaper', label: '壁纸详情', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/vehicleWallpaperDetail/{id}',
    params: [
      { in: 'path', key: 'id', label: '壁纸 ID', example: '100000', required: true },
      { in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true },
    ],
    auth: 'token', source: 'app306',
    note: '实测需要 vinNo；返回壁纸详情、wallpaperContent（JSON 字符串）和主题配置 ID。',
  },
  {
    id: 'wallpaper_use', group: 'wallpaper', label: '使用壁纸', method: 'POST',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/useWallpaper',
    params: [], body: { id: '100000', vinNo: 'TESTVIN0000000001' }, auth: 'token', source: 'app306', danger: true,
    note: '写操作：把壁纸下发到车机。body 字段以实际抓包为准（id / wallpaperId），本仓库未执行。',
  },
  {
    id: 'wallpaper_delete', group: 'wallpaper', label: '删除壁纸', method: 'DELETE',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/deleteWallpaperV2/{id}',
    params: [{ in: 'path', key: 'id', label: '壁纸 ID', example: '100000', required: true }],
    auth: 'token', source: 'app306', danger: true,
    note: '写操作：删除账号下的自定义壁纸，未执行验证。',
  },
  {
    id: 'wallpaper_update', group: 'wallpaper', label: '修改壁纸信息', method: 'PUT',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/update/{id}',
    params: [{ in: 'path', key: 'id', label: '壁纸 ID', example: '100000', required: true }],
    body: { wallpaperName: '新壁纸名' }, auth: 'token', source: 'app306', danger: true,
    note: '写操作：改壁纸名称/配置，未执行验证。',
  },
  {
    id: 'wallpaper_save', group: 'wallpaper', label: '上传自定义壁纸', method: 'POST',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/save',
    params: [], body: { wallpaperName: '我的壁纸' }, auth: 'token', source: 'app306', danger: true,
    note: '写操作：App 用 multipart 上传图片；调试台只发 JSON，需抓包补文件字段，未验证。',
  },
  {
    id: 'wallpaper_progress', group: 'wallpaper', label: '壁纸下发进度', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/useWallpaperProgress/{recordId}',
    params: [{ in: 'path', key: 'recordId', label: '下发记录 ID', example: '100000', required: true }],
    auth: 'token', source: 'app306',
    note: '写操作的进度查询；参数是 recordId，不是壁纸 id。未验证。',
  },
  {
    id: 'wallpaper_bt_report', group: 'wallpaper', label: '壁纸蓝牙传输上报', method: 'POST',
    path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/bluetooth/report',
    params: [], body: { vinNo: 'TESTVIN0000000001', id: '100000', status: '1' }, auth: 'token', source: 'app306', danger: true,
    note: '写操作：车机通过蓝牙传壁纸后的状态上报；字段未验证。',
  },
  {
    id: 'theme_list', group: 'theme', label: '车机主题列表', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/vehicleTheme/list',
    params: [{ in: 'query', key: 'vinNo', label: 'VIN', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
    note: '3.0.6 有该路径，但本车 themeShopFlag=0，实测只返回 40000；参数/返回结构待主题商店开通后验证。',
  },
  {
    id: 'theme_one', group: 'theme', label: '车机主题详情', method: 'GET',
    path: '/v1.0/app/cfmotoserverapp/theme/one',
    params: [{ in: 'query', key: 'themeId', label: '主题 ID', example: '6', required: true }],
    auth: 'token', source: 'app306',
    note: '3.0.6 有该路径；themeId 来自主题列表。实测 40000，待验证。',
  },

];

/** 按分组取接口。 */
export function byGroup(groupId) {
  return ENDPOINTS.filter((endpoint) => endpoint.group === groupId);
}

/** 按 id 取接口。 */
export function byId(id) {
  return ENDPOINTS.find((endpoint) => endpoint.id === id) || null;
}

/** 把 path 里的 {key} 换成参数值，未填的保留占位符（方便页面提示）。 */
export function fillPath(path, params = {}) {
  return path.replace(/\{([a-zA-Z0-9_]+)\}/g, (match, key) => {
    const value = params[key];
    return value === undefined || value === null || value === '' ? match : encodeURIComponent(value);
  });
}

export default { GROUPS, ENDPOINTS, byGroup, byId, fillPath };
