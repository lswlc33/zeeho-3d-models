// 本机测试服：给 debug 壳用（adb reverse 到设备 localhost）。
//
//   node app/tools/devserver.mjs <网页产物目录> [端口]
//   THROTTLE=40000 node app/tools/devserver.mjs <网页产物目录> 8787   # 限速，方便截首启进度条
//
// 只服务静态文件；/api/* 一律 404（页面会回落到演示数据/直连），所有响应 no-store。
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const dir = path.resolve(process.argv[2] || '.');
const port = Number(process.argv[3] || 8787);
const throttle = Number(process.env.THROTTLE || 0);
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.css': 'text/css; charset=utf-8',
};

http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  if (p === '/api/status' || p.startsWith('/api/')) {
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end('{}');
    return;
  }
  const f = path.join(dir, p);
  if (!f.startsWith(dir + path.sep)) {
    res.writeHead(403);
    res.end();
    return;
  }
  fs.stat(f, (e, st) => {
    if (e || !st.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': mime[path.extname(f).toLowerCase()] || 'application/octet-stream',
      'Content-Length': st.size,
      'Cache-Control': 'no-store',
    });
    if (!throttle) {
      fs.createReadStream(f).pipe(res);
      return;
    }
    const stream = fs.createReadStream(f, { highWaterMark: 16 * 1024 });
    const delay = Math.max(20, 16384 / throttle * 1000);
    stream.on('data', (chunk) => {
      res.write(chunk);
      stream.pause();
      setTimeout(() => stream.resume(), delay);
    });
    stream.on('end', () => res.end());
  });
}).listen(port, '127.0.0.1', () => {
  console.log('serving ' + dir + ' on ' + port + (throttle ? ' throttle=' + throttle : ''));
});
