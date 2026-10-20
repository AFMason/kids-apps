// Copies the shared games page and its media from the repo root into www/ (run via `npm run sync`).
const fs = require('fs');

fs.rmSync('www', { recursive: true, force: true });
fs.mkdirSync('www');
fs.copyFileSync('../index.html', 'www/index.html');
for (const dir of ['audio', 'photos']) {
  if (fs.existsSync('../' + dir)) fs.cpSync('../' + dir, 'www/' + dir, { recursive: true });
}
