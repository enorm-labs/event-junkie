/*
 * The push-only service worker (#3894, ADR-055). Registered only when a visitor turns notifications on, with the
 * scope `/push/`, so it controls no page. It has no `fetch` handler and stores nothing: it shows a push, and opens
 * its link on a click. Plain JavaScript in `public/`, so Vite copies it as it is and its URL never changes.
 */

self.addEventListener('push', (event) => {
  let message = {}
  try {
    message = event.data ? event.data.json() : {}
  } catch {
    message = {}
  }
  const title = typeof message.title === 'string' ? message.title : 'Event Junkie'
  event.waitUntil(
    self.registration.showNotification(title, {
      body: typeof message.body === 'string' ? message.body : '',
      icon: '/icon-192.png',
      // Only a path on this site: the payload is ours, but a link elsewhere is never opened.
      data: { url: typeof message.url === 'string' && message.url.startsWith('/') ? message.url : '/' },
    }),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const url = new URL(event.notification.data?.url ?? '/', self.location.origin).href
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((client) => client.url === url)
      return open ? open.focus() : self.clients.openWindow(url)
    }),
  )
})
