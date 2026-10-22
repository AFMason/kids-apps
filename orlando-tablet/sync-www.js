// Copies the shared games page and its media from the repo root into www/ (run via `npm run sync`).
// Marks the page as Orlando's (<html data-kid="orlando">) so it opens straight to his menu.
// Books: only the converted ones in books/shelf (run make-books.bat first).
const fs = require('fs');

fs.rmSync('www', { recursive: true, force: true });
fs.mkdirSync('www');
const page = fs.readFileSync('../index.html', 'utf8').replace('<html lang="en">', '<html lang="en" data-kid="orlando">');
if (!page.includes('data-kid="orlando"')) throw new Error('index.html: <html lang="en"> not found');
fs.writeFileSync('www/index.html', page);
for (const dir of ['audio', 'photos', 'books/shelf']) {
  if (fs.existsSync('../' + dir)) fs.cpSync('../' + dir, 'www/' + dir, { recursive: true, filter: f => !f.endsWith('book.json') });
}
