/**
 * 极核（ZEEHO）车机「壁纸 / OTA」数据整形层。
 *
 * 定位：把 `vehicle/wallpaper/*`、`ota/*` 这些接口的**原始响应**整理成面板能直接渲染的
 * 结构。纯函数、零依赖、不碰 DOM、不发网络请求，浏览器与 Node 都能 import。
 *
 * 依据：docs/API-3.0.6.md 第 5.4（OTA）、5.9（fileInfo / wifiAddress）、5.11（壁纸与主题）。
 * 说明：仓库里没有 OTA / 壁纸的真机抓包，只有文档；tests/fixtures/media/*.json 是按文档
 * 形状自造的夹具，字段名、状态枚举、百分比口径都可能与真机有差异（见各函数注释里的「假设」）。
 *
 * 两条硬规则：
 *   1. 任何入参（null / undefined / 空对象 / 字符串 / 数组 / 字段缺失）都不抛异常，
 *      拿不到东西就返回空结构（''、0、null、空数组）。
 *   2. `wifiAddress` 里的 Wi-Fi 密码、`fileUrl` 的 OSS 签名参数**绝不进主字段**：
 *      主字段用打码后的值（`wifi.masked` / `file.maskedUrl`），原文只放 `wifi.url` / `file.url`，
 *      供「显示原文」按钮使用。
 */

/* ------------------------------------------------------------------ *
 * 基础工具：类型收敛
 * ------------------------------------------------------------------ */

/** 任意值 → 去空白的字符串；对象/数组等一律给 ''，避免把 [object Object] 渲染出去。 */
function toText(value) {
  if (value == null) return '';
  if (typeof value === 'string') return value.trim();
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : '';
  if (typeof value === 'boolean') return String(value);
  return '';
}

/** 任意值 → 有限数字；'30%' → 30，'1.5 KB' → 1.5，取不到给 null。 */
function toNumber(value) {
  if (value == null) return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value !== 'string') return null;
  const text = value.replace(/[^\d.+-]/g, '');
  if (!text) return null;
  const num = Number(text);
  return Number.isFinite(num) ? num : null;
}

/** 百分比统一到 0..100，保留一位小数；缺失按 0。 */
function clampPercent(value) {
  const num = toNumber(value);
  if (num == null) return 0;
  return Math.min(100, Math.max(0, Math.round(num * 10) / 10));
}

/** 区间钳制；value 取不到就给区间下界（平移这类「默认居中」的场景由调用方先给 0）。 */
function clampRange(value, min, max) {
  const num = toNumber(value);
  if (num == null) return min <= 0 && max >= 0 ? 0 : min;
  return Math.min(max, Math.max(min, num));
}

const TRUE_WORDS = new Set(['1', 'true', 'yes', 'y', 'on', 'use', 'using', 'used', 'enable', 'enabled']);

/** 服务端的布尔字段常见有 true / 1 / '1' / 'true' / 'Y' 多种写法。 */
function isTruthyFlag(value) {
  if (value === true) return true;
  if (value === false || value == null) return false;
  if (typeof value === 'number') return value === 1;
  return TRUE_WORDS.has(toText(value).toLowerCase());
}

/** 三元布尔读取：识别不了给 null（用于区分「明确 false」与「没有这个字段」）。 */
function readBoolean(value) {
  if (value === true || value === false) return value;
  if (typeof value === 'number') {
    if (value === 1) return true;
    if (value === 0) return false;
    return null;
  }
  if (typeof value === 'string') {
    const text = value.trim().toLowerCase();
    if (['true', '1', 'y', 'yes', 'on'].includes(text)) return true;
    if (['false', '0', 'n', 'no', 'off', ''].includes(text)) return false;
  }
  return null;
}

/**
 * 剥掉 `{code, message, data}` 外壳。
 * 注意：`data` 为 null（比如 `{code:'40000', data:null}`）时返回原对象本身，
 * 后续取值自然全是空，不会因为少一层而炸。
 */
function unwrapData(resp) {
  if (!resp || typeof resp !== 'object') return {};
  const data = resp.data;
  if (data && typeof data === 'object') return data;
  return resp;
}

function basename(path) {
  const text = toText(path);
  if (!text) return '';
  const clean = text.split(/[?#]/)[0];
  const parts = clean.split('/').filter(Boolean);
  return parts.length ? parts[parts.length - 1] : clean;
}

/** 去掉 query，只留协议 + 域名 + 路径，用于去重比较。 */
function stripQuery(url) {
  const text = toText(url);
  if (!text) return '';
  return text.split(/[?#]/)[0];
}

/* ------------------------------------------------------------------ *
 * 壁纸：JSON 字符串字段
 * ------------------------------------------------------------------ */

/**
 * 解析「被塞进字符串里的 JSON」。
 * - 已经是对象 / 数组：原样返回；
 * - 是字符串：trim 后 JSON.parse，失败给 null；
 * - 其它（null、数字、布尔）：null。
 */
export function parseJsonField(value) {
  if (value == null) return null;
  if (typeof value === 'object') return value;
  if (typeof value !== 'string') return null;
  const text = value.trim();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** 连续剥掉「JSON 里再套一层 JSON 字符串」，最多三层。 */
function deepParse(value) {
  let current = parseJsonField(value);
  for (let depth = 0; depth < 3 && typeof current === 'string'; depth += 1) {
    current = parseJsonField(current);
  }
  return current;
}

const IMAGE_EXT_RE = /\.(?:png|jpe?g|webp|gif|bmp|avif)(?:[?#]|$)/i;
const THUMB_KEY_RE = /(thumb|small|mini|preview|low|compress|cover|blur)/i;
/** 越靠前越像「主图地址」，用于同一条目里多字段并存时的优先级。 */
const PRIMARY_IMAGE_KEYS = [
  'imageurl',
  'imgurl',
  'picurl',
  'wallpaperurl',
  'image',
  'img',
  'pic',
  'src',
  'fileurl',
  'originurl',
  'bigurl',
  'url',
];

const WIDTH_KEYS = ['width', 'imagewidth', 'wallpaperwidth', 'resolutionwidth', 'imgwidth', 'picwidth', 'w'];
const HEIGHT_KEYS = ['height', 'imageheight', 'wallpaperheight', 'resolutionheight', 'imgheight', 'picheight', 'h'];
const SIZE_TEXT_KEYS = ['resolution', 'wallpaperresolution', 'imageresolution', 'pixels', 'displayresolution'];

/** 值看起来是不是一张图（http(s)/协议相对/根路径 + 图片扩展名或图片域特征）。 */
function looksLikeImageUrl(value) {
  const text = toText(value);
  if (!text) return false;
  if (/^data:image\//i.test(text)) return true;
  if (!/^(?:https?:)?\/\//i.test(text) && !text.startsWith('/') && !/^images?\//i.test(text)) return false;
  return IMAGE_EXT_RE.test(text) || /(?:oss|aliyuncs|image|img|pic|cdn|wallpaper)/i.test(text);
}

/**
 * 递归收集对象/数组里的所有字符串字段（key 是**最近的**字段名），
 * 供「字段名不固定」的图片地址、分辨率搜索使用。
 */
function collectEntries(node, out, depth = 0) {
  if (node == null || depth > 8) return out;
  if (Array.isArray(node)) {
    for (const item of node) collectEntries(item, out, depth + 1);
    return out;
  }
  if (typeof node !== 'object') return out;
  for (const [key, child] of Object.entries(node)) {
    if (child == null) continue;
    if (typeof child === 'string' || typeof child === 'number') {
      const text = toText(child);
      if (text) out.push({ key, value: text });
    } else if (typeof child === 'object') {
      collectEntries(child, out, depth + 1);
    }
  }
  return out;
}

/** 把 wallpaperContent 与条目自身的字符串字段合并成候选列表。 */
function itemEntries(item) {
  const entries = [];
  const source = item && typeof item === 'object' ? item : {};
  for (const [key, value] of Object.entries(source)) {
    if (key === 'wallpaperContent' || key === 'raw') continue;
    if (typeof value === 'string' || typeof value === 'number') {
      const text = toText(value);
      if (text) entries.push({ key, value: text });
    }
  }
  const content = deepParse(source.wallpaperContent);
  if (content && typeof content === 'object') {
    collectEntries(content, entries);
  } else if (typeof source.wallpaperContent === 'string' && looksLikeImageUrl(source.wallpaperContent)) {
    // wallpaperContent 本身就是裸图片地址（不是 JSON）
    entries.push({ key: 'wallpaperContent', value: source.wallpaperContent.trim() });
  }
  return entries;
}

function imageRank(entry) {
  const key = entry.key.toLowerCase();
  const index = PRIMARY_IMAGE_KEYS.indexOf(key);
  return index === -1 ? PRIMARY_IMAGE_KEYS.length : index;
}

/** 从候选字符串里挑主图 / 缩略图。 */
function pickImages(entries) {
  const imageEntries = entries.filter((entry) => looksLikeImageUrl(entry.value));
  if (!imageEntries.length) return { imageUrl: '', thumbUrl: '' };
  const ordered = [...imageEntries].sort((left, right) => imageRank(left) - imageRank(right));
  const thumb = ordered.find((entry) => THUMB_KEY_RE.test(entry.key));
  const full = ordered.find((entry) => !THUMB_KEY_RE.test(entry.key)) || ordered[0];
  const imageUrl = full?.value || '';
  return { imageUrl, thumbUrl: thumb?.value || imageUrl };
}

/** 分辨率：优先成对的 width/height，其次 '1024x600' 这类文本。 */
function pickResolution(entries) {
  const byKey = (keys) => {
    for (const key of keys) {
      const hit = entries.find((entry) => entry.key.toLowerCase() === key);
      const num = toNumber(hit?.value);
      if (num != null && num > 0) return num;
    }
    return null;
  };
  const width = byKey(WIDTH_KEYS);
  const height = byKey(HEIGHT_KEYS);
  if (width && height) return { width, height };
  for (const entry of entries) {
    if (!SIZE_TEXT_KEYS.includes(entry.key.toLowerCase())) continue;
    const match = entry.value.match(/^(\d+)\s*[x*×]\s*(\d+)$/i);
    if (!match) continue;
    const parsedWidth = toNumber(match[1]);
    const parsedHeight = toNumber(match[2]);
    if (parsedWidth && parsedHeight) return { width: parsedWidth, height: parsedHeight };
  }
  return null;
}

/** 主题配置 ID 可能出现在条目上，也可能藏在 wallpaperContent 里。 */
function pickConfigId(item, contentEntries) {
  const direct = firstText(item, ['mmiSubjectConfigId', 'subjectConfigId', 'configId', 'themeId', 'subjectId']);
  if (direct) return direct;
  const keys = ['mmisubjectconfigid', 'subjectconfigid', 'configid', 'themeid', 'subjectid'];
  const hit = contentEntries.find((entry) => keys.includes(entry.key.toLowerCase()));
  return hit?.value || '';
}

function firstText(source, keys) {
  for (const key of keys) {
    const text = toText(source?.[key]);
    if (text) return text;
  }
  return '';
}

/**
 * 单条壁纸 → Wallpaper。拿不到任何标识（id / 名称 / 图片）时给 null。
 *
 * `usable` 的含义：**面板能直接拿来预览或下发** —— 有 id 且有图片地址。
 * 图片地址解析不出来的条目（比如 wallpaperContent 是坏 JSON）照样保留，标 usable=false，
 * 让界面提示「该壁纸内容无法解析」，而不是把整页列表丢掉。
 */
function buildWallpaper(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const entries = itemEntries(raw);
  const images = pickImages(entries);
  const resolution = pickResolution(entries);
  const id = toText(raw.id ?? raw.wallpaperId ?? raw.wallpaperID);
  const name = toText(raw.wallpaperName ?? raw.name ?? raw.title);
  const configId = pickConfigId(raw, entries);
  if (!id && !name && !images.imageUrl) return null;
  return {
    id,
    name,
    inUse: isTruthyFlag(raw.useState ?? raw.isUse ?? raw.using),
    configId,
    imageUrl: images.imageUrl,
    thumbUrl: images.thumbUrl,
    resolution,
    raw,
    usable: Boolean(id) && Boolean(images.imageUrl || images.thumbUrl),
  };
}

/**
 * 壁纸分页 → { total, pageNum, pageSize, hasMore, list }。
 * `total` 缺失时退回本页条数；`pageSize` 缺失时退回本页条数，因此 hasMore 一定是布尔值。
 */
export function normalizeWallpaperPage(resp) {
  const data = unwrapData(resp);
  const rawList = Array.isArray(data) ? data : extractList(data);
  const list = rawList.map(buildWallpaper).filter(Boolean);
  const total = toNumber(data?.total) ?? list.length;
  const pageNum = toNumber(data?.pageNum ?? data?.page) ?? 1;
  const pageSize = toNumber(data?.pageSize ?? data?.size) ?? list.length;
  return {
    total,
    pageNum,
    pageSize,
    hasMore: pageNum * pageSize < total,
    list,
  };
}

/** 壁纸详情 → Wallpaper | null。兼容 `data` 直挂、`data.wallpaper`、`data.detail` 三种。 */
export function normalizeWallpaperDetail(resp) {
  const data = unwrapData(resp);
  const candidate = data?.wallpaper ?? data?.detail ?? data;
  return buildWallpaper(candidate);
}

/**
 * 从 `vehicleHomePageV2`（完整响应或 `data`）取壁纸/主题能力。
 * `subjectWallpaperConfig` 是 JSON 字符串，解析失败按空数组，不影响 supported 等标量。
 */
export function wallpapersFromHome(homeResp) {
  const data = unwrapData(homeResp);
  const builtinRaw = deepParse(data?.subjectWallpaperConfig);
  const builtin = Array.isArray(builtinRaw)
    ? builtinRaw
      .map((item, index) => {
        if (!item || typeof item !== 'object') return null;
        return {
          name: toText(item.name ?? item.subjectName ?? item.title ?? item.wallpaperName) || `内置壁纸 ${index + 1}`,
          type: toText(item.type ?? item.subjectType),
          url: toText(item.url ?? item.imageUrl ?? item.picUrl ?? item.wallpaperUrl),
          subjectUrl: toText(item.subjectUrl ?? item.subjectLink ?? item.jumpUrl ?? item.link),
        };
      })
      .filter(Boolean)
    : [];
  return {
    supported: isTruthyFlag(data?.supportCustomWallpaper),
    width: toNumber(data?.wallpaperResolutionWidth),
    height: toNumber(data?.wallpaperResolutionHeight),
    builtin,
    theme: {
      shop: toText(data?.themeShopFlag),
      fresh: toText(data?.themeShopNewFlag),
      rgb: toText(data?.appThemeRgb),
      chargeRgb: toText(data?.appChargeRgb),
    },
  };
}

/** 只取图片地址：既接受归一化后的 Wallpaper，也接受原始条目。 */
export function wallpaperImages(wallpaper) {
  if (!wallpaper || typeof wallpaper !== 'object') return { imageUrl: '', thumbUrl: '' };
  const raw = wallpaper.raw && typeof wallpaper.raw === 'object' ? wallpaper.raw : wallpaper;
  const images = pickImages(itemEntries(raw));
  const imageUrl = images.imageUrl || toText(wallpaper.imageUrl);
  const thumbUrl = images.thumbUrl || images.imageUrl || toText(wallpaper.thumbUrl) || imageUrl;
  return { imageUrl, thumbUrl };
}

/**
 * 车机壁纸是**分两块图**的（官方 App 3.0.6 的证据）：
 *   · 整屏壁纸（1024×600，`BigWallPaperEditActivity`）；
 *   · 中间速度卡片区域的小图（`SmallWallPaperEditActivity` + `SmallWallpaperUtils.calculateSpeedCardMetrics`）；
 * 服务端把两块放在同一条记录里：`wallpaperContent` 是 JSON 字符串，
 * 元素形状由 dex 里的 `WallpaperConfigItem` 给出 —— `{ type, url, subjectUrl }`，
 * `type` 区分是整屏还是速度卡（`parseType2Wallpaper` / `parseSpeedCardWallpaper`），
 * 应用时按 `serviceAreaType` 逐块下发。
 *
 * 这里只做**容错解析**：type 为 '2'(或含 speed/card 的标记) 视为速度卡，其余当整屏；
 * 没写 type 时按数组顺序（第一张整屏、第二张速度卡）。
 */
export function parseWallpaperAreas(wallpaper) {
  const raw = wallpaper && typeof wallpaper === 'object'
    ? (wallpaper.raw && typeof wallpaper.raw === 'object' ? wallpaper.raw : wallpaper)
    : {};
  const rawContent = raw?.wallpaperContent ?? raw?.content ?? raw?.wallpaperContentJson;
  // deepParse 只处理 JSON 字符串；已经解析好的对象要原样用。
  const content = typeof rawContent === 'string' ? deepParse(rawContent) : rawContent;
  let entries = [];
  if (Array.isArray(content)) entries = content;
  else if (content && typeof content === 'object') {
    // 单张图（对象里直接带 url/imageUrl）与「带列表字段」两种形态都要吃。
    const list = content.items || content.list || content.wallpapers || content.wallpaperContent;
    entries = Array.isArray(list) ? list : [content];
  }
  const items = entries
    .map((item, index) => {
      if (!item || typeof item !== 'object') return null;
      const url = toText(item.url ?? item.imageUrl ?? item.picUrl ?? item.wallpaperUrl);
      const subjectUrl = toText(item.subjectUrl);
      const type = toText(item.type ?? item.areaType ?? item.serviceAreaType);
      const resolved = url || subjectUrl;
      return resolved ? { index, type, url: resolved, subjectUrl } : null;
    })
    .filter(Boolean);

  const isSpeed = (item) => String(item.type) === '2' || /speed|card/i.test(String(item.type));
  const typed = items.filter((item) => String(item.type) !== '');
  let main = null;
  let speedCard = null;
  if (typed.length) {
    main = typed.find((item) => !isSpeed(item)) || null;
    speedCard = typed.find(isSpeed) || null;
  } else if (items.length) {
    // 服务端没写 type 时按数组顺序：第一张整屏、第二张速度卡（官方列表里就是这个顺序）。
    main = items[0];
    speedCard = items[1] || null;
  }
  return {
    main: main ? { url: main.url, subjectUrl: main.subjectUrl, type: main.type || '1' } : null,
    speedCard: speedCard ? { url: speedCard.url, subjectUrl: speedCard.subjectUrl, type: speedCard.type || '2' } : null,
    items,
  };
}

/** 生成 `wallpaperContent`（两块图）。下游上传接口若收 JSON，就把它序列化后带上。 */
export function buildWallpaperContent(options = {}) {
  const { mainUrl = '', speedCardUrl = '' } = options || {};
  const items = [];
  if (mainUrl) items.push({ type: '1', url: mainUrl, subjectUrl: mainUrl });
  if (speedCardUrl) items.push({ type: '2', url: speedCardUrl, subjectUrl: speedCardUrl });
  return JSON.stringify(items);
}

/* ------------------- 本地定位（先拖拽调位置，点保存才上传） ------------------- */

/**
 * 官方 App 3.0.6 的顺序（「自定义仪表壁纸」→「仪表效果预览」）：
 *   1. 选图只在本地，**不联网**；
 *   2. 在预览里拖拽 / 双指缩放，把图挪到满意位置（原文：「双指放大/缩小可调整壁纸展示」）；
 *   3. 点「保存」时才把**调好的那张**传上去（`wallpaper/save` multipart）。
 * 面板没有原生手势层，所以裁切参数自己算：预览用百分比、导出用**同一份 transform** 画到 canvas，
 * 保证「看到哪儿、传上去就是哪儿」。
 *
 * 坐标口径：单位是目标框（整屏 1024×600、速度卡按车型面板）的像素；
 * `zoom` 是相对「覆盖适配（cover）」的倍数 —— zoom=1 时刚好铺满、四边无空白，
 * 所以平移极限天然是 (drawW-boxW)/2，拖不出黑边。
 */
export const WALLPAPER_ZOOM_MIN = 1;
export const WALLPAPER_ZOOM_MAX = 8;

/** 覆盖适配所需的最小缩放：max(boxW/imgW, boxH/imgH)。 */
export function wallpaperCoverScale(imgW, imgH, boxW, boxH) {
  const w = toNumber(imgW);
  const h = toNumber(imgH);
  const bw = toNumber(boxW);
  const bh = toNumber(boxH);
  if (!(w > 0 && h > 0 && bw > 0 && bh > 0)) return 1;
  return Math.max(bw / w, bh / h);
}

/** zoom 钳到 [1, 8]，非法值按 1。 */
export function clampWallpaperZoom(value, max = WALLPAPER_ZOOM_MAX) {
  const num = toNumber(value);
  const top = toNumber(max);
  const ceiling = top != null && top > WALLPAPER_ZOOM_MIN ? top : WALLPAPER_ZOOM_MAX;
  if (num == null) return WALLPAPER_ZOOM_MIN;
  return Math.min(ceiling, Math.max(WALLPAPER_ZOOM_MIN, Math.round(num * 1000) / 1000));
}

/**
 * 「图片尺寸 + 目标框 + zoom + 平移」→ 绘制参数（框坐标系）。
 * offsetX/offsetY 是图左上角坐标（可为负）；maxPanX/maxPanY 是可平移的极限。
 */
export function wallpaperFitTransform(options = {}) {
  const imgW = toNumber(options?.imgW) || 0;
  const imgH = toNumber(options?.imgH) || 0;
  const boxW = toNumber(options?.boxW) || 0;
  const boxH = toNumber(options?.boxH) || 0;
  const zoom = clampWallpaperZoom(options?.zoom);
  const ready = imgW > 0 && imgH > 0 && boxW > 0 && boxH > 0;
  const safeBoxW = boxW > 0 ? boxW : 1;
  const safeBoxH = boxH > 0 ? boxH : 1;
  const scale = ready ? wallpaperCoverScale(imgW, imgH, boxW, boxH) * zoom : 1;
  const drawW = ready ? imgW * scale : safeBoxW;
  const drawH = ready ? imgH * scale : safeBoxH;
  const maxPanX = Math.max(0, (drawW - safeBoxW) / 2);
  const maxPanY = Math.max(0, (drawH - safeBoxH) / 2);
  const panX = clampRange(toNumber(options?.panX), -maxPanX, maxPanX);
  const panY = clampRange(toNumber(options?.panY), -maxPanY, maxPanY);
  return {
    ready,
    zoom,
    scale,
    boxW: safeBoxW,
    boxH: safeBoxH,
    imgW: ready ? imgW : 0,
    imgH: ready ? imgH : 0,
    drawW,
    drawH,
    panX,
    panY,
    maxPanX,
    maxPanY,
    offsetX: (safeBoxW - drawW) / 2 + panX,
    offsetY: (safeBoxH - drawH) / 2 + panY,
  };
}

/** 同一份 transform → 预览层的 CSS 百分比（不依赖舞台像素，手机/桌面自适应）。 */
export function wallpaperLayerStyle(transform) {
  const boxW = toNumber(transform?.boxW) || 1;
  const boxH = toNumber(transform?.boxH) || 1;
  const drawW = toNumber(transform?.drawW);
  const drawH = toNumber(transform?.drawH);
  const pct = (value, base) => `${Number(((value / base) * 100).toFixed(4))}%`;
  return {
    left: pct(toNumber(transform?.offsetX) || 0, boxW),
    top: pct(toNumber(transform?.offsetY) || 0, boxH),
    width: pct(drawW > 0 ? drawW : boxW, boxW),
    height: pct(drawH > 0 ? drawH : boxH, boxH),
  };
}

/** 平移再钳一次（UI 每帧调；max 传 0 表示该方向不可动）。 */
export function clampWallpaperPan(panX, panY, maxPanX, maxPanY) {
  const mx = Math.abs(toNumber(maxPanX) || 0);
  const my = Math.abs(toNumber(maxPanY) || 0);
  return {
    panX: clampRange(toNumber(panX), -mx, mx),
    panY: clampRange(toNumber(panY), -my, my),
  };
}

/**
 * 速度卡是**竖长方形、居中**的一块固定区域（比例如下图，不给用户改）：
 *   · 高 = 整屏高 × heightRatio（默认 0.86）
 *   · 宽 = 高 × aspect（默认 1 / 1.9，即宽:高 ≈ 1:1.9）
 * 等正式参数下来，只改调用处的 heightRatio / aspect 即可。
 */
export function wallpaperSpeedCardBox(mainBox, spec = {}) {
  const mainW = toNumber(mainBox?.w) || 1024;
  const mainH = toNumber(mainBox?.h) || 600;
  const rawRatio = toNumber(spec?.heightRatio);
  const rawAspect = toNumber(spec?.aspect);
  const heightRatio = rawRatio == null ? 0.86 : clampRange(rawRatio, 0.1, 1);
  const aspect = rawAspect == null || rawAspect <= 0 ? 1 / 1.9 : rawAspect;
  const h = Math.max(1, Math.round(mainH * heightRatio));
  const w = Math.max(1, Math.round(h * aspect));
  return {
    w,
    h,
    // 居中：左右、上下各留一半
    left: Math.round((mainW - w) / 2),
    top: Math.round((mainH - h) / 2),
    // 相对整屏的百分比，预览层直接用
    leftPct: Number((((mainW - w) / 2 / mainW) * 100).toFixed(4)),
    topPct: Number((((mainH - h) / 2 / mainH) * 100).toFixed(4)),
    widthPct: Number(((w / mainW) * 100).toFixed(4)),
    heightPct: Number(((h / mainH) * 100).toFixed(4)),
  };
}

/**
 * 壁纸下发进度。
 *
 * **假设（未验证）**：`useWallpaperProgress` 的状态枚举文档没给，这里按
 * `2/3/4 + success/done/finish 等英文词` 判完成，`percent >= 100` 也算完成；
 * 字段缺失时 done=false、percent=0、status=''。
 */
export function normalizeProgress(resp) {
  const data = unwrapData(resp);
  const status = toText(data?.status ?? data?.state ?? data?.progressStatus);
  const percent = clampPercent(data?.percent ?? data?.progress ?? data?.rate ?? data?.schedule);
  const done = readBoolean(data?.done) === true
    || readBoolean(data?.finish) === true
    || isDoneStatus(status)
    || percent >= 100;
  return {
    status,
    percent,
    done,
    message: firstText(data, ['message', 'msg', 'desc', 'describe', 'errDesc', 'tips']),
  };
}

const DONE_STATUS = new Set([
  '2',
  '3',
  '4',
  'success',
  'succeed',
  'succeeded',
  'done',
  'finish',
  'finished',
  'complete',
  'completed',
  'ok',
  'end',
  'ended',
]);

function isDoneStatus(status) {
  return DONE_STATUS.has(toText(status).toLowerCase());
}

/** 官方 `useWallpaper` 的请求体（docs 5.11：body 为 id / vinNo 等）。 */
export function buildUseWallpaperBody(id, vin) {
  return {
    id: toText(id),
    vinNo: toText(vin),
  };
}

/* ------------------------------------------------------------------ *
 * 打码
 * ------------------------------------------------------------------ */

/**
 * Wi-Fi 二维码链接打码：只保留到 `pwd=`，密码换 `***`，其余 query（ssid 等）保留。
 * 没有 `pwd` 参数原样返回。
 */
export function maskWifiAddress(value) {
  const text = typeof value === 'string' ? value : toText(value);
  if (!text) return '';
  if (!/pwd=/i.test(text)) return text;
  return text
    .replace(/([?&]pwd=)[^&#]*/gi, '$1***')
    .replace(/^pwd=[^&#]*/i, 'pwd=***');
}

const SIGNED_PARAM_RE = /^(?:sign|signature|security-token|x-oss-signature|x-oss-credential|x-oss-security-token|ossaccesskeyid|accesskeyid|expires|x-amz-signature|x-amz-credential|x-amz-security-token|awsaccesskeyid|token|pwd|password)$/i;

function isSignedParam(key) {
  return SIGNED_PARAM_RE.test(String(key || '').trim());
}

/**
 * OSS 预签名直链打码：把签名类参数换成 `***`，**保留协议/域名/路径与 `fileName` 等非签名参数**，
 * 这样既不会把有时效的凭据漏到日志/截图里，又还能看出「是哪个包」。
 * 相对地址（没有域名）走正则兜底，同样只打码签名参数。
 */
export function maskSignedUrl(url) {
  const text = typeof url === 'string' ? url : toText(url);
  if (!text) return '';
  if (!text.includes('?')) return text;
  const fragmentIndex = text.indexOf('#');
  const fragment = fragmentIndex === -1 ? '' : text.slice(fragmentIndex);
  const withoutFragment = fragmentIndex === -1 ? text : text.slice(0, fragmentIndex);
  try {
    const parsed = new URL(withoutFragment);
    let touched = false;
    for (const key of [...parsed.searchParams.keys()]) {
      if (!isSignedParam(key)) continue;
      parsed.searchParams.set(key, '***');
      touched = true;
    }
    return touched ? `${parsed.toString()}${fragment}` : withoutFragment + fragment;
  } catch {
    const masked = withoutFragment.replace(
      /([?&])([^=&#]+)=([^&#]*)/g,
      (match, prefix, key) => (isSignedParam(key) ? `${prefix}${key}=***` : match),
    );
    return `${masked}${fragment}`;
  }
}

/** 字节数 → 人类可读（1024 进制，一位小数）：1536 → '1.5 KB'；非法值给 ''。 */
export function formatBytes(n) {
  const size = toNumber(n);
  if (size == null || size < 0) return '';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let value = size;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  if (unit === 0) return `${Math.round(value)} B`;
  const rounded = Math.round(value * 10) / 10;
  return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)} ${units[unit]}`;
}

/* ------------------------------------------------------------------ *
 * OTA
 * ------------------------------------------------------------------ */

/**
 * OTA 文件信息 `{fileName,fileSize,fileUrl,firmwareId,sign,fileBucket}` → OtaFile。
 * - `name`：文件名的 basename（固件落盘就用它）；
 * - `url`：**原始**预签名直链，只给「复制/显示原文」用；
 * - `maskedUrl`：签名参数已打码，界面主字段用这个。
 * 既没有 fileName 也没有 fileUrl 时给 null。
 */
export function normalizeOtaFile(info) {
  if (!info || typeof info !== 'object' || Array.isArray(info)) return null;
  // 容忍「传进来的是包了一层的 fileInfo」
  let source = info;
  if (!firstText(info, ['fileName', 'fileUrl']) && info.fileInfo && typeof info.fileInfo === 'object') {
    source = info.fileInfo;
  }
  const fileName = firstText(source, ['fileName', 'filename', 'firmwareName', 'name']);
  const url = firstText(source, ['fileUrl', 'fileURL', 'url', 'downloadUrl', 'fileDownloadUrl']);
  if (!fileName && !url) return null;
  const size = toNumber(source.fileSize ?? source.size ?? source.fileLength);
  return {
    name: basename(fileName) || fileName,
    size: size != null && size >= 0 ? size : null,
    sizeText: size != null && size >= 0 ? formatBytes(size) : '',
    url,
    maskedUrl: maskSignedUrl(url),
    firmwareId: firstText(source, ['firmwareId', 'firmwareID', 'fwId']),
    sign: firstText(source, ['sign', 'fileSign', 'checksum']),
    bucket: firstText(source, ['fileBucket', 'bucket', 'ossBucket']),
  };
}

/** 从 OTA 信息对象里挑出第一个可用的文件（fileInfo / fileInfoList[0]）。 */
function pickFileInfo(data) {
  const candidates = [data?.fileInfo, data?.otaFileInfo];
  if (Array.isArray(data?.fileInfoList) && data.fileInfoList.length) candidates.push(data.fileInfoList[0]);
  if (Array.isArray(data?.fileInfo) && data.fileInfo.length) candidates.push(data.fileInfo[0]);
  for (const candidate of candidates) {
    const file = normalizeOtaFile(candidate);
    if (file) return file;
  }
  return null;
}

/**
 * `ota/mmi/info/v2` → 归一化 OTA 信息。
 * `present` 表示「服务端确实返回了 OTA 信息对象」；`fileInfo:null` 不是错误，
 * 只是当前没有下发包，`notes` 里会写明。
 */
export function normalizeOtaInfo(resp) {
  const data = unwrapData(resp);
  const present = Boolean(data) && typeof data === 'object' && Object.keys(data).length > 0;
  const wifiRaw = firstText(data, ['wifiAddress', 'wifiUrl', 'wifiAddressUrl']);
  const file = pickFileInfo(data);
  const notes = [];
  if (file) {
    notes.push('fileUrl 是 OSS 预签名直链，带时效；界面请用 maskedUrl，原文只在 url 字段');
  } else {
    notes.push('fileInfo 为 null / 缺失：服务端当前没有下发 OTA 包（正常状态）');
  }
  if (wifiRaw) notes.push('wifiAddress 含 Wi-Fi 密码，wifi.masked 已打码');
  if (!firstText(data, ['recordId', 'recordID'])) notes.push('没有 recordId，无法查询下发/升级进度');
  return {
    present,
    upgradeStatus: firstText(data, ['upgradeStatus', 'status']),
    recordId: firstText(data, ['recordId', 'recordID']),
    softwareVersion: firstText(data, ['softwareVersion', 'currentVersion']),
    appVersion: firstText(data, ['appVersion']),
    otaVersion: firstText(data, ['otaVersion', 'targetVersion']),
    wifi: wifiRaw ? { masked: maskWifiAddress(wifiRaw), url: wifiRaw } : null,
    file,
    notes,
  };
}

/**
 * `checkVersion` → 是否有新版。文档明确：实测只返回 `true` / `false`，
 * 所以这里以布尔为准，顺带兼容 `{data:true}` / `{data:{hasUpdate:1}}` 这类包装。
 * `currentVersion` / `targetVersion` 由调用方（ctx）提供，接口本身不返回版本号。
 */
export function normalizeCheckVersion(resp, ctx = {}) {
  const data = unwrapData(resp);
  const flag = readBoolean(resp)
    ?? readBoolean(typeof resp === 'object' ? resp?.data : null)
    ?? readBoolean(data?.hasUpdate)
    ?? readBoolean(data?.needUpdate)
    ?? readBoolean(data?.checkResult)
    ?? readBoolean(data?.result)
    ?? readBoolean(data?.versionFlag);
  return {
    hasUpdate: flag === true,
    currentVersion: firstText(ctx, ['currentVersion', 'current', 'softwareVersion']),
    targetVersion: firstText(ctx, ['targetVersion', 'target', 'otaVersion']),
    raw: resp,
  };
}

/** 列表字段在不同接口里叫 list / records / rows / data，统一抓一个数组出来。 */
function extractList(data) {
  if (Array.isArray(data)) return data;
  if (!data || typeof data !== 'object') return [];
  for (const key of ['list', 'records', 'rows', 'items', 'recordList', 'otaList', 'data']) {
    if (Array.isArray(data[key])) return data[key];
  }
  return [];
}

const OTA_STATUS_TEXT = {
  0: '待升级',
  1: '下载中',
  2: '升级中',
  3: '升级成功',
  4: '升级失败',
  '-1': '已取消',
  success: '升级成功',
  succeed: '升级成功',
  done: '升级成功',
  finish: '升级成功',
  finished: '升级成功',
  fail: '升级失败',
  failed: '升级失败',
  error: '升级失败',
  running: '升级中',
  upgrading: '升级中',
  downloading: '下载中',
  waiting: '待升级',
  cancel: '已取消',
  canceled: '已取消',
  cancelled: '已取消',
};

/** 状态文案：枚举**未验证**，按常见「0 待升级 / 1 下载中 / 2 升级中 / 3 成功 / 4 失败」猜。 */
function otaStatusText(status) {
  const text = toText(status);
  if (!text) return '未知';
  return OTA_STATUS_TEXT[text.toLowerCase()] || `状态 ${text}`;
}

/**
 * 单条 OTA 记录 → OtaRecord。
 * `statusText` 是猜出来的中文文案，界面要严谨就同时显示原始 `status`。
 */
function buildOtaRecord(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  let file = normalizeOtaFile(raw.fileInfo ?? raw.file ?? raw.otaFileInfo);
  if (!file && firstText(raw, ['fileName', 'fileUrl'])) file = normalizeOtaFile(raw);
  if (!file) {
    const nested = findOtaFiles(raw);
    file = nested.length ? nested[0] : null;
  }
  const status = firstText(raw, ['status', 'upgradeStatus', 'state']);
  const id = firstText(raw, ['id', 'recordId', 'taskId', 'otaId', 'upgradeRecordId']);
  const version = firstText(raw, ['version', 'softwareVersion', 'targetVersion', 'otaVersion']);
  if (!id && !version && !status && !file) return null;
  return {
    id,
    version,
    status,
    statusText: otaStatusText(status),
    percent: clampPercent(raw.percent ?? raw.progress ?? raw.rate ?? raw.schedule),
    createdAt: firstText(raw, ['createTime', 'createdAt', 'gmtCreate', 'createDate']),
    updatedAt: firstText(raw, ['updateTime', 'updatedAt', 'gmtModified', 'finishTime']),
    file,
    raw,
  };
}

function normalizeRecordList(resp) {
  const data = unwrapData(resp);
  const items = extractList(data).map(buildOtaRecord).filter(Boolean);
  if (!items.length) {
    // 有些接口直接返回单条记录（otaDetail 之类），没有 list 包装
    const single = buildOtaRecord(data);
    if (single) items.push(single);
  }
  const total = toNumber(data?.total) ?? items.length;
  return { total, items };
}

/** `ota/list` → { total, items }。 */
export function normalizeOtaList(resp) {
  return normalizeRecordList(resp);
}

/** `otaRecord`（升级历史）→ { total, items }。 */
export function normalizeOtaRecord(resp) {
  return normalizeRecordList(resp);
}

/**
 * `ota/mmi/status` 或 `upstatus` → { status, percent, done, message, errCode, errDesc }。
 * 完成判定同 normalizeProgress（枚举未验证的假设）。
 */
export function normalizeOtaStatus(resp) {
  const data = unwrapData(resp);
  const status = firstText(data, ['status', 'upgradeStatus', 'state']);
  const percent = clampPercent(data?.percent ?? data?.progress ?? data?.rate ?? data?.schedule);
  const errCode = firstText(data, ['errCode', 'errorCode']);
  const errDesc = firstText(data, ['errDesc', 'errorDesc', 'errorMessage']);
  const done = readBoolean(data?.done) === true
    || readBoolean(data?.finish) === true
    || isDoneStatus(status)
    || percent >= 100;
  return {
    status,
    percent,
    done,
    message: firstText(data, ['message', 'msg', 'desc', 'describe', 'errDesc', 'tips']),
    errCode,
    errDesc,
  };
}

/**
 * 在任意 JSON 里递归找所有 `{fileName, fileUrl}` 形状的对象 → OtaFile[]。
 * 去重键：有 `firmwareId` 用 firmwareId，否则用 `文件名 + 去 query 的地址`
 * （同一文件换一次签名不算新文件）。遇到循环引用靠 visited 集合兜住。
 */
export function findOtaFiles(anyJson) {
  const files = [];
  const seen = new Set();
  const visited = new Set();
  const walk = (node, depth) => {
    if (node == null || typeof node !== 'object' || depth > 20) return;
    if (visited.has(node)) return;
    visited.add(node);
    if (Array.isArray(node)) {
      for (const item of node) walk(item, depth + 1);
      return;
    }
    const fileName = firstText(node, ['fileName', 'filename']);
    const fileUrl = firstText(node, ['fileUrl', 'fileURL']);
    if (fileName && fileUrl) {
      const file = normalizeOtaFile(node);
      if (file) {
        const key = file.firmwareId
          ? `fw:${file.firmwareId}`
          : `path:${file.name}|${stripQuery(file.url)}`;
        if (!seen.has(key)) {
          seen.add(key);
          files.push(file);
        }
      }
    }
    for (const child of Object.values(node)) {
      if (child && typeof child === 'object') walk(child, depth + 1);
    }
  };
  walk(anyJson, 0);
  return files;
}
