// pr-screenshots-account.mjs — the signed-in account behind `pr-screenshots.sh --signed-in`. It answers
// /api/auth/** and /api/me/** in the browser, before the preview server's proxy, so production never sees an
// account request.
//
// The account follows production's busiest venue, an artist and a promoter, keeps two saved searches and two RSVPs,
// has eight notifications over three days, three of them unread and the first a change to the event marked going, and has no push. Its feeds come from the public /api/events, so the cards are real events. A write answers 204 and
// changes nothing. An /api/me path this file does not know answers 404 and is logged once, so a new endpoint shows.

const ACCOUNT = { email: 'ada@example.de', locale: 'en', createdAt: '2026-10-01T10:00:00Z' }
const SEARCHES = [
  { id: '00000000-0000-4000-8000-000000000001', name: 'Techno', query: 'genre=techno', savedAt: '2026-10-02T10:00:00Z' },
  { id: '00000000-0000-4000-8000-000000000002', name: 'Free', query: 'free=true', savedAt: '2026-10-03T10:00:00Z' },
]
const PASSKEYS = [
  { id: 'passkey-1', name: 'This Mac', createdAt: '2026-10-04T10:00:00Z', lastUsedAt: '2026-10-20T10:00:00Z' },
]
const EMPTY_PAGE = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }

const berlinDay = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date())

// Reads the public API through the preview server, as the page itself would.
async function publicJson(context, origin, path) {
  const response = await context.request.get(origin + path)
  if (!response.ok()) throw new Error(`${path} answered ${response.status()}`)
  return response.json()
}

async function loadAccount(context, origin) {
  const [venues, artists, promoters, upcoming] = await Promise.all([
    publicJson(context, origin, '/api/venues?sort=upcomingEvents,desc&size=1'),
    publicJson(context, origin, '/api/artists?size=1'),
    publicJson(context, origin, '/api/promoters?size=1'),
    publicJson(context, origin, `/api/events?from=${berlinDay()}&size=8`),
  ])
  const follow = (kind, item) => (item?.slug ? [{ kind, slug: item.slug, name: item.name, notify: 'off' }] : [])
  const follows = [
    ...follow('venue', venues.content?.[0]),
    ...follow('artist', artists.content?.[0]),
    ...follow('promoter', promoters.content?.[0]),
  ]
  const events = upcoming.content ?? []
  const [going, interested] = events
  const rsvps = {
    ...(going ? { [going.slug]: 'going' } : {}),
    ...(interested ? { [interested.slug]: 'interested' } : {}),
  }
  const notifications = events.map((event, index) => ({
    id: `00000000-0000-4000-8000-00000000010${index}`,
    kind: 'new_date',
    event: event.slug,
    title: event.title,
    date: event.eventDate,
    aboutKind: 'venue',
    aboutName: event.venue?.name ?? event.title,
    // The first is a change to the event marked going (#4045).
    ...(index === 0 ? { kind: 'change', aboutKind: 'rsvp', change: 'cancelled' } : {}),
    // Nine hours apart, so the notifications page shows Today, Yesterday and a date (#4169).
    createdAt: new Date(Date.now() - index * 9 * 3_600_000).toISOString(),
    readAt: index < 2 || index === 4 ? null : new Date().toISOString(),
  }))
  return { follows, rsvps, rsvpEvents: [going, interested].filter(Boolean), notifications }
}

/** Signs every page of [context] in, against the preview server at [origin]. */
export async function signIn(context, origin) {
  const account = await loadAccount(context, origin)
  const unknown = new Set()
  await context.addCookies([{ name: 'XSRF-TOKEN', value: 'pr-screenshots', url: origin }])

  const json = (route, body, status = 200) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

  await context.route(
    (url) =>
      url.pathname.startsWith('/api/auth/') || url.pathname === '/api/me' || url.pathname.startsWith('/api/me/'),
    async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const path = url.pathname
      const method = request.method()

      if (path === '/api/auth/session') return json(route, { account: ACCOUNT, signUpOpen: true })
      if (path === '/api/auth/csrf') return route.fulfill({ status: 204 })
      if (method === 'POST' && path === '/api/me/searches') {
        const { name, query } = request.postDataJSON() ?? {}
        return json(route, { id: '00000000-0000-4000-8000-000000000003', name, query, savedAt: new Date().toISOString() })
      }
      if (method !== 'GET') return route.fulfill({ status: 204 })

      if (path === '/api/me') return json(route, ACCOUNT)
      if (path === '/api/me/follows') return json(route, account.follows)
      const followMatch = path.match(/^\/api\/me\/follows\/([^/]+)\/([^/]+)$/)
      if (followMatch) {
        const [, kind, slug] = followMatch
        const item = account.follows.find((f) => f.kind === kind && f.slug === slug)
        return json(route, { following: Boolean(item), notify: item?.notify ?? 'off' })
      }
      if (path === '/api/me/following') {
        const response = await route.fetch({ url: `${origin}/api/events?${url.searchParams}` })
        return route.fulfill({ response })
      }
      if (path === '/api/me/following/today') {
        const day = berlinDay()
        const page = await publicJson(context, origin, `/api/events?from=${day}&to=${day}&size=6`)
        return json(route, page.content ?? [])
      }
      if (path === '/api/me/searches') return json(route, SEARCHES)
      if (path === '/api/me/rsvps') return json(route, account.rsvps)
      if (path === '/api/me/rsvps/upcoming') {
        const content = account.rsvpEvents
        return json(route, { ...EMPTY_PAGE, content, totalElements: content.length, totalPages: 1 })
      }
      if (path === '/api/me/rsvps/past') return json(route, EMPTY_PAGE)
      if (path === '/api/me/rsvps/calendar') return json(route, account.rsvpEvents)
      if (path === '/api/me/passkeys') return json(route, PASSKEYS)
      if (path === '/api/me/push') return json(route, { publicKey: null })
      if (path === '/api/me/notifications') {
        const unread = url.searchParams.get('unread') === 'true'
        return json(route, unread ? account.notifications.filter((n) => !n.readAt) : account.notifications)
      }
      if (path === '/api/me/notifications/unread-count') {
        return json(route, { count: account.notifications.filter((n) => !n.readAt).length })
      }

      if (!unknown.has(path)) {
        unknown.add(path)
        console.error(`  --signed-in: no fixture for GET ${path}; answered 404`)
      }
      return json(route, { message: 'no fixture' }, 404)
    },
  )
}
