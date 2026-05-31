// Minimal service worker — just enough to make the remote installable as a PWA
// (in a secure context). Caches the app shell; network-first with cache fallback.
const CACHE = "shou-remote-v1";
const SHELL = [
  "/static/remote.css",
  "/static/remote.js",
  "/static/icon-192.png",
  "/static/icon-512.png",
];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  // Never cache control endpoints or live socket traffic.
  if (e.request.method !== "GET" || url.pathname.startsWith("/socket.io")) return;
  e.respondWith(
    fetch(e.request)
      .then((resp) => {
        if (SHELL.includes(url.pathname)) {
          const copy = resp.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy));
        }
        return resp;
      })
      .catch(() => caches.match(e.request))
  );
});
