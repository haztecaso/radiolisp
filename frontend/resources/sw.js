// radiolisp's service worker. `bb build` and `bb build:static` write it into
// the release, filling in VERSION (a hash of the build) and FILES (the shell).
//
// It only serves the shell, cache first: the page, its script, styles, fonts
// and icons. Everything else (Radio Browser, the streams, the backend, the
// stations' artwork) never goes through it. A new version installs in the
// background and waits: it takes over once every window of the app is closed,
// so a page that is playing never mixes two builds.
const VERSION = '__VERSION__';
const FILES = __FILES__;
const CACHE = 'radiolisp-' + VERSION;
const SCOPE = new URL(self.registration.scope);
const SHELL = new Set(FILES.map((f) => new URL(f, SCOPE).href));
const PAGE = new URL('./', SCOPE).href;

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(FILES.map((f) => new URL(f, SCOPE)))));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('radiolisp-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  url.search = '';
  url.hash = '';
  const page = request.mode === 'navigate' && (url.href === PAGE || url.href === PAGE + 'index.html');
  const key = page ? PAGE : url.href;
  if (!page && !SHELL.has(key)) return;
  event.respondWith(caches.match(key, { cacheName: CACHE }).then((hit) => hit || fetch(request)));
});
