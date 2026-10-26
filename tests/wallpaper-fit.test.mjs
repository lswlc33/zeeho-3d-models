/**
 * 壁纸「本地定位」几何层测试：`node --test tests/*.test.mjs`
 *
 * 背景：官方客户端是「选图 → 本地拖拽/双指缩放 → 点保存才上传」
 * （App 3.0.6「仪表效果预览」页原文：双指放大/缩小可调整壁纸展示）。
 * 面板要做同样的事，就必须保证两件事：
 *   1. 预览看到的位置 == 导出的裁切结果（预览与 canvas 共用同一份 transform）；
 *   2. zoom=1（覆盖适配）时四边不留空白，且平移永远拖不出边界。
 * 这里固定的是这两条不变量，不涉及任何网络行为。
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  WALLPAPER_ZOOM_MIN,
  WALLPAPER_ZOOM_MAX,
  wallpaperCoverScale,
  clampWallpaperZoom,
  wallpaperFitTransform,
  wallpaperLayerStyle,
  clampWallpaperPan,
  wallpaperSpeedCardBox,
} from '../sdk/vehicle-media.js';

const MAIN_BOX = { boxW: 1024, boxH: 600 };

/** 断言：绘制矩形完全盖住目标框（没有黑边）。 */
function assertCovers(transform, label) {
  const left = transform.offsetX;
  const top = transform.offsetY;
  const right = left + transform.drawW;
  const bottom = top + transform.drawH;
  assert.ok(left <= 1e-6, `${label}: 左边越界 ${left}`);
  assert.ok(top <= 1e-6, `${label}: 上边越界 ${top}`);
  assert.ok(right >= transform.boxW - 1e-6, `${label}: 右边露白 ${right} < ${transform.boxW}`);
  assert.ok(bottom >= transform.boxH - 1e-6, `${label}: 下边露白 ${bottom} < ${transform.boxH}`);
}

test('wallpaperCoverScale：取较大的那个比值（cover 语义）', () => {
  // 竖图进横框：宽度撑满
  assert.ok(Math.abs(wallpaperCoverScale(1080, 1920, 1024, 600) - 1024 / 1080) < 1e-9);
  // 超宽图进横框：高度撑满
  assert.ok(Math.abs(wallpaperCoverScale(4000, 1000, 1024, 600) - 0.6) < 1e-9);
  // 同比例：两边同时撑满
  assert.equal(wallpaperCoverScale(2048, 1200, 1024, 600), 0.5);
  // 非法输入不抛、回落到 1
  for (const args of [[], [0, 0, 0, 0], ['', null, undefined, {}], [NaN, NaN, NaN, NaN]]) {
    assert.equal(wallpaperCoverScale(...args), 1);
  }
});

test('clampWallpaperZoom：下限 1（覆盖）、上限 8、坏值按 1', () => {
  assert.equal(WALLPAPER_ZOOM_MIN, 1);
  assert.equal(clampWallpaperZoom(0.2), 1);
  assert.equal(clampWallpaperZoom(1), 1);
  assert.equal(clampWallpaperZoom(99), WALLPAPER_ZOOM_MAX);
  assert.equal(clampWallpaperZoom('2.5'), 2.5);
  assert.equal(clampWallpaperZoom(null), 1);
  assert.equal(clampWallpaperZoom('abc'), 1);
  assert.equal(clampWallpaperZoom(3, 4), 3);
  assert.equal(clampWallpaperZoom(8, 4), 4);
});

test('wallpaperFitTransform：zoom=1 刚好铺满，不留黑边', () => {
  for (const [w, h] of [[1080, 1920], [4000, 1000], [1024, 600], [1, 1], [600, 1024]]) {
    const t = wallpaperFitTransform({ imgW: w, imgH: h, ...MAIN_BOX, zoom: 1 });
    assertCovers(t, `${w}x${h} zoom=1`);
    // 至少有一边刚好等于框（cover 的定义）
    const exactW = Math.abs(t.drawW - t.boxW) < 1e-6;
    const exactH = Math.abs(t.drawH - t.boxH) < 1e-6;
    assert.ok(exactW || exactH, `${w}x${h}: 缩放后两边都不贴合框`);
    // 贴合的那一边不可平移
    if (exactW) assert.equal(t.maxPanX, 0);
    if (exactH) assert.equal(t.maxPanY, 0);
  }
});

test('wallpaperFitTransform：竖图进横框 → 只能上下拖，且范围受钳制', () => {
  const t = wallpaperFitTransform({ imgW: 1080, imgH: 1920, ...MAIN_BOX, zoom: 1 });
  assert.equal(t.maxPanX, 0);
  assert.ok(t.maxPanY > 500, `maxPanY 太小：${t.maxPanY}`);
  const top = wallpaperFitTransform({ imgW: 1080, imgH: 1920, ...MAIN_BOX, zoom: 1, panY: 99999 });
  assert.ok(Math.abs(top.panY - t.maxPanY) < 1e-9, '超出上限应被钳到 maxPanY');
  assertCovers(top, '拖到最上');
  const bottom = wallpaperFitTransform({ imgW: 1080, imgH: 1920, ...MAIN_BOX, zoom: 1, panY: -99999 });
  assert.ok(Math.abs(bottom.panY + t.maxPanY) < 1e-9);
  assertCovers(bottom, '拖到最下');
});

test('wallpaperFitTransform：放大后两边都能拖，仍然不出黑边', () => {
  const t = wallpaperFitTransform({ imgW: 1024, imgH: 600, ...MAIN_BOX, zoom: 2, panX: 1e9, panY: -1e9 });
  assert.ok(t.maxPanX > 0 && t.maxPanY > 0);
  assertCovers(t, 'zoom=2 极限平移');
  assert.ok(Math.abs(t.drawW - 2048) < 1e-9);
  assert.ok(Math.abs(t.drawH - 1200) < 1e-9);
});

test('wallpaperFitTransform：缺图时 ready=false 且不抛', () => {
  for (const options of [undefined, {}, { imgW: 0, imgH: 0 }, { boxW: 0 }]) {
    const t = wallpaperFitTransform(options);
    assert.equal(t.ready, false);
    assert.equal(t.zoom, 1);
    assert.ok(t.boxW > 0 && t.boxH > 0);
  }
});

test('wallpaperLayerStyle：百分比与绘制参数同口径（预览=导出）', () => {
  const t = wallpaperFitTransform({ imgW: 1080, imgH: 1920, ...MAIN_BOX, zoom: 1.5, panY: 40 });
  const style = wallpaperLayerStyle(t);
  const pct = (value, base) => `${Number(((value / base) * 100).toFixed(4))}%`;
  assert.equal(style.left, pct(t.offsetX, t.boxW));
  assert.equal(style.top, pct(t.offsetY, t.boxH));
  assert.equal(style.width, pct(t.drawW, t.boxW));
  assert.equal(style.height, pct(t.drawH, t.boxH));
  // 百分比反算回像素后应能复现同一矩形
  const back = {
    offsetX: (parseFloat(style.left) / 100) * t.boxW,
    offsetY: (parseFloat(style.top) / 100) * t.boxH,
    drawW: (parseFloat(style.width) / 100) * t.boxW,
    drawH: (parseFloat(style.height) / 100) * t.boxH,
  };
  assert.ok(Math.abs(back.offsetX - t.offsetX) < 1e-3);
  assert.ok(Math.abs(back.drawW - t.drawW) < 1e-3);
  assert.doesNotThrow(() => wallpaperLayerStyle(undefined));
});

test('clampWallpaperPan：逐轴钳制，坏值居中', () => {
  assert.deepEqual(clampWallpaperPan(500, -500, 100, 200), { panX: 100, panY: -200 });
  assert.deepEqual(clampWallpaperPan(-500, 500, 100, 200), { panX: -100, panY: 200 });
  assert.deepEqual(clampWallpaperPan('abc', null, 100, 200), { panX: 0, panY: 0 });
  assert.deepEqual(clampWallpaperPan(10, 10, 0, 0), { panX: 0, panY: 0 });
});

test('wallpaperSpeedCardBox：竖长方形、严格居中、可被参数覆盖', () => {
  const stage = { w: 1024, h: 600 };
  const box = wallpaperSpeedCardBox(stage);
  assert.ok(box.h > box.w, `速度卡应是竖长方形：${box.w}×${box.h}`);
  assert.ok(Math.abs(box.h - Math.round(600 * 0.86)) <= 0, `高应占整屏 86%：${box.h}`);
  assert.equal(box.left, Math.round((1024 - box.w) / 2), '左右居中');
  assert.equal(box.top, Math.round((600 - box.h) / 2), '上下居中');
  // 百分比与像素一致（预览层用百分比）
  assert.ok(Math.abs(box.widthPct - (box.w / 1024) * 100) < 0.01);
  assert.ok(Math.abs(box.heightPct - (box.h / 600) * 100) < 0.01);
  assert.ok(Math.abs(box.leftPct + box.widthPct / 2 - 50) < 0.01, '水平中心在 50%');
  assert.ok(Math.abs(box.topPct + box.heightPct / 2 - 50) < 0.01, '垂直中心在 50%');

  // 换参数（正式数值下来时就是这么换）
  const custom = wallpaperSpeedCardBox(stage, { heightRatio: 0.5, aspect: 1 / 2 });
  assert.equal(custom.h, 300);
  assert.equal(custom.w, 150);
  assert.equal(custom.left, 437);
  assert.equal(custom.top, 150);

  // 坏参数不抛，回落到默认比例
  for (const spec of [undefined, {}, { heightRatio: 'x', aspect: 0 }, { heightRatio: -5, aspect: -1 }]) {
    const safe = wallpaperSpeedCardBox(stage, spec);
    assert.ok(safe.w > 0 && safe.h > 0);
    assert.ok(safe.h > safe.w);
  }
  assert.doesNotThrow(() => wallpaperSpeedCardBox(null));
});
