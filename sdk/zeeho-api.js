/**
 * 极核（ZEEHO）开放接口 SDK —— 浏览器 / Node 通用的零依赖实现。
 *
 * 适用范围：极核 App 3.0.6 的 `com.cfmoto.network.service.*` 接口层（见 docs/API-3.0.6.md）。
 *
 * 设计原则：
 *   1. **不内置任何密钥**。appId / appSecret / Basic 由调用方注入（页面里由用户填写，
 *      Node 里从环境变量或未跟踪的 config.local.json 读取）。这是刻意的：
 *      本仓库是公开静态站，不能把明文凭证写进产物。
 *   2. 纯 JS 实现 SHA-1 / MD5：浏览器的 WebCrypto 不提供 MD5，而极核网关的签名
 *      恰好是 `md5(sha1(pre + param + secret))`，所以两条哈希都必须自己带。
 *   3. 不依赖 DOM，Node 端可以直接 import，供 tools/ 下的脚本复用。
 *
 * 签名算法（逆向自 App 3.0.6 的 `RequestSignInterceptor`，并已对生产网关验证）：
 *
 *     param = "appId=<APPID>&nonce=<随机16位+毫秒>&timestamp=<毫秒>"
 *     GET   pre = scheme://host + path (+ "?" + 规范化 query)
 *     POST  pre = 规范化 query + body（**不含** scheme/host/path）
 *     sign  = md5( sha1( pre + param + APPSECRET ) 的十六进制小写串 )
 *
 * 规范化 query：按 (key, value) 排序，value 用 RFC 3986（unreserved = A-Za-z0-9-._~）
 * 编码，空值保留。这与 App 的 OkHttp 拦截器行为一致，实测多参数接口可用。
 */

/** 极核生产网关。测试环境见 docs（tapi-test.zeehoev.com，凭据不同）。 */
export const DEFAULT_BASE = 'https://tapi.zeehoev.com';

/** 三套业务前缀（App 3.0.6 的 BASE + 业务线）。 */
export const PATHS = {
  app: '/v1.0/app/cfmotoserverapp/',
  mine: '/v1.0/mine/cfmotoservermine/',
  social: '/v1.0/social/cfmotoserversocial/',
  mall: '/v1.0/mall/cfmotoservermall/',
};

const encoder = new TextEncoder();

/** 字符串 → UTF-8 字节。 */
export function utf8Bytes(str) {
  return encoder.encode(String(str));
}

function bytesToHex(bytes) {
  let out = '';
  for (let i = 0; i < bytes.length; i += 1) out += bytes[i].toString(16).padStart(2, '0');
  return out;
}

/* ------------------------------------------------------------------ *
 * SHA-1
 * ------------------------------------------------------------------ */

function rotateLeft32(value, count) {
  return ((value << count) | (value >>> (32 - count))) >>> 0;
}

/** 对字节数组做 SHA-1，返回 40 位小写十六进制。 */
export function sha1Hex(input) {
  const bytes = typeof input === 'string' ? utf8Bytes(input) : Uint8Array.from(input);
  const bitLength = bytes.length * 8;
  // 补位：0x80 + 若干 0x00 + 64 位大端长度
  const paddedLength = (((bytes.length + 8) >> 6) + 1) << 6;
  const buffer = new Uint8Array(paddedLength);
  buffer.set(bytes);
  buffer[bytes.length] = 0x80;
  const view = new DataView(buffer.buffer);
  view.setUint32(paddedLength - 8, Math.floor(bitLength / 0x100000000));
  view.setUint32(paddedLength - 4, bitLength >>> 0);

  let h0 = 0x67452301;
  let h1 = 0xefcdab89;
  let h2 = 0x98badcfe;
  let h3 = 0x10325476;
  let h4 = 0xc3d2e1f0;
  const w = new Uint32Array(80);

  for (let offset = 0; offset < paddedLength; offset += 64) {
    for (let i = 0; i < 16; i += 1) w[i] = view.getUint32(offset + i * 4);
    for (let i = 16; i < 80; i += 1) {
      w[i] = rotateLeft32(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
    }
    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    for (let i = 0; i < 80; i += 1) {
      let f;
      let k;
      if (i < 20) {
        f = (b & c) | (~b & d);
        k = 0x5a827999;
      } else if (i < 40) {
        f = b ^ c ^ d;
        k = 0x6ed9eba1;
      } else if (i < 60) {
        f = (b & c) | (b & d) | (c & d);
        k = 0x8f1bbcdc;
      } else {
        f = b ^ c ^ d;
        k = 0xca62c1d6;
      }
      const temp = (rotateLeft32(a, 5) + (f >>> 0) + e + k + w[i]) >>> 0;
      e = d;
      d = c;
      c = rotateLeft32(b, 30);
      b = a;
      a = temp;
    }
    h0 = (h0 + a) >>> 0;
    h1 = (h1 + b) >>> 0;
    h2 = (h2 + c) >>> 0;
    h3 = (h3 + d) >>> 0;
    h4 = (h4 + e) >>> 0;
  }

  const digest = new Uint8Array(20);
  const dv = new DataView(digest.buffer);
  dv.setUint32(0, h0);
  dv.setUint32(4, h1);
  dv.setUint32(8, h2);
  dv.setUint32(12, h3);
  dv.setUint32(16, h4);
  return bytesToHex(digest);
}

/* ------------------------------------------------------------------ *
 * MD5
 * ------------------------------------------------------------------ */

const MD5_SHIFTS = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
];

const MD5_K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0);

/** 对字节数组做 MD5，返回 32 位小写十六进制。 */
export function md5Hex(input) {
  const bytes = typeof input === 'string' ? utf8Bytes(input) : Uint8Array.from(input);
  const bitLength = bytes.length * 8;
  const paddedLength = (((bytes.length + 8) >> 6) + 1) << 6;
  const buffer = new Uint8Array(paddedLength);
  buffer.set(bytes);
  buffer[bytes.length] = 0x80;
  const view = new DataView(buffer.buffer);
  view.setUint32(paddedLength - 8, bitLength >>> 0, true);
  view.setUint32(paddedLength - 4, Math.floor(bitLength / 0x100000000), true);

  let a0 = 0x67452301;
  let b0 = 0xefcdab89;
  let c0 = 0x98badcfe;
  let d0 = 0x10325476;

  for (let offset = 0; offset < paddedLength; offset += 64) {
    const m = new Uint32Array(16);
    for (let i = 0; i < 16; i += 1) m[i] = view.getUint32(offset + i * 4, true);
    let a = a0;
    let b = b0;
    let c = c0;
    let d = d0;
    for (let i = 0; i < 64; i += 1) {
      let f;
      let g;
      if (i < 16) {
        f = (b & c) | (~b & d);
        g = i;
      } else if (i < 32) {
        f = (d & b) | (~d & c);
        g = (5 * i + 1) % 16;
      } else if (i < 48) {
        f = b ^ c ^ d;
        g = (3 * i + 5) % 16;
      } else {
        f = c ^ (b | ~d);
        g = (7 * i) % 16;
      }
      const sum = (a + (f >>> 0) + MD5_K[i] + m[g]) >>> 0;
      const shifted = ((sum << MD5_SHIFTS[i]) | (sum >>> (32 - MD5_SHIFTS[i]))) >>> 0;
      a = d;
      d = c;
      c = b;
      b = (b + shifted) >>> 0;
    }
    a0 = (a0 + a) >>> 0;
    b0 = (b0 + b) >>> 0;
    c0 = (c0 + c) >>> 0;
    d0 = (d0 + d) >>> 0;
  }

  const digest = new Uint8Array(16);
  const dv = new DataView(digest.buffer);
  dv.setUint32(0, a0, true);
  dv.setUint32(4, b0, true);
  dv.setUint32(8, c0, true);
  dv.setUint32(12, d0, true);
  return bytesToHex(digest);
}

/* ------------------------------------------------------------------ *
 * 签名
 * ------------------------------------------------------------------ */

/** RFC 3986 编码：只保留 A-Z a-z 0-9 - . _ ~，其余按 UTF-8 百分号编码。 */
export function rfc3986(value) {
  return encodeURIComponent(String(value)).replace(/[!'()*]/g, (ch) => `%${ch.charCodeAt(0).toString(16).toUpperCase()}`);
}

/**
 * 把 URL 的 query 规范化成签名用的字符串。
 * 与 Python 参考实现一致：解析成键值对、按 (key,value) 排序、value 用 RFC 3986 编码、空值保留。
 * @param {string|URLSearchParams|Array<[string,string]>} query
 */
export function canonicalQuery(query) {
  let pairs;
  if (query instanceof URLSearchParams) {
    pairs = [...query.entries()];
  } else if (Array.isArray(query)) {
    pairs = query.map(([k, v]) => [String(k), v == null ? '' : String(v)]);
  } else {
    const text = String(query ?? '').replace(/^\?/, '');
    pairs = [...new URLSearchParams(text).entries()];
  }
  pairs.sort((left, right) => {
    if (left[0] !== right[0]) return left[0] < right[0] ? -1 : 1;
    if (left[1] !== right[1]) return left[1] < right[1] ? -1 : 1;
    return 0;
  });
  return pairs.map(([k, v]) => `${rfc3986(k)}=${rfc3986(v)}`).join('&');
}

/**
 * 计算一次签名。返回值里的 param / sign 要分别放进 `Cfmoto-X-Param` / `Cfmoto-X-Sign`。
 * @param {object} options
 * @param {string} options.url 完整 URL（可带 query）
 * @param {'GET'|'POST'|'PUT'|'DELETE'|string} [options.method]
 * @param {string} [options.body] 已序列化的请求体，必须与实际发送的字节完全一致
 * @param {string} options.appId
 * @param {string} options.appSecret
 * @param {string} [options.nonce] 测试时可固定
 * @param {number|string} [options.timestamp] 测试时可固定
 */
export function buildSign({ url, method = 'GET', body = '', appId, appSecret, nonce, timestamp }) {
  if (!appId) throw new TypeError('buildSign: appId is required');
  if (!appSecret) throw new TypeError('buildSign: appSecret is required');
  const upperMethod = String(method).toUpperCase();
  const parsed = new URL(url);
  const query = canonicalQuery(parsed.search);
  const ts = timestamp == null ? Date.now() : Number(timestamp);
  // App 3.0.6 的 nonce = 16 位随机串 + 毫秒时间戳；部分网关接口会校验这个长度，别省。
  const random = nonce == null ? `${randomNonce()}${ts}` : String(nonce);
  const param = `appId=${appId}&nonce=${random}&timestamp=${ts}`;
  const pre = upperMethod === 'GET'
    ? `${parsed.origin}${parsed.pathname}${query ? `?${query}` : ''}`
    : `${query}${body || ''}`;
  const sign = md5Hex(sha1Hex(`${pre}${param}${appSecret}`));
  return { param, sign, nonce: random, ts };
}

/** 16 位字母数字随机串（App 3.0.6 的 nonce 前半段）。 */
export function randomNonce() {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let out = '';
  const cryptoObj = globalThis.crypto;
  if (cryptoObj?.getRandomValues) {
    const buf = new Uint8Array(16);
    cryptoObj.getRandomValues(buf);
    for (let i = 0; i < 16; i += 1) out += alphabet[buf[i] % alphabet.length];
    return out;
  }
  for (let i = 0; i < 16; i += 1) out += alphabet[Math.floor(Math.random() * alphabet.length)];
  return out;
}

/**
 * 构造一次请求的完整头。token 为空时不带 Authorization。
 * @param {object} options 同 buildSign，外加 token
 */
export function buildHeaders(options) {
  const { token, ...signOptions } = options;
  const { param, sign, nonce, ts } = buildSign(signOptions);
  const headers = {
    'Content-Type': 'application/json',
    // 网关对部分接口（如 vehicleHomePageV2）会按 UA 做放行判断：缺省/Node UA 会 430 permit error。
    // 用 App 3.0.6 的原值最稳，调用方仍可通过 headers 覆盖。
    'User-Agent': 'okhttp/4.9.2',
    appId: signOptions.appId,
    nonce,
    timestamp: String(ts),
    signature: sign,
    'Cfmoto-X-Param': param,
    'Cfmoto-X-Sign': sign,
    'Cfmoto-X-Sign-Type': '0',
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

/* ------------------------------------------------------------------ *
 * 客户端
 * ------------------------------------------------------------------ */

export class ZeehoApiError extends Error {
  constructor(message, { status = 0, code = '', body = '', url = '' } = {}) {
    super(message);
    this.name = 'ZeehoApiError';
    this.status = status;
    this.code = code;
    this.body = body;
    this.url = url;
  }
}

/**
 * 极核接口客户端。
 *
 * ```js
 * const client = new ZeehoClient({ appId, appSecret, basic });
 * const { data } = await client.vehicleList(token);
 * ```
 *
 * 凭证只存在实例上，不写 localStorage、不打日志；页面层负责持久化策略。
 */
export class ZeehoClient {
  constructor({ base = DEFAULT_BASE, appId = '', appSecret = '', basic = '', token = '', fetchImpl, timeout = 20000 } = {}) {
    this.base = String(base).replace(/\/+$/, '');
    this.appId = appId;
    this.appSecret = appSecret;
    this.basic = basic;
    this.token = token;
    this.fetchImpl = fetchImpl || globalThis.fetch?.bind(globalThis);
    this.timeout = timeout;
    if (!this.fetchImpl) throw new TypeError('ZeehoClient: fetch is unavailable (pass fetchImpl)');
  }

  /** 是否具备自签名能力（页面「复用面板签名」模式下为 false）。 */
  get canSign() {
    return Boolean(this.appId && this.appSecret);
  }

  /** 拼绝对 URL。path 可以是 `/v1.0/...` 也可以直接是完整 URL。 */
  url(path, query) {
    let absolute = /^https?:\/\//i.test(path) ? path : `${this.base}${path.startsWith('/') ? '' : '/'}${path}`;
    if (query) {
      const pairs = [];
      if (query instanceof URLSearchParams) {
        pairs.push(...query.entries());
      } else {
        for (const [k, v] of Object.entries(query)) {
          if (v === undefined || v === null) continue;
          if (Array.isArray(v)) v.forEach((item) => pairs.push([k, String(item)]));
          else pairs.push([k, String(v)]);
        }
      }
      // 线上 query 与签名 canonicalQuery 使用同一套 RFC3986 编码，避免空格/+、~ 等差异。
      const text = pairs.map(([k, v]) => `${rfc3986(k)}=${rfc3986(v)}`).join('&');
      if (text) absolute += (absolute.includes('?') ? '&' : '?') + text;
    }
    return absolute;
  }

  /**
   * 发起请求。
   * @param {object} options
   * @param {string} options.path 例如 `/v1.0/app/cfmotoserverapp/vehicle/list`
   * @param {'GET'|'POST'|'PUT'|'DELETE'} [options.method]
   * @param {object|Array|URLSearchParams} [options.query]
   * @param {object|string} [options.body]
   * @param {string} [options.token] 覆盖实例 token
   * @param {boolean} [options.basicAuth] 用 Basic 认证代替 Bearer（登录接口）
   * @param {boolean} [options.anonymous] 不带 Authorization
   * @param {object} [options.headers]
   * @param {object} [options.sign] 自定义签名参数（复用面板签名时传 {param, sign}）
   * @param {number} [options.timeout]
   */
  async request({
    path,
    method = 'GET',
    query,
    body,
    token = this.token,
    basicAuth = false,
    anonymous = false,
    headers = {},
    sign = null,
    timeout = this.timeout,
  } = {}) {
    const upperMethod = String(method).toUpperCase();
    const url = this.url(path, query);
    const bodyText = body === undefined || body === null
      ? ''
      : typeof body === 'string' ? body : JSON.stringify(body);

    let signHeaders;
    if (sign) {
      // 面板签名模式：只拿到 param/sign，其余头照样补齐
      signHeaders = {
        'Content-Type': 'application/json',
        'User-Agent': 'okhttp/4.9.2',
        'Cfmoto-X-Param': sign.param,
        'Cfmoto-X-Sign': sign.sign,
        'Cfmoto-X-Sign-Type': '0',
      };
    } else {
      if (!this.canSign) {
        throw new ZeehoApiError('缺少 appId/appSecret：请先在设置里填写，或使用「复用面板签名」', { url });
      }
      signHeaders = buildHeaders({
        url,
        method: upperMethod,
        body: bodyText,
        appId: this.appId,
        appSecret: this.appSecret,
      });
    }
    const finalHeaders = { ...headers, ...signHeaders };
    if (basicAuth) {
      // 登录接口用 Basic；anonymous 只是「不要 Bearer」，不能把 Basic 也吞掉。
      if (!this.basic) throw new ZeehoApiError('缺少 Basic 凭据（登录接口需要）', { url });
      finalHeaders.Authorization = this.basic.startsWith('Basic ') ? this.basic : `Basic ${this.basic}`;
    } else if (!anonymous && token) {
      finalHeaders.Authorization = `Bearer ${token}`;
    }

    const controller = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = controller ? setTimeout(() => controller.abort(), timeout) : null;
    const startedAt = Date.now();
    let response;
    try {
      response = await this.fetchImpl(url, {
        method: upperMethod,
        headers: finalHeaders,
        body: upperMethod === 'GET' || upperMethod === 'HEAD' ? undefined : bodyText || undefined,
        signal: controller?.signal,
      });
    } catch (error) {
      if (timer) clearTimeout(timer);
      const reason = error?.name === 'AbortError' ? `请求超时（${timeout}ms）` : `网络错误：${error?.message || error}`;
      throw new ZeehoApiError(reason, { url });
    }
    if (timer) clearTimeout(timer);
    let text;
    try {
      text = await response.text();
    } catch (error) {
      throw new ZeehoApiError(`读取响应失败：${error?.message || error}`, { url });
    }
    const elapsed = Date.now() - startedAt;
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* 401 网关返回 XML */ }
    const result = {
      status: response.status,
      ok: response.ok,
      url,
      method: upperMethod,
      headers: Object.fromEntries(response.headers.entries?.() || []),
      text,
      data: json,
      code: json?.code || '',
      message: json?.message || '',
      elapsed,
      requestHeaders: finalHeaders,
    };
    if (response.status === 401 || (json && String(json.code || '') === '401')) {
      result.authFailed = true;
    }
    return result;
  }

  /* --------------------------- 登录 / 账号 --------------------------- */

  /** GET /v1.0/mine/cfmotoservermine/authCode/{phone}（3.0.6 的无图形验证码发码口）。 */
  authCode(phone) {
    return this.request({ path: `${PATHS.mine}authCode/${encodeURIComponent(phone)}`, anonymous: true });
  }

  /** POST /v1.0/mine/cfmotoservermine/user/loginByPhone（Basic 认证 + JSON body）。 */
  loginByPhone(phone, authCode) {
    return this.request({
      path: `${PATHS.mine}user/loginByPhone`,
      method: 'POST',
      body: { phone, authCode },
      anonymous: true,
      basicAuth: true,
    });
  }

  /** 从登录响应里取 access_token（不同版本字段位置都兼容）。 */
  static extractToken(payload) {
    const data = payload?.data ?? payload;
    return data?.tokenInfo?.access_token || data?.access_token || data?.token || '';
  }

  /* ------------------------------ 车辆 ------------------------------ */

  vehicleList(token) {
    return this.request({ path: `${PATHS.app}vehicle/list`, token });
  }

  vehicleHome(vin, token) {
    return this.request({ path: `${PATHS.app}vehicleHomePageV2/${encodeURIComponent(vin)}`, token });
  }

  vehicleHomeV1(vin, token) {
    return this.request({ path: `${PATHS.app}vehicleHomePage/${encodeURIComponent(vin)}`, token });
  }

  tireMonitoring(vin, token) {
    return this.request({
      path: `${PATHS.app}app/vehicle/tire/monitoring`,
      query: { vinNo: vin, sensorType: 'EXTERNAL' },
      token,
    });
  }

  vehicleSetItems(vin, token) {
    return this.request({ path: `${PATHS.app}vehicleSet/items`, query: { vinNo: vin, homepageInvoke: '1' }, token });
  }

  vehicleSetInfo(vin, token) {
    return this.request({ path: `${PATHS.app}vehicleSet/vehicleSetInfo/${encodeURIComponent(vin)}`, token });
  }

  bluetoothServiceCodes(vin, token) {
    return this.request({ path: `${PATHS.app}vehicleSet/blueToothServiceCode`, query: { vinNo: vin }, token });
  }

  carSet({ vin, carUserId, virtualFlag = '0' }, token) {
    return this.request({ path: `${PATHS.app}carSet`, query: { vinNo: vin, carUserId, virtualFlag }, token });
  }

  batteryInfo(vin, token) {
    return this.request({ path: `${PATHS.app}batteryInfo/${encodeURIComponent(vin)}`, token });
  }

  /* ------------------------------ 骑行 ------------------------------ */

  rideHome(vin, token) {
    return this.request({ path: `${PATHS.app}homeRideInfo`, query: { vinNo: vin }, token });
  }

  rideMonth(vin, month, token) {
    return this.request({ path: `${PATHS.app}myRideInfo`, query: { vinNo: vin, month }, token });
  }

  rideTrack({ vin, startDate = '', endDate = '', page = '1', pageSize = '20' }, token) {
    return this.request({
      path: `${PATHS.app}ridetrack_v2`,
      query: { vinNo: vin, startDate, endDate, page, pageSize },
      token,
    });
  }

  analyse(vin, month, token) {
    return this.request({ path: `${PATHS.app}analyse/analyse`, query: { vinNo: vin, month }, token });
  }

  /* ------------------------------- OTA ------------------------------- */

  /** GET checkVersion?vinNo=&targetVersion= —— 传当前版本，问服务端是否有更新。 */
  checkVersion(vin, targetVersion = '0', token) {
    return this.request({ path: `${PATHS.app}checkVersion`, query: { vinNo: vin, targetVersion }, token });
  }

  /** GET ota/mmi/info/v2?vinNo= —— 仪表 OTA 全量信息（含 fileInfo{wifiAddress,fileUrl,sign}）。 */
  otaInfo(vin, token) {
    return this.request({ path: `${PATHS.app}ota/mmi/info/v2`, query: { vinNo: vin }, token });
  }

  /** GET ota/mmi/status?recordId= —— 单个 OTA 任务的进度。 */
  otaStatus(recordId, token) {
    return this.request({ path: `${PATHS.app}ota/mmi/status`, query: { recordId }, token });
  }

  otaList(vin, token) {
    return this.request({ path: `${PATHS.app}ota/list`, query: { vinNo: vin }, token });
  }

  otaDetail(id, vin, token) {
    return this.request({ path: `${PATHS.app}otaDetail`, query: { id, vinNo: vin }, token });
  }

  otaRecord(vin, token) {
    return this.request({ path: `${PATHS.app}otaRecord`, query: { vinNo: vin }, token });
  }

  otaChecks(vin, token) {
    return this.request({ path: `${PATHS.app}checks`, query: { vinNo: vin }, token });
  }

  otaRedPoint(vin, token) {
    return this.request({ path: `${PATHS.app}redPoint`, query: { vinNo: vin }, token });
  }

  /** GET app/ota/v2/red-point/content?vinNo= —— 红点内容（fileInfoList）。 */
  otaContent(vin, token) {
    return this.request({ path: `${PATHS.app}app/ota/v2/red-point/content`, query: { vinNo: vin }, token });
  }

  /** GET upstatus?vinNo= —— 升级状态。 */
  upgradeStatus(vin, token) {
    return this.request({ path: `${PATHS.app}upstatus`, query: { vinNo: vin }, token });
  }

  /* ---------------------------- 消息 / 我的 ---------------------------- */

  /** GET app/versions/newest?systemType=1 —— 最新 App 版本（systemType 必传，否则 40000）。 */
  appVersion(systemType = '1', token) {
    return this.request({ path: `${PATHS.mine}app/versions/newest`, query: { systemType }, token });
  }

  appCodes(token) {
    return this.request({ path: `${PATHS.mine}user/appCode`, token });
  }

  messageUnread(token) {
    return this.request({ path: `${PATHS.mine}app/Message/allUnRead`, token });
  }

  messageList({ uid, page = '1', pageSize = '20', messageKind = '3' }, token) {
    return this.request({
      path: `${PATHS.mine}app/Message/list_v2`,
      query: { userId: uid, page, pageSize, messageKind },
      token,
    });
  }

  popwindow(token) {
    return this.request({ path: `${PATHS.mine}app/popwindow/v2`, token });
  }

  muteStatus(uid, token) {
    return this.request({ path: `${PATHS.mine}setting/userMuteStatus/${encodeURIComponent(uid)}`, token });
  }

  /* ------------------------------ 车控 ------------------------------ */

  /** PUT vehicleInfo/control/{vin} —— 寻车（响铃/闪灯）。 */
  findCar(vin, body, token) {
    return this.request({ path: `${PATHS.app}vehicleInfo/control/${encodeURIComponent(vin)}`, method: 'PUT', body, token });
  }

  /** POST dbpoint/send —— 通用车控指令下发。 */
  sendCommand(payload, token) {
    return this.request({ path: `${PATHS.app}dbpoint/send`, method: 'POST', body: payload, token });
  }

  /* ------------------------- 壁纸与主题（3.0.6） ------------------------- */

  /** GET vehicle/wallpaper/pageVehicleWallpaper —— 当前账号的自定义壁纸列表。 */
  wallpaperPage(vin, { pageNum = 1, pageSize = 20 } = {}, token) {
    return this.request({
      path: `${PATHS.app}vehicle/wallpaper/pageVehicleWallpaper`,
      method: 'GET',
      query: { vinNo: vin, pageNum, pageSize },
      token,
    });
  }

  /** GET vehicle/wallpaper/getUserWallpaperCount —— 自定义壁纸数量。 */
  wallpaperCount(vin, token) {
    return this.request({
      path: `${PATHS.app}vehicle/wallpaper/getUserWallpaperCount`,
      method: 'GET',
      query: { vinNo: vin },
      token,
    });
  }

  /** GET vehicle/wallpaper/vehicleWallpaperDetail/{id} —— 壁纸详情（需要 vinNo）。 */
  wallpaperDetail(id, vin, token) {
    return this.request({
      path: `${PATHS.app}vehicle/wallpaper/vehicleWallpaperDetail/${encodeURIComponent(id)}`,
      method: 'GET',
      query: { vinNo: vin },
      token,
    });
  }

  /** GET vehicle/wallpaper/getWallpaperVehicle/{id} —— 壁纸适用车型。 */
  wallpaperVehicles(id, token) {
    return this.request({
      path: `${PATHS.app}vehicle/wallpaper/getWallpaperVehicle/${encodeURIComponent(id)}`,
      method: 'GET',
      token,
    });
  }

  /** GET vehicleTheme/list —— 车机主题列表（是否开放取决于 themeShopFlag）。 */
  themeList(vin, token) {
    return this.request({ path: `${PATHS.app}vehicleTheme/list`, method: 'GET', query: { vinNo: vin }, token });
  }

  /** GET theme/one —— 车机主题详情。 */
  themeDetail(themeId, token) {
    return this.request({ path: `${PATHS.app}theme/one`, method: 'GET', query: { themeId }, token });
  }

  /** POST vehicle/wallpaper/useWallpaper —— 使用壁纸（写操作）。 */
  useWallpaper(payload, token) {
    return this.request({ path: `${PATHS.app}vehicle/wallpaper/useWallpaper`, method: 'POST', body: payload, token });
  }

  /** POST vehicleSet/network/unlock —— 云解锁（一次性 AES）。 */
  networkUnlock(payload, token) {
    return this.request({ path: `${PATHS.app}vehicleSet/network/unlock`, method: 'POST', body: payload, token });
  }
}

/** 从 `vehicleHomePageV2` 的响应里挑出界面要用的关键字段（容忍 null）。 */
export function pickVehicleSummary(home) {
  const data = home?.data ?? home ?? {};
  const soc = data.bmssoc === '' || data.bmssoc == null ? NaN : Number(data.bmssoc);
  const range = data.hmiRidableMile === '' || data.hmiRidableMile == null ? NaN : Number(data.hmiRidableMile);
  const charging = data.whetherChargeState === true || data.whetherChargeState === 1
    || ['1', 'true', 'TRUE'].includes(String(data.whetherChargeState));
  return {
    vin: data.vinNo || '',
    name: data.vehicleName || '',
    typeName: data.vehicleTypeName || '',
    online: data.onlineStatus || '',
    rideState: data.rideState || '',
    soc: Number.isFinite(soc) ? soc : null,
    rangeKm: Number.isFinite(range) ? range : null,
    totalKm: data.totalRideMile ?? null,
    monthKm: data.rideMileageMonth ?? null,
    monthMinutes: data.ridingTimeMonthUnitMinute ?? null,
    otaVersion: data.otaVersion || '',
    pic: data.vehiclePicUrl || '',
    pic3d: data.vehiclePic3dUrl || '',
    ble: data.bluetoothAddress || '',
    hmiBle: data.hmiBluetoothAddress || '',
    lock: data.seatLockState ?? null,
    charging,
    chargeState: data.chargeState ?? null,
    wifiAddress: data.wifiAddress || '',
    supportCustomWallpaper: data.supportCustomWallpaper === true
      || ['1', 'true', 'TRUE'].includes(String(data.supportCustomWallpaper)),
    wallpaperWidth: Number(data.wallpaperResolutionWidth) || null,
    wallpaperHeight: Number(data.wallpaperResolutionHeight) || null,
    themeShopFlag: String(data.themeShopFlag ?? ''),
    themeSupported: String(data.themeShopFlag ?? '') === '1',
    themeShopNewFlag: String(data.themeShopNewFlag ?? ''),
    appThemeRgb: data.appThemeRgb || '',
    appChargeRgb: data.appChargeRgb || '',
    appThemeImage: data.appThemeImage || '',
    appChargeImage: data.appChargeImage || '',
    wallpaperConfig: (() => {
      try {
        const parsed = JSON.parse(data.subjectWallpaperConfig || '[]');
        return Array.isArray(parsed) ? parsed : [];
      } catch { return []; }
    })(),
    refreshTime: data.refreshTime || '',
    encryptInfo: data.encryptInfo || null,
    location: data.location || null,
  };
}

export default { ZeehoClient, buildSign, buildHeaders, canonicalQuery, md5Hex, sha1Hex, pickVehicleSummary, PATHS, DEFAULT_BASE };
