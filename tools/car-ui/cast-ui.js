/* ==== ZEEHO_CAST_UI:JS:BEGIN ==== */
/* 车机投屏 UI（自包含）。
   —— 与壁纸/OTA 那套注入块完全独立：不依赖 panel-host.js / api-car-ui.js / 任何面板内部函数，
      只依赖内联进来的协议模型（__castModels ← sdk/vehicle-cast.js）和 App 的原生桥（window.ZeehoCast）。
   —— 页面壳：运行时建 #ze-cast-sheet 装卡片，扫码 dialog 挂 body 级（塞进 sheet 会被裁切）。
   —— 入口：面板「车辆设置」页底部一行 + hash 路由 #car-cast（面板重绘时用 MutationObserver 补回）。 */

const ZE_CAST = __castModels;
const {
  CAST_TIERS: zeCastTiers,
  detectCastBridge: zeCastDetectBridge,
  createCastClient: zeCastCreateClient,
  installCastEventSink: zeCastInstallSink,
  parsePairingPayload: zeCastParsePairing,
  summarizePairing: zeCastSummarize,
  castErrorText: zeCastErrorText,
  formatCastBytes: zeCastBytes,
  formatCastDuration: zeCastDuration,
  castSelfTest: zeCastSelfTest,
  redactDiagnostic: zeCastRedact,
} = ZE_CAST;

const ZE_CAST_TIER_LABEL = {
  performance: '性能 · 30fps · 2.5M',
  'bitrate-safe': '稳码率 · 30fps · 动态',
  compatibility: '兼容 · ≤24fps · Baseline',
  balanced: '均衡 · 20fps · 1.5M',
  'resolution-safe': '低分辨率 · ≤800×480 · 20fps',
  'decoder-safe': '解码器保底 · ≤800×480 · 15fps',
};

const ZE_CAST_PHASE = {
  idle: { text: '待连接', tone: 'idle' },
  provisioning: { text: '车机联网中', tone: 'busy' },
  handshaking: { text: '握手…', tone: 'busy' },
  ready: { text: '已连接', tone: 'ok' },
  casting: { text: '投屏中', tone: 'ok' },
  adapting: { text: '降档重试', tone: 'warn' },
  stopping: { text: '正在停止', tone: 'busy' },
  failed: { text: '失败', tone: 'bad' },
  unavailable: { text: '不可用', tone: 'warn' },
};

const zeCastState = {
  inited: false,
  client: null,
  profile: null,
  stream: null,
  scanTimer: null,
  pollTimer: null,
  lastError: '',
};

function zeCastEl(id) { return document.getElementById(id); }

function zeCastToast(message, ms = 2400) {
  let el = zeCastEl('ze-cast-toast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'ze-cast-toast';
    document.body.appendChild(el);
  }
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(zeCastToast.timer);
  zeCastToast.timer = setTimeout(() => el.classList.remove('show'), ms);
}

function zeCastClient() {
  if (zeCastState.client) return zeCastState.client;
  zeCastState.client = zeCastCreateClient({
    bridge: zeCastDetectBridge(window),
    emit: (event) => {
      if (event.type === 'phase' && event.payload.to === 'failed') {
        zeCastState.lastError = zeCastErrorText(event.payload.reason, event.payload.reason);
      }
      zeCastRender();
    },
  });
  zeCastInstallSink(zeCastState.client, window);
  return zeCastState.client;
}

function zeCastBridgeReady() {
  return Boolean(zeCastClient().isAvailable());
}

/* ------------------------------ 页面壳 ------------------------------ */

function zeCastEnsureSheet() {
  let sheet = zeCastEl('ze-cast-sheet');
  if (sheet) return sheet;
  sheet = document.createElement('div');
  sheet.id = 'ze-cast-sheet';
  sheet.hidden = true;
  sheet.innerHTML = `
    <div class="ze-cast-head">
      <h2>车机投屏</h2>
      <span class="ze-cast-sub">扫码配对 · 内置协议</span>
      <button class="ze-cast-close" id="ze-cast-close" type="button">关闭</button>
    </div>
    <div class="ze-cast-scroll"></div>`;
  document.body.appendChild(sheet);
  const card = zeCastEl('cast-card');
  if (card) sheet.querySelector('.ze-cast-scroll').appendChild(card);
  return sheet;
}

function zeCastOpenSheet({ push = true } = {}) {
  const sheet = zeCastEnsureSheet();
  if (!sheet) return;
  if (push && location.hash !== '#car-cast') {
    try { history.pushState(null, '', '#car-cast'); } catch { /* file:// */ }
  }
  sheet.hidden = false;
  document.body.classList.add('ze-cast-lock');
  zeCastInit();
  zeCastRender();
}

function zeCastCloseSheet() {
  const sheet = zeCastEl('ze-cast-sheet');
  if (!sheet) return;
  sheet.hidden = true;
  document.body.classList.remove('ze-cast-lock');
  if (location.hash === '#car-cast') {
    try { history.pushState(null, '', location.pathname + location.search); } catch { /* ignore */ }
  }
}

/** 面板「车辆设置」页底部挂一行入口（面板重绘会重建 #scroll，所以幂等补回）。 */
function zeCastEnsureEntry() {
  const page = document.getElementById('scroll')?.querySelector('.page');
  if (!page || page.querySelector('.ze-cast-entry')) return;
  if (!page.textContent.includes('设置项')) return;
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'ze-cast-entry';
  button.innerHTML = `<span><b>车机投屏</b><small>扫码配对 · 内置协议 · 不依赖第三方投屏</small></span>
    <span class="ze-cast-go">›</span>`;
  button.addEventListener('click', () => zeCastOpenSheet());
  page.appendChild(button);
}

/* ------------------------------ 渲染 ------------------------------ */

function zeCastFillTiers() {
  const select = zeCastEl('cast-tier');
  if (!select || select.options.length) return;
  select.innerHTML = zeCastTiers
    .map((tier) => `<option value="${tier}">${ZE_CAST_TIER_LABEL[tier] || tier}</option>`)
    .join('');
}

function zeCastSetHint(text, tone) {
  const hint = zeCastEl('cast-hint');
  if (!hint) return;
  hint.textContent = text || '';
  hint.dataset.tone = tone || '';
}

function zeCastRender() {
  const client = zeCastState.client;
  const phase = client ? client.phase : 'unavailable';
  const meta = ZE_CAST_PHASE[phase] || ZE_CAST_PHASE.idle;
  const pill = zeCastEl('cast-pill');
  if (pill) {
    pill.textContent = meta.text;
    pill.dataset.tone = meta.tone;
  }
  const sub = zeCastEl('cast-sub');
  if (sub) sub.textContent = zeCastBridgeReady() ? '原生桥已就绪' : '只读模式（缺原生桥）';

  const profile = zeCastState.profile;
  if (zeCastEl('cast-model')) zeCastEl('cast-model').textContent = profile ? profile.modelId : '—';
  if (zeCastEl('cast-mac')) {
    const mac = profile ? (zeCastSummarize(profile).find((row) => row.key === '车机地址') || {}).value : '';
    zeCastEl('cast-mac').textContent = mac || '—';
  }
  const stats = client ? client.state.stats : null;
  if (zeCastEl('cast-stream')) {
    zeCastEl('cast-stream').textContent = client && client.state.casting && stats
      ? `${stats.sentFrames || 0} 帧 · ${stats.fps || 0} fps · ${zeCastBytes(stats.sentBytes || 0)} · ${zeCastDuration(client.state.startedAt ? Date.now() - client.state.startedAt : 0)}`
      : '—';
  }

  const startable = zeCastBridgeReady() && Boolean(profile) && ['idle', 'failed'].includes(phase);
  const stoppable = zeCastBridgeReady() && !['idle', 'failed', 'unavailable'].includes(phase);
  if (zeCastEl('btn-cast-start')) zeCastEl('btn-cast-start').disabled = !startable;
  if (zeCastEl('btn-cast-stop')) zeCastEl('btn-cast-stop').disabled = !stoppable;

  if (!zeCastBridgeReady()) {
    zeCastSetHint('当前环境没有原生桥（浏览器直开 / GitHub Pages）。扫码和诊断可用，投屏要在极核面板 App 里开。', 'warn');
  } else if (phase === 'failed') {
    zeCastSetHint(zeCastState.lastError || '投屏失败，看诊断里的事件再重试。', 'bad');
  } else if (phase === 'casting') {
    zeCastSetHint('正在投屏。手机锁屏/切后台可能中断，保持 App 在前台。', 'ok');
  } else if (!profile) {
    zeCastSetHint('在车机上打开「投屏」，扫描车机屏幕上的二维码（车辆设置里的「投屏」需为「扫码投屏」）。', '');
  } else if (phase === 'idle') {
    zeCastSetHint('二维码已就绪，点「开始投屏」。', '');
  } else {
    zeCastSetHint('', '');
  }
}

/* ------------------------------ 扫码 ------------------------------ */

function zeCastScanStop() {
  if (zeCastState.scanTimer) {
    clearInterval(zeCastState.scanTimer);
    zeCastState.scanTimer = null;
  }
  if (zeCastState.stream) {
    for (const track of zeCastState.stream.getTracks()) track.stop();
    zeCastState.stream = null;
  }
  const video = zeCastEl('cast-video');
  if (video) video.srcObject = null;
}

async function zeCastScanStart() {
  const dialog = zeCastEl('ze-cast-scan');
  if (!dialog) { zeCastToast('页面缺少扫码弹层，改用「粘贴二维码」'); return; }
  if (!dialog.open) dialog.showModal();
  const hint = zeCastEl('cast-scan-hint');
  if (hint) hint.textContent = '把车机屏幕上的二维码放进取景框。';
  const manual = zeCastEl('cast-scan-manual');
  if (manual) manual.value = '';
  const video = zeCastEl('cast-video');
  if (!navigator.mediaDevices?.getUserMedia) {
    if (hint) hint.textContent = '这个环境打不开摄像头，把二维码内容粘贴到下面。';
    return;
  }
  try {
    zeCastState.stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
    if (video) {
      video.srcObject = zeCastState.stream;
      await video.play().catch(() => {});
    }
  } catch (error) {
    if (hint) hint.textContent = `打不开摄像头（${error.message}）。把二维码内容粘贴到下面。`;
    return;
  }
  if (typeof BarcodeDetector !== 'function') {
    if (hint) hint.textContent = '本环境没有条码识别（BarcodeDetector），请粘贴二维码内容。';
    return;
  }
  let detector;
  try {
    detector = new BarcodeDetector({ formats: ['qr_code'] });
  } catch {
    if (hint) hint.textContent = '条码识别初始化失败，请粘贴二维码内容。';
    return;
  }
  zeCastState.scanTimer = setInterval(async () => {
    if (!zeCastState.stream || !video) return;
    try {
      const codes = await detector.detect(video);
      if (codes && codes.length) {
        zeCastScanStop();
        zeCastEl('ze-cast-scan')?.close();
        await zeCastHandleText(codes[0].rawValue || '');
      }
    } catch { /* 单帧失败忽略 */ }
  }, 350);
}

async function zeCastHandleText(text) {
  const parsed = zeCastParsePairing(text);
  if (!parsed.ok) {
    zeCastToast(parsed.errors[0]?.message || '无法识别的二维码', 3200);
    return false;
  }
  const client = zeCastClient();
  if (!client.isAvailable()) {
    zeCastState.profile = parsed.profile;
    zeCastState.lastError = '';
    zeCastRender();
    zeCastToast('二维码已解析。只读模式没有原生桥，真正投屏要在 App 里。', 3600);
    return true;
  }
  const result = client.pair(parsed.profile);
  if (!result.ok) {
    zeCastState.lastError = zeCastErrorText(result.error, result.message);
    zeCastToast(`配对失败：${zeCastState.lastError}`, 3600);
    zeCastRender();
    return false;
  }
  zeCastState.profile = parsed.profile;
  zeCastState.lastError = '';
  zeCastRender();
  zeCastToast('已读取车机配对信息');
  return true;
}

/* ------------------------------ 开始 / 停止 / 诊断 ------------------------------ */

function zeCastPollStart() {
  if (zeCastState.pollTimer) return;
  zeCastState.pollTimer = setInterval(() => {
    const client = zeCastState.client;
    if (!client || !client.isAvailable()) return;
    if (['idle', 'failed', 'unavailable'].includes(client.phase)) {
      clearInterval(zeCastState.pollTimer);
      zeCastState.pollTimer = null;
      zeCastRender();
      return;
    }
    client.refresh();
    zeCastRender();
  }, 2000);
}

async function zeCastStart() {
  if (!zeCastState.profile) { zeCastToast('先扫车机上的投屏二维码'); return; }
  const client = zeCastClient();
  const result = client.start({
    tier: zeCastEl('cast-tier')?.value || 'performance',
    frameFormat: zeCastEl('cast-format')?.value || 'traditional',
    captureSource: 'screen',
  });
  if (!result.ok) {
    if (result.error === 'projection_permission_required') {
      client.requestProjection();
      zeCastSetHint('先允许系统的屏幕录制授权，再点一次「开始投屏」。', 'warn');
      zeCastToast('需要屏幕录制授权', 3200);
    } else {
      zeCastState.lastError = zeCastErrorText(result.error, result.message);
      zeCastToast(`启动失败：${zeCastState.lastError}`, 3600);
    }
  }
  zeCastRender();
  zeCastPollStart();
}

function zeCastStop() {
  zeCastClient().stop();
  if (zeCastState.pollTimer) {
    clearInterval(zeCastState.pollTimer);
    zeCastState.pollTimer = null;
  }
  zeCastRender();
  zeCastToast('已停止投屏');
}

async function zeCastCopyDiagnostics() {
  const client = zeCastClient();
  const text = JSON.stringify(zeCastRedact({
    at: new Date().toISOString(),
    phase: client.phase,
    bridge: client.isAvailable(),
    profile: zeCastState.profile ? zeCastSummarize(zeCastState.profile) : null,
    selfTest: zeCastSelfTest(),
    diagnostics: client.getDiagnostics(50),
  }), null, 2);
  try {
    await navigator.clipboard.writeText(text);
    zeCastToast('诊断已复制（凭证已打码）');
    return;
  } catch { /* 剪贴板不可用，落到可见文本 */ }
  const dialog = zeCastEl('ze-cast-scan');
  const hint = zeCastEl('cast-scan-hint');
  const manual = zeCastEl('cast-scan-manual');
  if (dialog && !dialog.open) dialog.showModal();
  if (hint) hint.textContent = '剪贴板不可用，下面是诊断内容，手动复制：';
  if (manual) { manual.value = text; manual.select?.(); }
}

/* ------------------------------ 初始化 ------------------------------ */

function zeCastInit() {
  if (zeCastState.inited) { zeCastFillTiers(); zeCastRender(); return; }
  zeCastState.inited = true;
  zeCastFillTiers();
  zeCastClient().refresh();
  zeCastRender();

  zeCastEl('btn-cast-scan')?.addEventListener('click', () => { void zeCastScanStart(); });
  zeCastEl('btn-cast-paste')?.addEventListener('click', () => {
    const dialog = zeCastEl('ze-cast-scan');
    if (dialog && !dialog.open) dialog.showModal();
    zeCastEl('cast-scan-manual')?.focus();
  });
  zeCastEl('ze-cast-manual-ok')?.addEventListener('click', () => {
    const text = zeCastEl('cast-scan-manual')?.value || '';
    if (!text.trim()) { zeCastToast('先把二维码内容粘进来'); return; }
    zeCastScanStop();
    zeCastEl('ze-cast-scan')?.close();
    void zeCastHandleText(text);
  });
  zeCastEl('btn-cast-start')?.addEventListener('click', () => { void zeCastStart(); });
  zeCastEl('btn-cast-stop')?.addEventListener('click', zeCastStop);
  zeCastEl('btn-cast-diag')?.addEventListener('click', () => { void zeCastCopyDiagnostics(); });
  zeCastEl('cast-tier')?.addEventListener('change', zeCastRender);

  zeCastEl('ze-cast-scan')?.addEventListener('close', zeCastScanStop);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) zeCastScanStop();
  });
}

/* 入口与路由 */
document.addEventListener('click', (event) => {
  if (event.target.closest?.('#ze-cast-close')) {
    event.preventDefault();
    zeCastCloseSheet();
  }
}, true);

window.addEventListener('hashchange', () => {
  if (location.hash === '#car-cast') zeCastOpenSheet({ push: false });
  else zeCastCloseSheet();
});

let zeCastSyncTimer = null;
const zeCastObserver = new MutationObserver(() => {
  clearTimeout(zeCastSyncTimer);
  zeCastSyncTimer = setTimeout(() => { zeCastEnsureSheet(); zeCastEnsureEntry(); }, 60);
});
zeCastObserver.observe(document.body, { childList: true, subtree: true });

zeCastInit();
zeCastEnsureEntry();
setTimeout(() => { zeCastEnsureSheet(); zeCastEnsureEntry(); zeCastRender(); }, 900);
setTimeout(zeCastEnsureEntry, 2400);
if (location.hash === '#car-cast') setTimeout(() => zeCastOpenSheet({ push: false }), 600);
/* ==== ZEEHO_CAST_UI:JS:END ==== */
