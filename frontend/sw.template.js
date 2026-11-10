/* Hisaab service worker. Generated at build time by vite.config.ts from sw.template.js, which fills in
 * the version and the list of app files below.
 *
 *   app files   kept on the device (names change every build), so the app opens instantly and offline
 *   pages       network first; offline, the saved app opens instead
 *   /api data   network first; offline, the last saved copy is shown. Cleared on sign-out and when the
 *               session ends (a 401), so data doesn't outlive the sign-in.
 *   never kept  sign-in (/oauth2, /login), downloads and anything that isn't a JSON GET
 */
const VERSION = '__VERSION__'
const SHELL = __SHELL__
const SHELL_CACHE = 'hisaab-shell-' + VERSION
const DATA_CACHE = 'hisaab-data'
// JSON that's never kept: sign-in state other than "who am I", and backups.
const SKIP = ['/api/auth/login', '/api/auth/logout', '/api/auth/link', '/api/auth/oidc', '/api/auth/options', '/api/backups']

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL_CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('hisaab-shell-') && k !== SHELL_CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('message', (e) => {
  if (e.data === 'clear-data') e.waitUntil(caches.delete(DATA_CACHE))
})

self.addEventListener('fetch', (e) => {
  const req = e.request
  if (req.method !== 'GET') return
  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return
  const p = url.pathname
  if (p.startsWith('/oauth2/') || p.startsWith('/login/')) return
  if (p.startsWith('/api/')) {
    if (!SKIP.some((s) => p.startsWith(s))) e.respondWith(data(req))
    return
  }
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).catch(() => caches.match('/', { cacheName: SHELL_CACHE })))
    return
  }
  e.respondWith(caches.match(req, { cacheName: SHELL_CACHE }).then((hit) => hit || fetch(req)))
})

async function data(req) {
  const cache = await caches.open(DATA_CACHE)
  let res
  try {
    res = await fetch(req)
  } catch (err) {
    const hit = await cache.match(req)
    if (hit) return hit
    throw err
  }
  if (res.status === 401) await caches.delete(DATA_CACHE)
  else if (res.ok && (res.headers.get('content-type') || '').includes('application/json')) await cache.put(req, res.clone())
  return res
}
