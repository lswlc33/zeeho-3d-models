/**
 * 签名与客户端的离线测试：node --test tests/
 *
 * 签名用例是「固定 nonce + 固定 timestamp」的已知答案（KAT），
 * 另用 Python 参考实现（与 zeeho_api.py 同一套 hashlib 步骤）交叉核对过。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';

import {
  md5Hex,
  sha1Hex,
  canonicalQuery,
  rfc3986,
  buildSign,
  buildHeaders,
  ZeehoClient,
  pickVehicleSummary,
} from '../sdk/zeeho-api.js';
import { ENDPOINTS, GROUPS, fillPath, byId } from '../sdk/endpoints.js';

const APP_ID = 'test-app-id';
const APP_SECRET = 'test-secret';
const NONCE = 'ABCDEFGHIJKLMNOP';
const TS = 1792770000000;

test('SHA-1 与 Node crypto 一致（含中文/长串/控制字符）', () => {
  const samples = ['', 'abc', '中文测试', 'a'.repeat(5000), '\u0000\u0001\u00ff'];
  for (const sample of samples) {
    const expected = crypto.createHash('sha1').update(Buffer.from(sample, 'utf8')).digest('hex');
    assert.equal(sha1Hex(sample), expected, `sha1(${JSON.stringify(sample.slice(0, 12))})`);
  }
});

test('MD5 与 Node crypto 一致（含中文/长串/控制字符）', () => {
  const samples = ['', 'abc', '中文测试', 'a'.repeat(5000), '\u0000\u0001\u00ff'];
  for (const sample of samples) {
    const expected = crypto.createHash('md5').update(Buffer.from(sample, 'utf8')).digest('hex');
    assert.equal(md5Hex(sample), expected, `md5(${JSON.stringify(sample.slice(0, 12))})`);
  }
});

test('RFC 3986 编码与 Python quote(safe="~") 对齐', () => {
  assert.equal(rfc3986('a b'), 'a%20b');
  assert.equal(rfc3986('中文'), '%E4%B8%AD%E6%96%87');
  assert.equal(rfc3986("!'()*"), '%21%27%28%29%2A');
  assert.equal(rfc3986('AZaz09-._~'), 'AZaz09-._~');
});

test('query 规范化：排序、空值保留、重复键', () => {
  assert.equal(canonicalQuery('b=2&a=1&empty=&sp=a%20b&cn=%E4%B8%AD%E6%96%87'), 'a=1&b=2&cn=%E4%B8%AD%E6%96%87&empty=&sp=a%20b');
  assert.equal(canonicalQuery([['a', '2'], ['a', '1']]), 'a=1&a=2');
  assert.equal(canonicalQuery(new URLSearchParams('?z=1&a=2')), 'a=2&z=1');
});

test('签名 KAT：GET / 带 query / POST（与 Python 参考实现一致）', () => {
  const cases = [
    ['https://tapi.zeehoev.com/v1.0/app/cfmotoserverapp/vehicle/list', 'GET', '', 'd7a31b4e438521b96186d6d67e99c85b'],
    ['https://tapi.zeehoev.com/v1.0/app/cfmotoserverapp/ota/mmi/info/v2?vinNo=TESTVIN0000000001', 'GET', '', '6c3fdd757be5e2e7b67c6563cfca6534'],
    ['https://tapi.zeehoev.com/v1.0/mine/cfmotoservermine/user/loginByPhone', 'POST', '{"phone":"10000000000","authCode":"123456"}', '2e8d503f39ae095fbe06c64f7d33c5ac'],
  ];
  for (const [url, method, body, expected] of cases) {
    const actual = buildSign({
      url,
      method,
      body,
      appId: APP_ID,
      appSecret: APP_SECRET,
      nonce: NONCE,
      timestamp: TS,
    });
    assert.equal(actual.sign, expected, `${method} ${url}`);
    assert.equal(actual.param, `appId=${APP_ID}&nonce=${NONCE}&timestamp=${TS}`);
  }
});

test('签名：POST 的 preimage 不含 scheme/host/path，只含 query + body', () => {
  const url = 'https://tapi.zeehoev.com/v1.0/mine/cfmotoservermine/user/loginByPhone';
  const body = '{"phone":"10000000000","authCode":"123456"}';
  const sign = buildSign({ url, method: 'POST', body, appId: APP_ID, appSecret: APP_SECRET, nonce: NONCE, timestamp: TS });
  const param = `appId=${APP_ID}&nonce=${NONCE}&timestamp=${TS}`;
  const expected = crypto.createHash('md5')
    .update(crypto.createHash('sha1').update(`${body}${param}${APP_SECRET}`).digest('hex'))
    .digest('hex');
  assert.equal(sign.sign, expected);
});

test('buildHeaders：默认 okhttp UA / 签名字段齐全 / token 可选', () => {
  const headers = buildHeaders({
    url: 'https://tapi.zeehoev.com/v1.0/app/cfmotoserverapp/vehicle/list',
    method: 'GET',
    body: '',
    appId: APP_ID,
    appSecret: APP_SECRET,
    nonce: NONCE,
    timestamp: TS,
  });
  assert.equal(headers['User-Agent'], 'okhttp/4.9.2');
  assert.equal(headers.appId, APP_ID);
  assert.equal(headers.nonce, NONCE);
  assert.equal(headers.timestamp, String(TS));
  assert.match(headers.signature, /^[a-f0-9]{32}$/);
  assert.equal(headers['Cfmoto-X-Sign'], headers.signature);
  assert.equal(headers['Cfmoto-X-Param'], `appId=${APP_ID}&nonce=${NONCE}&timestamp=${TS}`);
  assert.equal(headers.Authorization, undefined);

  const withToken = buildHeaders({
    url: 'https://tapi.zeehoev.com/v1.0/app/cfmotoserverapp/vehicle/list',
    method: 'GET',
    body: '',
    appId: APP_ID,
    appSecret: APP_SECRET,
    token: 't0ken',
  });
  assert.equal(withToken.Authorization, 'Bearer t0ken');
});

test('ZeehoClient：URL/query 拼接、Bearer/Basic、JSON 解析', async () => {
  const calls = [];
  const client = new ZeehoClient({
    base: 'https://example.test/',
    appId: APP_ID,
    appSecret: APP_SECRET,
    basic: 'Basic abc',
    fetchImpl: async (url, init) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ code: '10000', message: 'ok', data: { url } }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    },
  });

  const result = await client.request({
    path: '/v1.0/app/cfmotoserverapp/tire/monitoring',
    query: { vinNo: 'VIN 1', sensorType: 'EXTERNAL' },
    token: 'tok',
  });
  assert.equal(calls[0].url, 'https://example.test/v1.0/app/cfmotoserverapp/tire/monitoring?vinNo=VIN%201&sensorType=EXTERNAL');
  assert.equal(calls[0].init.headers.Authorization, 'Bearer tok');
  assert.equal(calls[0].init.headers['Cfmoto-X-Sign-Type'], '0');
  assert.equal(result.data.data.url, calls[0].url);
  assert.equal(result.status, 200);

  await client.loginByPhone('10000000000', '123456');
  const loginCall = calls.at(-1);
  assert.equal(loginCall.init.method, 'POST');
  assert.equal(loginCall.init.headers.Authorization, 'Basic abc');
  assert.equal(loginCall.init.body, '{"phone":"10000000000","authCode":"123456"}');
});

test('ZeehoClient.url：query 与签名共用 RFC3986 编码', () => {
  const client = new ZeehoClient({ fetchImpl: async () => new Response('{}') });
  assert.equal(
    client.url('/v1.0/x', { q: 'a b~!', empty: '' }),
    'https://tapi.zeehoev.com/v1.0/x?q=a%20b~%21&empty=',
  );
});

test('pickVehicleSummary：空 SOC / 字符串布尔值不被误判', () => {
  const summary = pickVehicleSummary({ data: { bmssoc: '', hmiRidableMile: '', whetherChargeState: '0' } });
  assert.equal(summary.soc, null);
  assert.equal(summary.rangeKm, null);
  assert.equal(summary.charging, false);
  const wall = pickVehicleSummary({ data: {
    supportCustomWallpaper: 'true', wallpaperResolutionWidth: '1024', wallpaperResolutionHeight: '600',
    themeShopFlag: '1', subjectWallpaperConfig: '[{"name":"主页","type":"2"}]',
  } });
  assert.equal(wall.supportCustomWallpaper, true);
  assert.equal(wall.wallpaperWidth, 1024);
  assert.equal(wall.wallpaperHeight, 600);
  assert.equal(wall.themeShopFlag, '1');
  assert.equal(wall.wallpaperConfig.length, 1);
});

test('ZeehoClient：非 JSON（网关 XML 401）也能返回而不抛异常', async () => {
  const client = new ZeehoClient({
    appId: APP_ID,
    appSecret: APP_SECRET,
    fetchImpl: async () => new Response('<InvalidTokenException><error>invalid_token</error></InvalidTokenException>', { status: 401 }),
  });
  const result = await client.request({ path: '/v1.0/app/cfmotoserverapp/vehicle/list', token: 'bad' });
  assert.equal(result.status, 401);
  assert.equal(result.data, null);
  assert.equal(result.authFailed, true);
});

test('ZeehoClient：缺少凭据时给出可操作的错误', async () => {
  const client = new ZeehoClient({ fetchImpl: async () => new Response('{}') });
  await assert.rejects(() => client.request({ path: '/v1.0/app/cfmotoserverapp/vehicle/list' }), /appId\/appSecret/);
});

test('pickVehicleSummary：容忍 null / 字符串数字', () => {
  const summary = pickVehicleSummary({
    data: {
      vinNo: 'V',
      vehicleName: 'AE5i',
      bmssoc: '69',
      hmiRidableMile: 11,
      onlineStatus: 'ONLINE',
      vehiclePic3dUrl: null,
      encryptInfo: { key: 'k', iv: 'i', encryptValue: 'v' },
    },
  });
  assert.equal(summary.soc, 69);
  assert.equal(summary.rangeKm, 11);
  assert.equal(summary.pic3d, '');
  assert.equal(summary.encryptInfo.key, 'k');
  assert.equal(pickVehicleSummary({}).soc, null);
});

test('接口目录：id 唯一、分组有效、path 参数已声明', () => {
  const ids = new Set();
  const groupIds = new Set(GROUPS.map((group) => group.id));
  for (const endpoint of ENDPOINTS) {
    assert.ok(!ids.has(endpoint.id), `重复 id: ${endpoint.id}`);
    ids.add(endpoint.id);
    assert.ok(groupIds.has(endpoint.group), `未知分组: ${endpoint.group}`);
    assert.match(endpoint.path, /^\//, `path 必须为绝对路径: ${endpoint.id}`);
    assert.ok(['GET', 'POST', 'PUT', 'DELETE'].includes(endpoint.method), `未知方法: ${endpoint.id}`);
    const placeholders = [...endpoint.path.matchAll(/\{([a-zA-Z0-9_]+)\}/g)].map((match) => match[1]);
    const declared = new Set((endpoint.params || []).filter((param) => param.in === 'path').map((param) => param.key));
    for (const placeholder of placeholders) assert.ok(declared.has(placeholder), `${endpoint.id} 缺少 path 参数 ${placeholder}`);
    for (const param of endpoint.params || []) {
      assert.ok(['path', 'query'].includes(param.in), `${endpoint.id} 参数 in 非法`);
    }
  }
  assert.ok(ENDPOINTS.length >= 40);
  assert.equal(byId('ota_info')?.path, '/v1.0/app/cfmotoserverapp/ota/mmi/info/v2');
  assert.equal(byId('wallpaper_page')?.path, '/v1.0/app/cfmotoserverapp/vehicle/wallpaper/pageVehicleWallpaper');
  assert.ok(ENDPOINTS.filter((endpoint) => endpoint.group === 'wallpaper').length >= 10);
  assert.equal(fillPath('/v1.0/x/{vin}', { vin: 'a b' }), '/v1.0/x/a%20b');
});
