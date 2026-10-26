/**
 * 车辆设置模型的离线测试：node --test tests/
 *
 * 夹具是官方 App 3.0.6 真机响应（tests/fixtures/settings/），
 * 所以断言里的数字（9 行 / 14 磁贴 / 15 个音效子项）就是官方主页的真实结构。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  CONTROL,
  deriveStateKey,
  deriveFunctionCode,
  buildSettingsModel,
  buildWriteBody,
  coerceValue,
  isDirty,
  summarize,
} from '../sdk/vehicle-settings.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.join(HERE, 'fixtures', 'settings');

/** 读夹具；缺文件就返回 null，验证模型对 null 入参的容错。 */
function fixture(name) {
  const file = path.join(FIXTURES, name);
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null;
}

const source = {
  itemsV2: fixture('items-v2.json'),
  items: fixture('items.json'),
  info: fixture('vehicle-set-info.json'),
  blueToothCodes: fixture('bluetooth-codes.json'),
  chargePowerOptions: fixture('charge-power-options.json'),
};

const model = buildSettingsModel(source);

/** 找磁贴 */
const tile = (code) => model.tiles.find((t) => t.code === code);

/* ------------------------------- 命名互转 ------------------------------- */

test('deriveStateKey：能力码 -> vehicleSetInfo 键名', () => {
  assert.equal(deriveStateKey('CUSHION_SENSOR'), 'cushionSensor');
  assert.equal(deriveStateKey('INDUCTIVE_UNLOCKING_2'), 'inductiveUnlocking2');
  assert.equal(deriveStateKey('E_TCS_TRACTION_CONTROL'), 'eTcsTractionControl');
  assert.equal(deriveStateKey('SOUND_EFFECT'), 'soundEffect');
  assert.equal(deriveStateKey('MILEAGE_UNIT'), 'mileageUnit');
  // 容错：空值、非字符串不给异常
  assert.equal(deriveStateKey(''), '');
  assert.equal(deriveStateKey(null), '');
  assert.equal(deriveStateKey(undefined), '');
  assert.equal(deriveStateKey(123), '');
});

test('deriveFunctionCode：反向推导与往返一致', () => {
  assert.equal(deriveFunctionCode('cushionSensor'), 'CUSHION_SENSOR');
  assert.equal(deriveFunctionCode('inductiveUnlocking2'), 'INDUCTIVE_UNLOCKING_2');
  assert.equal(deriveFunctionCode('eTcsTractionControl'), 'E_TCS_TRACTION_CONTROL');
  assert.equal(deriveFunctionCode('themeId'), 'THEME_ID');
  // 复合 code（音效子项）只反推最后一段
  assert.equal(deriveFunctionCode('soundEffectSetting.soundSize'), 'soundEffectSetting.SOUND_SIZE');
  assert.equal(deriveFunctionCode(''), '');
  assert.equal(deriveFunctionCode(null), '');

  // 常用键必须严格往返，否则 byStateKey 会漂移
  for (const code of ['CUSHION_SENSOR', 'INDUCTIVE_UNLOCKING_2', 'MILEAGE_UNIT', 'TOP_SPEED', 'EDGE_SUPPORT_SENSING']) {
    assert.equal(deriveFunctionCode(deriveStateKey(code)), code, `往返失败：${code}`);
  }
});

/* ------------------------------- 结构 ------------------------------- */

test('磁贴结构：9 行 / 14 个分组，与 itemsV2 一一对应', () => {
  const rows = source.itemsV2.data.vehicleSetFunctionVOList;
  assert.equal(model.rows.length, 9);
  assert.equal(model.tiles.length, 14);
  assert.deepEqual(
    model.rows.map((r) => r.tiles.length),
    rows.map((r) => r.length),
  );
  assert.deepEqual(
    model.rows.map((r) => r.index),
    [0, 1, 2, 3, 4, 5, 6, 7, 8],
  );
  // 拍平顺序 = 行序
  const flatFromRows = model.rows.flatMap((r) => r.tiles.map((t) => t.code));
  assert.deepEqual(
    model.tiles.map((t) => t.code),
    flatFromRows,
  );
  // 磁贴上的 row 与所在行一致
  for (const row of model.rows) {
    for (const t of row.tiles) assert.equal(t.row, row.index);
  }
});

test('KEY_SHARE 有 4 个官方子项，顺序与官方一致', () => {
  const keyShare = tile('KEY_SHARE');
  assert.ok(keyShare, '缺少 KEY_SHARE 磁贴');
  assert.equal(keyShare.name, '钥匙与共享');

  // source.itemsV2 里 KEY_SHARE.subFunctions 就是这 4 个，模型必须一一落成 SettingItem
  const officialCodes = source.itemsV2.data.vehicleSetFunctionVOList[1][0].subFunctions.map((s) => s.functionCode);
  assert.equal(officialCodes.length, 4);
  const official = keyShare.items.filter((i) => officialCodes.includes(i.code));
  assert.equal(official.length, 4, 'KEY_SHARE 的官方子项应为 4 个');
  assert.deepEqual(
    official.map((i) => i.code),
    ['INDUCTIVE_UNLOCKING', 'CUSTOM_NFC', 'PASSWORD_UNLOCKING', 'SHARE'],
  );
  // 官方子项必须排在最前面（后面 2 个是从 info / v1 补的感应距离，见下一个断言）
  assert.deepEqual(
    keyShare.items.slice(0, 4).map((i) => i.code),
    officialCodes,
  );
  assert.deepEqual(
    official.map((i) => i.label),
    ['感应解锁', 'NFC钥匙', '密码解锁', '车辆共享'],
  );
  // 有能力的项才能写；车辆共享只读
  assert.equal(official[0].writable, true);
  assert.equal(official[3].control, CONTROL.READONLY);
  assert.equal(official[3].writable, false);
  // 蓝牙能力码来自 items.blueToothServiceCodeList
  assert.equal(official[0].serviceCode, 268435526);

  // 补充项：感应解锁的距离设置只在 vehicleSetInfo / 蓝牙能力名里出现，仍要归到本组
  const extras = keyShare.items.slice(4).map((i) => i.code);
  assert.deepEqual(extras, ['INDUCTIVE_UNLOCKING_DISTANCE', 'CUSTOM_INDUCTIVE_DISTANCE']);
  assert.equal(model.byCode.INDUCTIVE_UNLOCKING_DISTANCE.stateKey, 'inductiveDistance');
  assert.equal(model.byCode.INDUCTIVE_UNLOCKING_DISTANCE.value, 5);
});

test('SettingItem 字段齐全且类型正确', () => {
  const item = model.byCode.EDGE_SUPPORT_SENSING;
  const keys = [
    'code', 'label', 'stateKey', 'groupCode', 'groupName', 'row', 'value', 'control',
    'options', 'min', 'max', 'step', 'unit', 'serviceCode', 'supported', 'writable',
    'disabledReason', 'note',
  ];
  for (const key of keys) assert.ok(key in item, `缺少字段 ${key}`);
  assert.equal(item.code, 'EDGE_SUPPORT_SENSING');
  assert.equal(item.label, '边撑感应');
  assert.equal(item.stateKey, 'edgeSupportSensing');
  assert.equal(item.groupCode, 'QUICK_RIDE');
  assert.equal(item.groupName, '便捷骑行');
  assert.equal(typeof item.row, 'number');
  assert.equal(item.control, CONTROL.SWITCH);
  assert.equal(item.value, 1);
  assert.ok(Array.isArray(item.options));
  assert.equal(typeof item.supported, 'boolean');
  assert.equal(typeof item.writable, 'boolean');
  assert.equal(item.serviceCode, 603980040);
  assert.equal(item.note, null);
});

/* ------------------------------- 控件推断 ------------------------------- */

test('控件推断：开关 / 枚举 / 连续值 / 只读', () => {
  // 0/1 -> switch
  assert.equal(model.byCode.CUSHION_SENSOR.control, CONTROL.SWITCH);
  assert.equal(model.byCode.CUSHION_SENSOR.value, 1);
  // 枚举：value 一律字符串
  const unit = model.byCode.MILEAGE_UNIT;
  assert.equal(unit.control, CONTROL.SELECT);
  assert.equal(unit.stateKey, 'mileageUnit');
  assert.ok(unit.options.length >= 2);
  assert.equal(unit.options[0].value, '1');
  assert.equal(unit.options[0].label, '公里');
  for (const opt of unit.options) assert.equal(typeof opt.value, 'string');
  // 连续值：单位/步长
  const speed = model.byCode.MAXIMUM_SPEED_LIMIT;
  assert.equal(speed.control, CONTROL.RANGE);
  assert.equal(speed.value, 25);
  assert.equal(typeof speed.min, 'number');
  assert.equal(typeof speed.max, 'number');
  assert.ok(speed.max > speed.min);
  assert.equal(speed.step, 5);
  assert.equal(speed.unit, 'km/h');
  // 只读
  for (const code of ['THEME_ID', 'UPDATE_DATE', 'BMS_UNIQUE_ID', 'BLUETOOTH_STRENGTH_MIN', 'BLUETOOTH_STRENGTH_MAX']) {
    assert.equal(model.byCode[code].control, CONTROL.READONLY, `${code} 应为 readonly`);
    assert.equal(model.byCode[code].writable, false, `${code} 不应可写`);
  }
  // 只读项即使有值也不能写
  assert.equal(model.byCode.THEME_ID.value, '1');
  assert.equal(model.byCode.THEME_NAME.value, '蓝色电波');
});

test('音效子项：15 个，label 是 dex 里核实过的中文名', () => {
  const audio = tile('AUDIO_SET');
  const sounds = audio.items.filter((i) => i.code.startsWith('SOUND_EFFECT.'));
  assert.equal(sounds.length, 15);

  const expected = {
    switchAll: '音效总开关',
    switchLowVelocity: '低速音效',
    switchStand: '边撑音效',
    switchRidingModel: '骑行模式音效',
    switchSitDown: '座垫音效',
    switchReady: '就绪音效',
    switchShutdown: '关机音效',
    switchCruiseControl: '定速巡航音效',
    unlockLock: '上锁解锁音效',
    modeSwitching: '模式切换音效',
    staticOperation: '静态操作音效',
    burglarAlarm: '防盗音效',
    cyclingSteering: '转向音效',
    radarSound: '雷达音效',
    soundSize: '音量',
  };
  for (const [key, label] of Object.entries(expected)) {
    const item = model.byCode[`SOUND_EFFECT.${key}`];
    assert.ok(item, `缺少音效子项 ${key}`);
    assert.equal(item.label, label, `中文名不符：${key}`);
    assert.equal(item.stateKey, `soundEffectSetting.${key}`);
    assert.equal(item.groupCode, 'AUDIO_SET');
  }
  // 中文名不能出现 ASCII 回退
  for (const item of sounds) assert.match(item.label, /[\u4e00-\u9fa5]/, `${item.code} 没有中文名`);

  // 只有音量是连续值，其余是开关；取值来自 soundEffectSetting
  assert.equal(model.byCode['SOUND_EFFECT.soundSize'].control, CONTROL.RANGE);
  assert.equal(model.byCode['SOUND_EFFECT.soundSize'].value, 100);
  assert.equal(model.byCode['SOUND_EFFECT.soundSize'].max, 100);
  assert.equal(model.byCode['SOUND_EFFECT.switchLowVelocity'].control, CONTROL.SWITCH);
  assert.equal(model.byCode['SOUND_EFFECT.switchLowVelocity'].value, 0);
  // 复合 code 的能力码取前缀 SOUND_EFFECT
  assert.equal(model.byCode['SOUND_EFFECT.switchAll'].serviceCode, 268435504);
});

test('disabledContent：按 functionName 命中自动关机', () => {
  const shutdown = model.byCode.AUTO_SHUTDOWN_TIME;
  assert.equal(shutdown.stateKey, 'automaticShutdown');
  assert.equal(shutdown.value, 1);
  assert.equal(shutdown.disabledReason, '打开座垫感应功能后可恢复正常');
  // 带 _01 后缀的条目也能命中（命中第一条）
  assert.match(model.byCode.CUSHION_SENSOR.disabledReason, /离车关机/);
  assert.match(model.byCode.EDGE_SUPPORT_SENSING.disabledReason, /简易骑行/);
  // 没有条目的项必须是 null，不能是 undefined
  assert.equal(model.byCode.CRUISE_CONTROL_HOLD.disabledReason, null);
});

/* ------------------------------- 索引与汇总 ------------------------------- */

test('byCode / byStateKey 一致，stats 与 summarize 同源', () => {
  assert.equal(model.stats.tiles, 14);
  assert.equal(model.stats.items, model.items.length);
  assert.equal(model.stats.writable, model.items.filter((i) => i.writable).length);
  assert.deepEqual(summarize(model), {
    total: model.stats.items,
    writable: model.stats.writable,
    unsupported: model.unsupported.length,
  });
  for (const item of model.items) {
    assert.equal(model.byStateKey[item.stateKey], item);
    assert.ok(model.byCode[item.code]);
  }
  // 同一个 stateKey 只建一次（v2 的 MAX_SPEED 与 v1 的 MAXIMUM_SPEED 是同一项）
  const stateKeys = model.items.map((i) => i.stateKey);
  assert.equal(new Set(stateKeys).size, stateKeys.length);
  assert.equal(model.byCode.MAX_SPEED.stateKey, 'maximumSpeed');
  // topSpeed 只在 vehicleSetInfo 里，也要能建项
  assert.equal(model.byCode.TOP_SPEED.stateKey, 'topSpeed');
  assert.equal(model.byCode.TOP_SPEED.value, '120');
  assert.equal(model.byCode.TOP_SPEED.control, CONTROL.RANGE);
  assert.equal(model.byCode.TOP_SPEED.unit, 'km/h');
  // 清洗后的数据坑：info 里拼写是 barkePower
  assert.equal(model.byCode.BRAKE_POWER.stateKey, 'barkePower');
  assert.equal(model.byCode.BRAKE_POWER.value, 1);
});

test('unsupported：isSupport=false / isSupportSetting=false 的项被收集并置为不可写', () => {
  // 夹具里 47 项全是支持，先验证空数组
  assert.deepEqual(model.unsupported, []);

  // 注入一个不支持的能力码，验证降级路径
  const patched = JSON.parse(JSON.stringify(source));
  const target = patched.items.data.find((entry) => entry.functionCode === 'E_TCS_TRACTION_CONTROL');
  target.isSupport = false;
  const degraded = buildSettingsModel(patched);
  assert.equal(degraded.byCode.E_TCS_TRACTION_CONTROL.supported, false);
  assert.equal(degraded.byCode.E_TCS_TRACTION_CONTROL.writable, false);
  assert.equal(degraded.unsupported.length, 1);
  assert.equal(degraded.unsupported[0].code, 'E_TCS_TRACTION_CONTROL');
  assert.equal(degraded.unsupported[0].groupName, '骑行偏好设置');
});

test('分组自身被标成不支持时整块置灰', () => {
  const patched = JSON.parse(JSON.stringify(source));
  const target = patched.items.data.find((entry) => entry.functionCode === 'KEY_SHARE');
  target.isSupportSetting = false;
  const degraded = buildSettingsModel(patched);
  assert.equal(degraded.tiles.find((t) => t.code === 'KEY_SHARE').available, false);
  assert.equal(degraded.tiles.find((t) => t.code === 'ASSIST_DRIVING').available, true);
});

/* ------------------------------- 写入体 ------------------------------- */

test('buildWriteBody：字段名、类型与字符串化规则', () => {
  const item = model.byCode['SOUND_EFFECT.switchAll'];
  const body = buildWriteBody(item, 0, { vin: model.vin });
  assert.deepEqual(Object.keys(body).sort(), [
    'commandParam', 'flag', 'message', 'serviceCode', 'vehicleSetEnum', 'vin',
  ]);
  assert.equal(body.vin, 'TESTVIN0000000001');
  assert.equal(body.serviceCode, '268435504'); // 字符串化
  assert.equal(body.vehicleSetEnum, 'SOUND_EFFECT.switchAll');
  assert.equal(body.commandParam, '0'); // String(nextValue)
  assert.equal(body.flag, true);
  assert.equal(body.message, '');
  assert.equal(typeof body.serviceCode, 'string');
  assert.equal(typeof body.commandParam, 'string');
  assert.equal(typeof body.flag, 'boolean');

  // flag 可关；数字 serviceCode 一律转字符串（MAX_SPEED 的码来自 v1 的 MAXIMUM_SPEED）
  const speedUnit = buildWriteBody(model.byCode.MAX_SPEED, 2, { vin: 'VIN2', flag: false });
  assert.equal(speedUnit.flag, false);
  assert.equal(speedUnit.commandParam, '2');
  assert.equal(speedUnit.serviceCode, '603980038');
  assert.equal(speedUnit.vehicleSetEnum, 'MAX_SPEED');
  assert.equal(typeof speedUnit.serviceCode, 'string');
  // 只有 info 值、没有能力码的项：serviceCode 给 null，不能编一个码出来
  const rangeOnly = buildWriteBody(model.byCode.MAXIMUM_SPEED_LIMIT, 30, { vin: 'VIN2' });
  assert.equal(rangeOnly.commandParam, '30');
  assert.equal(rangeOnly.serviceCode, null);

  // 缺 serviceCode 的项仍然给 null，不抛
  const noService = buildWriteBody(model.byCode.TOP_SPEED, 100, { vin: 'VIN3' });
  assert.equal(noService.serviceCode, null);
  assert.equal(noService.vehicleSetEnum, 'TOP_SPEED');

  // null 入参
  const empty = buildWriteBody(null, null);
  assert.equal(empty.serviceCode, null);
  assert.equal(empty.vehicleSetEnum, '');
  assert.equal(empty.commandParam, '');
  assert.equal(empty.vin, '');
});

test('coerceValue：表单字符串按控件转成下发值', () => {
  const sw = model.byCode.CUSHION_SENSOR;
  assert.equal(coerceValue(sw, '1'), 1);
  assert.equal(coerceValue(sw, '0'), 0);
  assert.equal(coerceValue(sw, 'true'), 1);
  assert.equal(coerceValue(sw, 'on'), 1);
  assert.equal(coerceValue(sw, ''), 0);
  assert.equal(coerceValue(sw, true), 1);
  assert.equal(coerceValue(sw, false), 0);
  assert.equal(coerceValue(sw, 1), 1);

  const range = model.byCode.MAXIMUM_SPEED_LIMIT; // 10..120 step 5
  assert.equal(coerceValue(range, '30'), 30);
  assert.equal(coerceValue(range, '5'), 10); // 夹到 min
  assert.equal(coerceValue(range, '999'), 120); // 夹到 max
  assert.equal(coerceValue(range, 'abc'), range.value); // 非法退回原值

  const select = model.byCode.MILEAGE_UNIT;
  assert.equal(coerceValue(select, 2), '2'); // 一律字符串
  assert.equal(coerceValue(select, '1'), '1');

  const readonly = model.byCode.THEME_ID;
  assert.equal(coerceValue(readonly, '9'), readonly.value); // 只读不接受输入

  // 默认 TEXT
  const textItem = { control: CONTROL.TEXT, value: '07:00:00' };
  assert.equal(coerceValue(textItem, '08:00:00'), '08:00:00');
  assert.equal(coerceValue(textItem, null), '07:00:00');

  // null item 不抛
  assert.equal(coerceValue(null, '1'), '1');
  assert.equal(coerceValue(null, null), null);
});

test('isDirty：类型归一后再比较', () => {
  const sw = model.byCode.CUSHION_SENSOR; // 1
  assert.equal(isDirty(sw, 1), false);
  assert.equal(isDirty(sw, '1'), false);
  assert.equal(isDirty(sw, 0), true);
  assert.equal(isDirty(sw, '0'), true);
  assert.equal(isDirty(sw, undefined), false);

  const range = model.byCode.MAXIMUM_SPEED_LIMIT; // 25
  assert.equal(isDirty(range, '25'), false);
  assert.equal(isDirty(range, 30), true);

  const select = model.byCode.MILEAGE_UNIT; // '1'
  assert.equal(isDirty(select, 1), false);
  assert.equal(isDirty(select, '2'), true);

  assert.equal(isDirty(null, 1), false);
});

/* ------------------------------- 容错 ------------------------------- */

test('buildSettingsModel：null / 缺字段 / 类型错误都返回空结构', () => {
  const empty = buildSettingsModel();
  assert.deepEqual(empty.rows, []);
  assert.deepEqual(empty.tiles, []);
  assert.deepEqual(empty.items, []);
  assert.deepEqual(empty.byCode, {});
  assert.deepEqual(empty.byStateKey, {});
  assert.deepEqual(empty.unsupported, []);
  assert.equal(empty.vin, '');
  assert.equal(empty.updatedAt, '');
  assert.deepEqual(empty.stats, { tiles: 0, items: 0, writable: 0 });

  // 全部显式传 null
  const nulls = buildSettingsModel({
    itemsV2: null,
    items: null,
    info: null,
    blueToothCodes: null,
    chargePowerOptions: null,
  });
  assert.deepEqual(nulls.stats, { tiles: 0, items: 0, writable: 0 });

  // 传了 itemsV2 但没有 info：磁贴还在，值全 null，不抛
  const noInfo = buildSettingsModel({ itemsV2: source.itemsV2, items: source.items });
  assert.equal(noInfo.tiles.length, 14);
  assert.equal(noInfo.vin, '');
  // 没有 info 时磁贴还在、官方子项还在，只是取值全为 null
  assert.equal(noInfo.tiles.find((t) => t.code === 'KEY_SHARE').items.length, 6);
  assert.equal(noInfo.byCode.INDUCTIVE_UNLOCKING.value, null);
  assert.equal(noInfo.byCode.INDUCTIVE_UNLOCKING.control, CONTROL.READONLY); // 没值就不猜控件

  // 传了 info 但没有 itemsV2：只有不能分组的信息，不抛
  const noLayout = buildSettingsModel({ info: source.info });
  assert.deepEqual(noLayout.rows, []);
  assert.equal(noLayout.vin, 'TESTVIN0000000001');

  // 结构化垃圾输入
  const garbage = buildSettingsModel({ itemsV2: '', items: 42, info: 'x', blueToothCodes: [], chargePowerOptions: {} });
  assert.deepEqual(garbage.stats, { tiles: 0, items: 0, writable: 0 });

  // summarize 也要容错
  assert.deepEqual(summarize(null), { total: 0, writable: 0, unsupported: 0 });
});

test('充电功率选项优先用 chargePower/options 的 data', () => {
  const item = model.byCode.CHARGING_POWER;
  assert.equal(item.control, CONTROL.SELECT);
  const official = source.chargePowerOptions.data.map((opt) => `${opt.key}W`);
  assert.deepEqual(
    item.options.slice(0, official.length).map((opt) => opt.label),
    official,
  );
  assert.deepEqual(
    item.options.slice(0, official.length).map((opt) => opt.value),
    ['1', '2', '3', '4', '5', '6'],
  );
  // 当前值 '200' 不在官方枚举里，补一条兜底，避免下拉框空选
  assert.ok(item.options.some((opt) => opt.value === '200'));
  for (const opt of item.options) assert.equal(typeof opt.value, 'string');
});
