/* ==== ZEEHO_CAR_UI:JS:BEGIN ==== */
/* 车辆工具：固件 / 车辆设置 / 车机壁纸。
   —— 复用本页已有的 request()（面板签名 + 自填凭据两条路）与状态（state.token / state.vin / state.uid），
      不另外加载面板 iframe，也不引入任何网络依赖。
   —— 数据模型来自 sdk/vehicle-settings.js 与 sdk/vehicle-media.js（由 tools/patch-api-car-ui.mjs 内联到 __carModels）。 */

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

/**
 * 官方壁纸是两步走：先整屏壁纸，再中间速度卡那一块（dex: BigWallPaperEditActivity / SmallWallPaperEditActivity）。
 * 这里做成两步向导，两步各选一张图；上传按两步各发一次 multipart（带 type=1 / type=2）。
 * 接口字段官方没公开 —— 页面上会显示实际发送的字段，失败时按提示改。
 */
function carCreateWallpaperWizard() {
  const state2 = { step: 1, main: null, speed: null, name: '' };
  const renderStep = () => {
    const step = state2.step;
    $('car-dialog-title').textContent = `新建壁纸（第 ${step}/2 步）`;
    $('car-dialog-body').innerHTML = `
      <div class="hint" style="margin-bottom:8px">
        ${step === 1 ? '整屏壁纸：仪表全屏铺满，官方推荐 1024×600。' : '速度卡：仪表中间那块速度卡片区域，尺寸按车型面板（AE5i 约 768×300）。'}
      </div>
      <div class="field">
        <label for="cw-file">${step === 1 ? '选择整屏壁纸' : '选择速度卡图片'}</label>
        <input id="cw-file" type="file" accept="image/*">
      </div>
      <div id="cw-preview" class="hint">${(step === 1 ? state2.main : state2.speed) ? ((step === 1 ? state2.main : state2.speed).name || '已选择') : '还没选文件'}</div>
      <div class="field" style="margin-top:10px">
        <label for="cw-name">壁纸名称</label>
        <input id="cw-name" maxlength="24" value="${escapeHtml(state2.name)}" placeholder="例如：夜景">
      </div>
      <p class="car-note">上传走 <span class="mono">wallpaper/save</span>（multipart）：字段
        <span class="mono">file / wallpaperName / vinNo / type</span>（type=1 整屏、type=2 速度卡）。
        另外会附一份 <span class="mono">wallpaperContent</span> 的两块配置。</p>`;
    $('car-dialog-foot').innerHTML = `
      ${step === 2 ? '<button class="btn small" id="cw-prev" type="button">上一步</button>' : ''}
      <button class="btn small primary" id="cw-next" type="button">${step === 1 ? '下一步' : '上传两张'}</button>`;
    $('cw-name').addEventListener('input', () => { state2.name = $('cw-name').value; });
    $('cw-file').addEventListener('change', (event) => {
      const file = event.target.files?.[0] || null;
      if (step === 1) state2.main = file; else state2.speed = file;
      $('cw-preview').textContent = file ? `${file.name} · ${carFormatBytes(file.size)}` : '还没选文件';
    });
    if ($('cw-prev')) {
      $('cw-prev').addEventListener('click', () => { state2.step = 1; renderStep(); });
    }
    $('cw-next').addEventListener('click', () => {
      if (step === 1) {
        if (!state2.main) { toast('先选一张整屏壁纸'); return; }
        state2.step = 2;
        renderStep();
        return;
      }
      if (!state2.speed) { toast('再选一张速度卡图片'); return; }
      $('car-dialog').close();
      carUploadTwoStep(state2);
    });
    $('car-dialog').showModal();
  };
  renderStep();
}

/** 两步上传：两次 multipart，第二次带 type=2。 */
async function carUploadTwoStep({ main, speed, name }) {
  if (!state.vin) { toast('还没有读到车'); return; }
  const endpoint = byId('wallpaper_save');
  const url = buildUrl(endpoint.path, endpoint.params, { vinNo: state.vin });
  const content = carBuildWallpaperContent({ mainUrl: 'pending://main', speedCardUrl: 'pending://speed' });
  carConfirm({
    title: '上传壁纸（两步）',
    sub: `${main.name} → 整屏；${speed.name} → 速度卡`,
    body: {
      url,
      steps: [
        { type: '1', file: main.name, wallpaperName: name || main.name.replace(/\.[^.]+$/, ''), vinNo: state.vin, wallpaperContent: content },
        { type: '2', file: speed.name, wallpaperName: name || main.name.replace(/\.[^.]+$/, ''), vinNo: state.vin, wallpaperContent: content },
      ],
    },
    run: async () => {
      const useOwn = state.mode !== 'panel' || hasOwnCredentials();
      const upload = async (file, type) => {
        const form = new FormData();
        form.append('file', file, file.name);
        form.append('wallpaperName', name || file.name.replace(/\.[^.]+$/, '').slice(0, 24));
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
      };
      await upload(main, 1);
      await sleep(400);
      await upload(speed, 2);
      toast('两张都传好了，去列表里应用');
      await carLoadWallpaper();
    },
  });
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

async function carUploadWallpaper(file) {
  if (!file) return;
  if (!state.vin) { toast('还没有读到车'); return; }
  const endpoint = byId('wallpaper_save');
  const url = buildUrl(endpoint.path, endpoint.params, { vinNo: state.vin });
  const form = new FormData();
  form.append('file', file, file.name);
  form.append('wallpaperName', file.name.replace(/\.[^.]+$/, '').slice(0, 24));
  form.append('vinNo', state.vin);
  carConfirm({
    title: '上传自定义壁纸',
    sub: `${file.name} · ${carFormatBytes(file.size)}（multipart，字段名与签名体官方没公开，按 file + 空 body 试）`,
    body: { url, fields: ['file', 'wallpaperName', 'vinNo'] },
    run: async () => {
      const useOwn = state.mode !== 'panel' || hasOwnCredentials();
      const headers = useOwn ? ownHeaders(url, 'POST', '') : panelHeaders(url, 'POST', '');
      if (state.token) headers.Authorization = `Bearer ${state.token}`;
      delete headers['Content-Type'];
      const response = await fetch(url, { method: 'POST', headers, body: form, cache: 'no-store' });
      const text = await response.text();
      let data = null;
      try { data = JSON.parse(text); } catch { /* 非 JSON */ }
      if (!(response.ok && (data?.code === '10000' || data?.code === undefined))) {
        throw new Error(data?.message || `HTTP ${response.status}`);
      }
      toast('已上传');
      await carLoadWallpaper();
    },
  });
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
carOn('btn-wall-upload', 'click', () => $('wp-file')?.click());
carOn('btn-wall-create', 'click', () => carCreateWallpaperWizard());
carOn('wp-file', 'change', (event) => {
  const file = event.target.files?.[0];
  event.target.value = '';
  void carUploadWallpaper(file);
});
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
// api.html：boot() 在文件末尾调用，等它把 token/vin 填好后再拉三块数据。
// 面板页（index.html）里这三块默认藏在 sheet 里，打开时由 panel-sheet.js 显式调用，
// 所以这里判断一下：有 #ze-car-sheet 且它还是 hidden 就什么都不做，避免进首页就打一堆接口。
setTimeout(() => {
  const sheet = document.getElementById('ze-car-sheet');
  if (sheet && sheet.hidden) return;
  void carInitPanels();
}, 1200);
