/* ==== ZEEHO_PANEL_CAR:BRIDGE:BEGIN ==== */
/* 面板页（index.html）里的“请求适配层”。
   api-car-ui.js 本来跑在 api.html 里，依赖 state / request / byId / buildUrl 这些页面内函数；
   面板页没有那些东西，这里用面板自己的 window.ZEEHO_SIGN 补一份等价实现，
   这样同一套车辆工具 UI 代码可以直接跑在面板里，不用维护两份。 */

const PANEL_CAR_STORAGE = { token: 'zeeho.token', vin: 'zeeho.api.vin', uid: 'zeeho.api.uid', theme: 'zeeho.api.theme' };
const PANEL_CAR_BASE = 'https://tapi.zeehoev.com';

const state = {
  mode: 'panel',
  token: '',
  vin: '',
  uid: '',
  vehicles: [],
};

function $(id) { return document.getElementById(id); }

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
}

function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

function toast(message, ms = 2400) {
  let el = document.getElementById('ze-car-toast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'ze-car-toast';
    el.className = 'ze-car-toast';
    document.body.appendChild(el);
  }
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), ms);
}

function fmtTime(value) {
  if (!value) return '';
  return String(value).replace('T', ' ').replace(/\..*$/, '');
}

function getBase() { return PANEL_CAR_BASE; }

function rfc3986(value) {
  return encodeURIComponent(String(value)).replace(/[!'()*]/g, (ch) => `%${ch.charCodeAt(0).toString(16).toUpperCase()}`);
}

function buildUrl(path, params, values) {
  const filled = String(path).replace(/\{([a-zA-Z0-9_]+)\}/g, (match, key) => {
    const value = values?.[key];
    return value === undefined || value === null || value === '' ? match : encodeURIComponent(value);
  });
  let url = `${getBase()}${filled.startsWith('/') ? '' : '/'}${filled}`;
  const pairs = [];
  for (const param of params || []) {
    if (param.in !== 'query') continue;
    const value = values?.[param.key];
    if (value === undefined || value === null || value === '') continue;
    pairs.push([param.key, value]);
  }
  if (pairs.length) url += (url.includes('?') ? '&' : '?') + pairs.map(([k, v]) => `${rfc3986(k)}=${rfc3986(v)}`).join('&');
  return url;
}

function hasOwnCredentials() { return false; }

function ownHeaders() {
  throw new Error('面板页只支持「借面板签名」，自填凭据请用 api.html');
}

function panelQueryParams(url) {
  const params = {};
  for (const [key, value] of new URL(url).searchParams) {
    if (params[key] === undefined) params[key] = value;
    else if (Array.isArray(params[key])) params[key].push(value);
    else params[key] = [params[key], value];
  }
  return params;
}

/** 面板签名：本页里 ZEEHO_SIGN 是全局的，直接调；它可能比我们的脚本晚就绪，稍等一会。 */
async function panelHeaders(url, method, bodyText) {
  let sign = window.ZEEHO_SIGN;
  for (let i = 0; i < 40 && !sign?.sign; i += 1) { await sleep(150); sign = window.ZEEHO_SIGN; }
  if (!sign?.sign) throw new Error('面板签名还没就绪，稍后重试');
  const signed = sign.sign(url, panelQueryParams(url), bodyText || '', String(method || 'GET').toUpperCase());
  return {
    'Content-Type': 'application/json;charset=UTF-8',
    'Accept-Language': 'zh-CN',
    interfaceversion: '2',
    'cfmoto-x-param': signed.param,
    'cfmoto-x-sign': signed.sign,
    'cfmoto-x-sign-type': '0',
  };
}

/** api.html 里那几个端点对象在这里就近声明（只列车辆工具用得到的）。 */
const PANEL_CAR_ENDPOINTS = {
  vehicle_list: { path: '/v1.0/app/cfmotoserverapp/vehicle/list', method: 'GET', params: [], auth: 'token' },
  vehicle_home: { path: '/v1.0/app/cfmotoserverapp/vehicleHomePageV2/{vin}', method: 'GET', params: [{ in: 'path', key: 'vin' }], auth: 'token' },
  ota_info: { path: '/v1.0/app/cfmotoserverapp/ota/mmi/info/v2', method: 'GET', params: [{ in: 'query', key: 'vinNo' }], auth: 'token' },
  check_version: { path: '/v1.0/app/cfmotoserverapp/checkVersion', method: 'GET', params: [{ in: 'query', key: 'vinNo' }, { in: 'query', key: 'targetVersion' }], auth: 'token' },
  upstatus: { path: '/v1.0/app/cfmotoserverapp/upstatus', method: 'GET', params: [{ in: 'query', key: 'vinNo' }], auth: 'token' },
  vehicle_set_items: { path: '/v1.0/app/cfmotoserverapp/vehicleSet/items', method: 'GET', params: [{ in: 'query', key: 'vinNo' }, { in: 'query', key: 'homepageInvoke' }], auth: 'token' },
  vehicle_set_info: { path: '/v1.0/app/cfmotoserverapp/vehicleSet/vehicleSetInfo/{vin}', method: 'GET', params: [{ in: 'path', key: 'vin' }], auth: 'token' },
  bt_codes: { path: '/v1.0/app/cfmotoserverapp/vehicleSet/blueToothServiceCode', method: 'GET', params: [{ in: 'query', key: 'vinNo' }], auth: 'token' },
  wallpaper_page: { path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/pageVehicleWallpaper', method: 'GET', params: [{ in: 'query', key: 'vinNo' }, { in: 'query', key: 'pageNum' }, { in: 'query', key: 'pageSize' }], auth: 'token' },
  wallpaper_count: { path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/getUserWallpaperCount', method: 'GET', params: [{ in: 'query', key: 'vinNo' }], auth: 'token' },
  wallpaper_detail: { path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/vehicleWallpaperDetail/{id}', method: 'GET', params: [{ in: 'path', key: 'id' }, { in: 'query', key: 'vinNo' }], auth: 'token' },
  wallpaper_use: { path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/useWallpaper', method: 'POST', params: [], auth: 'token' },
  wallpaper_delete: { path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/deleteWallpaperV2/{id}', method: 'DELETE', params: [{ in: 'path', key: 'id' }], auth: 'token' },
  wallpaper_update: { path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/update/{id}', method: 'POST', params: [{ in: 'path', key: 'id' }], auth: 'token' },
  wallpaper_save: { path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/save', method: 'POST', params: [{ in: 'query', key: 'vinNo' }], auth: 'token' },
  wallpaper_progress: { path: '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/useWallpaperProgress/{recordId}', method: 'GET', params: [{ in: 'path', key: 'recordId' }], auth: 'token' },
};

function byId(id) { return PANEL_CAR_ENDPOINTS[id]; }

/** 与 api.html 的 request() 同形：签名 + fetch + 解析，返回 {data, text, response, ...}。 */
async function request({ endpoint, values, body, token }) {
  const url = buildUrl(endpoint.path, endpoint.params, values || {});
  const method = String(endpoint.method || 'GET').toUpperCase();
  const bodyText = body == null ? '' : JSON.stringify(body);
  const finalToken = token ?? state.token;
  if (endpoint.auth === 'token' && !finalToken) throw new Error('先在面板登录（我的 → 登录）后再用');
  const headers = await panelHeaders(url, method, bodyText);
  if (finalToken) headers.Authorization = `Bearer ${finalToken}`;
  const response = await fetch(url, {
    method,
    headers,
    body: method === 'GET' || method === 'HEAD' ? undefined : bodyText || undefined,
    cache: 'no-store',
  });
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* XML/HTML */ }
  return { url, method, headers, body: bodyText, response, text, data };
}

function readPanelToken() {
  try { state.token = localStorage.getItem(PANEL_CAR_STORAGE.token) || ''; } catch { state.token = ''; }
  try { state.vin = localStorage.getItem(PANEL_CAR_STORAGE.vin) || ''; } catch { state.vin = ''; }
  try { state.uid = localStorage.getItem(PANEL_CAR_STORAGE.uid) || ''; } catch { state.uid = ''; }
  // 面板登录后 token 也可能只存在于面板自己的 API 对象上。
  const apiToken = String(window.API?.token || '');
  if (!state.token && apiToken) state.token = apiToken;
  return state.token;
}

/** 解析车辆列表：优先借面板的 API.call（它已经处理了 token/演示态），失败再走签名请求。 */
async function loadVehiclesForPanel() {
  readPanelToken();
  const pick = (list) => (Array.isArray(list) ? list : []).map((item) => ({
    vin: String(item.vinNo ?? item.vin ?? ''),
    name: String(item.vehicleName ?? item.name ?? ''),
    model: String(item.vehicleType ?? ''),
    uid: String(item.bindingUserId ?? ''),
  })).filter((item) => item.vin);
  let vehicles = [];
  try {
    const api = window.API;
    if (api?.call) {
      const result = await api.call('vehicle_list', {}, {});
      vehicles = pick(result?.data ?? result?.raw?.data);
    }
  } catch { /* 面板还没登录时正常失败 */ }
  if (!vehicles.length) {
    const result = await request({ endpoint: byId('vehicle_list'), values: {} }).catch(() => null);
    vehicles = pick(result?.data?.data);
  }
  state.vehicles = vehicles;
  const saved = state.vin;
  const found = vehicles.find((item) => item.vin === saved) || vehicles[0];
  if (found) {
    state.vin = found.vin;
    state.uid = found.uid || state.uid;
    try {
      localStorage.setItem(PANEL_CAR_STORAGE.vin, state.vin);
      if (state.uid) localStorage.setItem(PANEL_CAR_STORAGE.uid, state.uid);
    } catch { /* 隐私模式 */ }
  }
  return state.vin;
}
/* ==== ZEEHO_PANEL_CAR:BRIDGE:END ==== */
