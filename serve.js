// Local server for recording voice clips on the laptop.
// Serves the app and saves recordings into ./audio as <key>.wav (+ audio/manifest.json).
// Run: node serve.js   then open http://localhost:8080
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const AUDIO = path.join(ROOT, 'audio');
const MANIFEST = path.join(AUDIO, 'manifest.json');
const PORT = 8080;
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.wav': 'audio/wav', '.md': 'text/plain', '.css': 'text/css', '.png': 'image/png' };

fs.mkdirSync(AUDIO, { recursive: true });
const readManifest = () => { try { return JSON.parse(fs.readFileSync(MANIFEST, 'utf8')); } catch (e) { return {}; } };
const writeManifest = m => fs.writeFileSync(MANIFEST, JSON.stringify(m, Object.keys(m).sort(), 2));

http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split('?')[0]);
  const m = url.match(/^\/api\/clip\/([a-z0-9_]+)$/);

  if (url === '/api/ping') return res.end('ok');

  if (m && req.method === 'POST') {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      fs.writeFileSync(path.join(AUDIO, m[1] + '.wav'), Buffer.concat(chunks));
      const man = readManifest(); man[m[1]] = Date.now(); writeManifest(man);
      console.log('saved', m[1]);
      res.end('ok');
    });
    return;
  }
  if (m && req.method === 'DELETE') {
    try { fs.unlinkSync(path.join(AUDIO, m[1] + '.wav')); } catch (e) {}
    const man = readManifest(); delete man[m[1]]; writeManifest(man);
    console.log('deleted', m[1]);
    return res.end('ok');
  }

  const file = path.join(ROOT, url === '/' ? 'index.html' : url);
  if (!file.startsWith(ROOT)) { res.writeHead(403); return res.end(); }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end('not found'); }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
}).listen(PORT, '127.0.0.1', () => console.log(`Recorder running: http://localhost:${PORT}  (close this window to stop)`));
