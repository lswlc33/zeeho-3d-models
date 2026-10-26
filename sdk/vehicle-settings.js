/**
 * 车辆设置模型（App 3.0.6）：把四个只读接口拼成一份可直接渲染的表单模型。
 *
 * 数据来源（都在 tests/fixtures/settings/ 下，真机抓包）：
 *   itemsV2               GET /v1.0/app/cfmotoserverapp/vehicleSet/itemsV2
 *                         「车辆设置主页」的磁贴布局，data.vehicleSetFunctionVOList 是二维数组：
 *                         外层是行，内层是磁贴（分组），分组里可有 subFunctions。
 *   items                 GET .../vehicleSet/items
 *                         48 项（实测 47 条）能力表：isSupport / isSupportSetting / isSupportBlueTooth、
 *                         blueToothServiceCodeList（蓝牙能力码）、functionConfig（版本或枚举配置）。
 *   info                  GET .../vehicleSet/vehicleSetInfo/{vin}
 *                         当前值（约 90 个 camelCase 键）+ disabledContent（禁用原因文案）。
 *   blueToothCodes        GET .../vehicleSet/blueToothServiceCode
 *                         functionName -> serviceCode 的全局表（补 items 里缺的能力码）。
 *   chargePowerOptions    GET .../vehicleSet/chargePower/options
 *                         充电功率枚举，key 是瓦数、value 是下发值。
 *
 * 本模块是纯函数：不碰 DOM、不发网络、不引依赖，方便 api.html 车辆工具区块（内联）与单测共用。
 *
 * ---------------------------------------------------------------------------
 * 两份数据对不上的地方（都在代码里做了兜底，见 CODE_ALIAS / STATE_KEY_ALIAS）：
 *   1. v2 磁贴的 subFunctions 用的是 UI 简称，v1 用能力名：
 *      MAX_SPEED（v2）            ↔ MAXIMUM_SPEED（v1）
 *      CUSHION_SENSOR（v2）       ↔ E_CUSHION_SENSOR（v1）
 *      AUTO_SHUTDOWN_TIME（v2）   ↔ AUTO_SHUTDOWN_TIME_GO（v1）
 *   2. 同一份 vehicleSetInfo 里同时存在新旧两套键名：
 *      tcsTractionControl / etcsTractionControl、cushionSensor / ecushionSensor。
 *      取值时先精确匹配、再忽略大小写匹配，两套都能读到。
 *   3. v2 有 4 个分组没有 subFunctions（防盗与安全 / 音效设置 / 自定义按键设置 / 仪表设置），
 *      v1 里它们自身也没有能力码 —— 只能按 functionCode 手工归组（见 TILE_EXTRAS）。
 *   4. chargingPower 的值是 "200"（瓦数？），而 options 的 value 是 1..6、key 是 350..1900，
 *      两边对不上；模型里 value 原样保留，options 用官方枚举，UI 需要容错。
 * ---------------------------------------------------------------------------
 *
 * tile.items 的构成：itemsV2.subFunctions 建的官方子项在前，TILE_EXTRAS 补的在后
 * （例如 KEY_SHARE 是 4 个官方子项 + 感应距离/自定义感应距离 2 个补充项）。
 * model.items 是同一批对象的拍平去重结果（按 stateKey 去重），顺序为磁贴顺序 + 未分组兜底。
 */

/** 控件类型。readonly / action 不可写。 */
export const CONTROL = {
  SWITCH: 'switch',
  SELECT: 'select',
  RANGE: 'range',
  TEXT: 'text',
  READONLY: 'readonly',
  ACTION: 'action',
};

/**
 * 中文名兜底表（key 为 stateKey）。
 * 优先级：服务端 functionName > 这里的 LABELS > code 本身。
 * 音效子项的中文名来自 3.0.6 dex 的 SoundEffectSetting 字段注释，不能改。
 */
const LABELS = {
  // 音效（vehicleSetInfo.soundEffectSetting.*）
  'soundEffectSetting.switchAll': '音效总开关',
  'soundEffectSetting.switchLowVelocity': '低速音效',
  'soundEffectSetting.switchStand': '边撑音效',
  'soundEffectSetting.switchRidingModel': '骑行模式音效',
  'soundEffectSetting.switchSitDown': '座垫音效',
  'soundEffectSetting.switchReady': '就绪音效',
  'soundEffectSetting.switchShutdown': '关机音效',
  'soundEffectSetting.switchCruiseControl': '定速巡航音效',
  'soundEffectSetting.unlockLock': '上锁解锁音效',
  'soundEffectSetting.modeSwitching': '模式切换音效',
  'soundEffectSetting.staticOperation': '静态操作音效',
  'soundEffectSetting.burglarAlarm': '防盗音效',
  'soundEffectSetting.cyclingSteering': '转向音效',
  'soundEffectSetting.radarSound': '雷达音效',
  'soundEffectSetting.soundSize': '音量',
  // 骑行偏好 / 仪表
  topSpeed: '最高车速',
  maximumSpeed: '最高车速档',
  maximumSpeedLimit: '限速车速',
  lowPower: '低电量提醒电量',
  lowPowerThreshold: '低电量阈值',
  inductiveDistance: '感应距离',
  customInductiveDistance: '自定义感应距离',
  comeHomeWithYou: '伴我回家',
  comeHomeWithYouTime: '延时关灯时间',
  leaveOffTime: '离车关灯延时',
  automaticShutdown: '离车关机',
  automaticShutdownTimeout: '离车关机延时',
  automaticShutdownSwitch: '离车关机开关',
  mileageUnit: '里程单位',
  temperatureUnit: '温度单位',
  timeFormat: '时间格式',
  language: '语言',
  themeId: '仪表主题',
  themeName: '主题名称',
  updateDate: '更新时间',
  motoPlay: '投屏',
  rideMemoryModel: '骑行记忆模式',
  chargingPower: '充电功率',
  bmsBind: '电池绑定',
  bmsUniqueId: '电池唯一码',
  bluetoothStrengthMin: '蓝牙信号强度下限',
  bluetoothStrengthMax: '蓝牙信号强度上限',
  customBluetoothStrength: '自定义蓝牙信号强度',
  bluetoothAddress: '蓝牙地址',
  extendedRange: '增程模式',
  precisionEndurance: '精准续航',
  ultraLowBatteryReminder: '极低电量提醒',
  automaticLock: '自动锁车',
  radarBsd: '盲区监测',
  radarRcw: '后向碰撞预警',
  antiLockBrakeSystem: '防抱死制动',
  emergencyBrakeFlash: '紧急制动闪光',
  instrumentGame: '仪表小游戏',
  soundEffectStatus: '音效总开关',
  originNfc: 'NFC 钥匙',
  // 自定义按键：能力名 CUSTOM_BUTTON_LOW，实际键是 customButtonSingle
  customButtonSingle: '自定义按键（单键）',
  customButtonLow: '自定义按键（单键）',
  customButtonLeft: '自定义按键（左）',
  customButtonRight: '自定义按键（右）',
  customButtonCombination: '自定义按键（组合）',
  customButtonLongPress: '自定义按键（长按）',
  share: '车辆共享',
  inducedVibration: '感应振动',
  eCushionSensor: '座垫感应（eCushion）',
  lowBatteryWarn: '低电量告警',
  steepSlopeDescent: '陡坡缓降',
  customAudio: '自定义音效',
  firmwareVersion: '固件版本',
  ecuFirmware: 'ECU 固件',
  hmiFirmware: '仪表固件',
  labmsFirmware: 'LABMS 固件',
  bmsVoltage: '电池电压',
  inductiveUnlocking: '感应解锁',
  inductiveUnlocking2: '感应解锁 2.0',
  passwordUnlocking: '密码解锁',
  edgeSupportSensing: '边撑感应',
  cushionSensor: '座垫感应',
  cruiseControlHold: '动态定速巡航',
  kineticEnergyRecovery: '动能回收',
  eTcsTractionControl: 'eTCS',
  brakePower: '电子制动',
};

/**
 * 枚举类（SELECT）键的白名单。
 * 这些键在 vehicleSetInfo 里的值恰好也是 0/1/2 这种小整数，靠「0/1 当开关」会推断错，
 * 所以必须先命中白名单，再做开关推断。
 */
const SELECT_KEYS = new Set([
  'mileageUnit',
  'temperatureUnit',
  'timeFormat',
  'language',
  'motoPlay',
  'maximumSpeed',
  'rideMemoryModel',
  'chargingPower',
]);

/** 连续值（RANGE）键的白名单，min/max/step 见 RANGES。 */
const RANGE_KEYS = new Set([
  'topSpeed',
  'lowPower',
  'lowPowerThreshold',
  'maximumSpeedLimit',
  'inductiveDistance',
  'customInductiveDistance',
  'comeHomeWithYouTime',
  'leaveOffTime',
  'automaticShutdownTimeout',
  'soundEffectSetting.soundSize',
]);

/** 只读键：展示用，不参与写入（themeId 之类即使能写，App 也走主题接口）。 */
const READONLY_KEYS = new Set([
  'themeId',
  'themeName',
  'updateDate',
  'bmsUniqueId',
  'bluetoothStrengthMin',
  'bluetoothStrengthMax',
  'bluetoothAddress',
  'lowPowerThreshold',
]);

/**
 * RANGE 的区间都是**估算**：夹具只给了当前值，没有下发范围。
 * 依据是各值的量级（topSpeed 120、inductiveDistance 5、comeHomeWithYouTime 30、
 * leaveOffTime 180、soundSize 100），上下限留了车型差异的余量。
 */
const RANGES = {
  topSpeed: { min: 0, max: 150, step: 5, unit: 'km/h' },
  maximumSpeedLimit: { min: 10, max: 120, step: 5, unit: 'km/h' },
  lowPower: { min: 0, max: 50, step: 5, unit: '%' },
  lowPowerThreshold: { min: 0, max: 50, step: 5, unit: '%' },
  inductiveDistance: { min: 1, max: 10, step: 1, unit: 'm' },
  customInductiveDistance: { min: 1, max: 10, step: 1, unit: 'm' },
  comeHomeWithYouTime: { min: 0, max: 120, step: 10, unit: 's' },
  leaveOffTime: { min: 0, max: 300, step: 10, unit: 's' },
  automaticShutdownTimeout: { min: 5, max: 120, step: 5, unit: 's' },
  'soundEffectSetting.soundSize': { min: 0, max: 100, step: 1, unit: '' },
};

/**
 * SELECT 的选项：夹具里出现过的值优先，其余按「常识」补全（注释标了估算）。
 * value 一律字符串 —— 官方下发体 commandParam 也是字符串。
 */
const OPTIONS = {
  // 夹具值 '1'；2 = 英里是极核仪表通用做法（估算）
  mileageUnit: [
    { value: '1', label: '公里' },
    { value: '2', label: '英里' },
  ],
  // 夹具值 '1'；2 = 华氏度（估算）
  temperatureUnit: [
    { value: '1', label: '摄氏度' },
    { value: '2', label: '华氏度' },
  ],
  // 夹具值 '1'；2 = 12 小时制（估算）
  timeFormat: [
    { value: '1', label: '24 小时制' },
    { value: '2', label: '12 小时制' },
  ],
  // 夹具值 '1'（估算）
  language: [
    { value: '1', label: '中文' },
    { value: '2', label: 'English' },
  ],
  // items.json 里 MOTOPLAY 的 functionConfig：1 = 支持扫码投屏；2 为关闭（估算）
  motoPlay: [
    { value: '1', label: '扫码投屏' },
    { value: '2', label: '关闭' },
  ],
  // 夹具值 1；档位含义未在响应里给出，按限速档位补全（估算）
  maximumSpeed: [
    { value: '1', label: '低速' },
    { value: '2', label: '中速' },
    { value: '3', label: '高速' },
  ],
  // 夹具值 0（估算）
  rideMemoryModel: [
    { value: '0', label: '关闭' },
    { value: '1', label: '开启' },
  ],
};

/**
 * v2 简称 → v1 能力名。只用于查 isSupport / 蓝牙码，不改变 item.code。
 */
const CODE_ALIAS = {
  MAX_SPEED: 'MAXIMUM_SPEED',
  CUSHION_SENSOR: 'E_CUSHION_SENSOR',
  AUTO_SHUTDOWN_TIME: 'AUTO_SHUTDOWN_TIME_GO',
};

/**
 * vehicleSetInfo 里的键名和 functionCode 推导结果不一致的，逐个点名。
 * 这些是抓包对出来的，不是猜的。
 */
const STATE_KEY_ALIAS = {
  maxSpeed: 'maximumSpeed', // v2 的 MAX_SPEED 就是 info 里的最高车速档
  customNfc: 'originNfc', // CUSTOM_NFC 分组里能读的是 NFC 开关
  // 离车关机：能力名 AUTO_SHUTDOWN_TIME(_GO)，info 里叫 automaticShutdown
  // （disabledContent 也是用这个名字，所以这条别名同时决定了禁用文案能否命中）
  autoShutdownTime: 'automaticShutdown',
  autoShutdownTimeGo: 'automaticShutdown',
  automaticParkingGo: 'automaticParking', // 能力名带 _GO 后缀，info 里没有
  timeFormatSetting: 'timeFormat', // 蓝牙能力名 TIME_FORMAT_SETTING，info 里叫 timeFormat
  soundEffect: 'soundEffectStatus', // SOUND_EFFECT 总开关，info 里是 soundEffectStatus
  inductiveUnlockingDistance: 'inductiveDistance', // 蓝牙能力名更长
  brakePower: 'barkePower', // 上游拼写错误：info 里就是 barkePower
  motoplay: 'motoPlay', // MOTOPLAY 是整段大写，推导出来没有驼峰
  customButtonLow: 'customButtonSingle', // CUSTOM_BUTTON_LOW 的蓝牙名是 CUSTOM_BUTTON_SINGLE（单键）
};

/**
 * 没有 subFunctions 的分组（以及有 subFunctions 但官方主页没列全的分组）里，
 * 从 v1 items.json 或 vehicleSetInfo 补出来的项（code 一律用 v1 能力名或推导码）。
 * 补出来的项追加在官方子项之后，所以 tile.items 前后两段含义不同：
 * 前段 = itemsV2.subFunctions，后段 = 这里补的。来源标记见每项的 note 字段
 * （有蓝牙能力码的 note=null，只有 info 值的 note 写明「仅 vehicleSetInfo 下发」）。
 */
const TILE_EXTRAS = {
  // v1 里 AVOID_THEFT_SECURITY 自身没有能力码，安全类叶子项分散在其它能力码上
  AVOID_THEFT_SECURITY: ['EMERGENCY_BRAKE_FLASH', 'AUTOMATIC_LOCK', 'RADAR_BSD', 'RADAR_RCW', 'ANTI_LOCK_BRAKE_SYSTEM'],
  AUDIO_SET: ['SOUND_EFFECT'], // 15 个音效子项另行展开
  CUSTOM_KEY_SET: ['CUSTOM_BUTTON_LOW'],
  INSTRUMENT_SET: [
    'TIME_FORMAT_SETTING',
    'MILEAGE_UNIT',
    'TEMPERATURE_UNIT',
    'LANGUAGE',
    'MOTOPLAY',
    'THEME_ID',
    'THEME_NAME',
    'UPDATE_DATE',
  ],
  BATTERY_INFO_AND_SETTINGS: [
    'LOW_POWER',
    'LOW_POWER_THRESHOLD',
    'EXTENDED_RANGE',
    'PRECISION_ENDURANCE',
    'ULTRA_LOW_BATTERY_REMINDER',
    'CHARGING_POWER',
    'BMS_BIND',
    'BMS_UNIQUE_ID',
  ],
  SMART_PART: ['BLUETOOTH_STRENGTH_MIN', 'BLUETOOTH_STRENGTH_MAX', 'CUSTOM_BLUETOOTH_STRENGTH'],
  VEHICLE_INFO: [
    'TOP_SPEED',
    'BLUETOOTH_ADDRESS',
    'FIRMWARE_VERSION',
    'ECU_FIRMWARE',
    'HMI_FIRMWARE',
    'LABMS_FIRMWARE',
    'BMS_VOLTAGE',
  ],
  COME_HOME_WITH_YOU: ['COME_HOME_WITH_YOU', 'COME_HOME_WITH_YOU_TIME', 'LEAVE_OFF_TIME'],
  KEY_SHARE: ['INDUCTIVE_UNLOCKING_DISTANCE', 'CUSTOM_INDUCTIVE_DISTANCE'],
  DRIVING_MODE_SETTING: ['MAXIMUM_SPEED_LIMIT', 'RIDE_MEMORY_MODEL'],
  QUICK_RIDE: ['AUTOMATIC_SHUTDOWN_TIMEOUT'],
};

/** 反向推导用的已知 code 表：LABELS 的 stateKey 反推 + v1/v2 里出现过的代码。 */
const KNOWN_CODES = [
  ...Object.keys(LABELS).map((key) => key),
  'INDUCTIVE_UNLOCKING',
  'INDUCTIVE_UNLOCKING_2',
  'INDUCTIVE_UNLOCKING_DISTANCE',
  'CUSTOM_INDUCTIVE_DISTANCE',
  'CUSTOM_NFC',
  'PASSWORD_UNLOCKING',
  'SHARE',
  'KEY_SHARE',
  'E_TCS_TRACTION_CONTROL',
  'KINETIC_ENERGY_RECOVERY',
  'BRAKE_POWER',
  'MAX_SPEED',
  'MAXIMUM_SPEED',
  'MAXIMUM_SPEED_LIMIT',
  'EDGE_SUPPORT_SENSING',
  'CUSHION_SENSOR',
  'E_CUSHION_SENSOR',
  'AUTO_SHUTDOWN_TIME',
  'AUTO_SHUTDOWN_TIME_GO',
  'AUTOMATIC_SHUTDOWN_TIMEOUT',
  'CRUISE_CONTROL_HOLD',
  'COME_HOME_WITH_YOU',
  'COME_HOME_WITH_YOU_TIME',
  'LEAVE_OFF_TIME',
  'AUTOMATIC_PARKING_GO',
  'TOP_SPEED',
  'LOW_POWER',
  'LOW_BATTERY_WARN',
  'EXTENDED_RANGE',
  'PRECISION_ENDURANCE',
  'ULTRA_LOW_BATTERY_REMINDER',
  'CHARGING_POWER',
  'BMS_BIND',
  'BMS_UNIQUE_ID',
  'BMS_VOLTAGE',
  'SOUND_EFFECT',
  'SOUND_EFFECT_STATUS',
  'TIME_FORMAT_SETTING',
  'MILEAGE_UNIT',
  'TEMPERATURE_UNIT',
  'LANGUAGE',
  'THEME_ID',
  'THEME_NAME',
  'UPDATE_DATE',
  'MOTOPLAY',
  'RIDE_MEMORY_MODEL',
  'BLUETOOTH_STRENGTH_MIN',
  'BLUETOOTH_STRENGTH_MAX',
  'CUSTOM_BLUETOOTH_STRENGTH',
  'BLUETOOTH_ADDRESS',
  'FIRMWARE_VERSION',
  'ECU_FIRMWARE',
  'HMI_FIRMWARE',
  'LABMS_FIRMWARE',
  'EMERGENCY_BRAKE_FLASH',
  'AUTOMATIC_LOCK',
  'RADAR_BSD',
  'RADAR_RCW',
  'ANTI_LOCK_BRAKE_SYSTEM',
  'CUSTOM_BUTTON_LOW',
];

/**
 * 'CUSHION_SENSOR' -> 'cushionSensor'
 * 'INDUCTIVE_UNLOCKING_2' -> 'inductiveUnlocking2'
 * 'E_TCS_TRACTION_CONTROL' -> 'eTcsTractionControl'
 *
 * 规则：按 '_' 切段，首段全小写，其余段首字母大写、尾字母小写，纯数字段原样拼接。
 */
export function deriveStateKey(functionCode) {
  if (typeof functionCode !== 'string' || functionCode.length === 0) return '';
  const segments = functionCode.split('_').filter((seg) => seg.length > 0);
  return segments
    .map((seg, index) => {
      if (/^\d+$/.test(seg)) return seg;
      const head = seg.charAt(0);
      const tail = seg.slice(1).toLowerCase();
      return index === 0 ? head.toLowerCase() + tail : head.toUpperCase() + tail;
    })
    .join('');
}

/**
 * 'cushionSensor' -> 'CUSHION_SENSOR'；'inductiveUnlocking2' -> 'INDUCTIVE_UNLOCKING_2'
 *
 * 先查已知表（LABELS 与能力码里出现过的），查不到再按 camelCase 反推：
 * 遇到大写字母或「字母后接数字」就断段，全部转大写用 '_' 连接。
 */
export function deriveFunctionCode(stateKey) {
  if (typeof stateKey !== 'string' || stateKey.length === 0) return '';
  // 点号路径（音效子项）只反推最后一段，前缀原样保留
  if (stateKey.includes('.')) {
    const dot = stateKey.lastIndexOf('.');
    return stateKey.slice(0, dot + 1) + deriveFunctionCode(stateKey.slice(dot + 1));
  }
  for (const code of KNOWN_CODES) {
    if (deriveStateKey(code) === stateKey) return code;
  }
  const segments = [];
  let current = '';
  for (let i = 0; i < stateKey.length; i += 1) {
    const ch = stateKey.charAt(i);
    const isUpper = ch >= 'A' && ch <= 'Z';
    const isDigit = ch >= '0' && ch <= '9';
    if (isUpper && current.length > 0) {
      segments.push(current);
      current = ch;
      continue;
    }
    if (isDigit) {
      // 字母后缀的数字单独成段：inductiveUnlocking2 -> INDUCTIVE_UNLOCKING_2
      if (current.length > 0) segments.push(current);
      let digits = ch;
      while (i + 1 < stateKey.length && stateKey.charAt(i + 1) >= '0' && stateKey.charAt(i + 1) <= '9') {
        i += 1;
        digits += stateKey.charAt(i);
      }
      segments.push(digits);
      current = '';
      continue;
    }
    current += ch;
  }
  if (current.length > 0) segments.push(current);
  return segments.map((seg) => seg.toUpperCase()).join('_');
}

/* ------------------------------------------------------------------ *
 * 内部工具
 * ------------------------------------------------------------------ */

/** 兼容 { code, message, data } 包装与裸数据；null/undefined 一律退成 {}。 */
function unwrap(response) {
  if (response == null) return {};
  if (Array.isArray(response)) return response;
  if (typeof response !== 'object') return {};
  if (Object.prototype.hasOwnProperty.call(response, 'data')) {
    return response.data == null ? {} : response.data;
  }
  return response;
}

/** 把 data 里可能是数组、也可能是 { list: [...] } 的东西统一成数组。 */
function asList(value) {
  if (Array.isArray(value)) return value;
  if (value && typeof value === 'object') {
    for (const key of ['vehicleSetFunctionVOList', 'list', 'records', 'items']) {
      if (Array.isArray(value[key])) return value[key];
    }
  }
  return [];
}

/** 判断纯对象（用于判空容错，不把 [] / null 当对象）。 */
function isPlainObject(value) {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}

/**
 * 按 stateKey 取值，支持点号路径（'soundEffectSetting.soundSize'）。
 * 顺序：精确 → 别名 → 忽略大小写（夹具里 etcsTractionControl / ecushionSensor 是小写开头）。
 * 读不到返回 null（不是 undefined，方便 JSON 序列化与 UI 判空）。
 */
function readValue(infoData, stateKey) {
  if (!isPlainObject(infoData) || typeof stateKey !== 'string' || stateKey.length === 0) return null;
  const exact = readPath(infoData, stateKey);
  if (exact !== undefined) return exact;
  const aliasKey = STATE_KEY_ALIAS[stateKey];
  if (aliasKey) {
    const aliased = readPath(infoData, aliasKey);
    if (aliased !== undefined) return aliased;
    const flatAlias = matchKeyIgnoreCase(infoData, aliasKey);
    if (flatAlias) return infoData[flatAlias];
  }
  const flat = matchKeyIgnoreCase(infoData, stateKey);
  if (flat) return infoData[flat];
  return null;
}

function readPath(infoData, path) {
  const parts = path.split('.');
  let cursor = infoData;
  for (const part of parts) {
    if (!isPlainObject(cursor) || !Object.prototype.hasOwnProperty.call(cursor, part)) return undefined;
    cursor = cursor[part];
  }
  return cursor;
}

function matchKeyIgnoreCase(infoData, path) {
  const first = path.split('.')[0];
  const target = first.toLowerCase();
  for (const key of Object.keys(infoData)) {
    if (key.toLowerCase() === target) return key;
  }
  return null;
}

/**
 * 控件推断。顺序很重要（注释即规则）：
 *   1. 只读名单 → READONLY（themeId / updateDate / 蓝牙强度区间 / bmsUniqueId ...）
 *   2. 连续值名单 → RANGE
 *   3. 枚举名单 → SELECT
 *   4. customInductiveDistance 特例：空串 = 未自定义（只读展示），有值 = RANGE
 *   5. 布尔或 0/1 数字 → SWITCH（写回 0/1）
 *   6. 非数字字符串 → TEXT（'07:00:00' 这类时间文本）
 *   7. 其它（对象 / null / 数组）→ READONLY
 */
function inferControl(stateKey, value) {
  if (READONLY_KEYS.has(stateKey)) return CONTROL.READONLY;
  if (stateKey === 'customInductiveDistance') {
    return value === '' || value == null ? CONTROL.READONLY : CONTROL.RANGE;
  }
  if (RANGE_KEYS.has(stateKey)) return CONTROL.RANGE;
  if (SELECT_KEYS.has(stateKey)) return CONTROL.SELECT;
  if (typeof value === 'boolean') return CONTROL.SWITCH;
  if (typeof value === 'number') return value === 0 || value === 1 ? CONTROL.SWITCH : CONTROL.TEXT;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (trimmed === '') return CONTROL.TEXT;
    if (trimmed === '0' || trimmed === '1') return CONTROL.SWITCH;
    if (/^-?\d+(\.\d+)?$/.test(trimmed)) return CONTROL.TEXT;
    return CONTROL.TEXT;
  }
  return CONTROL.READONLY;
}

/** 选项生成：充电功率优先用官方 options 接口，其次 OPTIONS 表，最后 items.functionConfig。 */
function buildOptions(stateKey, value, chargeOptions, v1Entry) {
  let options = null;
  if (stateKey === 'chargingPower' && Array.isArray(chargeOptions) && chargeOptions.length > 0) {
    options = chargeOptions
      .filter((opt) => opt && opt.value != null)
      .map((opt) => ({
        value: String(opt.value),
        // key 是瓦数（350/450/.../1900），label 里带单位，value 保持官方下发值
        label: opt.key == null ? String(opt.value) : `${opt.key}W`,
      }));
  } else if (Array.isArray(OPTIONS[stateKey])) {
    options = OPTIONS[stateKey].map((opt) => ({ value: String(opt.value), label: String(opt.label) }));
  } else {
    options = optionsFromFunctionConfig(v1Entry);
  }
  if (!options || options.length === 0) return [];
  // 当前值不在枚举里时补一条，避免 UI 选中项为空（例如 chargingPower = '200'）
  if (value != null && value !== '') {
    const asString = String(value);
    if (!options.some((opt) => opt.value === asString)) {
      options.push({ value: asString, label: asString });
    }
  }
  return options;
}

/**
 * 从 items.functionConfig 生成枚举项（自定义按键这类）。
 * 过滤掉版本配置：AUTO_SHUTDOWN_TIME_GO 的 functionConfig 是 '1.0' / '2.0'，不是可选项。
 * 取值按配置顺序 1..n（**估算**：官方下发值就是下标，夹具里 customButton* = 3 对应第 3 项）。
 */
function optionsFromFunctionConfig(v1Entry) {
  const configs = Array.isArray(v1Entry && v1Entry.functionConfig) ? v1Entry.functionConfig : [];
  const usable = configs.filter(
    (cfg) =>
      isPlainObject(cfg) &&
      typeof cfg.functionName === 'string' &&
      cfg.functionName.length > 0 &&
      cfg.functionName !== cfg.functionCode &&
      !/^\d+(\.\d+)?$/.test(String(cfg.functionCode)),
  );
  if (usable.length === 0) return [];
  return usable.map((cfg, index) => ({ value: String(index + 1), label: cfg.functionName }));
}

/**
 * disabledContent 命中：条目形如 { functionName: 'automaticShutdown' | 'cushionSensor_01', content }。
 * 命中顺序：精确同名 → 带后缀的（取数字最小的 _01）→ 大小写不敏感。
 * 注意 disabledContent 里有 easyReadyGo_02/_03 这类「同前缀多文案」，选第一条最稳。
 */
function findDisabledReason(infoData, candidates) {
  const list = Array.isArray(infoData && infoData.disabledContent) ? infoData.disabledContent : [];
  if (list.length === 0) return null;
  const entries = list.filter((entry) => entry && typeof entry.functionName === 'string');
  for (const name of candidates) {
    if (!name) continue;
    const exact = entries.find((entry) => entry.functionName === name);
    if (exact) return exact.content || null;
  }
  for (const name of candidates) {
    if (!name) continue;
    const suffixed = entries
      .filter((entry) => entry.functionName.startsWith(`${name}_`))
      .sort((a, b) => a.functionName.localeCompare(b.functionName));
    if (suffixed.length > 0) return suffixed[0].content || null;
  }
  for (const name of candidates) {
    if (!name) continue;
    const lower = name.toLowerCase();
    const loose = entries.find((entry) => entry.functionName.toLowerCase() === lower);
    if (loose) return loose.content || null;
  }
  return null;
}

/* ------------------------------------------------------------------ *
 * 主入口
 * ------------------------------------------------------------------ */

/**
 * 把四份响应拼成渲染模型。任何入参缺失、类型不对、字段为空都不抛，退化成空模型。
 *
 * @param {{ itemsV2?: any, items?: any, info?: any, blueToothCodes?: any, chargePowerOptions?: any }} [input]
 * @returns {{ vin: string, updatedAt: string, rows: Array, tiles: Array, items: Array,
 *             byCode: Object, byStateKey: Object, unsupported: Array,
 *             stats: { tiles: number, items: number, writable: number } }}
 */
export function buildSettingsModel(input = {}) {
  const { itemsV2, items, info, blueToothCodes, chargePowerOptions } = input || {};

  const infoData = unwrap(info);
  const v2Data = unwrap(itemsV2);
  const v1List = asList(unwrap(items));
  const btList = asList(unwrap(blueToothCodes));
  const chargeOptions = asList(unwrap(chargePowerOptions));
  const v2Rows = asList(v2Data);

  // v1 能力表索引
  const v1Index = new Map();
  const serviceIndex = new Map();
  for (const entry of v1List) {
    if (!isPlainObject(entry) || typeof entry.functionCode !== 'string') continue;
    v1Index.set(entry.functionCode, entry);
    for (const bt of Array.isArray(entry.blueToothServiceCodeList) ? entry.blueToothServiceCodeList : []) {
      if (bt && typeof bt.functionName === 'string' && bt.serviceCode != null && !serviceIndex.has(bt.functionName)) {
        serviceIndex.set(bt.functionName, bt.serviceCode);
      }
    }
  }
  for (const bt of btList) {
    if (bt && typeof bt.functionName === 'string' && bt.serviceCode != null && !serviceIndex.has(bt.functionName)) {
      serviceIndex.set(bt.functionName, bt.serviceCode);
    }
  }

  const v1For = (code) => v1Index.get(code) || (CODE_ALIAS[code] ? v1Index.get(CODE_ALIAS[code]) : undefined);

  const serviceCodeFor = (code) => {
    if (typeof code !== 'string' || code.length === 0) return null;
    // 复合 code（SOUND_EFFECT.switchLowVelocity）用前缀查能力码
    const base = code.includes('.') ? code.slice(0, code.indexOf('.')) : code;
    const entry = v1For(base);
    if (entry) {
      const own = (Array.isArray(entry.blueToothServiceCodeList) ? entry.blueToothServiceCodeList : []).find(
        (bt) => bt && (bt.functionName === base || bt.functionName === code),
      );
      if (own && own.serviceCode != null) return own.serviceCode;
      if (entry.serviceCode != null) return entry.serviceCode;
    }
    if (serviceIndex.has(code)) return serviceIndex.get(code);
    if (serviceIndex.has(base)) return serviceIndex.get(base);
    const aliasName = CODE_ALIAS[base];
    if (aliasName && serviceIndex.has(aliasName)) return serviceIndex.get(aliasName);
    return null;
  };

  const itemsOut = [];
  const byCode = {};
  const byStateKey = {};
  const unsupported = [];

  /** 建一个 SettingItem；统一走这里，保证字段齐全、可写性一致。 */
  const createItem = ({ code, stateKey, label, groupCode, groupName, row, serverLabel }) => {
    const resolvedStateKey = stateKey || STATE_KEY_ALIAS[deriveStateKey(code)] || deriveStateKey(code);
    const value = readValue(infoData, resolvedStateKey);
    const v1Entry = v1For(code);
    const configOptions = optionsFromFunctionConfig(v1Entry);
    let control = inferControl(resolvedStateKey, value);
    // 能力表给了 functionConfig 枚举、但值本身是数字（如 customButtonLow = 3）时按枚举处理
    if (configOptions.length > 0 && control !== CONTROL.READONLY && control !== CONTROL.RANGE) {
      control = CONTROL.SELECT;
    }
    const supported = !(v1Entry && (v1Entry.isSupport === false || v1Entry.isSupportSetting === false));
    const range = control === CONTROL.RANGE ? RANGES[resolvedStateKey] || null : null;
    const options = control === CONTROL.SELECT ? buildOptions(resolvedStateKey, value, chargeOptions, v1Entry) : [];
    const writable = supported && control !== CONTROL.READONLY && control !== CONTROL.ACTION;
    const disabledReason = findDisabledReason(infoData, [resolvedStateKey, deriveStateKey(code), code]);
    const note =
      v1Entry || serviceCodeFor(code) != null
        ? null
        : '仅 vehicleSetInfo 下发，无独立能力码；写入前需确认官方参数';

    return {
      code,
      label: serverLabel || LABELS[resolvedStateKey] || LABELS[deriveStateKey(code)] || code,
      stateKey: resolvedStateKey,
      groupCode: groupCode || null,
      groupName: groupName || null,
      row: typeof row === 'number' ? row : null,
      value,
      control,
      options,
      min: range ? range.min : null,
      max: range ? range.max : null,
      step: range ? range.step : null,
      unit: range ? range.unit : null,
      serviceCode: serviceCodeFor(code),
      supported,
      writable,
      disabledReason,
      note,
    };
  };

  const seenStateKeys = new Set();
  /** 落库：按 stateKey 去重（v2 的 MAX_SPEED 与 v1 的 MAXIMUM_SPEED 是同一个开关）。 */
  const pushItem = (item) => {
    if (!item) return null;
    if (seenStateKeys.has(item.stateKey)) return byStateKey[item.stateKey] || null;
    seenStateKeys.add(item.stateKey);
    itemsOut.push(item);
    if (!byCode[item.code]) byCode[item.code] = item;
    byStateKey[item.stateKey] = item;
    if (!item.supported) {
      unsupported.push({ code: item.code, label: item.label, groupName: item.groupName });
    }
    return item;
  };

  /** 从能力码建项：stateKey 先过别名表，中文名先查 LABELS（v1 顶层没有 functionName）。 */
  const createFromV1Code = (code, groupCode, groupName, row) => {
    const derived = deriveStateKey(code);
    const stateKey = STATE_KEY_ALIAS[derived] || derived;
    return createItem({ code, stateKey, groupCode, groupName, row });
  };

  // 1) 按 itemsV2 的二维结构逐行走
  const rows = [];
  const tiles = [];
  for (let rowIndex = 0; rowIndex < v2Rows.length; rowIndex += 1) {
    const rawRow = Array.isArray(v2Rows[rowIndex]) ? v2Rows[rowIndex] : [v2Rows[rowIndex]];
    const tileList = [];
    for (const rawTile of rawRow) {
      if (!isPlainObject(rawTile)) continue;
      const code = typeof rawTile.functionCode === 'string' ? rawTile.functionCode : '';
      if (!code) continue;
      const name = rawTile.functionName || LABELS[deriveStateKey(code)] || code;
      const tile = {
        code,
        name,
        describe: rawTile.describe == null ? null : rawTile.describe,
        row: rowIndex,
        type: rawTile.type == null ? null : rawTile.type,
        available: true,
        items: [],
      };

      const v1Entry = v1For(code);
      // 分组本身在能力表里被标成不支持 → 整块置灰
      if (v1Entry && (v1Entry.isSupport === false || v1Entry.isSupportSetting === false)) {
        tile.available = false;
      }

      const subs = Array.isArray(rawTile.subFunctions) ? rawTile.subFunctions : [];
      for (const sub of subs) {
        if (!isPlainObject(sub) || typeof sub.functionCode !== 'string') continue;
        const subStateKey = STATE_KEY_ALIAS[deriveStateKey(sub.functionCode)] || deriveStateKey(sub.functionCode);
        const item = pushItem(
          createItem({
            code: sub.functionCode,
            stateKey: subStateKey,
            label: sub.functionName || null,
            groupCode: code,
            groupName: name,
            row: rowIndex,
            serverLabel: sub.functionName || null,
          }),
        );
        if (item) tile.items.push(item);
      }

      // 2) 补充项：按 functionCode 从 v1 / info 补。
      //    官方子项排在前面（KEY_SHARE 就是 4 个 subFunctions），补充项追加在后面，
      //    例如「感应距离」「限速车速」「离车关机延时」，UI 直接渲染 tile.items 即可。
      for (const extraCode of TILE_EXTRAS[code] || []) {
        const item = pushItem(createFromV1Code(extraCode, code, name, rowIndex));
        if (item) tile.items.push(item);
      }

      // 3) AUDIO_SET 展开 vehicleSetInfo.soundEffectSetting 的 15 个音效子项
      if (code === 'AUDIO_SET') {
        const sound = isPlainObject(infoData.soundEffectSetting) ? infoData.soundEffectSetting : {};
        for (const soundKey of Object.keys(sound)) {
          const compositeCode = `SOUND_EFFECT.${soundKey}`;
          const stateKey = `soundEffectSetting.${soundKey}`;
          const item = pushItem(
            createItem({
              code: compositeCode,
              stateKey,
              label: LABELS[stateKey] || null,
              groupCode: code,
              groupName: name,
              row: rowIndex,
              serverLabel: null,
            }),
          );
          if (item) tile.items.push(item);
        }
      }

      // 4) 没有 subFunctions 也没补到东西的分组：分组自身若在 info 里有值，就当单项处理
      if (tile.items.length === 0) {
        const selfKey = STATE_KEY_ALIAS[deriveStateKey(code)] || deriveStateKey(code);
        if (readValue(infoData, selfKey) !== null) {
          const item = pushItem(createItem({ code, stateKey: selfKey, label: name, groupCode: code, groupName: name, row: rowIndex }));
          if (item) tile.items.push(item);
        }
      }

      tileList.push(tile);
      tiles.push(tile);
    }
    rows.push({ index: rowIndex, tiles: tileList });
  }

  // 5) 兜底：v1 里有蓝牙能力码、但没被任何磁贴收走的项，避免「能写却看不到」
  //    分组容器（自身就是磁贴，或蓝牙叶子项已经全部建过项）不算可操作项，跳过。
  const tileCodes = new Set(tiles.map((tile) => tile.code));
  for (const entry of v1List) {
    if (!isPlainObject(entry) || typeof entry.functionCode !== 'string') continue;
    if (byCode[entry.functionCode] || tileCodes.has(entry.functionCode)) continue;
    const btNames = (Array.isArray(entry.blueToothServiceCodeList) ? entry.blueToothServiceCodeList : [])
      .map((bt) => bt && bt.functionName)
      .filter((name) => typeof name === 'string');
    if (btNames.length === 0) continue;
    if (btNames.every((name) => byCode[name])) continue;
    pushItem(createFromV1Code(entry.functionCode, null, '未分组', null));
  }

  const writableCount = itemsOut.filter((item) => item.writable).length;

  return {
    vin: typeof infoData.vinNo === 'string' ? infoData.vinNo : '',
    updatedAt: typeof infoData.updateDate === 'string' ? infoData.updateDate : '',
    rows,
    tiles,
    items: itemsOut,
    byCode,
    byStateKey,
    unsupported,
    stats: { tiles: tiles.length, items: itemsOut.length, writable: writableCount },
  };
}

/* ------------------------------------------------------------------ *
 * 写入包
 * ------------------------------------------------------------------ */

/**
 * 官方写入体（字段名来自 3.0.6 dex 的 AddVehicleSetReq）：
 * { vin, serviceCode, vehicleSetEnum, commandParam, flag, message }
 * serviceCode 字符串化（后端按字符串反序列化），commandParam 一律 String(nextValue)。
 */
export function buildWriteBody(item, nextValue, { vin, flag = true } = {}) {
  const safeItem = isPlainObject(item) ? item : {};
  return {
    vin: typeof vin === 'string' ? vin : vin == null ? '' : String(vin),
    serviceCode: safeItem.serviceCode == null ? null : String(safeItem.serviceCode),
    vehicleSetEnum: typeof safeItem.code === 'string' ? safeItem.code : '',
    commandParam: nextValue == null ? '' : String(nextValue),
    flag: Boolean(flag),
    message: '',
  };
}

/**
 * 表单字符串 -> 控件对应的值。
 *   switch  '1'/'0'/'true'/'on' -> 1/0
 *   range   数字，超出 min/max 会夹紧；非法输入退回原值
 *   select  保持字符串（options 的 value 都是字符串）
 *   TEXT    原样字符串；READONLY / ACTION 不接受输入，直接回原值
 */
export function coerceValue(item, rawInput) {
  if (!isPlainObject(item)) return rawInput == null ? null : rawInput;
  const control = item.control;
  if (control === CONTROL.SWITCH) {
    if (typeof rawInput === 'boolean') return rawInput ? 1 : 0;
    if (typeof rawInput === 'number') return rawInput ? 1 : 0;
    const text = String(rawInput == null ? '' : rawInput).trim().toLowerCase();
    return text === '1' || text === 'true' || text === 'on' || text === 'yes' ? 1 : 0;
  }
  if (control === CONTROL.RANGE) {
    const num = Number(String(rawInput == null ? '' : rawInput).trim());
    if (!Number.isFinite(num)) return item.value == null ? null : item.value;
    let next = num;
    if (typeof item.min === 'number' && next < item.min) next = item.min;
    if (typeof item.max === 'number' && next > item.max) next = item.max;
    return next;
  }
  if (control === CONTROL.SELECT) {
    if (rawInput == null) return null;
    return String(rawInput);
  }
  if (control === CONTROL.READONLY || control === CONTROL.ACTION) {
    return item.value == null ? null : item.value;
  }
  if (rawInput == null) return item.value == null ? null : item.value;
  return String(rawInput);
}

/** 归一化后比较，避免 '1' vs 1、'25' vs 25 被当成脏数据。 */
function compareKey(item, value) {
  if (value == null) return '';
  if (item.control === CONTROL.SWITCH) return String(value === true ? 1 : value === false ? 0 : Number(value) ? 1 : 0);
  if (item.control === CONTROL.RANGE) {
    const num = Number(value);
    return Number.isFinite(num) ? String(num) : String(value);
  }
  return String(value);
}

/** 值是否变更（用于「保存」按钮置灰）。 */
export function isDirty(item, nextValue) {
  if (!isPlainObject(item)) return false;
  if (nextValue === undefined) return false;
  return compareKey(item, item.value) !== compareKey(item, nextValue);
}

/** 汇总计数，和 model.stats 同源。 */
export function summarize(model) {
  const stats = model && isPlainObject(model.stats) ? model.stats : {};
  const unsupportedList = model && Array.isArray(model.unsupported) ? model.unsupported : [];
  return {
    total: typeof stats.items === 'number' ? stats.items : 0,
    writable: typeof stats.writable === 'number' ? stats.writable : 0,
    unsupported: unsupportedList.length,
  };
}

export default {
  CONTROL,
  deriveStateKey,
  deriveFunctionCode,
  buildSettingsModel,
  buildWriteBody,
  coerceValue,
  isDirty,
  summarize,
};
