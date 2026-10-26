/**
 * 壁纸 / OTA 整形层测试：node --test tests/*.test.mjs
 *
 * 夹具形状依据 docs/API-3.0.6.md 第 5.4 / 5.9 / 5.11 节**手写**，
 * 不是真机抓包（仓库里没有 OTA / 壁纸的抓包）：字段名、状态枚举、百分比口径
 * 都可能与真机有差异，测试只固定「文档承诺的形状 + 容错行为」。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import {
  parseJsonField,
  normalizeWallpaperPage,
  normalizeWallpaperDetail,
  wallpapersFromHome,
  normalizeProgress,
  buildUseWallpaperBody,
  wallpaperImages,
  normalizeOtaInfo,
  normalizeOtaFile,
  normalizeCheckVersion,
  normalizeOtaList,
  normalizeOtaRecord,
  normalizeOtaStatus,
  findOtaFiles,
  maskWifiAddress,
  maskSignedUrl,
  formatBytes,
  parseWallpaperAreas,
  buildWallpaperContent,
} from '../sdk/vehicle-media.js';

const FIXTURE_DIR = new URL('./fixtures/media/', import.meta.url);
const fixture = (name) => JSON.parse(readFileSync(fileURLToPath(new URL(name, FIXTURE_DIR)), 'utf8'));

const WALLPAPER_PAGE = fixture('wallpaper-page.json');
const WALLPAPER_HOME = fixture('wallpaper-home.json');
const OTA_INFO = fixture('ota-info.json');
const OTA_INFO_EMPTY = fixture('ota-info-empty.json');
const OTA_LIST = fixture('ota-list.json');

/* ------------------------------ 壁纸 ------------------------------ */

test('parseJsonField：字符串解析、对象直返、坏字符串给 null', () => {
  const obj = { a: 1 };
  assert.equal(parseJsonField(obj), obj);
  assert.deepEqual(parseJsonField('[{"name":"x"}]'), [{ name: 'x' }]);
  assert.equal(parseJsonField('not json'), null);
  assert.equal(parseJsonField(''), null);
  assert.equal(parseJsonField('   '), null);
  assert.equal(parseJsonField(null), null);
  assert.equal(parseJsonField(undefined), null);
  assert.equal(parseJsonField(12), null);
});

test('normalizeWallpaperPage：字符串 / 对象两种 wallpaperContent 都能解析', () => {
  const page = normalizeWallpaperPage(WALLPAPER_PAGE);
  assert.equal(page.total, 2);
  assert.equal(page.pageNum, 1);
  assert.equal(page.pageSize, 20);
  assert.equal(page.hasMore, false);
  assert.equal(page.list.length, 2);

  const [stringContent, objectContent] = page.list;
  // 第一项：wallpaperContent 是 JSON 字符串
  assert.equal(stringContent.id, '100001');
  assert.equal(stringContent.name, '原厂·晨曦橙');
  assert.match(stringContent.imageUrl, /^https:\/\/zeeho-oss\.example\.test\/wallpaper\/100001\.png\?/);
  assert.equal(stringContent.thumbUrl, 'https://zeeho-oss.example.test/wallpaper/100001_thumb.png');
  assert.deepEqual(stringContent.resolution, { width: 1024, height: 600 });
  assert.equal(stringContent.configId, '9001');
  assert.equal(stringContent.usable, true);
  assert.equal(stringContent.raw.id, '100001');

  // 第二项：wallpaperContent 是对象，字段名换成 pic / previewImage / resolution 文本
  assert.equal(objectContent.id, '100002');
  assert.equal(objectContent.imageUrl, 'https://zeeho-oss.example.test/wallpaper/custom/100002.jpg?Expires=1792770000&Signature=Def456Ghi');
  assert.equal(objectContent.thumbUrl, 'https://zeeho-oss.example.test/wallpaper/custom/100002_small.jpg');
  assert.deepEqual(objectContent.resolution, { width: 1024, height: 600 });
  assert.equal(objectContent.usable, true);
});

test('normalizeWallpaperPage：useState 判定（1 / 0 / 字符串 / 缺失）', () => {
  const make = (useState, id) => ({ id, wallpaperName: `w-${id}`, wallpaperContent: '{"imageUrl":"https://a.test/x.png"}', useState: useState });
  const page = normalizeWallpaperPage({
    data: {
      total: 5,
      list: [make('1', 'a'), make(1, 'b'), make('0', 'c'), make(0, 'd'), make(undefined, 'e')],
    },
  });
  assert.deepEqual(page.list.map((item) => item.inUse), [true, true, false, false, false]);
  assert.equal(page.hasMore, false);
});

test('normalizeWallpaperPage：total 缺失 / 空响应都不抛异常', () => {
  const noTotal = normalizeWallpaperPage({
    code: '10000',
    data: { list: [{ id: '1', wallpaperName: 'x', wallpaperContent: '{"imageUrl":"https://a.test/x.png"}' }] },
  });
  assert.equal(noTotal.total, 1, 'total 缺失时退回本页条数');
  assert.equal(noTotal.pageNum, 1);
  assert.equal(noTotal.pageSize, 1);
  assert.equal(noTotal.hasMore, false);

  const empty = normalizeWallpaperPage({});
  assert.deepEqual(empty, { total: 0, pageNum: 1, pageSize: 0, hasMore: false, list: [] });
  assert.deepEqual(normalizeWallpaperPage(null).list, []);
  assert.deepEqual(normalizeWallpaperPage(undefined).list, []);
  assert.deepEqual(normalizeWallpaperPage('boom').list, []);

  // 分页还有下一页
  assert.equal(normalizeWallpaperPage({ data: { total: 50, pageNum: 1, pageSize: 20, list: [] } }).hasMore, true);
});

test('normalizeWallpaperDetail：data 直挂 / 嵌套 wallpaper / 空对象', () => {
  const direct = normalizeWallpaperDetail(WALLPAPER_PAGE.data.list[1]);
  assert.equal(direct.id, '100002');
  assert.match(direct.imageUrl, /100002\.jpg/);

  const nested = normalizeWallpaperDetail({ code: '10000', data: { wallpaper: { id: '7', wallpaperName: 'n', wallpaperContent: { url: 'https://a.test/n.png' } } } });
  assert.equal(nested.id, '7');
  assert.equal(nested.imageUrl, 'https://a.test/n.png');

  assert.equal(normalizeWallpaperDetail({}), null);
  assert.equal(normalizeWallpaperDetail(null), null);
  assert.equal(normalizeWallpaperDetail({ data: null }), null);
});

test('wallpaperImages：接受归一化对象也接受原始条目', () => {
  const page = normalizeWallpaperPage(WALLPAPER_PAGE);
  const fromNormalized = wallpaperImages(page.list[0]);
  assert.equal(fromNormalized.imageUrl, page.list[0].imageUrl);
  assert.equal(fromNormalized.thumbUrl, page.list[0].thumbUrl);

  const fromRaw = wallpaperImages({ wallpaperContent: '{"img":"https://a.test/raw.png"}' });
  assert.equal(fromRaw.imageUrl, 'https://a.test/raw.png');
  assert.equal(fromRaw.thumbUrl, 'https://a.test/raw.png');

  assert.deepEqual(wallpaperImages(null), { imageUrl: '', thumbUrl: '' });
  assert.deepEqual(wallpaperImages({}), { imageUrl: '', thumbUrl: '' });
});

test('wallpapersFromHome：subjectWallpaperConfig 字符串解析 + 主题开关', () => {
  const home = wallpapersFromHome(WALLPAPER_HOME);
  assert.equal(home.supported, true);
  assert.equal(home.width, 1024);
  assert.equal(home.height, 600);
  assert.equal(home.builtin.length, 2);
  assert.deepEqual(home.builtin[0], {
    name: '晨曦',
    type: '1',
    url: 'https://zeeho-oss.example.test/subject/dawn.png',
    subjectUrl: 'https://zeeho-oss.example.test/subject/dawn.json',
  });
  assert.deepEqual(home.theme, { shop: '0', fresh: '0', rgb: '#FF6A00', chargeRgb: '#00C26E' });

  // 坏 JSON / 缺字段：空数组 + false + null 分辨率
  const broken = wallpapersFromHome({ data: { subjectWallpaperConfig: 'not json', supportCustomWallpaper: '0' } });
  assert.deepEqual(broken.builtin, []);
  assert.equal(broken.supported, false);
  assert.equal(broken.width, null);
  assert.equal(broken.height, null);
  assert.deepEqual(wallpapersFromHome(null).theme, { shop: '', fresh: '', rgb: '', chargeRgb: '' });
});

test('normalizeProgress：字段缺失时 done=false，状态/百分比成对识别', () => {
  assert.deepEqual(normalizeProgress({}), { status: '', percent: 0, done: false, message: '' });
  assert.deepEqual(normalizeProgress(null), { status: '', percent: 0, done: false, message: '' });

  const running = normalizeProgress({ code: '10000', data: { status: '1', percent: 35, message: '下发中' } });
  assert.equal(running.status, '1');
  assert.equal(running.percent, 35);
  assert.equal(running.done, false);
  assert.equal(running.message, '下发中');

  assert.equal(normalizeProgress({ data: { status: '3' } }).done, true);
  assert.equal(normalizeProgress({ data: { percent: '120' } }).percent, 100);
  assert.equal(normalizeProgress({ data: { percent: '120' } }).done, true);
});

test('buildUseWallpaperBody：官方 body 只有 id / vinNo，且统一成字符串', () => {
  assert.deepEqual(buildUseWallpaperBody('100001', 'TESTVIN0000000001'), { id: '100001', vinNo: 'TESTVIN0000000001' });
  assert.deepEqual(buildUseWallpaperBody(100001, 'TESTVIN0000000001'), { id: '100001', vinNo: 'TESTVIN0000000001' });
  assert.deepEqual(buildUseWallpaperBody(), { id: '', vinNo: '' });
  assert.deepEqual(buildUseWallpaperBody(null, null), { id: '', vinNo: '' });
});

/* ------------------------------ 打码 ------------------------------ */

test('maskWifiAddress：只打码 pwd，ssid 与其它参数保留', () => {
  assert.equal(
    maskWifiAddress('https://ota.example.test/wifi/setup?ssid=ZEEHO-AE5i-Pro&pwd=Passw0rd&v=2'),
    'https://ota.example.test/wifi/setup?ssid=ZEEHO-AE5i-Pro&pwd=***&v=2',
  );
  assert.equal(maskWifiAddress('https://x.test/a?pwd=abc'), 'https://x.test/a?pwd=***');
  assert.equal(maskWifiAddress('https://x.test/a?ssid=only'), 'https://x.test/a?ssid=only');
  assert.equal(maskWifiAddress(''), '');
  assert.equal(maskWifiAddress(null), '');
  assert.equal(maskWifiAddress(undefined), '');
});

test('maskSignedUrl：OSS 签名参数打码，path 与 fileName 保留', () => {
  const raw = OTA_INFO.data.fileInfo.fileUrl;
  const masked = maskSignedUrl(raw);
  assert.match(masked, /^https:\/\/zeeho-ota\.oss-cn-hangzhou\.aliyuncs\.com\/firmware\/hmi_ae5i_pro_v1\.2\.4\.bin\?/);
  assert.equal(masked.includes('AbCdEf'), false, '不能残留签名值');
  assert.equal(masked.includes('LTAI5tTESTKEY'), false, '不能残留 AccessKeyId');
  assert.equal(masked.includes('test-token'), false, '不能残留 security-token');
  for (const key of ['Expires=***', 'OSSAccessKeyId=***', 'Signature=***', 'security-token=***']) {
    assert.ok(masked.includes(key), `应打码 ${key}，实际：${masked}`);
  }

  // 相对地址走正则兜底，fileName 不动
  assert.equal(
    maskSignedUrl('/firmware/a.bin?sign=1&fileName=a.bin'),
    '/firmware/a.bin?sign=***&fileName=a.bin',
  );
  // 没有 query 原样；空值给 ''
  assert.equal(maskSignedUrl('https://x.test/a.bin'), 'https://x.test/a.bin');
  assert.equal(maskSignedUrl(''), '');
  assert.equal(maskSignedUrl(null), '');
});

test('formatBytes：1024 进制、一位小数、非法值给空串', () => {
  assert.equal(formatBytes(1536), '1.5 KB');
  assert.equal(formatBytes(1024), '1 KB');
  assert.equal(formatBytes(512), '512 B');
  assert.equal(formatBytes(0), '0 B');
  assert.equal(formatBytes(15728640), '15 MB');
  assert.equal(formatBytes('2048'), '2 KB');
  assert.equal(formatBytes(null), '');
  assert.equal(formatBytes(undefined), '');
  assert.equal(formatBytes(-1), '');
  assert.equal(formatBytes('abc'), '');
});

/* ------------------------------ OTA ------------------------------ */

test('normalizeOtaInfo：fileInfo 归一化，wifi 只把打码值放主字段', () => {
  const info = normalizeOtaInfo(OTA_INFO);
  assert.equal(info.present, true);
  assert.equal(info.upgradeStatus, '2');
  assert.equal(info.recordId, '3000001');
  assert.equal(info.softwareVersion, 'V1.2.3');
  assert.equal(info.appVersion, '3.0.6');
  assert.equal(info.otaVersion, 'V1.2.4');

  assert.equal(info.wifi.masked, 'https://ota.example.test/wifi/setup?ssid=ZEEHO-AE5i-Pro&pwd=***&v=2');
  assert.equal(info.wifi.url, OTA_INFO.data.wifiAddress, '原文只允许出现在 url 字段');
  assert.equal(info.wifi.masked.includes('Passw0rd'), false);

  assert.equal(info.file.name, 'hmi_ae5i_pro_v1.2.4.bin');
  assert.equal(info.file.size, 15728640);
  assert.equal(info.file.sizeText, '15 MB');
  assert.equal(info.file.firmwareId, 'FW-AE5I-PRO-124');
  assert.equal(info.file.bucket, 'zeeho-ota');
  assert.equal(info.file.sign, 'd41d8cd98f00b204e9800998ecf8427e');
  assert.equal(info.file.url, OTA_INFO.data.fileInfo.fileUrl);
  assert.equal(info.file.maskedUrl.includes('Signature=***'), true);
  assert.equal(info.file.maskedUrl.includes('AbCdEf'), false);

  assert.ok(info.notes.some((note) => note.includes('预签名直链')));
  assert.ok(info.notes.some((note) => note.includes('Wi-Fi 密码')));
});

test('normalizeOtaInfo：fileInfo:null 是正常状态，不是错误', () => {
  const info = normalizeOtaInfo(OTA_INFO_EMPTY);
  assert.equal(info.present, true);
  assert.equal(info.file, null);
  assert.equal(info.wifi, null);
  assert.equal(info.upgradeStatus, '0');
  assert.ok(info.notes.some((note) => note.includes('没有下发 OTA 包')));
  assert.ok(info.notes.some((note) => note.includes('recordId')));

  const empty = normalizeOtaInfo(null);
  assert.equal(empty.present, false);
  assert.equal(empty.file, null);
  assert.equal(empty.wifi, null);
  assert.deepEqual(normalizeOtaInfo({}).upgradeStatus, '');
});

test('normalizeOtaFile：缺字段 / 空对象 / 包一层的 fileInfo', () => {
  assert.equal(normalizeOtaFile(null), null);
  assert.equal(normalizeOtaFile({}), null);
  assert.equal(normalizeOtaFile('x'), null);

  const wrapped = normalizeOtaFile({ fileInfo: { fileName: 'dir/a.bin', fileUrl: 'https://x.test/a.bin?sign=1', fileSize: 2048 } });
  assert.equal(wrapped.name, 'a.bin');
  assert.equal(wrapped.sizeText, '2 KB');
  assert.equal(wrapped.maskedUrl, 'https://x.test/a.bin?sign=***');
  assert.equal(wrapped.firmwareId, '');
  assert.equal(wrapped.bucket, '');
});

test('normalizeCheckVersion：只认 true/false，版本号来自 ctx', () => {
  assert.equal(normalizeCheckVersion(true).hasUpdate, true);
  assert.equal(normalizeCheckVersion(true).raw, true);
  assert.equal(normalizeCheckVersion('false').hasUpdate, false);
  assert.equal(normalizeCheckVersion({ code: '10000', data: true }).hasUpdate, true);
  assert.equal(normalizeCheckVersion({ data: false }).hasUpdate, false);
  assert.equal(normalizeCheckVersion({}).hasUpdate, false);
  assert.equal(normalizeCheckVersion(null).hasUpdate, false);

  const withCtx = normalizeCheckVersion(true, { currentVersion: 'V1.2.3', targetVersion: 'V1.2.4' });
  assert.equal(withCtx.currentVersion, 'V1.2.3');
  assert.equal(withCtx.targetVersion, 'V1.2.4');
});

test('normalizeOtaList / normalizeOtaRecord：列表与单条记录都能归一化', () => {
  const list = normalizeOtaList(OTA_LIST);
  assert.equal(list.total, 3);
  assert.equal(list.items.length, 3);
  assert.equal(list.items[0].id, '4000001');
  assert.equal(list.items[0].statusText, '升级成功');
  assert.equal(list.items[0].percent, 100);
  assert.equal(list.items[0].createdAt, '2026-09-01 10:00:00');
  assert.equal(list.items[0].file.maskedUrl.includes('Signature=***'), true);
  assert.equal(list.items[1].file, null);
  assert.equal(list.items[1].statusText, '升级失败');
  assert.equal(list.items[1].percent, 45);
  assert.equal(list.items[2].percent, 30);
  assert.equal(list.items[2].statusText, '下载中');

  const single = normalizeOtaRecord({ code: '10000', data: { id: '9', version: 'V1.2.5', status: '3' } });
  assert.equal(single.total, 1);
  assert.equal(single.items.length, 1);
  assert.equal(single.items[0].id, '9');
  assert.equal(single.items[0].version, 'V1.2.5');

  assert.deepEqual(normalizeOtaList(null), { total: 0, items: [] });
  assert.deepEqual(normalizeOtaRecord({}), { total: 0, items: [] });
});

test('normalizeOtaStatus：upstatus 这类 {status,errCode,errDesc} 也吃得下', () => {
  const running = normalizeOtaStatus({ code: '10000', data: { status: 1 } });
  assert.equal(running.status, '1');
  assert.equal(running.done, false);
  assert.equal(running.percent, 0);

  const finished = normalizeOtaStatus({ data: { status: '3', errCode: '0', errDesc: 'ok' } });
  assert.equal(finished.done, true);
  assert.equal(finished.errCode, '0');
  assert.equal(finished.errDesc, 'ok');
  assert.equal(finished.message, 'ok');

  assert.deepEqual(normalizeOtaStatus(null), {
    status: '',
    percent: 0,
    done: false,
    message: '',
    errCode: '',
    errDesc: '',
  });
});

test('findOtaFiles：递归查找并按 firmwareId / 文件名+地址去重', () => {
  const cyc = { fileInfo: { fileName: 'b.bin', fileUrl: 'https://x.test/b.bin', fileSize: 2048 } };
  cyc.self = cyc;
  const nested = {
    a: { fileInfo: { fileName: 'a.bin', fileUrl: 'https://x.test/a.bin?sign=1', firmwareId: 'FW1', fileSize: 1024 } },
    b: [{ list: [{ fileInfo: { fileName: 'a.bin', fileUrl: 'https://x.test/a.bin?sign=2', firmwareId: 'FW1' } }] }],
    c: cyc,
    d: { fileInfo: null },
    e: 'not-an-object',
    f: [null, undefined, 1, { fileUrl: 'https://x.test/no-name.bin' }],
  };
  const files = findOtaFiles(nested);
  assert.equal(files.length, 2, `去重后应剩 2 个，实际 ${files.map((file) => file.name).join(',')}`);
  assert.deepEqual(files.map((file) => file.name).sort(), ['a.bin', 'b.bin']);
  assert.equal(files[0].maskedUrl, 'https://x.test/a.bin?sign=***', '签名不同但同一固件只留首次出现的');

  // 夹具里的 3 条记录只有 2 个包（第二条 fileInfo 为 null）
  assert.equal(findOtaFiles(OTA_LIST).length, 2);
  assert.deepEqual(findOtaFiles(null), []);
  assert.deepEqual(findOtaFiles({}), []);
  assert.deepEqual(findOtaFiles('x'), []);
});

test('全部导出对 null / undefined / 空对象 / 字符串都不抛异常', () => {
  const normalizers = [
    normalizeWallpaperPage,
    normalizeWallpaperDetail,
    wallpapersFromHome,
    normalizeProgress,
    wallpaperImages,
    normalizeOtaInfo,
    normalizeCheckVersion,
    normalizeOtaList,
    normalizeOtaRecord,
    normalizeOtaStatus,
    findOtaFiles,
    parseJsonField,
    maskWifiAddress,
    maskSignedUrl,
    formatBytes,
    parseWallpaperAreas,
    buildWallpaperContent,
  ];
  for (const input of [null, undefined, {}, '', 0, []]) {
    for (const fn of normalizers) {
      assert.doesNotThrow(() => fn(input, {}), `${fn.name}(${JSON.stringify(input)}) 不应抛异常`);
    }
    assert.doesNotThrow(() => buildUseWallpaperBody(input, input));
    assert.doesNotThrow(() => normalizeOtaFile(input));
    assert.doesNotThrow(() => normalizeWallpaperPage(input));
  }
});

test('parseWallpaperAreas：按 type 区分整屏与速度卡', () => {
  const wall = {
    wallpaperContent: JSON.stringify([
      { type: '1', url: 'https://cdn/main.png', subjectUrl: 'https://cdn/main-subject.png' },
      { type: '2', url: 'https://cdn/speed.png' },
    ]),
  };
  const areas = parseWallpaperAreas(wall);
  assert.equal(areas.main.url, 'https://cdn/main.png');
  assert.equal(areas.speedCard.url, 'https://cdn/speed.png');
  assert.equal(areas.items.length, 2);
});

test('parseWallpaperAreas：没有 type 时按顺序当作 整屏/速度卡', () => {
  const areas = parseWallpaperAreas({ wallpaperContent: JSON.stringify([{ url: 'https://a/1.png' }, { url: 'https://a/2.png' }]) });
  assert.equal(areas.main.url, 'https://a/1.png');
  assert.equal(areas.speedCard.url, 'https://a/2.png');
  const single = parseWallpaperAreas({ wallpaperContent: JSON.stringify([{ url: 'https://a/only.png' }]) });
  assert.equal(single.main.url, 'https://a/only.png');
  assert.equal(single.speedCard, null);
});

test('parseWallpaperAreas：对象形态 / 空值都容错', () => {
  assert.equal(parseWallpaperAreas(null).main, null);
  assert.equal(parseWallpaperAreas({}).main, null);
  const obj = parseWallpaperAreas({ wallpaperContent: { imageUrl: 'https://a/x.png' } });
  assert.equal(obj.main.url, 'https://a/x.png');
});

test('buildWallpaperContent：两块图的 type 与顺序', () => {
  const two = JSON.parse(buildWallpaperContent({ mainUrl: 'https://a/main.png', speedCardUrl: 'https://a/speed.png' }));
  assert.deepEqual(two.map((item) => item.type), ['1', '2']);
  assert.deepEqual(two.map((item) => item.url), ['https://a/main.png', 'https://a/speed.png']);
  const one = JSON.parse(buildWallpaperContent({ mainUrl: 'https://a/only.png' }));
  assert.equal(one.length, 1);
  assert.equal(one[0].type, '1');
});
