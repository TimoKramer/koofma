// Cache-first app shell. Only the code needs to load offline — data offline
// is IndexedDB's job (see koofma.persist). The whole shell is precached on
// install, so a single online visit is enough to work offline afterwards.
//
// __BUILD__ is replaced with the commit sha by the deploy workflow. Each
// deploy therefore ships a byte-different sw.js: the browser installs it,
// fills a fresh cache and shows the "reload for update" toast, and the old
// cache is dropped on activate. (main.js has no content hash in its name, so
// without this a cache-first worker would serve stale code forever.)
const CACHE = "koofma-shell-__BUILD__";

// Relative to this script's own URL, so it precaches the right thing whether
// served at the origin root or under a subpath (GitHub Pages: /<repo>/).
const SHELL = [
  "./",
  "index.html",
  "js/main.js",
  "css/app.css",
  "manifest.json",
  "icon.svg",
  "icon-192.png",
  "icon-512.png",
  "icon-maskable-512.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// Lets a waiting worker take over immediately when the user clicks "Reload"
// on the update toast (see koofma.main/register-service-worker!).
self.addEventListener("message", (event) => {
  if (event.data === "SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;
  if (new URL(event.request.url).origin !== self.location.origin) return;
  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) return cached;
      return fetch(event.request).then((resp) => {
        if (resp.ok) {
          const copy = resp.clone();
          caches.open(CACHE).then((cache) => cache.put(event.request, copy));
        }
        return resp;
      });
    })
  );
});
