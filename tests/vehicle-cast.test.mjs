/**
 * 车机投屏协议模型的离线测试：node --test tests/
 *
 * 断言里的字节布局来自 docs/CAST.md（逆向自 Motexa 2.7.0），
 * 固定写死的十六进制值是“协议金标”，改实现时这些值不该变。
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  CAST_CONTROL_IDS,
  CAST_HEARTBEAT_REPLY,
  CAST_MEDIA_CMDS,
  CAST_PAIRING_SCHEMA,
  CAST_DEFAULT_CAPTURE,
  CAST_MAX_RETRIES,
  CAST_PHASES,
  canCastTransition,
  normalizeCastPhase,
  shouldLearnTier,
  CAST_PORTS,
  CAST_STATUS_PROBE,
  CAST_TIERS,
  buildCaptureConfig,
  buildEncoderReply,
  castErrorText,
  castSelfTest,
  createCastClient,
  decodeControlFrame,
  decodeFramePacket,
  decodeMediaFrame,
  detectCastBridge,
  encodeControlFrame,
  encodeFramePacket,
  encodeMediaFrame,
  estimateFps,
  formatCastBytes,
  hasParameterSets,
  isKeyframe,
  maskP2pMac,
  maskSecret,
  nextTier,
  parseCaptureConfig,
  parseEncoderReply,
  parsePairingPayload,
  pickAvcLevel,
  planCapture,
  redactDiagnostic,
  shouldDowngrade,
  splitAnnexB,
  summarizePairing,
} from '../sdk/vehicle-cast.js';

const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join(' ');

/* ------------------------------ 常量 ------------------------------ */

test('端口与消息号与协议文档一致', () => {
  assert.equal(CAST_PORTS.data, 10920);
  assert.equal(CAST_PORTS.media, 10921);
  assert.equal(CAST_PORTS.control, 10922);
  assert.equal(CAST_PORTS.status, 10930);
  assert.equal(CAST_STATUS_PROBE.request, 0x70000010);
  assert.equal(CAST_STATUS_PROBE.response, 0x70000011);
  assert.equal(CAST_CONTROL_IDS.clientInfoRequest, 0x00010010);
  assert.equal(CAST_CONTROL_IDS.clientInfo, 0x00010011);
  assert.equal(CAST_CONTROL_IDS.serialCheck, 0x000103e0);
  assert.equal(CAST_CONTROL_IDS.serialAck, 0x000103e1);
  assert.equal(CAST_CONTROL_IDS.serialResult, 0x000201c0);
  assert.equal(CAST_CONTROL_IDS.startSignal, 0x00030020);
  assert.equal(CAST_MEDIA_CMDS.configure, 16);
  assert.equal(CAST_MEDIA_CMDS.start, 112);
  assert.equal(CAST_MEDIA_CMDS.pullFrame, 114);
});

test('心跳应答是固定 8 字节（5002, 1）', () => {
  assert.equal(hex(CAST_HEARTBEAT_REPLY), '8a 13 00 00 01 00 00 00');
});

/* --------------------------- 二维码解析 --------------------------- */

const VALID_QR = 'https://cast.example/?v=1&action=9&modelid=AE5I&ssid=ZEEHO-CAST-1234&pwd=Passw0rd!&mac=aa-bb-cc-dd-ee-ff&sn=TESTVIN0000000001';

test('解析合法二维码：URL 形式、密码含特殊字符、MAC 用 - 分隔', () => {
  const result = parsePairingPayload(VALID_QR);
  assert.equal(result.ok, true);
  assert.deepEqual(result.errors, []);
  assert.deepEqual(result.profile, {
    version: 1,
    mode: '9',
    modelId: 'AE5I',
    ssid: 'ZEEHO-CAST-1234',
    password: 'Passw0rd!',
    p2pMac: 'AA:BB:CC:DD:EE:FF',
    serial: 'TESTVIN0000000001',
  });
  assert.equal(result.fields.modelIdPresent, true);
  assert.equal(result.fields.serialPresent, true);
});

test('二维码可以没有 ?、可以用 & ; 换行分隔，并做 URL 解码', () => {
  const text = 'v=1;action=9\nmodelid=AE5&ssid=My%20Cast&pwd=a+b%2Bcdefgh&mac=AA:BB:CC:DD:EE:FF';
  const result = parsePairingPayload(text);
  assert.equal(result.ok, true);
  assert.equal(result.profile.ssid, 'My Cast');
  assert.equal(result.profile.password, 'a b+cdefgh'); // + → 空格，%2B → +
  assert.equal(result.profile.serial, '');
});

test('缺省 v 视为 1；v=2 拒绝；action 不是 9 拒绝', () => {
  const noVersion = parsePairingPayload('action=9&modelid=AE5&ssid=S&pwd=12345678&mac=AA:BB:CC:DD:EE:FF');
  assert.equal(noVersion.ok, true);
  assert.equal(noVersion.profile.version, CAST_PAIRING_SCHEMA.version);

  const badVersion = parsePairingPayload('v=2&action=9&modelid=AE5&ssid=S&pwd=12345678&mac=AA:BB:CC:DD:EE:FF');
  assert.equal(badVersion.ok, false);
  assert.deepEqual(badVersion.errors.map((e) => e.code), ['qr_version_unsupported']);

  const badMode = parsePairingPayload('v=1&action=1&modelid=AE5&ssid=S&pwd=12345678&mac=AA:BB:CC:DD:EE:FF');
  assert.equal(badMode.ok, false);
  assert.deepEqual(badMode.errors.map((e) => e.code), ['connection_mode_unsupported']);
});

test('字段约束：空、超长、短口令、坏 MAC、坏车型、超长 SN', () => {
  assert.equal(parsePairingPayload('').errors[0].code, 'qr_input_invalid');
  assert.equal(parsePairingPayload('x'.repeat(4097)).errors[0].code, 'qr_input_invalid');
  assert.equal(
    parsePairingPayload('v=1&action=9&modelid=AE5&ssid=S&pwd=123&mac=AA:BB:CC:DD:EE:FF').errors[0].code,
    'network_credential_invalid',
  );
  assert.equal(
    parsePairingPayload(`v=1&action=9&modelid=AE5&ssid=${'s'.repeat(33)}&pwd=12345678&mac=AA:BB:CC:DD:EE:FF`).errors[0].code,
    'network_name_invalid',
  );
  assert.equal(
    parsePairingPayload('v=1&action=9&modelid=AE5&ssid=S&pwd=12345678&mac=not-a-mac').errors[0].code,
    'device_address_invalid',
  );
  assert.equal(
    parsePairingPayload('v=1&action=9&modelid=AE%205&ssid=S&pwd=12345678&mac=AA:BB:CC:DD:EE:FF').errors[0].code,
    'model_id_invalid',
  );
  assert.equal(
    parsePairingPayload(`v=1&action=9&modelid=AE5&ssid=S&pwd=12345678&mac=AA:BB:CC:DD:EE:FF&sn=${'1'.repeat(129)}`).errors[0].code,
    'serial_invalid',
  );
  // 缺字段只报一次 pairing_field_missing，不刷屏
  const missing = parsePairingPayload('v=1&action=9&modelid=AE5');
  assert.equal(missing.ok, false);
  assert.equal(missing.errors.filter((e) => e.code === 'pairing_field_missing').length, 1);
  assert.equal(missing.fields.networkCredentialPresent, false);
});

test('展示摘要不含明文口令/SSID/SN', () => {
  const profile = parsePairingPayload(VALID_QR).profile;
  const summary = summarizePairing(profile);
  const text = JSON.stringify(summary);
  assert.equal(text.includes('Passw0rd!'), false);
  assert.equal(text.includes('ZEEHO-CAST-1234'), false);
  assert.equal(maskP2pMac('AA:BB:CC:DD:EE:FF'), '**:**:**:EE:FF');
  assert.equal(maskSecret('1234567890', 4), '******7890');
  assert.equal(maskSecret('abc', 4), '***');
});

test('错误码都有中文文案；未知码回落到 fallback', () => {
  assert.match(castErrorText('vehicle_status_unavailable'), /车机/);
  assert.equal(castErrorText('nope', '兜底'), '兜底');
  assert.equal(castErrorText(null), '');
});

/* ----------------------------- 帧编解码 ----------------------------- */

test('控制帧：16 字节头 + length^type 校验（金标字节）', () => {
  const frame = encodeControlFrame({ type: 0x00010010, body: 'A' });
  assert.equal(hex(frame), '10 00 01 00 11 00 00 00 01 00 01 00 00 00 00 00 41');
  const decoded = decodeControlFrame(frame);
  assert.equal(decoded.type, 0x00010010);
  assert.equal(decoded.length, 17);
  assert.equal(decoded.bodyLength, 1);
  assert.equal(new TextDecoder().decode(decoded.body), 'A');
});

test('控制帧：校验错 / 长度越界都抛错', () => {
  const frame = encodeControlFrame({ type: CAST_STATUS_PROBE.request, body: '{"a":1}' });
  const broken = Uint8Array.from(frame);
  broken[8] ^= 0xff;
  assert.throws(() => decodeControlFrame(broken), /checksum/i);
  const truncated = frame.subarray(0, 12);
  assert.throws(() => decodeControlFrame(truncated), /header/i);
});

test('状态探测帧的 body 是 phoneType/packageName，且能往返', () => {
  const body = JSON.stringify({ phoneType: 'Android', packageName: 'com.example.app' });
  const frame = encodeControlFrame({ type: CAST_STATUS_PROBE.request, body });
  const decoded = decodeControlFrame(frame);
  assert.deepEqual(JSON.parse(new TextDecoder().decode(decoded.body)), {
    phoneType: 'Android',
    packageName: 'com.example.app',
  });
});

test('媒体帧：8 字节头，serial 原样回填（金标字节）', () => {
  const frame = encodeMediaFrame({ command: CAST_MEDIA_CMDS.configure, serial: 7, body: Uint8Array.of(1, 2, 3) });
  assert.equal(hex(frame), '10 00 03 00 07 00 00 00 01 02 03');
  const decoded = decodeMediaFrame(frame);
  assert.equal(decoded.command, 16);
  assert.equal(decoded.serial, 7);
  assert.equal(decoded.length, 3);
});

test('采集配置 30 字节：偏移 0/2/4/8/20/29', () => {
  const bytes = buildCaptureConfig({ width: 1024, height: 592, fps: 30, encoder: 2, bitrate: 2097152, extended: true });
  assert.equal(bytes.length, 30);
  assert.equal(hex(bytes.subarray(0, 8)), '00 04 50 02 1e 00 00 00');
  assert.equal(hex(bytes.subarray(20, 24)), '00 00 20 00');
  assert.equal(bytes[29], 1);
  const parsed = parseCaptureConfig(bytes);
  assert.deepEqual(
    { width: parsed.width, height: parsed.height, fps: parsed.fps, encoder: parsed.encoder, bitrate: parsed.bitrate, extended: parsed.extended },
    { width: 1024, height: 592, fps: 30, encoder: 2, bitrate: 2097152, extended: true },
  );
});

test('采集配置：过短或尺寸非法抛错', () => {
  assert.throws(() => parseCaptureConfig(new Uint8Array(29)), /too short/i);
  const bad = buildCaptureConfig({ width: 16, height: 16 });
  bad[0] = 1; // width = 1
  assert.throws(() => parseCaptureConfig(bad), /dimensions/i);
});

test('命令 17 的 9 字节应答可往返', () => {
  const reply = buildEncoderReply({ width: 1024, height: 592, extended: true });
  assert.equal(reply.length, 9);
  assert.equal(hex(reply), '02 00 00 00 00 04 50 02 01');
  const parsed = parseEncoderReply(reply);
  assert.equal(parsed.codec, 2);
  assert.equal(parsed.width, 1024);
  assert.equal(parsed.height, 592);
  assert.equal(parsed.extended, true);
});

test('数据帧裸包：length 含 extended 的 4 字节 index（r1.java:1179-1186）', () => {
  const frame = Uint8Array.of(0, 0, 0, 1, 0x65, 0x88);
  const traditional = encodeFramePacket({ frame });
  assert.equal(hex(traditional.subarray(0, 4)), '06 00 00 00');
  assert.equal(decodeFramePacket(traditional).index, null);
  assert.deepEqual(Array.from(decodeFramePacket(traditional).frame), Array.from(frame));

  // extended：length = 帧长 6 + index 4 = 10，不是 6
  const extended = encodeFramePacket({ frame, index: 3, extended: true });
  assert.equal(hex(extended.subarray(0, 8)), '0a 00 00 00 03 00 00 00');
  const parsed = decodeFramePacket(extended, { extended: true });
  assert.equal(parsed.length, 10);
  assert.equal(parsed.index, 3);
  assert.deepEqual(Array.from(parsed.frame), Array.from(frame));

  // 没有新帧：固定 4 字节 0，extended 也不带 index
  const empty = encodeFramePacket({ frame: null, extended: true });
  assert.equal(hex(empty), '00 00 00 00');
  assert.equal(decodeFramePacket(empty, { extended: true }).length, 0);
  assert.throws(() => decodeFramePacket(extended.subarray(0, 8), { extended: true }), /Truncated/);
});

/* ------------------------------ H.264 ------------------------------ */

test('Annex-B 拆分：3 字节与 4 字节起始码混用', () => {
  const annexb = Uint8Array.of(
    0, 0, 0, 1, 0x67, 0x42, // SPS
    0, 0, 1, 0x68, 0xce, // PPS（3 字节起始码）
    0, 0, 0, 1, 0x65, 0x88, // IDR
  );
  const nals = splitAnnexB(annexb);
  assert.deepEqual(nals.map((nal) => nal.type), [7, 8, 5]);
  assert.equal(hasParameterSets(annexb), true);
  assert.equal(isKeyframe(annexb), true);
});

test('缺 SPS/PPS 时 hasParameterSets=false（需要拼 csd-0+csd-1）', () => {
  const frame = Uint8Array.of(0, 0, 0, 1, 0x65, 0x88);
  assert.equal(hasParameterSets(frame), false);
  assert.equal(isKeyframe(frame), true);
  const nonIdr = Uint8Array.of(0, 0, 1, 0x41, 0x9a);
  assert.equal(isKeyframe(nonIdr), false);
});

/* --------------------------- 档位与降级 --------------------------- */

test('16 对齐与 level 查表', () => {
  const plan = planCapture({ width: 1024, height: 600, fps: 30, bitrate: 2097152, encoder: 2 }, { tier: 'performance' });
  assert.equal(plan.width, 1024);
  assert.equal(plan.height, 592); // 600 & -16
  assert.equal(plan.fps, 30);
  assert.equal(plan.bitrate, 2621440);
  assert.equal(plan.level.name, '3.1');
  assert.equal(plan.level.id, 512);
  assert.equal(plan.codec, 2);
  assert.equal(pickAvcLevel(1024, 592, 30).name, '3.1');
  assert.equal(pickAvcLevel(1920, 1080, 30).name, '4.0');
});

test('performance 档：S905L3AB 用请求码率，其他机型固定 2.5 Mbps', () => {
  const config = parseCaptureConfig(buildCaptureConfig({ width: 1024, height: 592, fps: 30, bitrate: 1048576, encoder: 2 }));
  assert.equal(planCapture(config, { tier: 'performance', chipset: 's905l3ab' }).bitrate, 1048576);
  assert.equal(planCapture(config, { tier: 'performance', chipset: 'generic' }).bitrate, 2621440);
  // 码率不在 256k~8M 区间 → 2 MiB
  const weird = parseCaptureConfig(buildCaptureConfig({ width: 1024, height: 592, fps: 30, bitrate: 100, encoder: 2 }));
  assert.equal(planCapture(weird, { tier: 'performance', chipset: 's905l3ab' }).bitrate, 2097152);
});

test('compatibility 档：fps clamp 到 24 以内、Baseline、level 按宏块×fps 选', () => {
  const config = parseCaptureConfig(buildCaptureConfig({ width: 1024, height: 592, fps: 30, bitrate: 2097152, encoder: 2 }));
  const plan = planCapture(config, { tier: 'compatibility' });
  assert.equal(plan.fps, 24);
  assert.equal(plan.profile, 'baseline');
  assert.equal(plan.level.name, '3.1');
  const slow = parseCaptureConfig(buildCaptureConfig({ width: 1024, height: 592, fps: 10, bitrate: 2097152, encoder: 2 }));
  assert.equal(planCapture(slow, { tier: 'compatibility' }).fps, 24); // <15 也用 24
});

test('balanced / resolution-safe / decoder-safe 的分辨率与码率', () => {
  const config = parseCaptureConfig(buildCaptureConfig({ width: 1920, height: 1080, fps: 30, bitrate: 2097152, encoder: 2 }));
  const balanced = planCapture(config, { tier: 'balanced' });
  assert.equal(balanced.fps, 20);
  assert.equal(balanced.bitrate, 1572864);
  assert.equal(balanced.width, 1920);

  const safe = planCapture(config, { tier: 'resolution-safe' });
  assert.ok(safe.width <= 800);
  assert.ok(safe.height <= 480);
  assert.equal(safe.fps, 20);
  assert.equal(safe.bitrate, 1572864);
  const macroblocks = Math.ceil(safe.width / 16) * Math.ceil(safe.height / 16);
  assert.ok(macroblocks <= 1620, `宏块 ${macroblocks} 超限`);

  assert.equal(safe.level.name, '3.0');
  assert.equal(safe.level.id, 256);

  const decoderSafe = planCapture(config, { tier: 'decoder-safe' });
  assert.equal(decoderSafe.fps, 15);
  assert.equal(decoderSafe.bitrate, 1048576);
  assert.equal(decoderSafe.level.name, '3.0');
  assert.equal(decoderSafe.level.id, 256);
});

test('档位顺序；降级是「15s 内失败且发过帧」，学习是「15s + 150 帧」', () => {
  assert.deepEqual(CAST_TIERS, ['performance', 'bitrate-safe', 'compatibility', 'balanced', 'resolution-safe', 'decoder-safe']);
  assert.equal(nextTier('performance'), 'bitrate-safe');
  assert.equal(nextTier('decoder-safe'), null);
  assert.equal(nextTier('unknown'), null);

  // 降级：早期失败（<15s）但已发过帧
  assert.equal(shouldDowngrade({ tier: 'performance', elapsedMs: 1200, sentFrames: 1 }), true);
  assert.equal(shouldDowngrade({ tier: 'performance', elapsedMs: 14999, sentFrames: 300 }), true);
  assert.equal(shouldDowngrade({ tier: 'performance', elapsedMs: 15000, sentFrames: 300 }), false);
  assert.equal(shouldDowngrade({ tier: 'performance', elapsedMs: 90000, sentFrames: 500 }), false);
  assert.equal(shouldDowngrade({ tier: 'performance', elapsedMs: 500, sentFrames: 0 }), false);
  assert.equal(shouldDowngrade({ tier: 'performance', elapsedMs: 500, sentFrames: 10, stopped: true }), false);
  assert.equal(shouldDowngrade({ tier: 'decoder-safe', elapsedMs: 500, sentFrames: 10 }), false);
  assert.equal(shouldDowngrade({ tier: 'performance', retries: CAST_MAX_RETRIES, elapsedMs: 500, sentFrames: 10 }), false);

  // 学习：跟降级是两件事
  assert.equal(shouldLearnTier({ elapsedMs: 15000, sentFrames: 150 }), true);
  assert.equal(shouldLearnTier({ elapsedMs: 14999, sentFrames: 900 }), false);
  assert.equal(shouldLearnTier({ elapsedMs: 60000, sentFrames: 149 }), false);
  assert.equal(shouldLearnTier({ elapsedMs: 60000, sentFrames: 500, stopped: true }), false);
});

test('phase 归一：connecting/stopped/waiting-projection 等历史名字', () => {
  assert.equal(normalizeCastPhase('connecting'), 'provisioning');
  assert.equal(normalizeCastPhase('handshaking'), 'handshaking');
  assert.equal(normalizeCastPhase('stopped'), 'idle');
  assert.equal(normalizeCastPhase('saved'), 'idle');
  assert.equal(normalizeCastPhase('waiting-projection'), 'provisioning');
  assert.equal(normalizeCastPhase('hotspot-monitor'), 'provisioning');
  assert.equal(normalizeCastPhase('bogus'), null);
  assert.equal(normalizeCastPhase(null), null);
});

test('兜底采集配置与重试上限', () => {
  assert.deepEqual(CAST_DEFAULT_CAPTURE, { width: 1024, height: 600, fps: 0, encoder: 2, bitrate: 2097152, extended: false });
  assert.equal(CAST_MAX_RETRIES, 5);
  const plan = planCapture(CAST_DEFAULT_CAPTURE, { tier: 'performance' });
  assert.equal(plan.width, 1024);
  assert.equal(plan.height, 592);
  assert.equal(plan.fps, 30); // fps=0 → 30
});

/* --------------------------- 诊断与格式化 --------------------------- */

test('诊断打码：口令/SSID/MAC/SN/HUID/token 全遮，嵌套结构递归处理', () => {
  const redacted = redactDiagnostic({
    phase: 'handshaking',
    ssid: 'ZEEHO-CAST-1234',
    pwd: 'Passw0rd!',
    p2pMac: 'AA:BB:CC:DD:EE:FF',
    sn: 'TESTVIN0000000001',
    nested: { HUID: 'abc123', token: 'abcdef', pubkey: 'MIIB...' },
    stats: { sentFrames: 12 },
  });
  assert.equal(redacted.phase, 'handshaking');
  assert.equal(redacted.stats.sentFrames, 12);
  const text = JSON.stringify(redacted);
  for (const secret of ['ZEEHO-CAST-1234', 'Passw0rd!', 'AA:BB:CC:DD:EE:FF', 'TESTVIN0000000001', 'abc123', 'abcdef']) {
    assert.equal(text.includes(secret), false, `未打码：${secret}`);
  }
});

test('字节/时长/帧率格式化', () => {
  assert.equal(formatCastBytes(512), '512 B');
  assert.equal(formatCastBytes(2048), '2.0 KB');
  assert.equal(formatCastBytes(1572864), '1.5 MB');
  assert.equal(estimateFps([0, 33, 66, 99, 132]), 30.3);
  assert.equal(estimateFps([]), 0);
});

/* ---------------------------- 原生桥编排 ---------------------------- */

test('没有原生桥：客户端进入 unavailable，并给出可读原因', () => {
  const client = createCastClient({ bridge: null });
  assert.equal(client.phase, CAST_PHASES.unavailable);
  assert.equal(client.state.reason, 'native_bridge_missing');
  assert.equal(client.isAvailable(), false);
  const result = client.start({ tier: 'performance' });
  assert.equal(result.ok, false);
  assert.equal(result.error, 'native_bridge_missing');
  assert.equal(detectCastBridge({}), null);
});

test('有原生桥：pair → start → 事件推进到 casting → stop 回到 idle', () => {
  const calls = [];
  const bridge = {
    pair: (json) => { calls.push(['pair', JSON.parse(json)]); return JSON.stringify({ ok: true }); },
    start: (json) => { calls.push(['start', JSON.parse(json)]); return JSON.stringify({ ok: true, phase: 'provisioning' }); },
    stop: () => { calls.push(['stop']); return JSON.stringify({ ok: true }); },
    getStatus: () => JSON.stringify({ phase: 'casting', casting: true, stats: { sentFrames: 42, fps: 29.9 } }),
    getDiagnostics: () => JSON.stringify([{ event: 'socket-accepted', port: 10922 }]),
  };
  const events = [];
  const client = createCastClient({ bridge, emit: (event) => events.push(event) });

  const paired = client.pair(VALID_QR);
  assert.equal(paired.ok, true);
  assert.equal(calls[0][0], 'pair');
  assert.equal(calls[0][1].modelId, 'AE5I');

  const started = client.start({ tier: 'compatibility', frameFormat: 'extended' });
  assert.equal(started.ok, true);
  assert.equal(calls[1][1].tier, 'compatibility');
  assert.equal(calls[1][1].frameFormat, 'extended');

  client.handleEvent(JSON.stringify({ type: 'status', status: { phase: 'casting', casting: true, stats: { sentFrames: 100, sentBytes: 2048, fps: 30 } } }));
  assert.equal(client.phase, CAST_PHASES.casting);
  assert.equal(client.state.stats.sentFrames, 100);
  assert.equal(client.state.casting, true);

  const status = client.refresh();
  assert.equal(status.phase, 'casting');
  assert.equal(client.state.stats.sentFrames, 42);

  const diag = client.getDiagnostics(10);
  assert.equal(diag.bridgeAvailable, true);
  assert.equal(diag.remote[0].port, 10922);

  client.stop();
  assert.equal(calls[calls.length - 1][0], 'stop');
  assert.equal(client.phase, CAST_PHASES.idle);
  assert.ok(events.some((event) => event.type === 'phase'));
});

test('向前跳合法（页面中途打开），回退被拒绝', () => {
  const bridge = { getStatus: () => '{}', stop: () => '{"ok":true}' };
  const client = createCastClient({ bridge });
  client.handleEvent(JSON.stringify({ type: 'status', status: { phase: 'casting' } }));
  assert.equal(client.phase, CAST_PHASES.casting);
  assert.equal(client.state.casting, true);
  assert.ok(client.state.diagnostics.some((item) => item.type === 'phase-jump'));

  client.handleEvent(JSON.stringify({ type: 'status', status: { phase: 'provisioning' } }));
  assert.equal(client.phase, CAST_PHASES.casting);
  const rejected = client.state.diagnostics.filter((item) => item.type === 'phase-rejected');
  assert.equal(rejected.length, 1);
  assert.equal(rejected[0].payload.to, 'provisioning');

  // failed 之后允许重新 provisioning
  client.handleEvent(JSON.stringify({ type: 'error', error: 'vehicle_disconnected' }));
  assert.equal(client.phase, CAST_PHASES.failed);
  client.handleEvent(JSON.stringify({ type: 'status', status: { phase: 'provisioning' } }));
  assert.equal(client.phase, CAST_PHASES.provisioning);
});

test('屏幕录制授权：requestProjection 透传桥结果并记事件', () => {
  const calls = [];
  const bridge = {
    requestProjection: () => { calls.push('requestProjection'); return JSON.stringify({ ok: true }); },
    getStatus: () => '{}',
    stop: () => '{"ok":true}',
  };
  const events = [];
  const client = createCastClient({ bridge, emit: (event) => events.push(event.type) });
  const result = client.requestProjection();
  assert.equal(result.ok, true);
  assert.deepEqual(calls, ['requestProjection']);
  assert.ok(events.includes('projection'));
  const missing = createCastClient({ bridge: null }).requestProjection();
  assert.equal(missing.ok, false);
  assert.equal(missing.error, 'native_bridge_missing');
});

test('原生桥抛异常时归为 bridge_timeout，不冒泡', () => {
  const bridge = {
    pair: () => { throw new Error('boom'); },
    stop: () => '{"ok":true}',
  };
  const client = createCastClient({ bridge });
  const result = client.pair(VALID_QR);
  assert.equal(result.ok, false);
  assert.equal(result.error, 'bridge_timeout');
  // profile 已经本地解析成功，失败只体现在桥调用上
  assert.equal(client.phase, CAST_PHASES.failed);
});

test('二维码非法时不会调用原生桥', () => {
  const calls = [];
  const bridge = { pair: (json) => { calls.push(json); return '{"ok":true}'; }, stop: () => '{"ok":true}' };
  const client = createCastClient({ bridge });
  const result = client.pair('garbage');
  assert.equal(result.ok, false);
  assert.equal(result.error, 'connection_mode_unsupported');
  assert.equal(calls.length, 0);
});

test('自检：帧格式往返与端口表', () => {
  const result = castSelfTest();
  assert.equal(result.ok, true);
  assert.equal(result.plan.width, 1024);
  assert.equal(result.plan.profile, 'baseline');
  assert.equal(result.ports.status, 10930);
});
