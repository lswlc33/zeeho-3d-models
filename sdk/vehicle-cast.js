/* ==== ZEEHO_VEHICLE_CAST:MODEL:BEGIN ==== */
/**
 * 极核车机投屏 —— 协议模型（纯函数，不碰 DOM、不发网络请求）。
 *
 * 协议细节见 docs/CAST.md（逆向自 Motexa 2.7.0）。
 * 本文件有三个用途：
 *   1. 面板页（index.html 注入）与调试台共用同一套解析/校验/状态机；
 *   2. 单元测试用固定夹具验证帧格式、档位与降级条件（tests/vehicle-cast.test.mjs）；
 *   3. 给 Android 原生实现（android/cast/）当行为基准。
 *
 * 约定：所有整数字节序都是小端；字节数组一律 Uint8Array。
 * 真正的 socket / MediaProjection 在原生桥里（window.ZeehoCast），这里只描述与校验。
 */

/** 车机侧端口（见 docs/CAST.md §3）。 */
export const CAST_PORTS = {
  // 手机监听、车机连回来
  data: 10920, // 裸数据帧：车机拉动 H.264
  media: 10921, // 媒体控制帧：采集配置 / 开始 / 心跳
  control: 10922, // 控制帧：JSON 握手
  // 手机主动连车机
  status: 10930,
};

/** 状态探测失败时的兜底 GO 地址（Motexa r1.n2）。 */
export const CAST_FALLBACK_HOST = '192.168.0.1';

/** 控制帧消息号（docs/CAST.md §6）。 */
export const CAST_CONTROL_IDS = {
  ping: 0x70000000,
  pong: 0x70000001,
  ack: 0x00010000,
  ackReply: 0x00010001,
  infoA: 0x000106d0,
  infoAReply: 0x000106d1,
  infoB: 0x00020000,
  infoBReply: 0x00020001,
  clientInfoRequest: 0x00010010,
  clientInfo: 0x00010011,
  serialCheck: 0x000103e0,
  serialAck: 0x000103e1,
  serialResult: 0x000201c0,
  runningLog: 0x00010780,
  runningLogAck: 0x00010781,
  startSignal: 0x00030020,
  unsupported: -2147483632, // 0x80000010，按有符号 int32
};

/** 状态探测（10930）用的固定消息号。 */
export const CAST_STATUS_PROBE = {
  request: 0x70000010,
  response: 0x70000011,
};

/** 媒体帧命令号（docs/CAST.md §7）。 */
export const CAST_MEDIA_CMDS = {
  configure: 16,
  configureAck: 17,
  heartbeatA: 48,
  heartbeatAReply: 49,
  heartbeatB: 64,
  heartbeatBReply: 65,
  viewConfig: 96,
  viewConfigReply: 97,
  start: 112,
  startAck: 113,
  pullFrame: 114,
};

/** 控制帧长度上限 / 媒体帧 body 上限（c0.java）。 */
export const CAST_LIMITS = {
  controlHeader: 16,
  controlMax: 262144,
  mediaHeader: 8,
  mediaBodyMax: 65535,
  qrMaxChars: 4096,
};

/** 心跳应答的固定 8 字节（r1.v2）。 */
export const CAST_HEARTBEAT_REPLY = Uint8Array.of(0x8a, 0x13, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00); // 5002, 1

/** 视图配置应答（r1.w2）。 */
export const CAST_VIEW_CONFIG_REPLY = { viewAreaConfig: { state: 0 }, supportFunction: 0 };

/** 二维码支持的唯一版本 / 连接模式（d0.g）。 */
export const CAST_PAIRING_SCHEMA = { version: 1, mode: '9' };

/** 车机没先发命令 16 就发 112 时的兜底采集配置（r1.A0：1024×600, fps 0, encoder 2, 2 MiB）。 */
export const CAST_DEFAULT_CAPTURE = { width: 1024, height: 600, fps: 0, encoder: 2, bitrate: 2097152, extended: false };

/** 自动降级最多尝试次数（p.f7884a = values().length - 1 = 5）。 */
export const CAST_MAX_RETRIES = 5;

/** 档位学习阈值：会话跑满 15 s 且已发 ≥150 帧（p.b → compatibility-learned）。 */
export const CAST_LEARN_THRESHOLD = { elapsedMs: 15000, sentFrames: 150 };

/** 档位顺序（p.d 枚举顺序 = 降级顺序）。 */
export const CAST_TIERS = [
  'performance',
  'bitrate-safe',
  'compatibility',
  'balanced',
  'resolution-safe',
  'decoder-safe',
];

/** 采集配置里 encoder 的取值：2 = H.264（w.a 缺省也是 2）。 */
export const CAST_AVC_CODEC = 2;

/** 编码器 level 数值（p.d 表里的 id；3.0 → 256，3.1 → 512）。 */
export const CAST_LEVEL_IDS = { '3.0': 256, '3.1': 512, '4.0': 2048, '5.0': 16384 };

/** 会话阶段（与 Motexa phase 字段一致，外加本仓的 unavailable）。 */
export const CAST_PHASES = {
  idle: 'idle',
  provisioning: 'provisioning',
  handshaking: 'handshaking',
  ready: 'ready',
  casting: 'casting',
  adapting: 'adapting',
  stopping: 'stopping',
  failed: 'failed',
  unavailable: 'unavailable',
};

/**
 * 阶段序号：数值越大越“靠后”。
 * 允许向前跳（面板可能在投屏中途被打开，getStatus 直接给 casting），只拒绝回退。
 */
export const CAST_PHASE_RANK = {
  idle: 0,
  provisioning: 1,
  handshaking: 2,
  ready: 3,
  adapting: 4,
  casting: 5,
};

/**
 * 状态流转判定：
 *   - `failed` / `stopping` / `idle` 从任何活动态都可以进（出错、用户停止、系统复位）；
 *   - `adapting` 只从 `casting` 进，出到 `casting`；
 *   - 其余按序号只进不退（页面中途打开时的向前跳是合法的）。
 */
export function canCastTransition(from, to) {
  if (!to || to === from) return true;
  if (to === 'unavailable') return false;
  if (to === 'failed' || to === 'stopping' || to === 'idle') return from !== 'unavailable';
  if (from === 'unavailable') return false;
  if (from === 'failed') return to === 'provisioning';
  if (to === 'adapting') return from === 'casting' || from === 'adapting';
  const fromRank = CAST_PHASE_RANK[from];
  const toRank = CAST_PHASE_RANK[to];
  if (fromRank == null || toRank == null) return false;
  return toRank >= fromRank;
}

/**
 * Motexa 侧 phase 名字 → 本仓词表（docs/CAST.md §9）。
 * 原生桥可以直接用本仓词表；从旧实现/诊断日志里读到别的名字时用这个归一。
 */
export const CAST_PHASE_ALIASES = {
  idle: 'idle',
  connecting: 'provisioning',
  provisioning: 'provisioning',
  'hotspot-monitor': 'provisioning',
  handshaking: 'handshaking',
  ready: 'ready',
  casting: 'casting',
  adapting: 'adapting',
  stopping: 'stopping',
  stopped: 'idle',
  saved: 'idle',
  'waiting-projection': 'provisioning',
  failed: 'failed',
  unavailable: 'unavailable',
};

/** 归一 phase 名；未知值返回 null（调用方决定忽略还是报错）。 */
export function normalizeCastPhase(value) {
  if (!value) return null;
  const key = String(value).trim().toLowerCase();
  return CAST_PHASE_ALIASES[key] || null;
}

/** 错误码 → 中文（原生桥与页面共用同一套文案）。 */
export const CAST_ERROR_TEXT = {
  qr_input_invalid: '二维码内容为空或过长',
  qr_version_invalid: '二维码版本号不是数字',
  qr_version_unsupported: '二维码版本不受支持（只支持 v=1）',
  connection_mode_unsupported: '二维码连接模式不受支持（需要 action=9）',
  pairing_field_missing: '二维码缺少必填字段',
  model_id_invalid: '二维码里的车型不合法',
  network_name_invalid: '二维码里的 Wi-Fi 名称不合法',
  network_credential_invalid: '二维码里的 Wi-Fi 密码不合法（8~63 位）',
  device_address_missing: '二维码缺少车机 P2P 地址',
  device_address_invalid: '车机 P2P 地址格式不对',
  serial_invalid: '二维码里的 SN 过长',
  qr_invalid: '无法识别的投屏二维码',
  native_bridge_missing: '当前环境没有投屏原生桥，需在极核面板 App 内使用',
  bridge_method_missing: '原生桥版本过旧，缺少所需方法',
  bridge_timeout: '原生桥无响应',
  wifi_permission_denied: '缺少 Wi-Fi / 附近设备权限',
  wifi_disabled: 'Wi-Fi 未开启',
  wifi_direct_unsupported: '本机不支持 Wi-Fi Direct',
  hotspot_active: '手机热点已开启，请先关闭',
  projection_permission_required: '需要先授权屏幕录制',
  vehicle_status_unavailable: '车机没有响应（检查车机是否停在投屏页）',
  vehicle_status_rejected: '车机拒绝了握手',
  vehicle_disconnected: '车机断开连接',
  listener_port_unavailable: '端口 10920~10922 被占用',
  encoder_unavailable: '本机没有可用的 H.264 编码器',
  media_incompatible_after_fallback: '车机解码不兼容，已降档仍失败',
  unsupported_frame_format: '数据帧格式不受支持',
  p2p_connect_failed: 'Wi-Fi Direct 连接车机失败',
  p2p_timeout: 'Wi-Fi Direct 组网超时',
  session_active: '已有投屏会话在进行',
  security_error: '系统拒绝该操作',
  bridge_bad_json: '原生桥返回的数据不是合法 JSON',
  capture_source_unsupported: '只支持投屏手机整屏（不支持主题渲染）',
  internal_error: '原生桥内部错误',
  media_config_invalid: '车机下发的采集配置不合法',
  projection_stopped: '屏幕录制被系统或用户停止',
  projection_unavailable: '系统没有可用的屏幕录制能力',
  vehicle_not_found: '已保存的车机信息失效，请重新扫码',
  wifi_direct_disabled: 'Wi-Fi Direct 被系统关闭',
};

/** 把协议错误码翻成人话。 */
export function castErrorText(code, fallback) {
  if (!code) return fallback || '';
  return CAST_ERROR_TEXT[String(code)] || fallback || String(code);
}

/* ------------------------------ 打码 ------------------------------ */

const CAST_SECRET_KEYS = /(pwd|password|passwd|ssid|p2p_?mac|^mac$|serial|^sn$|huid|token|secret|pubkey|encryptedhuid|envinfo|encryptinfo|encryptvalue|^key$|^iv$)/i;

/** 保留末尾 keep 位，其余打 `*`；长度不足直接全打码。 */
export function maskSecret(value, keep = 4) {
  const text = value == null ? '' : String(value);
  if (!text) return '';
  if (text.length <= keep) return '*'.repeat(text.length);
  return '*'.repeat(text.length - keep) + text.slice(-keep);
}

/** P2P MAC：Motexa 只露后 5 位（`**:**:**:EE:FF`）。 */
export function maskP2pMac(mac) {
  const text = mac == null ? '' : String(mac).trim();
  if (!text) return '';
  if (text.length < 5) return '--';
  return `**:**:**:${text.slice(-5)}`;
}

/** 递归打码诊断/日志里的一次性凭证与位置信息。 */
export function redactDiagnostic(payload) {
  if (Array.isArray(payload)) return payload.map((item) => redactDiagnostic(item));
  if (!payload || typeof payload !== 'object') return payload;
  const out = {};
  for (const [key, value] of Object.entries(payload)) {
    if (CAST_SECRET_KEYS.test(key)) {
      out[key] = typeof value === 'string' || typeof value === 'number' ? maskSecret(value) : '[REDACTED]';
    } else if (value && typeof value === 'object') {
      out[key] = redactDiagnostic(value);
    } else {
      out[key] = value;
    }
  }
  return out;
}

/* --------------------------- 二维码解析 --------------------------- */

function decodeUrlComponent(text) {
  try {
    return decodeURIComponent(text.replace(/\+/g, ' '));
  } catch {
    return text;
  }
}

function pickField(map, ...keys) {
  for (const key of keys) {
    const value = map.get(key);
    if (value != null && value !== '') return value;
  }
  return '';
}

/**
 * 解析车机投屏二维码（docs/CAST.md §2）。
 * 返回 `{ ok, profile, errors, fields }`：
 *   - `profile`：`{ version, modelId, ssid, password, p2pMac, serial, mode }`；
 *   - `errors`：`[{ code, message }]`，`ok` 为 false 时非空；
 *   - `fields`：各必填项是否存在（用于诊断，不含明文口令）。
 */
export function parsePairingPayload(text) {
  const errors = [];
  const raw = text == null ? '' : String(text);
  const trimmed = raw.trim();
  const withinLimit = trimmed.length > 0 && trimmed.length <= CAST_LIMITS.qrMaxChars;
  const fields = {
    qrInputWithinLimit: withinLimit,
    pairingVersionPresent: false,
    connectionModePresent: false,
    modelIdPresent: false,
    networkNamePresent: false,
    networkCredentialPresent: false,
    deviceAddressPresent: false,
    serialPresent: false,
  };
  if (!withinLimit) {
    return {
      ok: false,
      profile: null,
      fields,
      errors: [{ code: 'qr_input_invalid', message: castErrorText('qr_input_invalid') }],
    };
  }

  const query = trimmed.includes('?') ? trimmed.slice(trimmed.indexOf('?') + 1) : trimmed;
  const map = new Map();
  for (const segment of query.split(/[&;\n]/)) {
    const eq = segment.indexOf('=');
    if (eq <= 0) continue;
    const key = decodeUrlComponent(segment.slice(0, eq)).trim().toLowerCase();
    const value = decodeUrlComponent(segment.slice(eq + 1)).trim();
    if (key && value) map.set(key, value);
  }

  const versionRaw = pickField(map, 'v', 'version', 'ver');
  const modeRaw = pickField(map, 'action', 'mode');
  const modelId = pickField(map, 'modelid', 'model_id', 'channel');
  const ssid = pickField(map, 'ssid');
  const password = pickField(map, 'pwd', 'password');
  const macRaw = pickField(map, 'mac', 'p2pmac', 'p2p_mac');
  const serial = pickField(map, 'sn', 'serial');

  fields.pairingVersionPresent = Boolean(versionRaw);
  fields.connectionModePresent = Boolean(modeRaw);
  fields.modelIdPresent = Boolean(modelId);
  fields.networkNamePresent = Boolean(ssid);
  fields.networkCredentialPresent = Boolean(password);
  fields.deviceAddressPresent = Boolean(macRaw);
  fields.serialPresent = Boolean(serial);

  let version = CAST_PAIRING_SCHEMA.version;
  if (versionRaw) {
    version = Number.parseInt(versionRaw, 10);
    if (!Number.isFinite(version)) {
      errors.push({ code: 'qr_version_invalid', message: castErrorText('qr_version_invalid') });
    } else if (version !== CAST_PAIRING_SCHEMA.version) {
      errors.push({ code: 'qr_version_unsupported', message: castErrorText('qr_version_unsupported') });
    }
  }
  if (modeRaw !== CAST_PAIRING_SCHEMA.mode) {
    errors.push({ code: 'connection_mode_unsupported', message: castErrorText('connection_mode_unsupported') });
  }
  if (!modelId || !ssid || !password || !macRaw) {
    errors.push({ code: 'pairing_field_missing', message: castErrorText('pairing_field_missing') });
  }
  if (modelId && !/^[A-Za-z0-9_.-]{1,64}$/.test(modelId)) {
    errors.push({ code: 'model_id_invalid', message: castErrorText('model_id_invalid') });
  }
  if (ssid && (ssid.length > 32 || ssid.includes('\u0000'))) {
    errors.push({ code: 'network_name_invalid', message: castErrorText('network_name_invalid') });
  }
  if (password && (password.length < 8 || password.length > 63)) {
    errors.push({ code: 'network_credential_invalid', message: castErrorText('network_credential_invalid') });
  }

  let p2pMac = '';
  if (macRaw) {
    const normalized = macRaw.replace(/-/g, ':').toUpperCase();
    if (/^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(normalized)) {
      p2pMac = normalized;
    } else {
      errors.push({ code: 'device_address_invalid', message: castErrorText('device_address_invalid') });
    }
  }
  if (serial.length > 128) {
    errors.push({ code: 'serial_invalid', message: castErrorText('serial_invalid') });
  }

  // 同一类错误只保留第一条，避免 UI 刷屏
  const seen = new Set();
  const unique = errors.filter((item) => (seen.has(item.code) ? false : seen.add(item.code)));

  const profile = unique.length
    ? null
    : { version, mode: modeRaw, modelId, ssid, password, p2pMac, serial };
  return { ok: unique.length === 0, profile, fields, errors: unique };
}

/** 展示用摘要：口令/SID/SN 必须打码。 */
export function summarizePairing(profile) {
  if (!profile) return [];
  return [
    { key: '车型', value: profile.modelId || '—' },
    { key: '车机网络', value: profile.ssid ? maskSecret(profile.ssid, 3) : '—' },
    { key: '车机地址', value: maskP2pMac(profile.p2pMac) },
    { key: 'SN', value: profile.serial ? maskSecret(profile.serial, 4) : '—' },
  ];
}

/* ----------------------------- 帧编解码 ----------------------------- */

function asBytes(input) {
  if (input instanceof Uint8Array) return input;
  if (input instanceof ArrayBuffer) return new Uint8Array(input);
  if (ArrayBuffer.isView(input)) return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  if (Array.isArray(input)) return Uint8Array.from(input);
  throw new TypeError('需要 Uint8Array / ArrayBuffer / number[]');
}

function toBodyBytes(body) {
  if (body == null) return new Uint8Array(0);
  if (typeof body === 'string') return new TextEncoder().encode(body);
  return asBytes(body);
}

/** 控制帧：`int32 type | uint32 length | int32 length^type | int32 code | body`（c0.a）。 */
export function encodeControlFrame({ type, code = 0, body } = {}) {
  const payload = toBodyBytes(body);
  const length = CAST_LIMITS.controlHeader + payload.length;
  if (!Number.isInteger(type)) throw new TypeError('控制帧 type 必须是整数');
  if (length > CAST_LIMITS.controlMax) throw new RangeError('控制帧过长');
  const buffer = new Uint8Array(length);
  const view = new DataView(buffer.buffer);
  view.setInt32(0, type | 0, true);
  view.setUint32(4, length, true);
  view.setInt32(8, (length ^ (type | 0)) | 0, true);
  view.setInt32(12, code | 0, true);
  buffer.set(payload, CAST_LIMITS.controlHeader);
  return buffer;
}

/**
 * 解控制帧头，返回 `{ type, code, body, length }`；`bytes` 只给头部时 `body` 为 null。
 * 校验失败抛错（对应 c0.a.a 的三条 IOException）。
 */
export function decodeControlFrame(buffer) {
  const bytes = asBytes(buffer);
  if (bytes.length < CAST_LIMITS.controlHeader) throw new RangeError('Truncated frame header.');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const type = view.getInt32(0, true);
  const length = view.getUint32(4, true);
  const checksum = view.getInt32(8, true);
  const code = view.getInt32(12, true);
  if (length < CAST_LIMITS.controlHeader || length > CAST_LIMITS.controlMax) {
    throw new RangeError('Invalid control frame length.');
  }
  if (((length ^ type) | 0) !== checksum) throw new Error('Invalid control frame checksum.');
  const body = bytes.length >= length ? bytes.subarray(CAST_LIMITS.controlHeader, length) : null;
  return { type, code, length, body, bodyLength: length - CAST_LIMITS.controlHeader };
}

/** 媒体帧：`uint16 command | uint16 length | int32 serial | body`（c0.b）。 */
export function encodeMediaFrame({ command, serial = 0, body } = {}) {
  const payload = toBodyBytes(body);
  if (!Number.isInteger(command) || command < 0 || command > 0xffff) throw new RangeError('Media command out of range.');
  if (payload.length > CAST_LIMITS.mediaBodyMax) throw new RangeError('Media body is too large.');
  const buffer = new Uint8Array(CAST_LIMITS.mediaHeader + payload.length);
  const view = new DataView(buffer.buffer);
  view.setUint16(0, command, true);
  view.setUint16(2, payload.length, true);
  view.setInt32(4, serial | 0, true);
  buffer.set(payload, CAST_LIMITS.mediaHeader);
  return buffer;
}

export function decodeMediaFrame(buffer) {
  const bytes = asBytes(buffer);
  if (bytes.length < CAST_LIMITS.mediaHeader) throw new RangeError('Truncated frame header.');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const command = view.getUint16(0, true);
  const length = view.getUint16(2, true);
  const serial = view.getInt32(4, true);
  const body = bytes.length >= CAST_LIMITS.mediaHeader + length
    ? bytes.subarray(CAST_LIMITS.mediaHeader, CAST_LIMITS.mediaHeader + length)
    : null;
  return { command, serial, length, body };
}

/** 采集配置 30 字节（w.a）。 */
export function parseCaptureConfig(buffer) {
  const bytes = asBytes(buffer);
  if (bytes.length < 30) throw new RangeError('Capture configuration is too short.');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const width = view.getUint16(0, true);
  const height = view.getUint16(2, true);
  if (width < 16 || height < 16) throw new RangeError('Invalid capture dimensions.');
  return {
    width,
    height,
    fps: view.getInt32(4, true),
    encoder: view.getInt32(8, true) || 2,
    bitrate: view.getInt32(20, true),
    extended: bytes[29] !== 0,
    reserved: {
      r12: view.getInt32(12, true),
      r16: view.getInt32(16, true),
      r24: view.getInt32(24, true),
      r28: bytes[28],
    },
  };
}

/** 生成采集配置（测试与“连接诊断”自检用）。 */
export function buildCaptureConfig({ width, height, fps = 30, encoder = 2, bitrate = 2097152, extended = false } = {}) {
  if (width < 16 || height < 16) throw new RangeError('Invalid capture dimensions.');
  const buffer = new Uint8Array(30);
  const view = new DataView(buffer.buffer);
  view.setUint16(0, width, true);
  view.setUint16(2, height, true);
  view.setInt32(4, fps, true);
  view.setInt32(8, encoder, true);
  view.setInt32(20, bitrate, true);
  buffer[29] = extended ? 1 : 0;
  return buffer;
}

/** 命令 17 的 9 字节应答（w.b）。 */
export function buildEncoderReply({ codec = CAST_AVC_CODEC, width, height, extended = false } = {}) {
  const buffer = new Uint8Array(9);
  const view = new DataView(buffer.buffer);
  view.setInt32(0, codec | 0, true);
  view.setUint16(4, width, true);
  view.setUint16(6, height, true);
  buffer[8] = extended ? 1 : 0;
  return buffer;
}

export function parseEncoderReply(buffer) {
  const bytes = asBytes(buffer);
  if (bytes.length < 9) throw new RangeError('Encoder reply is too short.');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return {
    codec: view.getInt32(0, true),
    width: view.getUint16(4, true),
    height: view.getUint16(6, true),
    extended: bytes[8] !== 0,
  };
}

/**
 * 数据帧裸包（docs/CAST.md §5.3）。**length 是 length 字段之后的全部字节数**：
 *   - 传统模式：`[int32 length][frame]`；
 *   - extended：`[int32 length = frameLen + 4][int32 frameIndex][frame]`。
 * 没有新帧时固定 4 字节 0（extended 也不带 index，见 r1.java:1167-1170）。
 */
export function encodeFramePacket({ frame, index = 0, extended = false } = {}) {
  const payload = frame == null ? new Uint8Array(0) : asBytes(frame);
  const withIndex = Boolean(extended) && payload.length > 0;
  const header = withIndex ? 8 : 4;
  const length = payload.length + (withIndex ? 4 : 0);
  const buffer = new Uint8Array(header + payload.length);
  const view = new DataView(buffer.buffer);
  view.setInt32(0, length, true);
  if (withIndex) view.setInt32(4, index | 0, true);
  if (payload.length) buffer.set(payload, header);
  return buffer;
}

export function decodeFramePacket(buffer, { extended = false } = {}) {
  const bytes = asBytes(buffer);
  if (bytes.length < 4) throw new RangeError('Truncated frame packet.');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const length = view.getInt32(0, true);
  if (length < 0 || length > 32 * 1024 * 1024) throw new RangeError('Invalid frame length.');
  if (length === 0) return { length: 0, index: null, frame: new Uint8Array(0) };
  if (!extended) {
    if (bytes.length < 4 + length) throw new RangeError('Truncated frame body.');
    return { length, index: null, frame: bytes.subarray(4, 4 + length) };
  }
  const frameLength = length - 4;
  if (frameLength < 0 || bytes.length < 4 + length) throw new RangeError('Truncated frame body.');
  return { length, index: view.getInt32(4, true), frame: bytes.subarray(8, 8 + frameLength) };
}

/* ----------------------------- H.264 ----------------------------- */

/** 扫描 Annex-B 起始码，返回 `[{ type, start, payloadStart }]`。 */
export function splitAnnexB(buffer) {
  const bytes = asBytes(buffer);
  const starts = [];
  for (let i = 0; i + 3 < bytes.length; i += 1) {
    if (bytes[i] === 0 && bytes[i + 1] === 0) {
      if (bytes[i + 2] === 1) {
        starts.push({ start: i, header: 3 });
        i += 2;
      } else if (i + 4 < bytes.length && bytes[i + 2] === 0 && bytes[i + 3] === 1) {
        starts.push({ start: i, header: 4 });
        i += 3;
      }
    }
  }
  return starts.map((item) => ({
    type: bytes[item.start + item.header] & 0x1f,
    start: item.start,
    payloadStart: item.start + item.header,
  }));
}

/** 帧里是否带 SPS(7)+PPS(8)。 */
export function hasParameterSets(buffer) {
  const types = new Set(splitAnnexB(buffer).map((nal) => nal.type));
  return types.has(7) && types.has(8);
}

/** 是否关键帧：带 IDR(5)，或调用方已知关键帧标记。 */
export function isKeyframe(buffer) {
  return splitAnnexB(buffer).some((nal) => nal.type === 5);
}

/* ----------------------------- 档位规划 ----------------------------- */

function align16(value) {
  return Math.max(16, value & -16);
}

function macroblocksFor(width, height) {
  return Math.ceil(width / 16) * Math.ceil(height / 16);
}

/** H.264 level 查表（p.d）：按宏块数与“宏块 × fps”选最小可用档。 */
export function pickAvcLevel(width, height, fps) {
  const macroblocks = macroblocksFor(width, height);
  const load = macroblocks * fps;
  const table = [
    { id: 1, name: '1.0', maxMacroblocks: 99, maxLoad: 1485 },
    { id: 4, name: '1.1', maxMacroblocks: 396, maxLoad: 3000 },
    { id: 8, name: '1.2', maxMacroblocks: 396, maxLoad: 6000 },
    { id: 16, name: '1.3', maxMacroblocks: 396, maxLoad: 11880 },
    { id: 64, name: '2.1', maxMacroblocks: 792, maxLoad: 19800 },
    { id: 128, name: '2.2', maxMacroblocks: 1620, maxLoad: 20250 },
    { id: 256, name: '3.0', maxMacroblocks: 1620, maxLoad: 40500 },
    { id: 512, name: '3.1', maxMacroblocks: 3600, maxLoad: 108000 },
    { id: 1024, name: '3.2', maxMacroblocks: 5120, maxLoad: 216000 },
    { id: 2048, name: '4.0', maxMacroblocks: 8192, maxLoad: 245760 },
    { id: 8192, name: '4.2', maxMacroblocks: 8704, maxLoad: 522240 },
    { id: 16384, name: '5.0', maxMacroblocks: 22080, maxLoad: 589824 },
    { id: 32768, name: '5.1', maxMacroblocks: 36864, maxLoad: 983040 },
    { id: 65536, name: '5.2', maxMacroblocks: 36864, maxLoad: 2073600 },
  ];
  for (const row of table) {
    if (macroblocks <= row.maxMacroblocks && load <= row.maxLoad) return row;
  }
  return table[table.length - 1];
}

function clampBitrate(requested) {
  return requested >= 256000 && requested <= 8000000 ? Math.min(2097152, requested) : 2097152;
}

/** 按车机请求的采集配置规划实际编码参数（p.e）。 */
export function planCapture(config, { tier = 'performance', chipset = 'generic' } = {}) {
  const width = align16(Math.max(16, config.width));
  const height = align16(Math.max(16, config.height));
  const encoder = config.encoder || CAST_AVC_CODEC;
  const requestedFps = config.fps > 0 ? config.fps : 30;
  const requestedBitrate = config.bitrate > 0 ? config.bitrate : 2097152;
  const extended = Boolean(config.extended);
  const base = { tier, width, height, encoder, codec: encoder, extended, profile: null };
  const level31 = { id: CAST_LEVEL_IDS['3.1'], name: '3.1' };
  const level30 = { id: CAST_LEVEL_IDS['3.0'], name: '3.0' };

  if (tier === 'performance') {
    return {
      ...base,
      fps: 30,
      bitrate: chipset === 's905l3ab' ? clampBitrate(requestedBitrate) : 2621440,
      level: level31,
    };
  }
  if (tier === 'bitrate-safe') {
    return { ...base, fps: 30, bitrate: clampBitrate(requestedBitrate), level: level31 };
  }
  if (tier === 'compatibility') {
    const fps = requestedFps >= 15 ? Math.min(24, requestedFps) : 24;
    return {
      ...base,
      fps,
      bitrate: clampBitrate(requestedBitrate),
      level: pickAvcLevel(width, height, fps),
      profile: 'baseline',
    };
  }
  if (tier === 'balanced') {
    return { ...base, fps: 20, bitrate: 1572864, level: level31 };
  }

  // resolution-safe / decoder-safe：等比缩到 ≤ 800×480 且宏块总数 ≤ 1620
  const scale = Math.min(1, Math.min(800 / width, 480 / height));
  let outWidth = align16(Math.max(16, Math.floor(width * scale)));
  let outHeight = align16(Math.max(16, Math.floor(height * scale)));
  while (macroblocksFor(outWidth, outHeight) > 1620 && outWidth > 16 && outHeight > 16) {
    if (outWidth >= outHeight) outWidth -= 16;
    else outHeight -= 16;
  }
  return {
    ...base,
    width: outWidth,
    height: outHeight,
    fps: tier === 'resolution-safe' ? 20 : 15,
    bitrate: tier === 'resolution-safe' ? 1572864 : 1048576,
    level: level30,
  };
}

/** 下一档；已到最后一档返回 null。 */
export function nextTier(tier) {
  const index = CAST_TIERS.indexOf(tier);
  if (index < 0 || index + 1 >= CAST_TIERS.length) return null;
  return CAST_TIERS[index + 1];
}

/**
 * 是否该自动降档（p.f + r1.O0）：**15 s 内**就失败、但已经发出过帧 → 换下一档重试，
 * 且重试次数必须 < 5（不是“跑得越久越降档”）。
 */
export function shouldDowngrade({ tier, retries = 0, elapsedMs = 0, sentFrames = 0, stopped = false } = {}) {
  if (stopped) return false;
  if (!nextTier(tier)) return false;
  if (retries >= CAST_MAX_RETRIES) return false;
  return elapsedMs >= 0 && elapsedMs < CAST_LEARN_THRESHOLD.elapsedMs && sentFrames > 0;
}

/**
 * 是否该把当前档位记成“这台车能跑”的档位（p.b → compatibility-learned）：
 * 跑满 15 s 且 ≥150 帧。**它只影响下次首选档位，不触发降级**。
 */
export function shouldLearnTier({ elapsedMs = 0, sentFrames = 0, stopped = false } = {}) {
  if (stopped) return false;
  return elapsedMs >= CAST_LEARN_THRESHOLD.elapsedMs && sentFrames >= CAST_LEARN_THRESHOLD.sentFrames;
}

/* --------------------------- 诊断与格式化 --------------------------- */

export function formatCastBytes(value) {
  const n = Number(value) || 0;
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export function formatCastDuration(ms) {
  const total = Math.max(0, Math.round((Number(ms) || 0) / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  if (!minutes) return `${seconds}s`;
  return `${minutes}m${String(seconds).padStart(2, '0')}s`;
}

/** 帧率估算：用最近 N 帧时间戳，避免抖动。 */
export function estimateFps(timestamps, windowMs = 2000) {
  if (!Array.isArray(timestamps) || timestamps.length < 2) return 0;
  const end = timestamps[timestamps.length - 1];
  const start = Math.max(timestamps[0], end - windowMs);
  const count = timestamps.filter((ts) => ts >= start).length;
  const span = end - start;
  if (span <= 0) return 0;
  return Math.round(((count - 1) * 1000 * 10) / span) / 10;
}

/* --------------------------- 原生桥适配 --------------------------- */

export const CAST_BRIDGE_GLOBAL = 'ZeehoCast';
export const CAST_EVENT_SINK = '__zeehoCastEvent';
export const CAST_EVENT_NAME = 'zeeho-cast-event';

/** 找到原生桥；浏览器环境返回 null（不抛异常）。 */
export function detectCastBridge(scope) {
  const host = scope || (typeof globalThis !== 'undefined' ? globalThis : null);
  if (!host) return null;
  const bridge = host[CAST_BRIDGE_GLOBAL];
  if (!bridge || typeof bridge !== 'object') return null;
  return bridge;
}

function parseBridgeJson(value) {
  if (value == null) return {};
  if (typeof value === 'object') return value;
  try {
    return JSON.parse(String(value));
  } catch {
    return { ok: false, error: 'bridge_bad_json', raw: String(value) };
  }
}

/**
 * 投屏会话控制器：只依赖注入的 `bridge`，方便在没有原生桥的环境里跑状态机测试。
 *
 * @param {object} options
 * @param {object|null} options.bridge 原生桥（`window.ZeehoCast`）
 * @param {() => number} [options.now] 时钟，测试可注入
 * @param {(event) => void} [options.emit] 事件出口
 */
export function createCastClient({ bridge = null, now = () => Date.now(), emit = null } = {}) {
  const state = {
    phase: bridge ? CAST_PHASES.idle : CAST_PHASES.unavailable,
    reason: bridge ? '' : 'native_bridge_missing',
    profile: null,
    startedAt: 0,
    casting: false,
    vehicleConnected: false,
    tier: 'performance',
    retries: 0,
    stats: { sentFrames: 0, sentBytes: 0, keyframes: 0, fps: 0, emptyPulls: 0 },
    encoder: null,
    p2p: null,
    diagnostics: [],
  };

  const listeners = new Set();
  function notify(type, payload) {
    const event = { type, at: now(), payload: payload == null ? {} : payload };
    state.diagnostics.push({ type, at: event.at, payload: redactDiagnostic(event.payload) });
    if (state.diagnostics.length > 200) state.diagnostics.splice(0, state.diagnostics.length - 200);
    if (typeof emit === 'function') {
      try { emit(event); } catch { /* 调用方异常不影响状态机 */ }
    }
    for (const listener of listeners) {
      try { listener(event); } catch { /* 同上 */ }
    }
  }

  function canTransition(next) {
    return canCastTransition(state.phase, next);
  }

  function setPhase(next, reason) {
    if (!next || next === state.phase) {
      if (reason !== undefined) state.reason = reason || '';
      return state.phase;
    }
    if (!canTransition(next)) {
      notify('phase-rejected', { from: state.phase, to: next, reason: reason || '' });
      return state.phase;
    }
    const previous = state.phase;
    const fromRank = CAST_PHASE_RANK[previous];
    const toRank = CAST_PHASE_RANK[next];
    if (fromRank != null && toRank != null && toRank > fromRank + 1) {
      notify('phase-jump', { from: previous, to: next, skipped: toRank - fromRank - 1 });
    }
    state.phase = next;
    state.reason = reason || '';
    if (next === CAST_PHASES.casting) {
      state.startedAt = now();
      state.casting = true;
    }
    if (next === CAST_PHASES.idle || next === CAST_PHASES.failed) {
      state.casting = false;
      if (next === CAST_PHASES.idle) state.startedAt = 0;
    }
    notify('phase', { from: previous, to: next, reason: state.reason });
    return next;
  }

  function call(method, argument) {
    if (!bridge) {
      return { ok: false, error: 'native_bridge_missing' };
    }
    const fn = bridge[method];
    if (typeof fn !== 'function') {
      return { ok: false, error: 'bridge_method_missing', method };
    }
    try {
      const result = parseBridgeJson(argument === undefined ? fn() : fn(argument));
      if (result && result.ok === false) {
        return { ok: false, error: result.error || 'bridge_error', detail: result };
      }
      return { ok: true, data: result };
    } catch (error) {
      return { ok: false, error: 'bridge_timeout', message: error && error.message ? error.message : String(error) };
    }
  }

  /** 设置待连接的二维码 profile（页面上“扫到码”后调用）。 */
  function setProfile(profile) {
    state.profile = profile || null;
    notify('profile', { profile: profile ? { modelId: profile.modelId, p2pMac: profile.p2pMac } : null });
    return state.profile;
  }

  /** 让原生侧解析并保存 profile（口令不经过 JS 日志）。 */
  function pair(textOrProfile) {
    const parsed = typeof textOrProfile === 'string'
      ? parsePairingPayload(textOrProfile)
      : { ok: Boolean(textOrProfile), profile: textOrProfile || null, errors: [] };
    if (!parsed.ok || !parsed.profile) {
      const first = parsed.errors && parsed.errors[0];
      const error = first ? first.code : 'qr_invalid';
      setPhase(CAST_PHASES.failed, error);
      return { ok: false, error, message: castErrorText(error) };
    }
    state.profile = parsed.profile;
    const result = call('pair', JSON.stringify(parsed.profile));
    if (!result.ok) {
      setPhase(CAST_PHASES.failed, result.error);
      return result;
    }
    notify('paired', { modelId: parsed.profile.modelId, p2pMac: parsed.profile.p2pMac });
    return { ok: true, profile: parsed.profile };
  }

  /** 开始投屏：`options.tier` / `options.captureSource` / `options.frameFormat`。 */
  function start(options = {}) {
    if (!bridge) {
      state.phase = CAST_PHASES.unavailable;
      state.reason = 'native_bridge_missing';
      return { ok: false, error: 'native_bridge_missing', message: castErrorText('native_bridge_missing') };
    }
    if (!state.profile) {
      return { ok: false, error: 'qr_invalid', message: '还没有可用的投屏二维码' };
    }
    state.tier = CAST_TIERS.includes(options.tier) ? options.tier : 'performance';
    state.retries = 0;
    setPhase(CAST_PHASES.provisioning, '');
    const payload = {
      ...options,
      tier: state.tier,
      frameFormat: options.frameFormat === 'extended' ? 'extended' : 'traditional',
      profile: state.profile,
    };
    const result = call('start', JSON.stringify(payload));
    if (!result.ok) {
      setPhase(CAST_PHASES.failed, result.error);
      return result;
    }
    if (result.data && result.data.phase) setPhase(String(result.data.phase));
    return { ok: true, data: result.data };
  }

  function stop() {
    if (!bridge) return { ok: true, skipped: true };
    const wasActive = state.phase !== CAST_PHASES.idle;
    if (wasActive) setPhase(CAST_PHASES.stopping, 'user');
    const result = call('stop');
    setPhase(CAST_PHASES.idle, result.ok ? '' : result.error);
    return result;
  }

  /**
   * 申请屏幕录制授权（系统弹窗）。原生 start() 要求授权已完成，返回 `projection_permission_required`
   * 时页面应调用这个，等用户点允许后再点一次「开始投屏」。
   */
  function requestProjection() {
    if (!bridge) return { ok: false, error: 'native_bridge_missing' };
    const result = call('requestProjection');
    notify('projection', { ok: result.ok, error: result.error || '' });
    return result;
  }

  /** 拉一次原生状态（页面每次打开或定时刷新）。 */
  function refresh() {
    if (!bridge) return null;
    const result = call('getStatus');
    if (!result.ok) return null;
    applyStatus(result.data);
    return result.data;
  }

  /** 把原生上报的状态合并进本地状态机。 */
  function applyStatus(payload) {
    const data = parseBridgeJson(payload);
    if (!data || typeof data !== 'object') return state;
    if (data.phase) {
      const phase = normalizeCastPhase(data.phase);
      if (phase) setPhase(phase, data.reason);
      else notify('phase-unknown', { phase: String(data.phase) });
    }
    if (typeof data.vehicleConnected === 'boolean') state.vehicleConnected = data.vehicleConnected;
    if (data.captureSource) state.captureSource = data.captureSource;
    if (data.frameFormat) state.frameFormat = data.frameFormat;
    if (data.compatibilityTier) state.tier = data.compatibilityTier;
    if (Number.isFinite(Number(data.compatibilityRetryCount))) state.retries = Number(data.compatibilityRetryCount);
    if (data.encoder) state.encoder = data.encoder;
    if (data.p2p) state.p2p = data.p2p;
    if (data.stats) {
      state.stats = {
        sentFrames: Number(data.stats.sentFrames) || 0,
        sentBytes: Number(data.stats.sentBytes) || 0,
        keyframes: Number(data.stats.keyframes) || 0,
        fps: Number(data.stats.fps) || 0,
        emptyPulls: Number(data.stats.emptyPulls) || 0,
      };
    }
    if (typeof data.casting === 'boolean') state.casting = data.casting;
    return state;
  }

  /** 原生事件入口：`window.__zeehoCastEvent('<json>')`。 */
  function handleEvent(payload) {
    const data = parseBridgeJson(payload);
    if (!data || typeof data !== 'object') return state;
    const type = data.type || data.event || 'status';
    if (type === 'status' || type === 'phase') {
      applyStatus(data.status || data);
    } else if (type === 'diagnostic') {
      notify('diagnostic', data.payload || data);
    } else if (type === 'error') {
      setPhase(CAST_PHASES.failed, data.error || data.reason || 'bridge_error');
      notify('error', data);
    } else {
      notify(type, data.payload || data);
    }
    if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function' && typeof CustomEvent !== 'undefined') {
      try {
        window.dispatchEvent(new CustomEvent(CAST_EVENT_NAME, { detail: data }));
      } catch { /* 忽略：自定义事件只是可选的通知通道 */ }
    }
    return state;
  }

  function subscribe(listener) {
    if (typeof listener !== 'function') return () => {};
    listeners.add(listener);
    return () => listeners.delete(listener);
  }

  function getDiagnostics(limit = 50) {
    const remote = call('getDiagnostics', String(limit));
    const local = state.diagnostics.slice(-limit);
    return {
      local,
      remote: remote.ok ? remote.data : [],
      bridgeAvailable: Boolean(bridge),
    };
  }

  return {
    state,
    get phase() { return state.phase; },
    setProfile,
    start,
    stop,
    pair,
    requestProjection,
    refresh,
    applyStatus,
    handleEvent,
    subscribe,
    getDiagnostics,
    isAvailable: () => Boolean(bridge),
  };
}

/** 安装原生事件回调（幂等）：原生侧用 `window.__zeehoCastEvent('<json>')` 推送。 */
export function installCastEventSink(client, scope) {
  const host = scope || (typeof window !== 'undefined' ? window : null);
  if (!host || !client) return false;
  host[CAST_EVENT_SINK] = (payload) => client.handleEvent(payload);
  return true;
}

/** 一次性自检：帧格式往返，供“诊断”按钮打印。 */
export function castSelfTest() {
  const config = { width: 1024, height: 600, fps: 30, encoder: 2, bitrate: 2097152, extended: true };
  const capture = buildCaptureConfig(config);
  const parsedConfig = parseCaptureConfig(capture);
  const plan = planCapture(parsedConfig, { tier: 'compatibility' });
  const reply = buildEncoderReply({ width: plan.width, height: plan.height, extended: plan.extended });
  const control = encodeControlFrame({ type: CAST_CONTROL_IDS.clientInfoRequest, body: '{"HUID":"x"}' });
  const mediaFrame = encodeMediaFrame({ command: CAST_MEDIA_CMDS.configure, serial: 7, body: capture });
  return {
    ok: parseEncoderReply(reply).width === plan.width
      && decodeControlFrame(control).type === CAST_CONTROL_IDS.clientInfoRequest
      && decodeMediaFrame(mediaFrame).command === CAST_MEDIA_CMDS.configure,
    plan,
    ports: CAST_PORTS,
  };
}

/* ==== ZEEHO_VEHICLE_CAST:MODEL:END ==== */
