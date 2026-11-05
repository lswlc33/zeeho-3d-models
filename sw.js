// 纯透传 Service Worker（2026-11-06 用户明确要求）。
//
// 为什么整份重写：原来的 SW 同时承担"签名验签 + 本地快照 + 断网兜底"三件事，
// 三者共用同一套代码（缓存只存验过的字节），拆掉验签就等于拆掉缓存。
// 用户已确认"纯透传、断网打不开也无所谓"，所以这里只保留最小职责：
//
//   · 不缓存任何响应（没有 caches.open / cache.put）；
//   · 不校验任何签署（不读 release-manifest.json、不验 RSA 签名）；
//   · 不拦截任何请求的语义（不区分资产清单、不给 HTML 请求设限）。
//
// 它现在只做一件事：**把请求原样交给网络**，并在激活时清掉旧版留下的缓存与数据库，
// 这样从旧版本升级过来的用户不会带着一套过期快照继续跑（表现为"怎么刷新都是旧页面"）。
//
// 与 Cloudflare 的关系（重要）：纯透传**不像旧版那样叠第二层缓存**，
// 所以"CF 更新了但 SW 还给旧内容"这类冲突从根上消失。页面新鲜度完全由 CF 一层决定：
// 关键文件（sw.js / release-manifest.json / version.json）应在 CF 上设 Bypass Cache，
// 静态资源（index.html / icons / mock.json）可以放心让 CF 缓存。
//
// 双域名：zhb.xn--fiqz59cpva341l.top 与 lswlc33.github.io/zeeho-3d-models 跑同一份产物。
// SW 按 self.location 自动分域（每个域名各自一份注册），所以代码里不写死任何域名，
// 也不做 origin 判断——纯透传下没有"本站/外站"之分。

const LEGACY_CACHE_PREFIX = 'zeeho-dashboard-signed-';
const LEGACY_DB_PREFIX = 'zeeho-dashboard-release-trust-v1';

self.addEventListener('install', () => {
  // 立刻推进到 waiting 之后，不等旧 worker 的 client 全部关闭。
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    try {
      // 1) 删掉旧版签名快照缓存。只动前缀匹配的，不碰同源下其它应用的缓存。
      const names = await caches.keys();
      await Promise.all(names
        .filter((name) => name.startsWith(LEGACY_CACHE_PREFIX))
        .map((name) => caches.delete(name)));
    } catch { /* 存储不可用不影响透传 */ }
    try {
      // 2) 删掉旧版的高水位/回执数据库（release sequence、客户端摘要等）。
      //    IndexedDB 无法枚举库名，只能按已知名删除；名字带 pathname，与旧版一致。
      const root = new URL('./', self.location.href);
      indexedDB.deleteDatabase(`${LEGACY_DB_PREFIX}:${root.pathname}`);
    } catch { /* 同上 */ }
    try {
      // 3) 立即接管已打开的页面，新策略从下一次请求就生效。
      await self.clients.claim();
    } catch { /* 忽略 */ }
  })());
});

// 透传：不判断 origin、不判断路径、不缓存、不改写响应。
// 非 GET 直接 return（交给浏览器原生处理）：SW 无法为 POST/PUT 做有意义的包装，
// 硬拦反而可能误伤将来的表单提交。
self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  event.respondWith(fetch(request));
});
