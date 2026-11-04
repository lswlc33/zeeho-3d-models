// 独立发布验签边界：页面 preflight 的 A 不能替 SW 将要执行的 B 背书。
// 首次 sw.js 安装仍依赖 HTTPS / 浏览器的更新信任；本公钥不是浏览器 bootstrap 的替代品。
// 公钥与 tools/release-sign-gate.mjs 的公开锚一致，不加载私钥、外链脚本或页面的 ReleaseTrust。
const CACHE = 'zeeho-dashboard-signed-v8';
const KEY_ID = 'a7c07850e5f23509cd161ddbccdde87319d43bc33038f7a991b39073fc00d86d';
const SPKI = 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0Z3V1aFZ8gpsaGWOsOb9cHbOqb5H4OcCmPUtjoJn5t7m1rsSkfSRa/tZsVcqsQ9bf4Ki58DnxVaWuBuZ6ohx6vOvgN6V9zfHp2bhG3a9qx4y/75pDmzJ1qNgcI1voqgcak+SQeP95GC4U7BYoxc6Up85qnCWVkWXvJSFwDCNjgj9JX/rLf30s/IoiP9fTs84pAoVN3YL0sZqy1sIsoViuXrbUA31iHeO4VRw0hgTPys0lCcnx1gvUase4dwV3LNHCy+gCk0WxrT3W5MznRZUdVQeejPv2GCyUHebzgiTlus0fTase6lvR74D6qinlnMwOaMluqsMgeRoi+g7khzoFwIDAQAB';
const PATHS = ['index.html', 'mock.json', 'version.json', 'manifest.webmanifest', 'sw.js', 'icons/favicon-32.png',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/icon-maskable-512.png', 'icons/apple-touch-icon.png',
  'theme.LICENSE.txt', 'app/version.json', 'app/zeehowebnext.apk'].sort();
const PRECACHE = ['./index.html', './mock.json', './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png',
  './icons/icon-maskable-512.png', './icons/apple-touch-icon.png', './icons/favicon-32.png'];
const ROOT = new URL('./', self.location.href);
const MANIFEST = new URL('release-manifest.json', ROOT).href;
const DB_NAME = `zeeho-dashboard-release-trust-v1:${ROOT.pathname}`;
const MIME = { 'index.html': 'text/html; charset=utf-8', 'mock.json': 'application/json; charset=utf-8',
  'version.json': 'application/json; charset=utf-8', 'app/version.json': 'application/json; charset=utf-8',
  'manifest.webmanifest': 'application/manifest+json; charset=utf-8', 'sw.js': 'text/javascript; charset=utf-8',
  'theme.LICENSE.txt': 'text/plain; charset=utf-8', 'app/zeehowebnext.apk': 'application/vnd.android.package-archive' };
const fail = reason => { throw new Error(`release_authentication_failed: ${reason}`); };
let importedKey, database;
// 单个 worker 的缓存发布串行；跨 install/active worker 的高水位使用 IDB readwrite 事务，不能只靠内存锁。
let work = Promise.resolve();
function serialized(fn) {
  const next = work.then(fn, fn);
  work = next.catch(() => {});
  return next;
}
function canonical(value) {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'number') { if (!Number.isSafeInteger(value)) fail('canonical number'); return String(value); }
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
  return fail('canonical value');
}
function base64(text) {
  if (typeof text !== 'string') fail('base64');
  let raw; try { raw = atob(text); } catch { fail('base64'); }
  if (btoa(raw) !== text) fail('noncanonical base64');
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
const hash = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b => b.toString(16).padStart(2, '0')).join('');
const integer = (n, min, max) => Number.isSafeInteger(n) && n >= min && n <= max;
const text = (s, max) => typeof s === 'string' && s.length > 0 && s.length <= max;
function versionCompare(a, b) {
  const aa = a.split('.').map(Number), bb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(aa.length, bb.length); i++) if ((aa[i] || 0) !== (bb[i] || 0)) return (aa[i] || 0) - (bb[i] || 0);
  return 0;
}
async function verifyManifest(bytes) {
  if (!crypto?.subtle || bytes.length > 73728) fail('crypto/manifest limit');
  const rawEnvelope = new TextDecoder('utf-8', { fatal: true }).decode(bytes), o = JSON.parse(rawEnvelope);
  if (o.schema !== 1 || o.keyId !== KEY_ID || !text(o.signature, 1024) || !text(o.payload, 65536)) fail('publisher/schema');
  const payload = base64(o.payload);
  if (payload.length > 49152) fail('payload limit');
  importedKey ||= crypto.subtle.importKey('spki', base64(SPKI), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  if (!await crypto.subtle.verify('RSASSA-PKCS1-v1_5', await importedKey, base64(o.signature), payload)) fail('signature');
  const raw = new TextDecoder('utf-8', { fatal: true }).decode(payload), m = JSON.parse(raw);
  if (canonical(m) !== raw) fail('noncanonical payload');
  if (!integer(m.sequence, 1, Number.MAX_SAFE_INTEGER) || !m.panel || !m.apk
    || !text(m.panel.build, 80) || !/^[0-9A-Za-z .:_-]+$/.test(m.panel.build)
    || !text(m.panel.version, 32) || !/^\d+(?:\.\d+){1,3}$/.test(m.panel.version)
    || !integer(m.apk.versionCode, 1, 2147483647) || !text(m.apk.versionName, 32) || !text(m.apk.notes, 4096)
    || m.apk.path !== 'app/zeehowebnext.apk' || !Array.isArray(m.assets) || m.assets.length !== PATHS.length) fail('metadata');
  for (let i = 0; i < PATHS.length; i++) {
    const a = m.assets[i];
    if (!a || a.path !== PATHS[i] || !/^[0-9a-f]{64}$/.test(a.sha256)
      || !integer(a.size, 1, a.path.endsWith('.apk') ? 31457280 : 8388608)) fail('exact asset set/hash/size');
  }
  // 不信任清单中的派生字段；摘要只来自已验证的 canonical 字节。
  m.digest = await hash(payload);
  return { m, bytes };
}
function openDatabase() {
  database ||= new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      request.result.createObjectStore('state');
      request.result.createObjectStore('clients');
    };
    request.onsuccess = () => {
      const db = request.result;
      db.onversionchange = () => { db.close(); database = null; };
      resolve(db);
    };
    request.onerror = () => reject(request.error || new Error('trust database unavailable'));
    request.onblocked = () => reject(new Error('trust database blocked'));
  }).catch(error => { database = null; throw error; });
  return database;
}
function validState(old) {
  if (!old || !integer(old.sequence, 1, Number.MAX_SAFE_INTEGER) || !/^[0-9a-f]{64}$/.test(old.digest)
    || !integer(old.apkCode, 1, 2147483647) || !text(old.build, 80)
    || !text(old.panelVersion, 32) || !/^\d+(?:\.\d+){1,3}$/.test(old.panelVersion)) fail('checkpoint corrupt');
}
// accept: 只接受单调网络清单。snapshot: 可读已签旧快照，不把高水位倒退，也不允许把新网络降版装成快照。
// 更新清单先到而 HTML 尚未发布时，允许上一份已缓存且重新验签的快照；不允许从网络接纳旧清单。
async function checkpoint(m, mode, clientId = '') {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(['state', 'clients'], 'readwrite'), state = tx.objectStore('state');
    let error;
    tx.oncomplete = () => resolve();
    tx.onabort = tx.onerror = () => reject(error || tx.error || new Error('trust checkpoint unavailable'));
    const read = state.get('highwater');
    read.onsuccess = () => {
      try {
        const old = read.result;
        if (old !== undefined) validState(old);
        if (mode === 'snapshot') {
          if (!old || m.sequence > old.sequence || (m.sequence === old.sequence && m.digest !== old.digest)
            || m.apk.versionCode > old.apkCode || versionCompare(m.panel.version, old.panelVersion) > 0 || m.panel.build > old.build) fail('snapshot outside checkpoint');
        } else {
          if (old && (m.sequence < old.sequence || (m.sequence === old.sequence && m.digest !== old.digest)
            || m.apk.versionCode < old.apkCode || versionCompare(m.panel.version, old.panelVersion) < 0 || m.panel.build < old.build)) fail('replay/rollback');
          if (mode === 'accept') {
            state.put({ sequence: m.sequence, digest: m.digest, apkCode: m.apk.versionCode,
              build: m.panel.build, panelVersion: m.panel.version }, 'highwater');
            state.put(m.sequence, `accepted:${m.digest}`);
          }
          else if (!old || m.sequence !== old.sequence || m.digest !== old.digest) fail('checkpoint advanced');
        }
        if (mode === 'snapshot') {
          const accepted = state.get(`accepted:${m.digest}`);
          accepted.onsuccess = () => {
            if (accepted.result !== m.sequence) { error = new Error('snapshot never accepted'); tx.abort(); }
          };
        }
        if (clientId) tx.objectStore('clients').put({ digest: m.digest }, clientId);
      } catch (e) { error = e; tx.abort(); }
    };
  });
}
async function clientDigest(clientId) {
  if (!clientId) return '';
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('clients', 'readonly'), req = tx.objectStore('clients').get(clientId);
    tx.oncomplete = () => resolve(req.result?.digest || '');
    tx.onabort = tx.onerror = () => reject(tx.error || new Error('client checkpoint unavailable'));
  });
}
async function boundedBytes(response, max) {
  if (!response || response.status !== 200 || response.redirected || !['basic', 'default'].includes(response.type)) fail('HTTP status/type/redirect');
  const declared = Number(response.headers.get('Content-Length') || 0);
  if (!Number.isFinite(declared) || declared < 0 || declared > max || !response.body?.getReader) fail('response limit/body');
  const reader = response.body.getReader(), chunks = []; let count = 0;
  try {
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      count += value.length; if (count > max) fail('response limit'); chunks.push(value);
    }
  } finally { await reader.cancel().catch(() => {}); }
  const bytes = new Uint8Array(count); let at = 0;
  for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.length; }
  return bytes;
}
async function network(path, max) {
  const url = new URL(path, ROOT);
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 15000);
  try {
    // 永远只取已知的同源干净 URL：导航的 token / query / Cookie 不交给发布资源服务器。
    const response = await fetch(url.href, { cache: 'no-store', credentials: 'omit', redirect: 'error', signal: controller.signal });
    if (!response || response.type !== 'basic' || (response.url && response.url !== url.href)) fail('network origin/type');
    return { bytes: await boundedBytes(response, max), headers: response.headers };
  } finally { clearTimeout(timer); }
}
function restored(bytes, path, networkHeaders) {
  const headers = new Headers({ 'Content-Type': MIME[path] || (path.endsWith('.png') ? 'image/png' : 'application/octet-stream'),
    'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'no-store' });
  // bytes 重建 Response 后保留服务器的安全策略，但不复用编码/长度/重定向/Set-Cookie 等未签元数据。
  for (const name of ['Content-Security-Policy', 'Referrer-Policy', 'Permissions-Policy',
    'Cross-Origin-Opener-Policy', 'Cross-Origin-Embedder-Policy', 'Cross-Origin-Resource-Policy']) {
    const value = networkHeaders?.get(name); if (value) headers.set(name, value);
  }
  if (path === 'index.html') {
    const baseline = "base-uri 'self'; object-src 'none'; frame-ancestors 'self'";
    if (!(headers.get('Content-Security-Policy') || '').includes(baseline)) headers.append('Content-Security-Policy', baseline);
  }
  return new Response(bytes, { status: 200, headers });
}
function snapshotName(m) { return `${CACHE}-${m.digest}`; }
function ownSnapshot(name) { return /^zeeho-dashboard-signed-v\d+-[0-9a-f]{64}$/.test(name); }
async function rememberManifest(release) {
  const cache = await caches.open(snapshotName(release.m));
  await cache.put(MANIFEST, restored(release.bytes, 'mock.json'));
  return cache;
}
async function networkManifest() {
  const { bytes } = await network('release-manifest.json', 73728), release = await verifyManifest(bytes);
  await checkpoint(release.m, 'accept');
  await rememberManifest(release);
  return release;
}
async function cachedManifests(digest = '') {
  const out = [];
  for (const name of await caches.keys()) {
    if (!ownSnapshot(name) || (digest && !name.endsWith(`-${digest}`))) continue;
    try {
      const cache = await caches.open(name), response = await cache.match(MANIFEST);
      if (!response) continue;
      const release = await verifyManifest(await boundedBytes(response, 73728));
      if (!name.endsWith(`-${release.m.digest}`)) continue;
      await checkpoint(release.m, 'snapshot');
      out.push({ ...release, cache });
    } catch { /* 损坏/未签清单不参与回落，保留文件而不是删掉唯一备份。 */ }
  }
  return out.sort((a, b) => b.m.sequence - a.m.sequence);
}
async function verifyAsset(release, path, bytes) {
  const a = release.m.assets.find(a => a.path === path);
  if (!a || bytes.length !== a.size || await hash(bytes) !== a.sha256) fail('asset size/hash mismatch');
  return bytes;
}
async function cachedAsset(release, path) {
  const response = await release.cache.match(new URL(path, ROOT).href);
  if (!response) fail('no signed snapshot asset');
  const a = release.m.assets.find(a => a.path === path);
  const bytes = await boundedBytes(response, a.size);
  await verifyAsset(release, path, bytes);
  await checkpoint(release.m, 'snapshot');
  return restored(bytes, path, response.headers);
}
async function freshAsset(release, path, persist, clientId = '') {
  const a = release.m.assets.find(a => a.path === path);
  const { bytes, headers } = await network(path, a.size);
  await verifyAsset(release, path, bytes);
  await checkpoint(release.m, 'current');
  const response = restored(bytes, path, headers);
  if (persist) {
    try {
      const cache = await rememberManifest(release);
      await cache.put(new URL(path, ROOT).href, response.clone());
    } catch { /* 缓存配额失败不把已验证的网络响应变成故障；旧快照保持原样。 */ }
  }
  // 写缓存期间另一 install/active worker 也可能接受新清单；响应发布前再原子复查并绑定 tab。
  await checkpoint(release.m, 'current', clientId);
  // 返回的正是刚 hash 的 bytes，不再 fetch，不返回 res.clone() 的另一条未验证流。
  return response;
}
function unavailable() {
  // 不是应用 HTML：固定无脚本提示不会执行未验证发布内容，503 也不会假装升级成功。
  return new Response('<!doctype html><meta charset="utf-8"><h1>离线或发布校验失败</h1><p>没有可验证的离线页面，请联网后重新加载。</p>',
    { status: 503, headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff', 'Content-Security-Policy': "default-src 'none'; base-uri 'none'; frame-ancestors 'none'" } });
}
function cacheableUrl(value) {
  try {
    const url = new URL(value, ROOT);
    return url.origin === ROOT.origin && url.pathname.startsWith(ROOT.pathname) && !url.search
      && !url.username && !url.password && !url.pathname.includes('/api/');
  } catch { return false; }
}
function assetPath(url) {
  if (url.origin !== ROOT.origin || !url.pathname.startsWith(ROOT.pathname)) return '';
  const path = url.pathname.slice(ROOT.pathname.length);
  return path === '' ? 'index.html' : PATHS.includes(path) ? path : '';
}
function requestedSequence(url) {
  if (!url.searchParams.has('release')) return 0;
  const raw = url.searchParams.get('release');
  if (!/^\d+$/.test(raw) || !integer(Number(raw), 1, Number.MAX_SAFE_INTEGER)) fail('release query');
  return Number(raw);
}
async function navigate(req, url, clientId) {
  if (assetPath(url) !== 'index.html' || req.method !== 'GET') return unavailable();
  try {
    const release = await networkManifest();
    return await freshAsset(release, 'index.html', cacheableUrl(url.href) && req.cache !== 'no-store', clientId);
  } catch {
    // 已签旧 snapshot 可用，不接纳网络重放；每次离线返回仍重新验证签名和实际 HTML bytes。
    for (const release of await cachedManifests()) {
      try {
        const response = await cachedAsset(release, 'index.html');
        await checkpoint(release.m, 'snapshot', clientId);
        return response;
      } catch { /* 一个残缺快照不能遮挡另一份完整的可信离线页。 */ }
    }
    return unavailable();
  }
}
async function signedResource(req, url, path, clientId) {
  const sequence = requestedSequence(url);
  const pinned = path === 'mock.json' ? await clientDigest(clientId) : '';
  // 新 worker 不知道旧未签 worker 已执行的 HTML；不能替未知 tab 选 latest mock，需要可信导航建立 pin。
  if (path === 'mock.json' && clientId && !pinned) return Response.error();
  // 页面在旧快照运行时，mock 必须来自同一 signed build；新 tab 的导航不会覆盖旧 tab 的 pin。
  if (pinned) {
    const releases = await cachedManifests(pinned);
    for (const release of releases) {
      if (sequence && sequence !== release.m.sequence) continue;
      try { return await cachedAsset(release, path); } catch {
        try { return await freshAsset(release, path, cacheableUrl(url.href) && req.cache !== 'no-store'); } catch { /* 不拿另一 build 的 mock 顶替。 */ }
      }
    }
    return Response.error();
  }
  try {
    const release = await networkManifest();
    if (sequence && sequence !== release.m.sequence) fail('requested release mismatch');
    return await freshAsset(release, path, cacheableUrl(url.href) && req.cache !== 'no-store');
  } catch {
    for (const release of await cachedManifests()) {
      if (sequence && sequence !== release.m.sequence) continue;
      try { return await cachedAsset(release, path); } catch { /* 继续找可信字节。 */ }
    }
    return Response.error();
  }
}
async function manifestResponse(clientId, update = false) {
  // 只有显式更新探测脱离 page pin；普通 fetchManifest / mock 永远属于当前文档的 signed build。
  const pin = update ? '' : await clientDigest(clientId);
  if (pin) {
    const [release] = await cachedManifests(pin);
    return release ? restored(release.bytes, 'mock.json') : Response.error();
  }
  try { const release = await networkManifest(); return restored(release.bytes, 'mock.json'); }
  catch {
    const [release] = await cachedManifests();
    return release ? restored(release.bytes, 'mock.json') : Response.error();
  }
}
async function handleFetch(e) {
  const req = e.request, url = new URL(req.url);
  if (req.mode === 'navigate' || req.destination === 'document') return navigate(req, url, e.resultingClientId || e.clientId || '');
  if (url.href.split('?')[0] === MANIFEST) return manifestResponse(e.clientId || '', url.searchParams.get('update') === '1');
  // 校验发生在 navigation handler 或已知 HTML 资源入口；未知 URL 的 HTML 只会拒绝。
  const path = assetPath(url);
  if (path) return signedResource(req, url, path, e.clientId || '');
  // 未签字体/静态文件沿用网络，不持久化，也不给脚本请求塞 HTML；不充当跨域代理。
  try {
    const response = await fetch(req);
    if (!response || /(?:text\/html|application\/xhtml\+xml)/i.test(response.headers.get('Content-Type') || '')) return Response.error();
    return response;
  } catch { return Response.error(); }
}
self.addEventListener('fetch', e => {
  const req = e.request, url = new URL(req.url);
  if (url.origin !== ROOT.origin || !url.pathname.startsWith(ROOT.pathname)) return;
  if (req.mode !== 'navigate' && req.destination !== 'document' && (req.method !== 'GET' || url.pathname.includes('/api/'))) return;
  e.respondWith(serialized(() => handleFetch(e)).catch(() => req.mode === 'navigate' || req.destination === 'document' ? unavailable() : Response.error()));
});
self.addEventListener('install', e => {
  e.waitUntil(serialized(async () => {
    try {
      const release = await networkManifest();
      // 同一已签清单约束整批预缓存；缺某图标不妨碍 HTML / mock 离线，但从不 cache.add 未验 bytes。
      for (const relative of PRECACHE) { try { await freshAsset(release, relative.slice(2), true); } catch { /* 安全失败；保留已有可信快照。 */ } }
    } catch { /* 首次离线不迁移旧无签缓存；安装的是可信 worker，不是旧 HTML。 */ }
    await self.skipWaiting();
  }));
});
self.addEventListener('activate', e => {
  // 不全盘删除同源缓存，也不自动删除唯一旧无签备份。旧族永不读取/执行。
  // signed cache 内容寻址；保留旧快照供多 tab 的 build pin 使用，配额失败时回落而不是破坏原快照。
  e.waitUntil(self.clients.claim());
});
self.addEventListener('message', e => {
  if (e.data?.type !== 'warm') return;
  const paths = [...new Set((Array.isArray(e.data.urls) ? e.data.urls : []).filter(cacheableUrl)
    .map(value => assetPath(new URL(value, ROOT))).filter(Boolean))];
  if (!paths.length) return;
  e.waitUntil(serialized(async () => {
    const pin = await clientDigest(e.source?.id || '');
    const release = pin ? (await cachedManifests(pin))[0] : await networkManifest();
    if (!release) return;
    for (const path of paths) { try { await freshAsset(release, path, true); } catch { /* 不采纳页面提供的任意资源或外链。 */ } }
  }).catch(() => {}));
});
