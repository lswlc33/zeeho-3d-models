/* ==== ZEEHO_PANEL_CAR:ENTRY:BEGIN ==== */
/* 面板页的车机工具：**分开路由**，两个入口各进各的视图，不再共用一个带 tab 的 sheet。
 *   #car-wallpaper → 只显示「车机壁纸」卡片
 *   #car-ota       → 只显示「固件版本」卡片
 * 入口：
 *   · 「车辆设置」页底部一行「车机壁纸」；
 *   · 「我的」页「固件版本」卡片右上角一个「OTA 升级」按钮。
 * 面板的 app.js 会重绘 #scroll，所以两处入口都用 MutationObserver 补回（幂等）。
 */

const ZE_ROUTES = {
  wallpaper: { hash: '#car-wallpaper', card: 'wallpaper-card', title: '车机壁纸' },
  ota: { hash: '#car-ota', card: 'firmware-card', title: 'OTA 升级' },
};

const zeCarSheet = () => document.getElementById('ze-car-sheet');

/**
 * 面板里三块卡片是**平铺注入**到 body 的（不套外层 div：卡片里若有未闭合标签，
 * 会把后面的 dialog 吞进容器里，踩过）。这里在运行时把它们搬进全屏 sheet，
 * dialog 留在 body 级。
 */
function zeCarEnsureSheet() {
  let sheet = document.getElementById('ze-car-sheet');
  if (sheet) return sheet;
  const cards = ['firmware-card', 'wallpaper-card', 'settings-card']
    .map((id) => document.getElementById(id))
    .filter(Boolean);
  if (!cards.length) return null;
  sheet = document.createElement('div');
  sheet.id = 'ze-car-sheet';
  sheet.hidden = true;
  sheet.innerHTML = `
    <div class="ze-car-head">
      <h2>车机工具</h2>
      <span class="ze-car-hint">壁纸 / OTA</span>
      <button class="ze-car-close" id="ze-car-close" type="button">关闭</button>
    </div>
    <div class="ze-car-scroll"></div>`;
  document.body.appendChild(sheet);
  const scroll = sheet.querySelector('.ze-car-scroll');
  for (const card of cards) scroll.appendChild(card);
  return sheet;
}

function zeCarRouteFromHash() {
  const hash = location.hash;
  const entry = Object.entries(ZE_ROUTES).find(([, route]) => route.hash === hash);
  return entry ? entry[0] : '';
}

function zeCarApplyRoute(routeName) {
  const sheet = zeCarEnsureSheet();
  if (!sheet) return;
  const route = ZE_ROUTES[routeName];
  // 面板里不展示「完整车辆设置」（面板自己就有设置页），只留壁纸 / OTA 两块。
  for (const id of ['firmware-card', 'settings-card', 'wallpaper-card']) {
    const el = document.getElementById(id);
    if (!el) continue;
    el.hidden = !(route && id === route.card);
  }
  sheet.querySelector('.ze-car-head h2').textContent = route ? route.title : '车机工具';
}

async function zeCarOpenRoute(routeName, { push = true } = {}) {
  const route = ZE_ROUTES[routeName];
  if (!route) return;
  const sheet = zeCarEnsureSheet();
  if (!sheet) { toast('车机工具还没准备好，刷新页面再试'); return; }
  if (push && location.hash !== route.hash) {
    try { history.pushState(null, '', route.hash); } catch { /* file:// */ }
  }
  zeCarApplyRoute(routeName);
  sheet.hidden = false;
  document.body.classList.add('ze-car-lock');
  if (!readPanelToken()) toast('还没登录：先在「我的」里登录，再回来看', 3200);
  try {
    await loadVehiclesForPanel();
    await carInitPanels();
  } catch (error) {
    toast(`读取失败：${error.message}`);
  }
  document.getElementById(route.card)?.scrollIntoView({ block: 'start' });
}

function zeCarCloseSheet() {
  const sheet = zeCarEnsureSheet();
  if (!sheet) return;
  sheet.hidden = true;
  document.body.classList.remove('ze-car-lock');
  if (zeCarRouteFromHash()) {
    try { history.pushState(null, '', location.pathname + location.search); } catch { /* ignore */ }
  }
}

/** 「车辆设置」页底部：只留一行「车机壁纸」。 */
function zeCarEnsureSettingsEntry() {
  const scroll = document.getElementById('scroll');
  const page = scroll?.querySelector('.page');
  if (!page || page.querySelector('.ze-car-entry')) return;
  if (!page.textContent.includes('设置项')) return;
  const block = document.createElement('div');
  block.className = 'ze-car-entry';
  block.innerHTML = `
    <div class="section-title ze-car-entry-title"><span>车机工具</span><span class="line"></span></div>
    <section class="card group-card">
      <button class="group-head ze-car-open" type="button" data-ze-route="wallpaper">
        <span class="gt">车机壁纸</span><span class="gc">整屏 + 速度卡 · 两步上传</span>
        <span class="gv"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="m9 6 6 6-6 6"/></svg></span>
      </button>
    </section>`;
  page.appendChild(block);
}

/** 「我的」页：在包含「固件版本」的卡片右上角挂一个「OTA 升级」按钮。 */
function zeCarEnsureMineEntry() {
  const scroll = document.getElementById('scroll');
  const page = scroll?.querySelector('.page');
  if (!page || page.querySelector('.ze-car-mine-btn')) return;
  if (!page.textContent.includes('固件版本')) return;
  const card = [...page.querySelectorAll('section.card')].find((section) => section.textContent.includes('固件版本'));
  if (!card) return;
  card.style.position = 'relative';
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'ze-car-mine-btn ze-car-open';
  button.dataset.zeRoute = 'ota';
  button.textContent = 'OTA 升级';
  card.appendChild(button);
}

function zeCarSyncEntries() {
  zeCarEnsureSheet();
  zeCarEnsureSettingsEntry();
  zeCarEnsureMineEntry();
}

document.addEventListener('click', (event) => {
  const close = event.target.closest?.('#ze-car-close');
  if (close) { event.preventDefault(); zeCarCloseSheet(); return; }
  const open = event.target.closest?.('.ze-car-open');
  if (open) {
    event.preventDefault();
    event.stopPropagation();
    void zeCarOpenRoute(open.dataset.zeRoute || 'wallpaper');
  }
}, true);

window.addEventListener('hashchange', () => {
  const route = zeCarRouteFromHash();
  if (route) void zeCarOpenRoute(route, { push: false });
  else zeCarCloseSheet();
});

// 面板切 tab / 重绘会重建 #scroll，这里补回入口。
let zeCarSyncTimer = null;
const zeCarObserver = new MutationObserver(() => {
  clearTimeout(zeCarSyncTimer);
  zeCarSyncTimer = setTimeout(zeCarSyncEntries, 60);
});
zeCarObserver.observe(document.body, { childList: true, subtree: true });
zeCarSyncEntries();
setTimeout(zeCarSyncEntries, 800);
setTimeout(zeCarSyncEntries, 2500);

// 直接带 #car-wallpaper / #car-ota 打开时，等面板首屏渲染完再进对应视图。
if (zeCarRouteFromHash()) {
  setTimeout(() => { const route = zeCarRouteFromHash(); if (route) void zeCarOpenRoute(route, { push: false }); }, 900);
}
/* ==== ZEEHO_PANEL_CAR:ENTRY:END ==== */
