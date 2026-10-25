#!/usr/bin/env node
/**
 * 把「车辆设置 / 车机壁纸 / 固件版本」三块 UI 注入上游的 api.html。
 *
 * 为什么是注入而不是新建页面：仓库里的 index.html 是 dashboard 的混淆构建产物，
 * api.html 才是可读的单文件产物。功能要落在**上游已有的页面**上，上游每次 release
 * 覆盖 api.html 后，重跑这个脚本即可复原（幂等：已有标记就先剥掉再插入）。
 *
 * 注入三块：
 *   1. CSS：<style> 末尾一段 .car-* 样式（只用既有设计变量）；
 *   2. HTML：车况概览卡片后面三个 <section class="card"> + 两个 <dialog>；
 *   3. JS：把 sdk/vehicle-settings.js + sdk/vehicle-media.js 内联成 __carModels，
 *      再接 tools/car-ui/api-car-ui.js 的交互代码（复用 api.html 里的 request/state）。
 *
 * 用法：
 *   node tools/patch-api-car-ui.mjs            # 就地改写 api.html
 *   node tools/patch-api-car-ui.mjs --check    # 只检查是否已注入
 */

import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const target = resolve(root, 'api.html');
const uiDir = resolve(root, 'tools/car-ui');

const CSS_BEGIN = '/* ==== ZEEHO_CAR_UI:CSS:BEGIN ==== */';
const CSS_END = '/* ==== ZEEHO_CAR_UI:CSS:END ==== */';
const HTML_BEGIN = '  <!-- ==== ZEEHO_CAR_UI:HTML:BEGIN ==== -->';
const HTML_END = '  <!-- ==== ZEEHO_CAR_UI:HTML:END ==== -->';
const JS_BEGIN = '/* ==== ZEEHO_CAR_UI:JS:BEGIN ==== */';
const JS_END = '/* ==== ZEEHO_CAR_UI:JS:END ==== */';

const SETTINGS_EXPORTS = [
  'CONTROL', 'deriveStateKey', 'deriveFunctionCode', 'buildSettingsModel',
  'buildWriteBody', 'coerceValue', 'isDirty', 'summarize',
];
const MEDIA_EXPORTS = [
  'parseJsonField', 'normalizeWallpaperPage', 'normalizeWallpaperDetail', 'wallpapersFromHome',
  'normalizeProgress', 'buildUseWallpaperBody', 'wallpaperImages', 'normalizeOtaInfo',
  'normalizeOtaFile', 'normalizeCheckVersion', 'normalizeOtaList', 'normalizeOtaRecord',
  'normalizeOtaStatus', 'findOtaFiles', 'maskWifiAddress', 'maskSignedUrl', 'formatBytes',
  'parseWallpaperAreas', 'buildWallpaperContent',
];

/** 把 ES module 源码改成 IIFE 内部代码：去掉 import/export 关键字与默认导出。 */
function toIifeBody(source) {
  return source
    // 默认导出对象是本文件的汇总，内联时丢掉（IIFE 结尾会显式 return 需要的那几个）。
    .replace(/^export\s+default\s*\{[\s\S]*?\n\};\s*$/m, '')
    .replace(/^export\s+/gm, '')
    .replace(/^import[\s\S]*?;\s*$/gm, '')
    .trim();
}

function buildModels(settingsSource, mediaSource) {
  return `${JS_BEGIN}
/* ===== 内联数据模型（源文件：sdk/vehicle-settings.js、sdk/vehicle-media.js）=====
   api.html 是自包含单文件产物，所以模型在构建时内联；改动请改 sdk/ 下的源文件后重跑
   node tools/patch-api-car-ui.mjs，不要直接改这一段。 */
const __carModels = (() => {
  const settings = (() => {
${toIifeBody(settingsSource)}
    return { ${SETTINGS_EXPORTS.join(', ')} };
  })();
  const media = (() => {
${toIifeBody(mediaSource)}
    return { ${MEDIA_EXPORTS.join(', ')} };
  })();
  return { ...settings, ...media };
})();
`;
}

function stripBlocks(html) {
  // 用 indexOf 精确剥离（正则的非贪婪匹配会被内容里的同名标记干扰，踩过）。
  let out = html;
  for (const [begin, end] of [[CSS_BEGIN, CSS_END], [HTML_BEGIN, HTML_END], [JS_BEGIN, JS_END]]) {
    let guard = 0;
    while (guard += 1) {
      const b = out.indexOf(begin);
      if (b < 0) break;
      const e = out.indexOf(end, b + begin.length);
      if (e < 0) break;
      let start = b;
      let stop = e + end.length;
      if (out[stop] === '\n') stop += 1;
      if (out[start - 1] === '\n') start -= 1;
      out = out.slice(0, start) + out.slice(stop);
      if (guard > 8) break;
    }
  }
  return out;
}

function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function insertAfterSummaryCard(html, block) {
  const anchor = html.indexOf('id="summary-card"');
  if (anchor < 0) throw new Error('api.html 里找不到 id="summary-card"');
  const close = html.indexOf('</section>', anchor);
  if (close < 0) throw new Error('summary-card 没有闭合的 </section>');
  const at = close + '</section>'.length;
  return `${html.slice(0, at)}\n${block}${html.slice(at)}`;
}

function insertBeforeStyleEnd(html, block) {
  const at = html.indexOf('</style>');
  if (at < 0) throw new Error('api.html 里找不到 </style>');
  return `${html.slice(0, at)}\n${block}${html.slice(at)}`;
}

function insertBeforeEvents(html, block) {
  const marker = '/* ---------------------------- 事件 ---------------------------- */';
  const at = html.indexOf(marker);
  if (at < 0) throw new Error('api.html 里找不到事件区标记');
  return `${html.slice(0, at)}\n${block}${html.slice(at)}`;
}

async function main() {
  const checkOnly = process.argv.includes('--check');
  const [htmlRaw, css, htmlBlock, js, settingsSource, mediaSource] = await Promise.all([
    readFile(target, 'utf8'),
    readFile(resolve(uiDir, 'api-car-ui.css'), 'utf8'),
    readFile(resolve(uiDir, 'api-car-ui.html'), 'utf8'),
    readFile(resolve(uiDir, 'api-car-ui.js'), 'utf8'),
    readFile(resolve(root, 'sdk/vehicle-settings.js'), 'utf8'),
    readFile(resolve(root, 'sdk/vehicle-media.js'), 'utf8'),
  ]);

  const already = htmlRaw.includes(CSS_BEGIN) && htmlRaw.includes(HTML_BEGIN) && htmlRaw.includes(JS_BEGIN);
  if (checkOnly) {
    console.log(already ? 'api.html 已含车辆工具区块。' : 'api.html 缺少车辆工具区块。');
    process.exitCode = already ? 0 : 2;
    return;
  }

  const baseline = stripBlocks(htmlRaw);
  const models = buildModels(settingsSource, mediaSource);
  // 三个块统一以「BEGIN…END + 结尾换行」结尾，插入时统一在前面补一个换行，
  // stripBlocks 就按同样规则反向剥离，保证重跑幂等。
  const ensure = (text) => `${text.replace(/\s+$/, '')}\n`;
  const cssBlock = ensure(css);
  const htmlSection = ensure(htmlBlock);
  const jsBlock = ensure(`${models}${js}\n${JS_END}`);
  let next = insertAfterSummaryCard(baseline, htmlSection);
  next = insertBeforeStyleEnd(next, cssBlock);
  next = insertBeforeEvents(next, jsBlock);
  await writeFile(target, next, 'utf8');
  console.log(`${already ? '已更新' : '已注入'}车辆工具区块：${target}`);
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
