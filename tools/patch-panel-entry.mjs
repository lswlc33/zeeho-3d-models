#!/usr/bin/env node
/**
 * 给面板 index.html 注入「车机工具」（车机壁纸 / OTA / 车辆设置）。
 *
 * 为什么是注入：index.html 是 dashboard 的混淆构建产物，上游每次 release 整份覆盖，
 * 所以入口以一个可重放的块维护（幂等：已存在就先按标记剥掉再插入）。
 *
 * 注入内容（全部塞在 </body> 前）：
 *   1. <style>：panel-sheet.css（全屏 sheet + 入口样式）+ api-car-ui.css（三块卡片样式）；
 *   2. HTML：全屏 sheet 外壳 + 三块卡片（来自 tools/car-ui/api-car-ui.html 的卡片段）
 *      + 两个 dialog（同一文件 SPLIT 标记之后，必须放在 body 级，不能嵌在 sheet 里）；
 *   3. <script type="module">：数据模型（sdk/ 内联）+ panel-host.js（借面板签名发请求）
 *      + api-car-ui.js（三块 UI，与 api.html 共用同一份源码）+ panel-sheet.js（入口与开关）。
 *
 * 入口有两处：顶栏「车机」按钮（observer 补回）、「车辆设置」页末尾的「车机工具」卡片行。
 *
 * 用法：
 *   node tools/patch-panel-entry.mjs            # 就地注入 index.html
 *   node tools/patch-panel-entry.mjs --check    # 只检查
 */

import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const target = resolve(root, 'index.html');
const uiDir = resolve(root, 'tools/car-ui');

const BEGIN = '<!-- ==== ZEEHO_PANEL_CAR:BEGIN ==== -->';
const END = '<!-- ==== ZEEHO_PANEL_CAR:END ==== -->';

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
  // 壁纸「本地定位」几何：预览与导出共用同一份 transform（见 sdk/vehicle-media.js）
  'wallpaperCoverScale', 'clampWallpaperZoom', 'wallpaperFitTransform', 'wallpaperLayerStyle',
  'clampWallpaperPan', 'wallpaperSpeedCardBox', 'WALLPAPER_ZOOM_MIN', 'WALLPAPER_ZOOM_MAX',
];

function toIifeBody(source) {
  return source
    .replace(/^export\s+default\s*\{[\s\S]*?\n\};\s*$/m, '')
    .replace(/^export\s+/gm, '')
    .replace(/^import[\s\S]*?;\s*$/gm, '')
    .trim();
}

function buildModels(settingsSource, mediaSource) {
  return `/* ===== 内联数据模型（源文件：sdk/vehicle-settings.js、sdk/vehicle-media.js）===== */
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

function stripBlock(html) {
  let out = html;
  for (let guard = 0; guard < 8; guard += 1) {
    const start = out.indexOf(BEGIN);
    if (start < 0) break;
    const end = out.indexOf(END, start + BEGIN.length);
    if (end < 0) break;
    let from = start;
    let to = end + END.length;
    if (out[to] === '\n') to += 1;
    if (out[from - 1] === '\n') from -= 1;
    out = out.slice(0, from) + out.slice(to);
  }
  return out;
}

async function main() {
  const checkOnly = process.argv.includes('--check');
  const [htmlRaw, sheetCss, cardsCss, panelJs, hostJs, uiJs, cardsHtml, settingsSource, mediaSource] = await Promise.all([
    readFile(target, 'utf8'),
    readFile(resolve(uiDir, 'panel-sheet.css'), 'utf8'),
    readFile(resolve(uiDir, 'api-car-ui.css'), 'utf8'),
    readFile(resolve(uiDir, 'panel-sheet.js'), 'utf8'),
    readFile(resolve(uiDir, 'panel-host.js'), 'utf8'),
    readFile(resolve(uiDir, 'api-car-ui.js'), 'utf8'),
    readFile(resolve(uiDir, 'api-car-ui.html'), 'utf8'),
    readFile(resolve(root, 'sdk/vehicle-settings.js'), 'utf8'),
    readFile(resolve(root, 'sdk/vehicle-media.js'), 'utf8'),
  ]);

  const already = htmlRaw.includes(BEGIN);
  if (checkOnly) {
    console.log(already ? 'index.html 已含车机工具注入块。' : 'index.html 缺少车机工具注入块。');
    process.exitCode = already ? 0 : 2;
    return;
  }

  const splitAt = cardsHtml.indexOf('ZEEHO_CAR_UI:SPLIT');
  if (splitAt < 0) throw new Error('api-car-ui.html 缺少 SPLIT 标记（卡片与弹层要分开注入）');
  // 切在 SPLIT 注释的**开头**：直接切在 marker 文本会把 `<!--` 留成未闭合注释，
  // 后面整段（含 dialog）都会被当注释吞掉（已踩）。
  const cut = cardsHtml.lastIndexOf('<!--', splitAt);
  const cardsOnly = cardsHtml.slice(0, cut < 0 ? splitAt : cut)
    .replace(/^\s*<!-- ==== ZEEHO_CAR_UI:HTML:BEGIN ==== -->\s*$/m, '')
    .trimEnd();
  const dialogs = cardsHtml.slice(cardsHtml.indexOf('<dialog id="car-dialog">')).trimEnd();

  const models = buildModels(settingsSource, mediaSource);
  const css = `${sheetCss.trim()}\n${cardsCss.trim()}`;
  const block = `${BEGIN}
<style id="ze-car-style">
${css}
</style>

${cardsOnly}

${dialogs}

<script type="module">
${models}
${hostJs.trim()}
${uiJs.trim()}
${panelJs.trim()}
</script>
${END}
`;

  const baseline = stripBlock(htmlRaw);
  if (!baseline.includes('</body>')) throw new Error('index.html 里找不到 </body>');
  // 块前补一个换行，stripBlock 会按同样规则剥掉，保证重跑逐字节一致。
  const next = baseline.replace('</body>', `\n${block}</body>`);
  await writeFile(target, next, 'utf8');
  console.log(`${already ? '已更新' : '已注入'}车机工具注入块：${target}`);
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
