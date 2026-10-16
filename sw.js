// 极核面板 service worker —— 只为两件事：能"装到桌面"，以及断网时还能打开看上一屏。
//
// 策略：**网络优先，失败才回落到缓存**。
//   · 不用 cache-first：这个页面是频繁改动的单文件产物，cache-first 会让人看到旧版本还以为改动没生效。
//   · 只碰同源 GET。跨域一律直接放行、不拦不缓存——极核接口（tapi/h5.zeehoev.com）和 OSM 瓦片都不该进 SW 缓存。
//   · `/api/` 是同源但绝对不能缓存：它是本机代理的转发口，缓存了会假装"服务还在"，把 direct 模式也带偏。
//   · **带 query 的一律不缓存**：模块 App 是用 `?token=..&uid=..` 打开本页的（app.js readUrlCreds），
//     而 Cache API 的键是含 query 的完整 URL —— 缓存了等于把账号凭据写进磁盘，
//     页面里的 history.replaceState 只清地址栏、清不掉它（v2 的坑，v3 起按这条禁掉）。
//
// 改了缓存内容记得改 CACHE 版本号，否则老缓存不会被清掉（v2→v3 就是要把已经存下的带 token 条目一起清掉）。

const CACHE = 'zeeho-dashboard-v3';
const PRECACHE = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './icons/apple-touch-icon.png',
];

self.addEventListener('install', (e) => {
  // 逐个 add：某个文件缺失不该让整批预缓存失败
  e.waitUntil(
    caches
      .open(CACHE)
      .then((c) => Promise.all(PRECACHE.map((u) => c.add(u).catch(() => {}))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches
      .keys()
      // 只清自己这一族（zeeho-dashboard-*）：GitHub Pages 用户站跟别的项目同源，
      // 无条件删"非当前名"的缓存会把隔壁页面的缓存一起干掉。
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('zeeho-dashboard-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.includes('/api/')) return;

  // 能不能进缓存：带 query 的不行（凭据/构建号都在 query 里，见文件头），
  // 显式 no-store 的也不行（version.json 探针与 `?b=` 整页重载每次都换 URL，
  // 缓存它们只会让 CACHE 无限涨，而且永远命中不到）。
  const cacheable = url.search === '' && req.cache !== 'no-store';

  e.respondWith(
    fetch(req)
      .then((res) => {
        // 只缓存拿得出手的同源响应（basic = 同源，ok = 2xx）
        if (cacheable && res && res.ok && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
        }
        return res;
      })
      .catch(() =>
        caches.match(req).then((hit) => {
          if (hit) return hit;
          // 导航请求断网时回落到页面本体；其它资源（js/css）回落到 HTML 反而会因 MIME 不对而报错。
          // 兜底必须**永远返回一个 Response**：命中不到再 resolve 成 undefined 的话，
          // respondWith(undefined) 会抛 TypeError，用户拿到的是浏览器原生错误页。
          if (req.mode === 'navigate') {
            return caches.match('./index.html').then((page) => page || new Response(
              '<!doctype html><meta charset="utf-8"><h1>离线</h1><p>这一页还没被缓存过，联网打开一次就好。</p>',
              { status: 200, headers: { 'Content-Type': 'text/html; charset=utf-8' } },
            ));
          }
          return Response.error();
        }),
      ),
  );
});

// 预热：页面把自己的同源子资源清单发过来，这里补进缓存。
// 为什么需要：SW 是首次访问**之后**才接管页面的，那次请求的 js/css 走的是普通网络、没经过我们，
// 不补一下的话"装好之后第一次断网"会白屏（单文件产物没这问题，它的 js/css 全内联在 index.html 里）。
self.addEventListener('message', (e) => {
  if (e.data?.type !== 'warm') return;
  // 只认同源 URL：这条通道的承诺是"跨域一律不缓存"，别被塞进来的跨域地址破了例
  const urls = (Array.isArray(e.data.urls) ? e.data.urls : []).filter((u) => {
    try { return new URL(u, self.location.href).origin === self.location.origin; } catch { return false; }
  });
  if (!urls.length) return;
  e.waitUntil(
    caches.open(CACHE).then((c) => Promise.all(urls.map((u) => c.add(u).catch(() => {})))),
  );
});
