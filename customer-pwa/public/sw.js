// Minimal service worker: lets the page be installed and shows a friendly offline page.
// Table pages and API calls are never cached (they carry the table's signed token and live order data).
const CACHE = "qd-shell-v1";
self.addEventListener("install", (e) => { self.skipWaiting(); e.waitUntil(caches.open(CACHE).then((c) => c.addAll(["/offline.html"]))); });
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
self.addEventListener("fetch", (e) => {
  if (e.request.mode === "navigate") e.respondWith(fetch(e.request).catch(() => caches.match("/offline.html")));
});
