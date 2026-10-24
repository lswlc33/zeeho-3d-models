#!/usr/bin/env node
/**
 * 极核接口命令行（Node 18+，零依赖）。
 *
 * 与 api.html 共用 sdk/zeeho-api.js：同一套签名、同一套端点目录。适合：
 *   · 在服务器/CI 上直接查车况、拉 OTA 固件；
 *   · 没有浏览器、或者需要把结果管道给 jq 的场景；
 *   · 给仓库加车模时，从 vehicleHomePageV2 的 vehiclePic3dUrl 直接落盘。
 *
 * 凭据（不随仓库分发，按需通过环境变量或未跟踪的 config.local.json 注入）：
 *   ZEEHO_BASE / ZEEHO_APP_ID / ZEEHO_APP_SECRET / ZEEHO_BASIC / ZEEHO_TOKEN
 *
 * 用法：
 *   node tools/zeeho-cli.mjs login --phone 10000000000
 *   node tools/zeeho-cli.mjs login --phone 10000000000 --code 123456
 *   node tools/zeeho-cli.mjs vehicle --vin TESTVIN0000000001
 *   node tools/zeeho-cli.mjs ota --vin TESTVIN0000000001
 *   node tools/zeeho-cli.mjs ota --vin ... --download ./firmware
 *   node tools/zeeho-cli.mjs watch --vin ... --every 600 --hours 2
 *   node tools/zeeho-cli.mjs models --vin ... --out models
 *   node tools/zeeho-cli.mjs call ota_info --query vinNo=... --json
 *   node tools/zeeho-cli.mjs call /v1.0/app/cfmotoserverapp/vehicle/list
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { parseArgs } from 'node:util';
import { createInterface } from 'node:readline/promises';
import { fileURLToPath } from 'node:url';

import { ZeehoClient, DEFAULT_BASE, pickVehicleSummary } from '../sdk/zeeho-api.js';
import { ENDPOINTS, byId, fillPath } from '../sdk/endpoints.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TOKEN_FILE = path.join(process.env.HOME || process.env.USERPROFILE || ROOT, '.zeeho_token.json');
const LOCAL_CONFIG = path.join(ROOT, 'config.local.json');

function readJson(file, fallback = null) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; }
}

function loadConfig() {
  const fileConfig = readJson(LOCAL_CONFIG, {}) || {};
  return {
    base: process.env.ZEEHO_BASE || fileConfig.base || DEFAULT_BASE,
    appId: process.env.ZEEHO_APP_ID || fileConfig.appId || '',
    appSecret: process.env.ZEEHO_APP_SECRET || fileConfig.appSecret || '',
    basic: process.env.ZEEHO_BASIC || fileConfig.basic || '',
    token: process.env.ZEEHO_TOKEN || readJson(TOKEN_FILE, {})?.access_token || '',
  };
}

function saveToken(token) {
  fs.writeFileSync(TOKEN_FILE, JSON.stringify({ access_token: token, saved_at: new Date().toISOString() }, null, 2), { mode: 0o600 });
  try { fs.chmodSync(TOKEN_FILE, 0o600); } catch { /* Windows */ }
}

function makeClient(config) {
  return new ZeehoClient({
    base: config.base,
    appId: config.appId,
    appSecret: config.appSecret,
    basic: config.basic,
    token: config.token,
  });
}

function fail(message) {
  console.error(`✗ ${message}`);
  process.exitCode = 1;
}

function requireCredentials(config) {
  if (!config.appId || !config.appSecret) {
    fail('缺少 ZEEHO_APP_ID / ZEEHO_APP_SECRET（或 config.local.json 里的 appId/appSecret）');
    return false;
  }
  return true;
}

function printJson(value) {
  console.log(JSON.stringify(value, null, 2));
}function maskUrl(value) {
  const text = String(value || '');
  try {
    const url = new URL(text);
    for (const key of [...url.searchParams.keys()]) {
      if (/^(sign|signature|token|pwd|expires|ossaccesskeyid|security-token)$/i.test(key)) {
        url.searchParams.set(key, '***');
      }
    }
    return url.toString();
  } catch {
    return text.replace(/(^|[?&])(sign|signature|token|pwd|password|expires|ossaccesskeyid)=[^&]*/gi, '$1$2=***');
  }
}

function redactSensitive(value, showSecrets = false) {
  if (showSecrets || value == null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map((item) => redactSensitive(item, false));
  const out = {};
  for (const [key, child] of Object.entries(value)) {
    if (key === 'encryptInfo' && child && typeof child === 'object') {
      out[key] = { present: true, keys: Object.keys(child) };
    } else if (key === 'location' && child && typeof child === 'object') {
      out[key] = { present: true };
    } else if (typeof child === 'string' && /(?:[?&](?:pwd|password|token|sign|signature|expires|ossaccesskeyid)=)/i.test(child)) {
      out[key] = maskUrl(child);
    } else {
      out[key] = redactSensitive(child, false);
    }
  }
  return out;
}



function unwrap(result) {
  if (!result.data) {
    fail(`HTTP ${result.status}：${result.text.slice(0, 200)}`);
    return null;
  }
  if (result.data.code !== '10000') {
    fail(`业务错误 ${result.data.code}：${result.data.message || ''}`);
    return null;
  }
  return result.data.data;
}

/* ------------------------------- 命令 ------------------------------- */

async function cmdLogin(options, config) {
  if (!requireCredentials(config)) return;
  const phone = options.phone;
  if (!/^\d{11}$/.test(phone || '')) return fail('--phone 需要 11 位手机号');
  const client = makeClient(config);

  if (!options.code) {
    const sent = await client.authCode(phone);
    if (sent.data?.code !== '10000') {
      console.error(`发送验证码失败：${sent.data?.message || sent.text.slice(0, 120)}`);
      if (sent.data?.code === '30121') {
        console.error('提示：面板 appId 没有 authCode/{phone} 权限，请改用 App 3.0.6 的 appId。');
      }
      return;
    }
    console.log(`验证码已发送到 ${phone}（${sent.data?.message || 'ok'}）`);
  }

  let code = options.code;
  if (!code) {
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    code = (await rl.question('请输入 6 位验证码：')).trim();
    rl.close();
  }
  const login = await client.loginByPhone(phone, code);
  const token = ZeehoClient.extractToken(login.data);
  if (!token) {
    return fail(`登录失败：${login.data?.message || login.text.slice(0, 160)}`);
  }
  saveToken(token);
  console.log(`✓ 登录成功，token 已保存到 ${TOKEN_FILE}`);
  const list = await client.vehicleList(token);
  const vehicles = list.data?.data || [];
  if (vehicles.length) {
    console.log('车辆：');
    for (const v of vehicles) console.log(`  ${v.vehicleName || '?'}  ${v.vinNo}  ${v.vehicleType || ''}`);
  }
}

async function cmdVehicle(options, config) {
  const client = makeClient(config);
  if (!client.token) return fail('缺少 token：先 login，或设置 ZEEHO_TOKEN');
  const list = await client.vehicleList(client.token);
  const vehicles = unwrap(list);
  if (!vehicles) return;
  if (!options.json) {
    console.log('车辆列表：');
    for (const v of vehicles) console.log(`  ${v.vehicleName || '?'}\t${v.vinNo}\t${v.vehicleType || ''}`);
  }

  const vin = options.vin || vehicles[0]?.vinNo;
  if (!vin) return;
  const home = await client.vehicleHome(vin, client.token);
  const data = unwrap(home);
  if (!data) {
    console.error('提示：浏览器/Node 直接请求 vehicleHomePageV2 需要 User-Agent: okhttp/4.9.2；本 CLI 已带上。');
    return;
  }
  const summary = pickVehicleSummary(data);
  if (options.json) {
    if (!summary.name) summary.name = vehicles[0]?.vehicleName || '';
    return printJson(redactSensitive({ vehicles, summary }, Boolean(options['show-secrets'])));
  }
  console.log(`\n车况（${summary.name} · ${summary.typeName}）：`);
  console.log(`  在线状态   ${summary.online || '—'}`);
  console.log(`  电量       ${summary.soc == null ? '—' : `${summary.soc}%`}`);
  console.log(`  续航       ${summary.rangeKm == null ? '—' : `${summary.rangeKm} km`}`);
  console.log(`  总里程     ${summary.totalKm == null ? '—' : `${summary.totalKm} km`}`);
  console.log(`  本月骑行   ${summary.monthKm == null ? '—' : `${summary.monthKm} km / ${summary.monthMinutes || 0} min`}`);
  console.log(`  OTA 版本   ${summary.otaVersion || '—'}`);
  console.log(`  3D 车模    ${summary.pic3d || '（服务端未下发 vehiclePic3dUrl）'}`);
  if (summary.encryptInfo) console.log('  投屏资料   已取到（key/iv/encryptValue），按需单独导出，别贴到公开渠道。');

}

async function cmdOta(options, config) {
  const client = makeClient(config);
  if (!client.token) return fail('缺少 token：先 login，或设置 ZEEHO_TOKEN');
  const vin = options.vin;
  if (!vin) return fail('--vin 必填');

  const [info, records, content] = await Promise.all([
    client.otaInfo(vin, client.token),
    client.otaRecord(vin, client.token),
    client.otaContent(vin, client.token),
  ]);
  const data = unwrap(info);
  if (!data) return;
  const file = data.fileInfo || {};
  if (options.json) {
    return printJson(redactSensitive(
      { info: data, records: records.data?.data, content: content.data?.data },
      Boolean(options['show-secrets']),
    ));
  }

  console.log(`仪表 OTA（${vin}）`);
  console.log(`  upgradeStatus   ${data.upgradeStatus}`);
  console.log(`  softwareVersion ${data.softwareVersion || '—'}`);
  console.log(`  appVersion      ${data.appVersion || '—'}`);
  console.log(`  recordId        ${data.recordId || '—'}`);
  console.log(`  otaVersion      ${data.otaVersion || '—'}`);
  if (data.wifiAddress) console.log(`  wifiAddress     ${maskUrl(data.wifiAddress)}`);
  if (file.fileUrl) {
    console.log(`  file            ${file.fileName || '?'}  ${file.fileSize || '?'} bytes  firmwareId=${file.firmwareId || '?'}`);
    console.log(`  fileUrl         ${options['show-secrets'] ? file.fileUrl : maskUrl(file.fileUrl)}`);
    if (options.download) {
      const saved = await download(file.fileUrl, path.resolve(options.download), file.fileName || `${vin}.bin`);
      console.log(`  ✓ 已下载 ${saved}`);
    }
  } else {
    console.log('  fileInfo        null（服务端当前没有给这台车下发 OTA 包）');
  }
  const rec = records.data?.data;
  console.log(`  历史记录        ${Array.isArray(rec) ? rec.length : 0} 条`);
  const c = content.data?.data;
  if (c && (c.title || c.content)) console.log(`  红点内容        ${c.title || ''} ${String(c.content || '').slice(0, 60)}`);
}

async function cmdWallpaper(options, config) {
  const client = makeClient(config);
  if (!client.token) return fail('缺少 token：先 login，或设置 ZEEHO_TOKEN');
  const vin = options.vin;
  if (!vin) return fail('--vin 必填');

  if (options.id) {
    const [detail, vehicles] = await Promise.all([
      client.wallpaperDetail(options.id, vin, client.token),
      client.wallpaperVehicles(options.id, client.token),
    ]);
    const data = detail.data?.data;
    const carTypes = vehicles.data?.data;
    if (options.json) {
      return printJson(redactSensitive({ detail: data, vehicles: carTypes }, Boolean(options['show-secrets'])));
    }
    if (!data) return fail(`壁纸详情读取失败：${detail.data?.message || detail.text.slice(0, 120)}`);
    console.log(`壁纸 #${data.id} ${data.wallpaperName || ''}`);
    console.log(`  使用状态   ${data.useState === 1 ? '使用中' : data.useState}`);
    console.log(`  显示设置   ${data.displayWallpaperSet}`);
    console.log(`  创建时间   ${data.createTime || '—'}`);
    console.log(`  适用车型   ${Array.isArray(carTypes) ? carTypes.join(', ') : '—'}`);
    if (data.wallpaperContent) {
      try {
        const items = JSON.parse(data.wallpaperContent);
        for (const item of items) console.log(`  ${String(item.type) === '1' ? '小屏' : '主页'}  ${item.url}`);
      } catch { console.log(`  wallpaperContent  ${String(data.wallpaperContent).slice(0, 120)}`); }
    }
    return;
  }

  const [count, page] = await Promise.all([
    client.wallpaperCount(vin, client.token),
    client.wallpaperPage(vin, { pageNum: 1, pageSize: 20 }, client.token),
  ]);
  if (options.json) {
    return printJson(redactSensitive({ count: count.data?.data, page: page.data?.data }, Boolean(options['show-secrets'])));
  }
  const list = page.data?.data?.list || [];
  console.log(`壁纸数量：${count.data?.data ?? '—'}；本页 ${list.length} 条`);
  for (const item of list) {
    console.log(`  #${item.id}\t${item.wallpaperName || '?'}\t${item.useState === 1 ? '使用中' : '未使用'}\t${item.createTime || ''}`);
  }
  console.log('用 --id <壁纸ID> 查看详情；写操作（use/delete/upload）请用 call + --yes。');
}

async function cmdWatch(options, config) {
  const client = makeClient(config);
  if (!client.token) return fail('缺少 token：先 login，或设置 ZEEHO_TOKEN');
  const vin = options.vin;
  if (!vin) return fail('--vin 必填');
  const everyRaw = Number(options.every || 600);
  const hoursRaw = Number(options.hours || 2);
  const every = Number.isFinite(everyRaw) ? Math.max(30, everyRaw) : 600;
  const hours = Number.isFinite(hoursRaw) ? Math.max(0.1, hoursRaw) : 2;
  const deadline = Date.now() + (options.once ? 0 : hours * 3600 * 1000);

  while (true) {
    const stamp = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    const result = await client.otaInfo(vin, client.token);
    const data = result.data?.data;
    if (result.data?.code !== '10000') {
      console.log(`[${stamp}] 查询失败：${result.data?.message || result.text.slice(0, 80)}`);
    } else if (data?.fileInfo?.fileUrl) {
      const file = data.fileInfo;
      console.log(`[${stamp}] ★ 发现固件包：${file.fileName} ${file.fileSize} bytes firmwareId=${file.firmwareId}`);
      const saved = await download(file.fileUrl, path.resolve(options.download || '.'), file.fileName || `${vin}.bin`);
      console.log(`[${stamp}] ✓ 已下载 ${saved}`);
      return;
    } else {
      console.log(`[${stamp}] 暂无包（upgradeStatus=${data?.upgradeStatus}, softwareVersion=${data?.softwareVersion || '—'}）`);
    }
    if (options.once || Date.now() + every * 1000 > deadline) return;
    await new Promise((resolve) => setTimeout(resolve, every * 1000));
  }
}

async function download(url, directory, fileName) {
  fs.mkdirSync(directory, { recursive: true });
  const target = path.join(directory, path.basename(fileName || 'firmware.bin'));
  const response = await fetch(url, { headers: { 'User-Agent': 'okhttp/4.9.2' } });
  if (!response.ok) throw new Error(`下载失败 HTTP ${response.status}`);
  const maxBytes = 512 * 1024 * 1024;
  const contentLength = Number(response.headers.get('content-length') || 0);
  if (contentLength > maxBytes) throw new Error(`文件过大：${contentLength} bytes（上限 512MB）`);
  const buffer = Buffer.from(await response.arrayBuffer());
  if (buffer.length > maxBytes) throw new Error(`文件过大：${buffer.length} bytes（上限 512MB）`);
  fs.writeFileSync(target, buffer);
  const hash = crypto.createHash('sha256').update(buffer).digest('hex');
  console.log(`    sha256 ${hash}`);
  return `${target}（${buffer.length.toLocaleString()} bytes）`;
}

async function cmdModels(options, config) {
  const client = makeClient(config);
  if (!options.url && !client.token) return fail('缺少 token：先 login，或设置 ZEEHO_TOKEN');
  let url = options.url;
  let name = options.name;

  if (!url) {
    const home = await client.vehicleHome(options.vin, client.token);
    const data = unwrap(home);
    if (!data) return;
    url = data.vehiclePic3dUrl;
    name = name || `${data.vehicleName || data.vehicleType || options.vin}-3d`;
    if (!url) {
      console.log('这台车的 vehicleHomePageV2 没有返回 vehiclePic3dUrl（服务端未配置 3D 车模）。');
      console.log('可以用 --url 直接导入一个 glb 地址。');
      return;
    }
  }
  if (!/^https?:\/\//.test(url)) return fail('--url 需要 http(s) 地址');
  name = name || path.basename(new URL(url).pathname).replace(/\.[^.]+$/, '');
  const outDir = path.resolve(options.out || path.join(ROOT, 'models'));
  const saved = await download(url, outDir, `${name}.glb`);
  const file = path.basename(saved.split('（')[0]);
  const size = fs.statSync(path.join(outDir, file)).size;
  const indexPath = path.join(ROOT, 'index.json');
  const index = readJson(indexPath, { ver: 1, models: [] });
  index.models = index.models || [];
  const existing = index.models.find((m) => m.file === file || m.id === name);
  if (existing) {
    existing.size = size;
    existing.ver = Number(existing.ver || 1) + 1;
  } else {
    index.models.push({
      id: name,
      name: options.title || name,
      file,
      size,
      ver: 1,
      note: options.note || '从极核接口 vehiclePic3dUrl 导入的官方车模',
    });
  }
  index.updated = new Date().toISOString().slice(0, 10);
  fs.writeFileSync(indexPath, `${JSON.stringify(index, null, 2)}\n`);
  console.log(`✓ 已写入 ${path.relative(ROOT, path.join(outDir, file))}，并更新 index.json`);
  console.log('提示：单个文件需小于 20MB（jsDelivr 限制）；提交前用 git diff 看一眼 index.json。');
}

async function cmdCall(options, config) {
  const client = makeClient(config);
  const target = options.target;
  if (!target) return fail('用法：call <接口 id | 路径> [--query k=v ...] [--body JSON]');

  const endpoint = byId(target);
  if (endpoint?.danger && !options.yes) {
    return fail(`这是写操作/发短信端点（${endpoint.id}），确认后加 --yes 再执行`);
  }
  if (endpoint?.auth !== 'anonymous' && !client.token) {
    return fail('缺少 token：先 login，或设置 ZEEHO_TOKEN');
  }
  const query = { ...(options.query || {}) };
  const values = { ...query };
  const method = (options.method || endpoint?.method || 'GET').toUpperCase();
  let requestPath = endpoint ? endpoint.path : target;

  if (endpoint) {
    for (const param of endpoint.params || []) {
      if (param.in === 'path' && query[param.key]) values[param.key] = query[param.key];
    }
    requestPath = fillPath(requestPath, values);
  }
  // path 参数已经填进 URL，不要再作为 query 重复发送。
  if (endpoint) {
    for (const param of endpoint.params || []) {
      if (param.in === 'path') delete query[param.key];
    }
  }
  const body = options.body ? JSON.parse(options.body) : undefined;
  const result = await client.request({ path: requestPath, method, query, body, token: client.token });
  if (options.json) {
    const payload = result.data ?? { status: result.status, text: result.text };
    return printJson(redactSensitive(payload, Boolean(options['show-secrets'])));
  }
  if (result.data) {
    console.log(`HTTP ${result.status} · code ${result.data.code} · ${result.data.message}`);
    printJson(result.data);
  } else {
    console.log(`HTTP ${result.status}`);
    console.log(result.text);
  }
}

/* ------------------------------- 入口 ------------------------------- */

const HELP = `极核接口 CLI

用法：
  node tools/zeeho-cli.mjs <命令> [参数]

命令：
  login    --phone <手机号> [--code <验证码>]        发码并登录，token 存到 ~/.zeeho_token.json
  vehicle  [--vin <VIN>] [--json]                    车辆列表 + 车况概览
  ota      --vin <VIN> [--download <目录>] [--json]   仪表 OTA 信息 / 固件包 / 历史
  wallpaper --vin <VIN> [--id <壁纸ID>] [--json]      壁纸数量/列表/详情
  watch    --vin <VIN> [--every 600] [--hours 2] [--once] [--download <目录>]
  models   [--vin <VIN> | --url <glb 地址>] [--name/--title/--note] [--out models]
  call     <接口 id | 路径> [--query k=v ...] [--body JSON] [--method GET] [--json] [--yes]

  --show-secrets  保留 encryptInfo / location / wifi 密码 / OTA fileUrl 原文（默认打码）

凭据（环境变量或未跟踪的 config.local.json）：
  ZEEHO_BASE  ZEEHO_APP_ID  ZEEHO_APP_SECRET  ZEEHO_BASIC  ZEEHO_TOKEN

接口目录见 sdk/endpoints.js，文档见 docs/API-3.0.6.md。
`;

async function main() {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: {
      phone: { type: 'string' },
      code: { type: 'string' },
      vin: { type: 'string' },
      id: { type: 'string' },
      download: { type: 'string' },
      every: { type: 'string' },
      hours: { type: 'string' },
      once: { type: 'boolean' },
      url: { type: 'string' },
      name: { type: 'string' },
      title: { type: 'string' },
      note: { type: 'string' },
      out: { type: 'string' },
      query: { type: 'string', multiple: true },
      body: { type: 'string' },
      method: { type: 'string' },
      json: { type: 'boolean' },
      yes: { type: 'boolean' },
      'show-secrets': { type: 'boolean' },
      help: { type: 'boolean', short: 'h' },
    },
  });

  const options = { ...values };
  if (options.query) {
    options.query = Object.fromEntries(options.query.map((pair) => {
      const index = pair.indexOf('=');
      return index < 0 ? [pair, ''] : [pair.slice(0, index), pair.slice(index + 1)];
    }));
  }

  const command = positionals[0];
  if (!command || options.help) {
    console.log(HELP);
    return;
  }
  options.target = positionals[1];

  const config = loadConfig();
  switch (command) {
    case 'login': return cmdLogin(options, config);
    case 'vehicle': return cmdVehicle(options, config);
    case 'ota': return cmdOta(options, config);
    case 'wallpaper': return cmdWallpaper(options, config);
    case 'watch': return cmdWatch(options, config);
    case 'models': return cmdModels(options, config);
    case 'call': return cmdCall(options, config);
    default:
      console.log(HELP);
      process.exitCode = 64;
  }
}

main().catch((error) => {
  console.error(`✗ ${error.message}`);
  process.exitCode = 1;
});
