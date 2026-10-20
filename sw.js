// Offline support for the web version (GitHub Pages). Luisa's tablet app bundles everything already.
// Saves the app, every voice clip in audio/manifest.json, and photos, so the games work on the plane.
const CACHE = 'kids-apps-v1';
const PHOTOS = ['photos/mum.jpg'];

function clipUrls(manifest) {
  return Object.entries(manifest).map(([key, v]) => typeof v === 'string' ? 'audio/' + v : `audio/${key}.wav?v=${v}`);
}

async function saveEverything() {
  const cache = await caches.open(CACHE);
  await cache.addAll(['./', 'index.html']);
  const res = await fetch('audio/manifest.json', { cache: 'no-cache' });
  if (res.ok) {
    await cache.put('audio/manifest.json', res.clone());
    const urls = clipUrls(await res.json());
    // a missing clip shouldn't stop the rest from saving
    await Promise.all(urls.concat(PHOTOS).map(u => cache.match(u).then(hit => hit || cache.add(u).catch(() => {}))));
  }
}

self.addEventListener('install', e => { self.skipWaiting(); e.waitUntil(saveEverything()); });
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
// The page asks for a refresh each time it opens online, to pick up new clips
self.addEventListener('message', e => { if (e.data === 'refresh') e.waitUntil(saveEverything()); });

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  const isPage = e.request.mode === 'navigate' || url.pathname.endsWith('/index.html') || url.pathname.endsWith('manifest.json');
  if (isPage) {
    // online: newest version (and keep a copy); offline: the saved copy
    e.respondWith(fetch(e.request).then(res => {
      const copy = res.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return res;
    }).catch(() => caches.match(e.request, { ignoreSearch: true }).then(r => r || caches.match('index.html'))));
  } else {
    // voice clips and photos: saved copy first
    e.respondWith(caches.match(e.request).then(hit => hit || fetch(e.request).then(res => {
      if (res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); }
      return res;
    })));
  }
});
