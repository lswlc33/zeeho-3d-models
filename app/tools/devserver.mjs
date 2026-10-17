// 本机测试服：给 debug 壳用（adb reverse 到设备 localhost）。
//
//   node app/tools/devserver.mjs <网页产物目录> [端口]
//   THROTTLE=40000 node app/tools/devserver.mjs <网页产物目录> 8787   # 限速，方便截首启进度条
//
// 只服务静态文件；/api/* 一律 404（页面会回落到演示数据/直连），所有响应 no-store。
// 对文本资源按 Accept-Encoding 返回 gzip，和 GitHub Pages 的真实行为一致。
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

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
const compressible = new Set(['.html', '.js', '.json', '.webmanifest', '.css']);

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
    fs.readFile(f, (err, raw) => {
      if (err) {
        res.writeHead(500);
        res.end();
        return;
      }
      const ext = path.extname(f).toLowerCase();
      const wantsGzip = /\bgzip\b/.test(req.headers['accept-encoding'] || '');
      let body = raw;
      const headers = {
        'Content-Type': mime[ext] || 'application/octet-stream',
        'Cache-Control': 'no-store',
      };
      if (wantsGzip && compressible.has(ext) && raw.length > 1024) {
        body = zlib.gzipSync(raw);
        headers['Content-Encoding'] = 'gzip';
      }
      headers['Content-Length'] = body.length;
      res.writeHead(200, headers);
      if (!throttle) {
        res.end(body);
        return;
      }
      const chunk = 16 * 1024;
      const delay = Math.max(20, chunk / throttle * 1000);
      let off = 0;
      const timer = setInterval(() => {
        if (off >= body.length) {
          clearInterval(timer);
          res.end();
          return;
        }
        res.write(body.subarray(off, off + chunk));
        off += chunk;
      }, delay);
      res.on('close', () => clearInterval(timer));
    });
  });
}).listen(port, '127.0.0.1', () => {
  console.log('serving ' + dir + ' on ' + port + (throttle ? ' throttle=' + throttle : ''));
});
