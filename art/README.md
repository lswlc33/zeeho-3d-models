# 车辆专属立绘

按 **VIN 码**命名的车辆立绘，网页启动后如果当前车辆 VIN 能匹配到这里，就优先用这张图替换
车况接口返回的车辆图；匹配不到就继续用接口图 / 内置矢量车。

## 命名

```
art/<VIN>.webp
```

- VIN 用**大写字母 + 数字**，与车况接口的 `vinNo` 完全一致，不要带空格、下划线或车型名。
- 优先放 `.webp`（体积小）；网页会按 `.webp` → `.png` 的顺序尝试。
- 例：`art/358122500025900.webp`

## 图片要求

- **透明背景**（PNG/WebP 带 alpha），不要白底；深色主题下白底会非常突兀。
- 建议宽度 900–1400px，车体尽量占满画布；页面会按 `object-fit: contain` 缩放。
- 车头朝左或朝右都可以，保持水平、不要裁到轮胎/车把。
- 建议 WebP quality 85–90；单张控制在 300KB 以内，避免每次开面板都等大图。
- 生成 WebP 的参考命令：

```bash
python - <<'PY'
from PIL import Image
im = Image.open('source.png').convert('RGBA')
w, h = im.size
if w > 1200:
    im = im.resize((1200, round(h * 1200 / w)), Image.Resampling.LANCZOS)
im.save('art/<VIN>.webp', 'WEBP', quality=90, method=6, alpha_quality=100)
PY
```

## 行为

- 首页车辆区优先使用 `art/<VIN>.webp`；失败自动试 `.png`，再失败回退到接口图。
- 车辆切换列表的缩略图也优先用同一张立绘；加载失败时回退到接口图。
- 这是**公开仓库**：VIN 和图片都会公开，只放确定可以公开的车辆。
- 新增/替换图片后直接提交 `art/` 即可；GitHub Pages 生效后网页会按 VIN 自动匹配，不需要改 `app.js`。
