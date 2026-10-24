// Offline support for the web version (GitHub Pages). Luisa's tablet app bundles everything already.
// Saves the app, every voice clip in audio/manifest.json, photos, and the books in books/shelf, so it all works on the plane.
const CACHE = 'kids-apps-v1';
const PHOTOS = ['photos/mum.jpg'];

function clipUrls(manifest) {
  return Object.entries(manifest).map(([key, v]) => typeof v === 'string' ? 'audio/' + v : `audio/${key}.wav?v=${v}`);
}
function bookUrls(shelf) {
  return (shelf.books || []).flatMap(b => b.pages.filter(p => p.img).map(p => `books/shelf/${b.id}/${p.img}`));
}

// Fetch a list (fresh), keep a copy, and return the files it names
async function list(cache, url, toUrls) {
  try {
    const res = await fetch(url, { cache: 'no-cache' });
    if (!res.ok) return [];
    await cache.put(url, res.clone());
    return toUrls(await res.json());
  } catch (e) { return []; }
}

async function saveEverything() {
  const cache = await caches.open(CACHE);
  await cache.addAll(['./', 'index.html', 'snake-maths.html', 'robot-builder_v2.html']);
  const urls = [...await list(cache, 'audio/manifest.json', clipUrls), ...await list(cache, 'books/shelf/index.json', bookUrls), ...PHOTOS];
  // a missing file shouldn't stop the rest from saving
  await Promise.all(urls.map(u => cache.match(u).then(hit => hit || cache.add(u).catch(() => {}))));
}

self.addEventListener('install', e => { self.skipWaiting(); e.waitUntil(saveEverything()); });
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
// The page asks for a refresh each time it opens online, to pick up new clips and books
self.addEventListener('message', e => { if (e.data === 'refresh') e.waitUntil(saveEverything()); });

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  const isPage = e.request.mode === 'navigate' || url.pathname.endsWith('/index.html') || url.pathname.endsWith('.json');
  if (isPage) {
    // online: newest version (and keep a copy); offline: the saved copy
    e.respondWith(fetch(e.request).then(res => {
      const copy = res.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return res;
    }).catch(() => caches.match(e.request, { ignoreSearch: true }).then(r => r || caches.match('index.html'))));
  } else {
    // voice clips, photos and book pages: saved copy first
    e.respondWith(caches.match(e.request).then(hit => hit || fetch(e.request).then(res => {
      if (res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); }
      return res;
    })));
  }
});
