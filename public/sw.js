// 简单的 Service Worker：缓存页面外壳，让应用可以“添加到主屏幕”并秒开。
// 注意：/api/ 请求永远走网络（聊天需要联网）。
const CACHE = 'voice-assistant-v1';
const SHELL = [
  '.',
  'index.html',
  'style.css',
  'app.js',
  'manifest.webmanifest',
  'icon.svg',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.pathname.startsWith('/api/')) return;
  e.respondWith(
    caches.match(e.request).then((cached) => cached || fetch(e.request))
  );
});
