/* ==== ZEEHO_CAR_UI:JS:BEGIN ==== */
/* 车辆工具：固件 / 车辆设置 / 车机壁纸。
   —— 复用本页已有的 request()（面板签名 + 自填凭据两条路）与状态（state.token / state.vin / state.uid），
      不另外加载面板 iframe，也不引入任何网络依赖。
   —— 数据模型来自 sdk/vehicle-settings.js 与 sdk/vehicle-media.js（由 tools/patch-panel-entry.mjs 内联到 __carModels）。 */

const CAR_MODELS = __carModels;
const {
  CONTROL: CAR_CONTROL,
  buildSettingsModel: carBuildSettingsModel,
  buildWriteBody: carBuildWriteBody,
  coerceValue: carCoerceValue,
  wallpapersFromHome: carWallpapersFromHome,
  normalizeWallpaperPage: carNormalizeWallpaperPage,
  normalizeProgress: carNormalizeProgress,
  buildUseWallpaperBody: carBuildUseWallpaperBody,
  normalizeOtaInfo: carNormalizeOtaInfo,
  normalizeCheckVersion: carNormalizeCheckVersion,
  maskSignedUrl: carMaskSignedUrl,
  formatBytes: carFormatBytes,
  parseWallpaperAreas: carWallpaperAreas,
  buildWallpaperContent: carBuildWallpaperContent,
  wallpaperFitTransform: carWallpaperFit,
  wallpaperLayerStyle: carWallpaperLayerStyle,
  clampWallpaperZoom: carWallpaperZoom,
  clampWallpaperPan: carWallpaperClampPan,
  wallpaperSpeedCardBox: carSpeedCardBox,
} = CAR_MODELS;

/** 目录里没有的端点（itemsV2 / 充电功率选项 / 通用写入），就地声明。 */
const CAR_EP = {
  itemsV2: {
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/itemsV2', method: 'GET',
    params: [
      { in: 'query', key: 'vinNo', example: 'TESTVIN0000000001', required: true },
      { in: 'query', key: 'homepageInvoke', example: '0' },
    ],
    auth: 'token', source: 'app306',
  },
  chargePower: {
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/chargePower/options', method: 'GET',
    params: [{ in: 'query', key: 'vinNo', example: 'TESTVIN0000000001', required: true }],
    auth: 'token', source: 'app306',
  },
  addSet: {
    path: '/v1.0/app/cfmotoserverapp/vehicleSet/addVehicleSet', method: 'POST',
    params: [], auth: 'token', source: 'app306', danger: true,
  },
};

const carState = {
  fw: null,
  fwFile: null,
  settings: null,
  items: null,
  walls: null,
  wallsMeta: null,
  wallsCount: null,
  wallsError: '',
  progressTimer: null,
};

const CAR_STATUS_TEXT = { 0: '待升级', 1: '下载中', 2: '升级中', 3: '升级成功', 4: '升级失败' };
function carStatusText(value) {
  if (value === undefined || value === null || value === '') return '—';
  return CAR_STATUS_TEXT[String(value)] || String(value);
}

/** 统一的调用封装：拿到 {ok, code, message, data}，把网关 XML/HTTP 错误也归一化。 */
async function carCall(endpoint, values = {}, body, method) {
  const ep = typeof endpoint === 'string' ? CAR_EP[endpoint] : endpoint;
  const target = method ? { ...ep, method } : ep;
  const result = await request({ endpoint: target, values, body, token: state.token });
  const env = result?.data || {};
  const code = String(env.code ?? '');
  const ok = code === '10000';
  return {
    ok,
    code,
    message: String(env.message || (ok ? '' : (result?.response?.status ? `HTTP ${result.response.status}` : ''))),
    data: env.data,
    raw: result,
  };
}

/* ---------------------------- 固件版本 ---------------------------- */

async function carLoadFirmware() {
  if (!state.vin) return;
  const result = await carCall(byId('ota_info'), { vinNo: state.vin }).catch((error) => ({ ok: false, message: error.message }));
  if (!result.ok) {
    carState.fw = null;
    carState.fwFile = null;
    $('fw-version').textContent = `读取失败：${result.message || result.code || '未知错误'}`;
    return;
  }
  const info = carNormalizeOtaInfo(result.raw.data);
  carState.fw = info;
  carState.fwFile = info.file || null;
  $('fw-version').textContent = info.softwareVersion || info.otaVersion || '—';
  $('fw-app').textContent = info.appVersion || '—';
  $('fw-status').textContent = carStatusText(info.upgradeStatus);
  $('fw-record').textContent = info.recordId || '—';
  $('fw-package').textContent = info.file
    ? `${info.file.name} · ${info.file.sizeText || ''}`.trim()
    : '当前没有下发包（fileInfo=null，属正常）';
  const fileBox = $('fw-file');
  fileBox.hidden = !info.file;
  if (info.file) {
    const masked = info.file.maskedUrl || carMaskSignedUrl(info.file.url);
    $('fw-hint').textContent = `firmwareId ${info.file.firmwareId || '—'} · ${masked.slice(0, 96)}${masked.length > 96 ? '…' : ''}`;
    $('btn-fw-copy').onclick = async () => {
      try { await navigator.clipboard.writeText(masked); toast('已复制（打码地址）'); } catch { toast('复制失败'); }
    };
    $('btn-fw-reveal').onclick = () => {
      carConfirm({
        title: '显示完整下载地址',
        sub: '含 OSS 签名参数，等同临时凭证，别贴群里',
        body: info.file.url,
        run: async () => {},
        actionLabel: '我知道了',
      });
    };
  }
}

async function carCheckOta() {
  if (!state.vin) { toast('还没有读到车'); return; }
  const target = carState.fw?.softwareVersion || carState.fw?.otaVersion || '0';
  const result = await carCall(byId('check_version'), { vinNo: state.vin, targetVersion: target || '0' })
    .catch((error) => ({ ok: false, message: error.message }));
  if (!result.ok) { toast(`checkVersion 失败：${result.message || result.code}`); return; }
  const check = carNormalizeCheckVersion({ code: '10000', data: result.data }, { currentVersion: target });
  $('fw-sub').textContent = check.hasUpdate ? `checkVersion：有新版（${target || '当前版本'}）` : 'checkVersion：已是最新';
  toast(check.hasUpdate ? '有更新（包地址看 fileInfo）' : '已是最新');
}

/* ---------------------------- 车辆设置 ---------------------------- */

async function carLoadSettings() {
  if (!state.vin) return;
  const [v2, items, info, bt, charge] = await Promise.all([
    carCall('itemsV2', { vinNo: state.vin, homepageInvoke: '0' }).catch((e) => ({ ok: false, message: e.message })),
    carCall(byId('vehicle_set_items'), { vinNo: state.vin, homepageInvoke: '1' }).catch((e) => ({ ok: false, message: e.message })),
    carCall(byId('vehicle_set_info'), { vin: state.vin }).catch((e) => ({ ok: false, message: e.message })),
    carCall(byId('bt_codes'), { vinNo: state.vin }).catch(() => ({ ok: false })),
    carCall('chargePower', { vinNo: state.vin }).catch(() => ({ ok: false })),
  ]);
  if (!info.ok) {
    $('cs-tiles').innerHTML = `<div class="hint">设置项读取失败：${escapeHtml(info.message || info.code || '未知错误')}</div>`;
    return;
  }
  const model = carBuildSettingsModel({
    itemsV2: v2.ok ? { code: v2.code, data: v2.data } : null,
    items: items.ok ? { code: items.code, data: items.data } : null,
    info: { code: info.code, data: info.data },
    blueToothCodes: bt.ok ? { code: bt.code, data: bt.data } : null,
    chargePowerOptions: charge.ok ? { code: charge.code, data: charge.data } : null,
  });
  carState.settings = model;
  carState.items = { items: info.data };
  carRenderSettings();
}

function carRenderSettings() {
  const model = carState.settings;
  const box = $('cs-tiles');
  if (!model || !(model.tiles || []).length) {
    box.innerHTML = '<div class="hint">没有读到设置项（先登录并选择车辆）</div>';
    return;
  }
  const grouped = new Set((model.tiles || []).flatMap((tile) => (tile.items || []).map((item) => item.code)));
  const loose = (model.items || []).filter((item) => !grouped.has(item.code));
  $('cs-sub').textContent = `${(model.stats && model.stats.items) || 0} 项 · 可写 ${(model.stats && model.stats.writable) || 0} 项`;
  const tileHtml = (model.rows || []).map((row) => (row.tiles || []).map((tile) => {
    const labels = [...new Set((tile.items || []).map((item) => item.label))].slice(0, 3).join('、');
    return `<button class="car-tile${tile.available ? '' : ' dim'}" type="button" data-tile="${escapeHtml(tile.code)}">
      <b>${escapeHtml(tile.name || tile.code)}</b>
      <span>${escapeHtml(tile.describe || labels || '这台车未开放')}</span>
    </button>`;
  }).join('')).join('');
  const looseHtml = loose.length
    ? `<button class="car-tile" type="button" data-loose="1"><b>其他设置项</b><span>能力表里有、官方主页没挂磁贴的 ${loose.length} 项</span></button>`
    : '';
  box.innerHTML = tileHtml + looseHtml;
  for (const button of box.querySelectorAll('[data-tile]')) {
    button.addEventListener('click', () => carOpenTile(button.dataset.tile));
  }
  const looseButton = box.querySelector('[data-loose]');
  if (looseButton) looseButton.addEventListener('click', () => carOpenItems('其他设置项', '未挂进官方磁贴的能力项', loose));
}

function carControlLabel(item) {
  switch (item.control) {
    case CAR_CONTROL.SWITCH: return Number(item.value) === 1 || item.value === true ? '开' : '关';
    case CAR_CONTROL.SELECT: {
      const option = (item.options || []).find((o) => String(o.value) === String(item.value));
      return option?.label ?? String(item.value ?? '—');
    }
    case CAR_CONTROL.RANGE: return `${item.value ?? '—'}${item.unit || ''}`;
    default: return String(item.value ?? '—');
  }
}

function carItemRow(item, index) {
  const id = `ci-${index}`;
  const locked = !item.writable || Boolean(item.disabledReason);
  const note = item.disabledReason
    ? `<small>${escapeHtml(item.disabledReason)}</small>`
    : (item.note ? `<small>${escapeHtml(item.note)}</small>` : '');
  let control = '';
  if (item.control === CAR_CONTROL.SWITCH) {
    const on = Number(item.value) === 1 || item.value === true || item.value === '1';
    control = `<button class="car-switch" type="button" role="switch" data-item="${id}" data-kind="switch" aria-checked="${on}" ${locked ? 'disabled' : ''}></button>`;
  } else if (item.control === CAR_CONTROL.SELECT) {
    control = `<select class="car-select" data-item="${id}" data-kind="select" ${locked ? 'disabled' : ''}>${
      (item.options || []).map((o) => `<option value="${escapeHtml(o.value)}"${String(o.value) === String(item.value) ? ' selected' : ''}>${escapeHtml(o.label)}</option>`).join('')
    }</select>`;
  } else if (item.control === CAR_CONTROL.RANGE) {
    control = `<input class="car-range" type="range" data-item="${id}" data-kind="range" min="${item.min ?? 0}" max="${item.max ?? 100}" step="${item.step ?? 1}" value="${escapeHtml(item.value ?? 0)}" ${locked ? 'disabled' : ''}>
      <span class="car-val" data-val="${id}">${escapeHtml(carControlLabel(item))}</span>`;
  } else if (item.control === CAR_CONTROL.TEXT) {
    control = `<input class="car-input" type="text" data-item="${id}" data-kind="text" value="${escapeHtml(item.value ?? '')}" ${locked ? 'disabled' : ''}>`;
  } else {
    control = `<span class="car-val">${escapeHtml(carControlLabel(item))}</span>`;
  }
  return `<div class="car-item${locked ? ' locked' : ''}">
    <div class="txt"><b>${escapeHtml(item.label)}${item.serviceCode ? ` <span class="hint mono">${escapeHtml(item.serviceCode)}</span>` : ''}</b>${note}</div>
    ${control}
  </div>`;
}

function carOpenTile(code) {
  const tile = (carState.settings?.tiles || []).find((t) => t.code === code);
  if (!tile) return;
  carOpenItems(tile.name, tile.describe || `${(tile.items || []).length} 项`, tile.items || []);
}

function carOpenItems(title, sub, items) {
  $('car-dialog-title').textContent = title;
  $('car-dialog-body').innerHTML = (items || []).map((item, index) => carItemRow(item, index)).join('')
    || '<div class="hint">这台车没有开放这一组</div>';
  $('car-dialog-foot').innerHTML = '<button class="btn small" id="car-items-refresh" type="button">刷新当前值</button><span class="hint">改完会自动重读</span>';
  const refresh = $('car-items-refresh');
  if (refresh) {
    refresh.addEventListener('click', async () => {
      await carLoadSettings();
      $('car-dialog').close();
      toast('已刷新');
    });
  }
  for (const el of $('car-dialog-body').querySelectorAll('[data-item]')) {
    const item = items[Number(String(el.dataset.item).slice(3))];
    if (!item) continue;
    const kind = el.dataset.kind;
    if (kind === 'switch') {
      el.addEventListener('click', () => {
        const next = !(Number(item.value) === 1 || item.value === true || item.value === '1');
        carAskWrite(item, next ? 1 : 0);
      });
    } else if (kind === 'select') {
      el.addEventListener('change', () => carAskWrite(item, carCoerceValue(item, el.value)));
    } else if (kind === 'range') {
      el.addEventListener('input', () => {
        const out = $('car-dialog-body').querySelector(`[data-val="${el.dataset.item}"]`);
        if (out) out.textContent = `${el.value}${item.unit || ''}`;
      });
      el.addEventListener('change', () => carAskWrite(item, carCoerceValue(item, el.value)));
    } else if (kind === 'text') {
      el.addEventListener('change', () => carAskWrite(item, el.value));
    }
  }
  $('car-dialog').showModal();
}

function carAskWrite(item, nextValue) {
  if (!item.serviceCode) {
    toast('这项没有 serviceCode（服务端没下发），写入不了；可在下面接口目录里试 propertyOne', 3600);
    return;
  }
  const payload = carBuildWriteBody(item, nextValue, { vin: state.vin });
  carConfirm({
    title: `写入「${item.label}」`,
    sub: `${carControlLabel(item)} → ${String(nextValue)}`,
    body: payload,
    run: async () => {
      const result = await carCall('addSet', {}, payload);
      if (!result.ok) throw new Error(result.message || result.code || '写入失败');
      toast('已提交，等车端生效');
      await sleep(800);
      await carLoadSettings();
    },
  });
}

/* ---------------------------- 车机壁纸 ---------------------------- */

async function carLoadWallpaper() {
  if (!state.vin) return;
  const [page, count, home] = await Promise.all([
    carCall(byId('wallpaper_page'), { vinNo: state.vin, pageNum: '1', pageSize: '50' }).catch((e) => ({ ok: false, message: e.message })),
    carCall(byId('wallpaper_count'), { vinNo: state.vin }).catch(() => ({ ok: false })),
    carCall(byId('vehicle_home'), { vin: state.vin }).catch(() => ({ ok: false })),
  ]);
  carState.wallsMeta = home.ok ? carWallpapersFromHome({ code: home.code, data: home.data }) : carState.wallsMeta;
  carState.wallsCount = count.ok ? Number(count.data) : null;
  if (!page.ok) {
    carState.walls = [];
    carState.wallsError = page.message || page.code || '壁纸列表读取失败';
  } else {
    const normalized = carNormalizeWallpaperPage({ code: page.code, data: page.data });
    carState.walls = normalized.list;
    carState.wallsError = '';
    carState.wallsTotal = normalized.total;
  }
  carRenderWallpaper();
}

function carRenderWallpaper() {
  const meta = carState.wallsMeta || {};
  const count = carState.wallsCount;
  const list = carState.walls || [];
  $('wp-sub').textContent = `${meta.width && meta.height ? `${meta.width}×${meta.height}` : '分辨率未读到'} · ${
    count === null || count === undefined ? '数量未知' : `${count} 张`} · 主题商店${meta.theme && String(meta.theme.shop) === '1' ? '开放' : '未开放'}`;

  const builtin = meta.builtin || [];
  $('wp-builtin').innerHTML = builtin.map((item) => `
    <div class="car-wall" style="cursor:default">
      ${item.url ? `<img src="${escapeHtml(item.url)}" alt="${escapeHtml(item.name || '内置壁纸')}" loading="lazy" referrerpolicy="no-referrer">` : '<div class="ph">无预览</div>'}
      <span class="nm">${escapeHtml(item.name || '内置壁纸')}</span>
    </div>`).join('');

  $('wp-grid').innerHTML = list.length ? list.map((wall) => {
    const image = wall.thumbUrl || wall.imageUrl;
    return `<button class="car-wall" type="button" data-wall="${escapeHtml(wall.id)}">
      ${wall.usable === false ? '<div class="ph">信息不全（缺图片地址）</div>'
        : image ? `<img src="${escapeHtml(image)}" alt="${escapeHtml(wall.name || '')}" loading="lazy" referrerpolicy="no-referrer">`
          : '<div class="ph">无预览图</div>'}
      ${wall.inUse ? '<span class="tag on">使用中</span>' : ''}
      <span class="nm">${escapeHtml(wall.name || `#${wall.id}`)}</span>
    </button>`;
  }).join('') : `<div class="hint">${escapeHtml(carState.wallsError || '还没有自定义壁纸；点右上「上传」')}</div>`;

  for (const button of $('wp-grid').querySelectorAll('[data-wall]')) {
    button.addEventListener('click', () => carOpenWallpaper(button.dataset.wall));
  }
}

function carOpenWallpaper(id) {
  const wall = (carState.walls || []).find((item) => String(item.id) === String(id));
  if (!wall) return;
  const areas = carWallpaperAreas(wall);
  $('car-dialog-title').textContent = wall.name || `壁纸 #${wall.id}`;
  const areaCard = (label, area, ratio) => `
    <div style="margin-top:10px">
      <div class="hint" style="margin-bottom:4px">${label}</div>
      ${area?.url
        ? `<img src="${escapeHtml(area.url)}" alt="${escapeHtml(label)}" style="width:100%;border-radius:10px;display:block;aspect-ratio:${ratio};object-fit:cover" referrerpolicy="no-referrer">`
        : '<div class="hint">这一块还没有图</div>'}
    </div>`;
  $('car-dialog-body').innerHTML = `
    ${areaCard('整屏壁纸（1024×600）', areas.main, '16 / 9')}
    ${areaCard('中间速度卡', areas.speedCard, '2 / 1')}
    <dl class="car-kv" style="margin-top:12px">
      <dt>状态</dt><dd>${wall.inUse ? '使用中' : '未使用'}</dd>
      <dt>可下发</dt><dd>${wall.usable === false ? '缺图片/ID，仅能看原文' : '可以下发到车机'}</dd>
      <dt>主题配置</dt><dd class="mono">${escapeHtml(wall.configId ?? '—')}</dd>
    </dl>
    <pre class="car-pre">${escapeHtml(JSON.stringify(wall.raw, null, 2))}</pre>`;
  $('car-dialog-foot').innerHTML = `
    <button class="btn small primary" id="wall-apply" type="button"${wall.usable === false ? ' disabled' : ''}>应用这张壁纸</button>
    <button class="btn small danger" id="wall-del" type="button">删除</button>
    <button class="btn small" id="wall-rename" type="button">改名字</button>`;
  $('wall-apply').addEventListener('click', () => carApplyWallpaper(wall));
  $('wall-del').addEventListener('click', () => carDeleteWallpaper(wall));
  $('wall-rename').addEventListener('click', () => carRenameWallpaper(wall));
  $('car-dialog').showModal();
}

/** 速度卡比例（固定，不给用户改；正式参数下来只改这两个数）。 */
const CAR_SPEED_CARD_SPEC = { heightRatio: 0.86, aspect: 1 / 1.9 };

/**
 * 官方顺序：选图 → 本地拖拽/双指缩放调位置 → 点保存才上传。
 * 面板做成两层视图（同一个弹窗内切换，避免 modal 套 modal）：
 *   list    —— 两个缩略图（整屏 / 速度卡）+ 名称 + 「保存并上传」；
 *   preview —— 点缩略图进来：拖拽 / 滚轮 / 双指缩放，点「完成」回列表。
 * 两个预览的舞台都是整屏比例，区别只在：
 *   · 整屏预览：只显示整屏图，速度卡不出现；
 *   · 速度卡预览：整屏图当背景（不可拖动），中间那块竖长方形速度卡跟着手指动 / 缩放。
 * 选图、拖动、缩放全程不发请求；只有「保存并上传」才按当前裁切导出 multipart。
 */
function carCreateWallpaper() {
  const meta = carState.wallsMeta || {};
  const mainW = Number(meta.width) || 1024;
  const mainH = Number(meta.height) || 600;
  const speedGeo = carSpeedCardBox({ w: mainW, h: mainH }, CAR_SPEED_CARD_SPEC);
  const editor = {
    view: 'list',
    editing: 'main',
    name: '',
    box: { main: { w: mainW, h: mainH }, speed: { w: speedGeo.w, h: speedGeo.h } },
    layers: {
      main: { file: null, img: null, url: '', zoom: 1, panX: 0, panY: 0 },
      speed: { file: null, img: null, url: '', zoom: 1, panX: 0, panY: 0 },
    },
  };
  let detachPreview = null;
  let pendingArea = 'main';

  /** 预览与导出共用这一份裁切参数。 */
  const fitOf = (key) => carWallpaperFit({
    imgW: editor.layers[key].img?.naturalWidth || 0,
    imgH: editor.layers[key].img?.naturalHeight || 0,
    boxW: editor.box[key].w,
    boxH: editor.box[key].h,
    zoom: editor.layers[key].zoom,
    panX: editor.layers[key].panX,
    panY: editor.layers[key].panY,
  });

  /* ---------------- list：两个缩略图 + 名称 ---------------- */
  const renderList = () => {
    if (detachPreview) { detachPreview(); detachPreview = null; }
    $('car-dialog-title').textContent = '新建壁纸';
    $('car-dialog-body').innerHTML = `
      <div class="cw-grid" id="cw-grid">
        <button class="cw-thumb" type="button" data-area="main">
          <span class="cw-thumb-box"><img id="cw-thumb-main" alt="" draggable="false"></span>
          <span class="cw-thumb-tag">整屏</span>
        </button>
        <button class="cw-thumb cw-thumb-speed" type="button" data-area="speed">
          <span class="cw-thumb-box"><img id="cw-thumb-speed" alt="" draggable="false"></span>
          <span class="cw-thumb-tag">速度卡</span>
        </button>
      </div>
      <div class="field" style="margin-top:14px">
        <label for="cw-name">壁纸名称</label>
        <input id="cw-name" maxlength="24" value="${escapeHtml(editor.name)}" placeholder="例如：夜景">
      </div>
      <div id="cw-export-box"></div>
      <input id="cw-file" type="file" accept="image/*" hidden>`;
    $('car-dialog-foot').innerHTML = `
      <button class="btn small" id="cw-export" type="button">只看导出图</button>
      <button class="btn small primary" id="cw-save" type="button">保存并上传</button>`;

    const paintThumbs = () => {
      for (const key of ['main', 'speed']) {
        const layer = editor.layers[key];
        const thumb = document.querySelector(`.cw-thumb[data-area="${key}"]`);
        const img = $(`cw-thumb-${key}`);
        thumb.classList.toggle('empty', !layer.url);
        if (layer.url) img.src = layer.url;
      }
    };
    paintThumbs();

    $('cw-grid').addEventListener('click', (event) => {
      const thumb = event.target.closest('[data-area]');
      if (!thumb) return;
      const area = thumb.dataset.area;
      editor.name = $('cw-name')?.value || editor.name;
      if (!editor.layers[area].url) { pendingArea = area; $('cw-file').click(); return; }
      openPreview(area);
    });

    $('cw-file').addEventListener('change', async (event) => {
      const file = event.target.files?.[0] || null;
      event.target.value = '';
      if (!file) return;
      if (!/^image\//.test(file.type)) { toast('这不是图片文件'); return; }
      try {
        const { img, url } = await carReadLocalImage(file);
        const previous = editor.layers[pendingArea].url;
        editor.layers[pendingArea] = { file, img, url, zoom: 1, panX: 0, panY: 0 };
        if (previous && previous !== url) URL.revokeObjectURL(previous);
        paintThumbs();
        openPreview(pendingArea);
      } catch (error) {
        toast(`读图失败：${error.message}`);
      }
    });

    $('cw-name').addEventListener('input', () => { editor.name = $('cw-name').value; });

    $('cw-export').addEventListener('click', async () => {
      if (!editor.layers.main.url) { toast('先选一张整屏壁纸'); return; }
      const box = $('cw-export-box');
      box.innerHTML = '<div class="cw-out"><span class="hint">导出中…</span></div>';
      try {
        const files = [await exportLayer('main'), await exportLayer('speed')].filter(Boolean);
        box.innerHTML = files.map((file) => {
          const href = URL.createObjectURL(file);
          return `<div class="cw-out">
            <img src="${href}" alt="${escapeHtml(file.name)}">
            <div class="cw-out-txt"><b>${escapeHtml(file.name)}</b><small>${carFormatBytes(file.size)}</small></div>
            <a class="btn small" download="${escapeHtml(file.name)}" href="${href}">下载</a>
          </div>`;
        }).join('');
      } catch (error) {
        box.innerHTML = '';
        toast(`导出失败：${error.message}`);
      }
    });

    $('cw-save').addEventListener('click', async () => {
      if (!state.vin) { toast('还没有读到车'); return; }
      if (!editor.layers.main.url) { toast('先选一张整屏壁纸'); return; }
      editor.name = $('cw-name')?.value || editor.name;
      try {
        const mainFile = await exportLayer('main');
        const speedFile = editor.layers.speed.url ? await exportLayer('speed') : null;
        const name = (editor.name.trim() || editor.layers.main.file.name.replace(/\.[^.]+$/, '')).slice(0, 24);
        $('car-dialog').close();
        carUploadWallpaper({ mainFile, speedFile, name });
      } catch (error) {
        toast(`导出失败：${error.message}`);
      }
    });
  };

  /* ---------------- preview：一个整屏比例的舞台 ---------------- */
  const renderPreview = () => {
    if (detachPreview) { detachPreview(); detachPreview = null; }
    const isSpeed = editor.editing === 'speed';
    $('car-dialog-title').textContent = isSpeed ? '调整速度卡' : '调整整屏壁纸';
    $('car-dialog-body').innerHTML = `
      <div class="cw-stage" id="cw-stage" data-mode="${isSpeed ? 'speed' : 'main'}"
        style="aspect-ratio:${editor.box.main.w}/${editor.box.main.h}">
        <div class="cw-area cw-area-main" id="cw-main-area">
          <div class="cw-layer" id="cw-main-layer"><img id="cw-main-img" alt="" draggable="false"></div>
        </div>
        <div class="cw-area cw-area-speed" id="cw-speed-area"
          style="left:${speedGeo.leftPct}%;top:${speedGeo.topPct}%;width:${speedGeo.widthPct}%;height:${speedGeo.heightPct}%">
          <div class="cw-layer" id="cw-speed-layer"><img id="cw-speed-img" alt="" draggable="false"></div>
        </div>
      </div>`;
    $('car-dialog-foot').innerHTML = '<button class="btn small primary" id="cw-done" type="button">完成</button>';

    const stage = $('cw-stage');
    // 整屏预览不出现速度卡；速度卡预览时整屏图只当背景（不接手势）。
    $('cw-speed-area').hidden = !isSpeed;

    const paint = () => {
      for (const key of ['main', 'speed']) {
        const layer = editor.layers[key];
        const t = fitOf(key);
        layer.zoom = t.zoom;
        layer.panX = t.panX;
        layer.panY = t.panY;
        const style = carWallpaperLayerStyle(t);
        const host = $(`cw-${key}-layer`);
        host.style.left = style.left;
        host.style.top = style.top;
        host.style.width = style.width;
        host.style.height = style.height;
        host.hidden = !layer.url;
        const previewImg = $(`cw-${key}-img`);
        if (previewImg && layer.url && previewImg.getAttribute('src') !== layer.url) previewImg.src = layer.url;
      }
      $('cw-main-area').classList.toggle('background', isSpeed);
    };

    const pointers = new Map();
    let drag = null;
    let pinch = null;
    const scaleOf = () => {
      const area = isSpeed ? $('cw-speed-area') : $('cw-main-area');
      const rect = area.getBoundingClientRect();
      const box = editor.box[editor.editing];
      return { kx: rect.width > 0 ? rect.width / box.w : 1, ky: rect.height > 0 ? rect.height / box.h : 1 };
    };

    const onDown = (event) => {
      const layer = editor.layers[editor.editing];
      if (!layer.url) return;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      if (pointers.size === 1) {
        const k = scaleOf();
        drag = {
          id: event.pointerId,
          kx: k.kx,
          ky: k.ky,
          x: event.clientX,
          y: event.clientY,
          panX: layer.panX,
          panY: layer.panY,
        };
        // 抓住指针：拖出舞台边界也要继续收到 move。
        try { stage.setPointerCapture(event.pointerId); } catch { /* 某些宿主不支持 */ }
      }
      if (pointers.size === 2) {
        const [a, b] = [...pointers.values()];
        pinch = { dist: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)), zoom: layer.zoom };
        drag = null;
      }
      event.preventDefault();
    };

    const onMove = (event) => {
      if (!pointers.has(event.pointerId)) return;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      const layer = editor.layers[editor.editing];
      if (pointers.size >= 2 && pinch) {
        const [a, b] = [...pointers.values()];
        const dist = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y));
        layer.zoom = carWallpaperZoom(pinch.zoom * (dist / pinch.dist));
        paint();
        return;
      }
      if (!drag || drag.id !== event.pointerId) return;
      const t = fitOf(editor.editing);
      const dx = (event.clientX - drag.x) / (drag.kx || 1);
      const dy = (event.clientY - drag.y) / (drag.ky || 1);
      const pan = carWallpaperClampPan(drag.panX + dx, drag.panY + dy, t.maxPanX, t.maxPanY);
      layer.panX = pan.panX;
      layer.panY = pan.panY;
      paint();
    };

    const onUp = (event) => {
      if (!pointers.has(event.pointerId)) return;
      pointers.delete(event.pointerId);
      if (drag && drag.id === event.pointerId) drag = null;
      if (pointers.size < 2) pinch = null;
      try { stage.releasePointerCapture(event.pointerId); } catch { /* 没抓住就算了 */ }
    };

    const onWheel = (event) => {
      const layer = editor.layers[editor.editing];
      if (!layer.url) return;
      event.preventDefault();
      layer.zoom = carWallpaperZoom(layer.zoom * Math.exp(-event.deltaY * 0.0015));
      paint();
    };

    const onDouble = (event) => {
      event.preventDefault();
      const layer = editor.layers[editor.editing];
      layer.zoom = 1;
      layer.panX = 0;
      layer.panY = 0;
      paint();
    };

    stage.addEventListener('pointerdown', onDown);
    stage.addEventListener('pointermove', onMove);
    stage.addEventListener('pointerup', onUp);
    stage.addEventListener('pointercancel', onUp);
    stage.addEventListener('wheel', onWheel, { passive: false });
    stage.addEventListener('dblclick', onDouble);

    $('cw-done').addEventListener('click', () => {
      editor.view = 'list';
      render();
    });

    detachPreview = () => {
      stage.removeEventListener('pointerdown', onDown);
      stage.removeEventListener('pointermove', onMove);
      stage.removeEventListener('pointerup', onUp);
      stage.removeEventListener('pointercancel', onUp);
      stage.removeEventListener('wheel', onWheel);
      stage.removeEventListener('dblclick', onDouble);
    };

    paint();
  };

  const openPreview = (area) => {
    editor.editing = area;
    editor.view = 'preview';
    render();
  };

  /* ---------------- 导出：canvas 按整屏/速度卡输出尺寸 + 同一份 transform ---------------- */
  const exportLayer = async (key) => {
    const layer = editor.layers[key];
    if (!layer.url || !layer.img) return null;
    const box = editor.box[key];
    const t = fitOf(key);
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(box.w));
    canvas.height = Math.max(1, Math.round(box.h));
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(layer.img, t.offsetX, t.offsetY, t.drawW, t.drawH);
    const blob = await new Promise((resolve) => { canvas.toBlob(resolve, 'image/jpeg', 0.92); });
    if (!blob) throw new Error('导出失败：浏览器拿不到 canvas 数据');
    const name = (editor.name.trim() || layer.file.name.replace(/\.[^.]+$/, '') || 'wallpaper').slice(0, 24);
    return new File([blob], `${name}-${key}-${canvas.width}x${canvas.height}.jpg`, { type: 'image/jpeg' });
  };

  const render = () => (editor.view === 'preview' ? renderPreview() : renderList());

  $('car-dialog').showModal();
  render();
}

/** 读本地图片为 Image（Blob URL，不联网）。 */
function carReadLocalImage(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => resolve({ img, url });
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('这张图解不开（换 jpg/png 试试）'));
    };
    img.src = url;
  });
}

/**
 * 保存：把**裁切好的**图按官方两步 multipart 传上去（type=1 整屏、type=2 速度卡）。
 * `wallpaperContent` 里的地址只有上传成功后才可能拿到，所以先用 pending:// 占位；
 * 第 1 张若回带了地址，就替换进第 2 张的 content。
 */
async function carUploadWallpaper({ mainFile, speedFile, name }) {
  const endpoint = byId('wallpaper_save');
  const url = buildUrl(endpoint.path, endpoint.params, { vinNo: state.vin });
  const steps = [
    { type: '1', file: mainFile.name, bytes: carFormatBytes(mainFile.size), wallpaperName: name },
    ...(speedFile ? [{ type: '2', file: speedFile.name, bytes: carFormatBytes(speedFile.size), wallpaperName: name }] : []),
  ];
  carConfirm({
    title: '保存壁纸（上传裁切后的图）',
    sub: speedFile ? '整屏 + 速度卡 各一张' : '只传整屏（速度卡留空）',
    body: { url, steps },
    run: async () => {
      const useOwn = state.mode !== 'panel' || hasOwnCredentials();
      const upload = async (file, type, content) => {
        const form = new FormData();
        form.append('file', file, file.name);
        form.append('wallpaperName', name);
        form.append('vinNo', state.vin);
        form.append('type', String(type));
        form.append('wallpaperContent', content);
        const headers = useOwn ? ownHeaders(url, 'POST', '') : await panelHeaders(url, 'POST', '');
        if (state.token) headers.Authorization = `Bearer ${state.token}`;
        delete headers['Content-Type'];
        const response = await fetch(url, { method: 'POST', headers, body: form, cache: 'no-store' });
        const text = await response.text();
        let data = null;
        try { data = JSON.parse(text); } catch { /* 非 JSON */ }
        if (!(response.ok && (data?.code === '10000' || data?.code === undefined))) {
          throw new Error(`第 ${type} 步失败：${data?.message || `HTTP ${response.status}`}`);
        }
        return data;
      };
      const first = await upload(mainFile, 1, carBuildWallpaperContent({ mainUrl: 'pending://main', speedCardUrl: '' }));
      const firstUrl = carPickUploadedUrl(first?.data ?? first);
      if (speedFile) {
        await sleep(400);
        await upload(speedFile, 2, carBuildWallpaperContent({
          mainUrl: firstUrl || 'pending://main',
          speedCardUrl: 'pending://speed',
        }));
      }
      toast(speedFile ? '两张都传好了，去列表里应用' : '整屏壁纸已传好，去列表里应用');
      await carLoadWallpaper();
    },
  });
}

/** 上传响应里可能带文件地址（字段名不确定）：取第一个 http(s) 字符串。 */
function carPickUploadedUrl(data) {
  const seen = new Set();
  const walk = (node, depth) => {
    if (!node || depth > 4) return '';
    if (typeof node === 'string') return /^https?:\/\//i.test(node.trim()) ? node.trim() : '';
    if (typeof node !== 'object' || seen.has(node)) return '';
    seen.add(node);
    for (const value of Object.values(node)) {
      const hit = walk(value, depth + 1);
      if (hit) return hit;
    }
    return '';
  };
  return walk(data, 0);
}


function carApplyWallpaper(wall) {
  const payload = carBuildUseWallpaperBody(wall.id, state.vin);
  carConfirm({
    title: '下发壁纸到车机',
    sub: '车机在线才会生效；下发后可以看进度',
    body: payload,
    run: async () => {
      const result = await carCall(byId('wallpaper_use'), {}, payload);
      if (!result.ok) throw new Error(result.message || result.code || '下发失败');
      const recordId = result.data && typeof result.data === 'object' ? (result.data.recordId || result.data.id) : result.data;
      toast('已下发，开始看进度');
      if (recordId) carPollProgress(String(recordId));
    },
  });
}

function carDeleteWallpaper(wall) {
  carConfirm({
    title: '删除壁纸',
    sub: `#${wall.id} ${wall.name || ''}（不可恢复，不影响车机上正在用的那张）`,
    body: { id: wall.id, vinNo: state.vin },
    run: async () => {
      const result = await carCall(byId('wallpaper_delete'), { id: wall.id }, null);
      if (!result.ok) throw new Error(result.message || result.code || '删除失败');
      toast('已删除');
      await carLoadWallpaper();
    },
  });
}

function carRenameWallpaper(wall) {
  $('car-dialog-title').textContent = '修改壁纸信息';
  $('car-dialog-body').innerHTML = `
    <div class="field"><label for="wr-name">新的名称</label><input id="wr-name" maxlength="24" value="${escapeHtml(wall.name || '')}"></div>
    <div class="field"><label for="wr-method">请求方法</label>
      <select id="wr-method">
        <option value="POST" selected>POST（3.0.6 客户端清单）</option>
        <option value="PUT">PUT（早期抓包文档）</option>
      </select>
    </div>
    <p class="car-note">接口未在真机验证：40004 多半是字段名不对，30121 是签名/网关问题。</p>`;
  $('car-dialog-foot').innerHTML = '<button class="btn small primary" id="wr-save" type="button">保存</button>';
  $('wr-save').addEventListener('click', () => {
    const name = $('wr-name').value.trim();
    if (!name) { toast('名称不能为空'); return; }
    const method = $('wr-method').value;
    const payload = { wallpaperName: name, vinNo: state.vin };
    $('car-dialog').close();
    carConfirm({
      title: '修改壁纸信息',
      sub: `${method} wallpaper/update/${wall.id}`,
      body: payload,
      run: async () => {
        const result = await carCall(byId('wallpaper_update'), { id: wall.id }, payload, method);
        if (!result.ok) throw new Error(result.message || result.code || '保存失败');
        toast('已保存');
        await carLoadWallpaper();
      },
    });
  });
  $('car-dialog').showModal();
}

async function carPollProgress(recordId) {
  $('wp-progress').hidden = false;
  $('wp-progress-text').textContent = `下发进度：recordId ${recordId} …`;
  clearInterval(carState.progressTimer);
  let ticks = 0;
  carState.progressTimer = setInterval(async () => {
    ticks += 1;
    // 路径是 useWallpaperProgress/{recordId}，注意不是壁纸 id。
    const detail = await carCall(byId('wallpaper_progress'), { recordId }).catch(() => null);
    const progress = detail && detail.ok ? carNormalizeProgress({ code: '10000', data: detail.data }) : null;
    $('wp-progress-text').textContent = progress
      ? `下发进度：${progress.percent || 0}% ${progress.message || ''}`
      : '下发进度：等待车端…';
    if (progress?.done || ticks >= 20) {
      clearInterval(carState.progressTimer);
      carState.progressTimer = null;
      await carLoadWallpaper();
    }
  }, 1500);
}

/* ---------------------------- 确认弹层 ---------------------------- */

let carConfirmRun = null;
function carConfirm({ title, sub, body, run, actionLabel = '执行' }) {
  carConfirmRun = run;
  $('cc-title').textContent = title;
  $('cc-sub').textContent = sub || '';
  $('cc-body').textContent = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
  const needCheck = actionLabel === '执行';
  $('cc-check').checked = false;
  $('cc-ok').disabled = needCheck;
  $('cc-ok').textContent = actionLabel;
  $('cc-check').closest('label').hidden = !needCheck;
  $('car-confirm').showModal();
}

/* ---------------------------- 初始化与事件 ---------------------------- */

async function carInitPanels() {
  if (!state.token) return;
  for (let i = 0; i < 20 && !state.vin; i += 1) await sleep(300);
  if (!state.vin) return;
  await Promise.allSettled([carLoadFirmware(), carLoadSettings(), carLoadWallpaper()]);
}

/** 同一套 UI 也会跑在面板页里（那里没有 api.html 的 #vin-select 等元素），所以逐个判空。 */
function carOn(id, event, handler) {
  const el = $(id);
  if (el) el.addEventListener(event, handler);
}

carOn('btn-ota-check', 'click', () => { void carCheckOta(); });
carOn('btn-cs-refresh', 'click', async () => { await carLoadSettings(); toast('设置已刷新'); });
carOn('btn-wp-refresh', 'click', async () => { await carLoadWallpaper(); toast('壁纸已刷新'); });
carOn('btn-open-walls', 'click', () => $('wallpaper-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
carOn('btn-wall-edit', 'click', () => {
  const wall = (carState.walls || [])[0];
  if (!wall) { toast('还没有壁纸，先上传一张'); return; }
  carRenameWallpaper(wall);
});
// 「上传新壁纸」「新建壁纸」都走同一个本地定位编辑器：选图 → 拖拽/缩放 → 保存才上传。
carOn('btn-wall-upload', 'click', () => carCreateWallpaper());
carOn('btn-wp-new', 'click', () => carCreateWallpaper());
carOn('btn-wall-create', 'click', () => carCreateWallpaper());
carOn('cc-check', 'change', (event) => { $('cc-ok').disabled = !event.target.checked; });
carOn('cc-ok', 'click', async () => {
  const run = carConfirmRun;
  $('car-confirm')?.close();
  carConfirmRun = null;
  if (!run) return;
  try {
    await run();
  } catch (error) {
    toast(`失败：${error.message}`, 3600);
  }
});
carOn('vin-select', 'change', () => {
  void carInitPanels();
});
// 面板页（index.html）里这三块默认藏在 sheet 里，打开时由 panel-sheet.js 显式调用；
// 这里只在没有 #ze-car-sheet 的宿主（例如单独调试页）里自动加载一次，避免进首页就打一堆接口。
setTimeout(() => {
  const sheet = document.getElementById('ze-car-sheet');
  if (sheet && sheet.hidden) return;
  void carInitPanels();
}, 1200);
