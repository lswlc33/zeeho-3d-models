// 极核仪表盘 service worker —— 只为两件事：能"装到桌面"，以及断网时还能打开看上一屏。
//
// 策略：**网络优先，失败才回落到缓存**。
//   · 不用 cache-first：这个页面是频繁改动的单文件产物，cache-first 会让人看到旧版本还以为改动没生效。
//   · 只碰同源 GET。跨域一律直接放行、不拦不缓存——极核接口（tapi/h5.zeehoev.com）和 OSM 瓦片都不该进 SW 缓存。
//   · `/api/` 是同源但绝对不能缓存：它是本机代理的转发口，缓存了会假装"服务还在"，把 direct 模式也带偏。
//
// 改了缓存内容记得改 CACHE 版本号，否则老缓存不会被清掉。

const CACHE = 'zeeho-dashboard-v1';
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
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.includes('/api/')) return;

  e.respondWith(
    fetch(req)
      .then((res) => {
        // 只缓存拿得出手的同源响应（basic = 同源，ok = 2xx）
        if (res && res.ok && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
        }
        return res;
      })
      .catch(() =>
        caches.match(req).then((hit) => {
          if (hit) return hit;
          // 导航请求断网时回落到页面本体；其它资源（js/css）回落到 HTML 反而会因 MIME 不对而报错
          if (req.mode === 'navigate') return caches.match('./index.html');
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
  const urls = Array.isArray(e.data.urls) ? e.data.urls : [];
  if (!urls.length) return;
  e.waitUntil(
    caches.open(CACHE).then((c) => Promise.all(urls.map((u) => c.add(u).catch(() => {})))),
  );
});
