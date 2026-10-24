// Copies the shared games page and its media from the repo root into www/ (run via `npm run sync`).
// Books: only the converted ones in books/shelf (run make-books.bat first).
const fs = require('fs');

fs.rmSync('www', { recursive: true, force: true });
fs.mkdirSync('www');
fs.copyFileSync('../index.html', 'www/index.html');
fs.copyFileSync('../snake-maths.html', 'www/snake-maths.html');
fs.copyFileSync('../robot-builder_v2.html', 'www/robot-builder_v2.html');
for (const dir of ['audio', 'photos', 'books/shelf']) {
  if (fs.existsSync('../' + dir)) fs.cpSync('../' + dir, 'www/' + dir, { recursive: true, filter: f => !f.endsWith('book.json') });
}
