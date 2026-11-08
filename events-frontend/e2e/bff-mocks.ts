import type { Page, Route } from '@playwright/test'

/** BFF mocks shared by a11y.spec.ts and screen-reader.spec.ts: one payload set, so both scan the same markup. */
export function json(route: Route, body: unknown): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

export const eventSummaries = [
  {
    slug: 'tonight-show',
    title: 'Tonight Show',
    subtitle: 'With a support act',
    eventDate: '2026-08-15',
    startTime: '21:00',
    venue: { slug: 'mock-venue', name: 'Mock Venue', latitude: 52.5, longitude: 13.45 },
  },
  { slug: 'second-show', title: 'Second Show', eventDate: '2026-08-16' },
]

export const page1 = <T>(content: T[], size: number) => ({
  content,
  page: 0,
  size,
  totalElements: content.length,
  // Two pages, so the pagination controls render and get scanned.
  totalPages: 2,
})

/**
 * Every read endpoint the data-driven views touch, answered with something renderable. The
 * matchers are deliberately non-overlapping, hence the lookahead: `/events` has three
 * sub-resources, and Playwright consults handlers in reverse registration order, so an overlap
 * picks the one registered last. The failure is silent: a feed served an object renders an
 * empty state, and axe passes on markup that was never there.
 */
export async function mockBff(page: Page): Promise<void> {
  await page.route(/\/api\/events\/today/, (route) => json(route, eventSummaries))
  // Events are placed on the requested `from` date, so the grid is populated whatever the clock
  // says, as calendar.spec.ts does.
  await page.route(/\/api\/events\/calendar(\?|$)/, (route) => {
    const from = new URL(route.request().url()).searchParams.get('from') ?? '2026-08-15'
    return json(
      route,
      eventSummaries.map((event) => ({ ...event, eventDate: from })),
    )
  })
  await page.route(/\/api\/events\/(?!today|calendar)[^/?]+/, (route) =>
    json(route, {
      slug: 'tonight-show',
      title: 'Tonight Show',
      eventDate: '2026-08-15',
      startTime: '21:00',
      status: 'SCHEDULED',
      venue: { slug: 'mock-venue', name: 'Mock Venue', address: 'Test Str. 1', city: 'Berlin' },
      lineup: [
        {
          artist: { slug: 'mock-artist', name: 'Mock Artist' },
          role: 'HEADLINER',
          billingOrder: 1,
          // A running order, so the sweep reaches its floor headings and time column (#2002).
          stage: 'Main Floor',
          setStart: '2026-08-15T21:00:00+02:00',
          setEnd: '2026-08-15T23:00:00+02:00',
        },
      ],
      promoters: [{ slug: 'mock-promoter', name: 'Mock Promoter' }],
    }),
  )
  await page.route(/\/api\/events(\?|$)/, (route) => json(route, page1(eventSummaries, 20)))
  await page.route(/\/api\/venues(\?|$)/, (route) =>
    json(
      route,
      page1(
        [
          {
            slug: 'mock-venue',
            name: 'Mock Venue',
            city: 'Berlin',
            district: 'kreuzberg',
            latitude: 52.5,
            longitude: 13.45,
          },
          { slug: 'other-venue', name: 'Other Venue', city: 'Berlin' },
        ],
        24,
      ),
    ),
  )
  await page.route(/\/api\/promoters(\?|$)/, (route) =>
    json(
      route,
      page1(
        [
          {
            slug: 'mock-promoter',
            name: 'Mock Promoter',
            websiteUrl: 'https://example.com/',
            description: 'Books the mock nights.',
            descriptionLanguage: 'en',
            upcomingEventCount: 3,
          },
          { slug: 'quiet-promoter', name: 'Quiet Promoter', upcomingEventCount: 0 },
        ],
        24,
      ),
    ),
  )
  await page.route(/\/api\/search(\?|$)/, (route) =>
    json(route, {
      events: { items: eventSummaries, total: 2 },
      venues: { items: [{ slug: 'mock-venue', name: 'Mock Venue', upcomingEventCount: 2 }], total: 1 },
      artists: { items: [{ slug: 'mock-artist', name: 'Mock Artist' }], total: 1 },
      promoters: { items: [{ slug: 'mock-promoter', name: 'Mock Promoter', upcomingEventCount: 3 }], total: 1 },
    }),
  )
  await page.route(/\/api\/genres/, (route) =>
    json(route, [
      { slug: 'techno', name: 'Techno', family: 'electronic' },
      { slug: 'jazz', name: 'Jazz', family: 'jazz-blues' },
    ]),
  )
}
