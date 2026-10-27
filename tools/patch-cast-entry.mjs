#!/usr/bin/env node
/**
 * 把「车机投屏」注入面板 index.html（幂等）。
 *
 * 为什么是注入：index.html 是 dashboard 的混淆构建产物，上游每次 release 整份覆盖，
 * 所以入口以一个可重放的块维护（已存在就先按标记剥掉再插入，重跑逐字节一致）。
 *
 * 注入内容（全部塞在 </body> 前，样式与脚本完全自包含）：
 *   1. <style id="ze-cast-style">：tools/car-ui/cast-ui.css；
 *   2. 卡片 + 扫码弹层：tools/car-ui/cast-ui.html；
 *   3. <script type="module">：sdk/vehicle-cast.js（内联成 __castModels）+ tools/car-ui/cast-ui.js。
 *
 * 与壁纸/OTA 那套注入块（tools/patch-panel-entry.mjs）互不依赖，两者的标记块可以共存。
 *
 * 用法：
 *   node tools/patch-cast-entry.mjs            # 就地注入 index.html
 *   node tools/patch-cast-entry.mjs --check    # 只检查
 */

import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const target = resolve(root, 'index.html');
const uiDir = resolve(root, 'tools/car-ui');

const BEGIN = '<!-- ==== ZEEHO_CAST:BEGIN ==== -->';
const END = '<!-- ==== ZEEHO_CAST:END ==== -->';

/** sdk/vehicle-cast.js 里页面要用的导出（内联后挂在 __castModels 上）。 */
const CAST_EXPORTS = [
  'CAST_PORTS', 'CAST_LIMITS', 'CAST_PHASES', 'CAST_PHASE_RANK', 'CAST_TIERS',
  'CAST_CONTROL_IDS', 'CAST_MEDIA_CMDS', 'CAST_STATUS_PROBE', 'CAST_AVC_CODEC', 'CAST_LEVEL_IDS',
  'CAST_ERROR_TEXT', 'CAST_DEFAULT_CAPTURE', 'CAST_MAX_RETRIES', 'CAST_LEARN_THRESHOLD',
  'castErrorText', 'maskSecret', 'maskP2pMac', 'redactDiagnostic',
  'parsePairingPayload', 'summarizePairing',
  'encodeControlFrame', 'decodeControlFrame', 'encodeMediaFrame', 'decodeMediaFrame',
  'parseCaptureConfig', 'buildCaptureConfig', 'buildEncoderReply', 'parseEncoderReply',
  'encodeFramePacket', 'decodeFramePacket', 'splitAnnexB', 'hasParameterSets', 'isKeyframe',
  'pickAvcLevel', 'planCapture', 'nextTier', 'shouldDowngrade', 'shouldLearnTier', 'canCastTransition',
  'normalizeCastPhase', 'formatCastBytes', 'formatCastDuration', 'estimateFps',
  'CAST_BRIDGE_GLOBAL', 'CAST_EVENT_SINK', 'CAST_EVENT_NAME',
  'detectCastBridge', 'createCastClient', 'installCastEventSink', 'castSelfTest',
];

/** 去掉 ESM 语法，变成可直接内联的 IIFE 主体。 */
function toIifeBody(source) {
  return source
    .replace(/^export\s+default\s*\{[\s\S]*?\n\};\s*$/m, '')
    .replace(/^export\s+/gm, '')
    .replace(/^import[\s\S]*?;\s*$/gm, '')
    .trim();
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
  const [htmlRaw, css, cardHtml, uiJs, castSource] = await Promise.all([
    readFile(target, 'utf8'),
    readFile(resolve(uiDir, 'cast-ui.css'), 'utf8'),
    readFile(resolve(uiDir, 'cast-ui.html'), 'utf8'),
    readFile(resolve(uiDir, 'cast-ui.js'), 'utf8'),
    readFile(resolve(root, 'sdk/vehicle-cast.js'), 'utf8'),
  ]);

  const already = htmlRaw.includes(BEGIN);
  if (checkOnly) {
    console.log(already ? 'index.html 已含投屏注入块。' : 'index.html 缺少投屏注入块。');
    process.exitCode = already ? 0 : 2;
    return;
  }

  const ui = cardHtml
    .replace(/^\s*<!-- ==== ZEEHO_CAST_UI:HTML:BEGIN ==== -->\s*$/m, '')
    .replace(/^\s*<!-- ==== ZEEHO_CAST_UI:HTML:END ==== -->\s*$/m, '')
    .trim();

  const models = `/* ===== 内联协议模型（源文件：sdk/vehicle-cast.js）===== */
const __castModels = (() => {
${toIifeBody(castSource)}
  return { ${CAST_EXPORTS.join(', ')} };
})();`;

  const block = `${BEGIN}
<style id="ze-cast-style">
${css.trim()}
</style>

${ui}

<script type="module">
${models}
${uiJs.trim()}
</script>
${END}
`;

  const baseline = stripBlock(htmlRaw);
  if (!baseline.includes('</body>')) throw new Error('index.html 里找不到 </body>');
  const next = baseline.replace('</body>', `\n${block}</body>`);
  await writeFile(target, next, 'utf8');
  console.log(`${already ? '已更新' : '已注入'}车机投屏注入块：${target}`);
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
