import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Detail-route e2e tests with a fully mocked BFF.
 *
 * The smoke suite skips these routes because they need real data; Playwright's request routing
 * intercepts the BFF instead, so both the happy and the not-found path run with no backend.
 *
 * Endpoints per page:
 *   /events/:slug     → GET /api/events/:slug
 *   /venues/:slug     → GET /api/venues/:slug   + GET /api/events?venue=… (feed)
 *   /artists/:slug    → GET /api/artists/:slug  + GET /api/events?artist=… (feed)
 *   /promoters/:slug  → GET /api/promoters/:slug + GET /api/events?promoter=… (feed)
 *
 * Regexes rather than globs, because the search URL carries a query string that glob wildcards
 * handle awkwardly. The detail and search matchers do not overlap, so registration order is free.
 */

/** Collect uncaught exceptions — the "the app broke" signal, as in the smoke suite. */
function collectPageErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  return errors
}

/** Fulfill a matched request with a JSON body. */
function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

/** Empty paged result for the upcoming-events feed on venue/artist/promoter pages. */
const emptyEventPage = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }

/** An ISO date offset from today. A literal one changes what these tests assert once it passes. */
/** Today in Berlin, the boundary the app treats as the start of "upcoming". */
const todayInBerlin = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date())

function isoDaysFromNow(days: number): string {
  // Anchored on Berlin's calendar date rather than the runner's, because that is what the app
  // computes from (`todayIso` in lib/format). The two agree until the runner is on UTC and Berlin
  // has already turned over — between 22:00 and midnight UTC — and then every assertion built on
  // this is a day out. The arithmetic runs on a midnight-UTC instant so no DST hour can move it.
  const date = new Date(`${todayInBerlin()}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

const eventBody = {
  slug: 'mock-event',
  title: 'Mock Fest',
  eventDate: isoDaysFromNow(30),
  ticketUrl: 'https://tickets.test/buy',
  startTime: '20:00',
  status: 'SCHEDULED',
  venue: { slug: 'mock-venue', name: 'Mock Venue', address: 'Test Str. 1', city: 'Berlin' },
  lineup: [
    { artist: { slug: 'mock-artist', name: 'Mock Artist' }, role: 'HEADLINER', billingOrder: 1 },
  ],
  promoters: [{ slug: 'mock-promoter', name: 'Mock Promoter' }],
}
const venueBody = { slug: 'mock-venue', name: 'Mock Venue', city: 'Berlin' }

/** The smallest valid PNG, so a routed poster request decodes instead of rendering as broken. */
const ONE_PIXEL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
  'base64',
)
const artistBody = { slug: 'mock-artist', name: 'Mock Artist' }
const promoterBody = { slug: 'mock-promoter', name: 'Mock Promoter' }

/** Matches the events search feed (`/api/events?…` or bare `/api/events`), not `/api/events/:slug`. */
const eventsFeed = /\/api\/events(\?|$)/

const detailRoutes = [
  {
    name: 'event',
    path: '/events/mock-event',
    matcher: /\/api\/events\/[^/?]+/,
    body: eventBody,
    heading: 'Mock Fest',
    notFoundHeading: 'Event not found',
  },
  {
    name: 'venue',
    path: '/venues/mock-venue',
    matcher: /\/api\/venues\//,
    body: venueBody,
    heading: 'Mock Venue',
    notFoundHeading: 'Venue not found',
  },
  {
    name: 'artist',
    path: '/artists/mock-artist',
    matcher: /\/api\/artists\//,
    body: artistBody,
    heading: 'Mock Artist',
    notFoundHeading: 'Artist not found',
  },
  {
    name: 'promoter',
    path: '/promoters/mock-promoter',
    matcher: /\/api\/promoters\//,
    body: promoterBody,
    heading: 'Mock Promoter',
    notFoundHeading: 'Promoter not found',
  },
] as const

test.beforeEach(async ({ page }) => {
  // Venue/artist/promoter pages also load an upcoming-events feed; stub it empty so tests
  // are deterministic and never fall through to the network. Harmless for the event page,
  // which never hits this endpoint.
  await page.route(eventsFeed, (route) => json(route, emptyEventPage))
})

for (const detail of detailRoutes) {
  test.describe(`${detail.name} detail page`, () => {
    test('renders the page when the API returns data', async ({ page }) => {
      const errors = collectPageErrors(page)
      await page.route(detail.matcher, (route) => json(route, detail.body))

      await page.goto(detail.path)

      await expect(page.getByRole('heading', { level: 1, name: detail.heading })).toBeVisible()
      expect(errors, 'unexpected uncaught exceptions').toEqual([])
    })

    test('shows the not-found state on a 404', async ({ page }) => {
      await page.route(detail.matcher, (route) => json(route, { message: 'not found' }, 404))

      await page.goto(detail.path)

      await expect(
        page.getByRole('heading', { level: 1, name: detail.notFoundHeading }),
      ).toBeVisible()
    })

    test('shows a reloadable error state on a 500', async ({ page }) => {
      await page.route(detail.matcher, (route) => json(route, { message: 'boom' }, 500))

      await page.goto(detail.path)

      // A 500 is the `error` branch (not `notFound`): the view renders the describeError
      // message, which always opens with "Couldn't load …", not a heading. Asserting that
      // copy distinguishes the error state from both success and the 404 empty state.
      await expect(page.getByText(/couldn't load/i)).toBeVisible()
      await expect(page.getByRole('heading', { level: 1, name: detail.heading })).toHaveCount(0)
      await expect(
        page.getByRole('heading', { level: 1, name: detail.notFoundHeading }),
      ).toHaveCount(0)
    })
  })
}

test.describe('a past event', () => {
  const pastEventBody = { ...eventBody, eventDate: isoDaysFromNow(-30) }

  // Links shared in a group chat outlive the event. This is what stops a deletion policy
  // (#350) turning them into 404s without failing a test first.
  test('still resolves rather than 404-ing', async ({ page }) => {
    const errors = collectPageErrors(page)
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, pastEventBody))

    await page.goto('/events/mock-event')

    await expect(page.getByRole('heading', { level: 1, name: 'Mock Fest' })).toBeVisible()
    await expect(page.getByText('Event not found')).toHaveCount(0)
    expect(errors, 'unexpected uncaught exceptions').toEqual([])
  })

  test('says it has taken place and stops selling tickets for it', async ({ page }) => {
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, pastEventBody))

    await page.goto('/events/mock-event')

    await expect(page.getByText('This event has already taken place.')).toBeVisible()
    await expect(page.getByRole('link', { name: 'Buy tickets' })).toHaveCount(0)
  })

  test('an upcoming event still offers its tickets', async ({ page }) => {
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, eventBody))

    await page.goto('/events/mock-event')

    await expect(page.getByRole('link', { name: 'Buy tickets' })).toBeVisible()
    await expect(page.getByText('This event has already taken place.')).toHaveCount(0)
  })

  test('an all-headliner co-bill lists its acts without role labels', async ({ page }) => {
    // A venue that bills `A + B + C` names no order, and the importer stores every act as a
    // headliner. Three "Headliner" tags would only repeat the list.
    const coBill = {
      ...eventBody,
      lineup: [
        { artist: { slug: 'alibi', name: 'Alibi' }, role: 'HEADLINER', billingOrder: 0 },
        { artist: { slug: 'onyon', name: 'Onyon' }, role: 'HEADLINER', billingOrder: 1 },
      ],
    }
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, coBill))

    await page.goto('/events/mock-event')

    await expect(page.getByRole('link', { name: 'Onyon' })).toBeVisible()
    await expect(page.getByText('Headliner', { exact: true })).toHaveCount(0)
  })

  test('an all-DJ night keeps its role labels', async ({ page }) => {
    // Uniform is not the test — `DJ` still says the acts play records rather than live.
    const djNight = {
      ...eventBody,
      lineup: [
        { artist: { slug: 'dj-one', name: 'DJ One' }, role: 'DJ', billingOrder: 0 },
        { artist: { slug: 'dj-two', name: 'DJ Two' }, role: 'DJ', billingOrder: 1 },
      ],
    }
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, djNight))

    await page.goto('/events/mock-event')

    await expect(page.getByText('DJ', { exact: true })).toHaveCount(2)
  })

  test('keeps a gap between the poster and the description', async ({ page }) => {
    // `space-y-8` puts its margin on the element before the gap, and the cached-image <picture>
    // is `display: contents`, so without a wrapper the poster sat flush against the description.
    const withPoster = {
      ...eventBody,
      description: 'Doors at eight.',
      imageUrl: '/api/images/poster/704.jpg',
      imageSources: [{ type: 'image/jpeg', srcset: '/api/images/poster/704.jpg 704w' }],
      intrinsicWidth: 704,
      intrinsicHeight: 469,
    }
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, withPoster))
    await page.route(/\/api\/images\//, (route) =>
      route.fulfill({ status: 200, contentType: 'image/png', body: ONE_PIXEL_PNG }),
    )

    await page.goto('/events/mock-event')

    const poster = await page.getByRole('img', { name: 'Mock Fest' }).boundingBox()
    const description = await page.getByText('Doors at eight.').boundingBox()
    expect(poster).not.toBeNull()
    expect(description).not.toBeNull()
    expect(description!.y - (poster!.y + poster!.height)).toBeGreaterThanOrEqual(32)
  })

  test('a mixed lineup labels every act', async ({ page }) => {
    const mixed = {
      ...eventBody,
      lineup: [
        { artist: { slug: 'main', name: 'Main Act' }, role: 'HEADLINER', billingOrder: 0 },
        { artist: { slug: 'opener', name: 'Opener' }, role: 'SUPPORT', billingOrder: 1 },
      ],
    }
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, mixed))

    await page.goto('/events/mock-event')

    await expect(page.getByText('Headliner', { exact: true })).toBeVisible()
    await expect(page.getByText('Support', { exact: true })).toBeVisible()
  })
})

test('a venue with only past events shows them as an archive', async ({ page }) => {
  const pastEvent = {
    slug: 'past-night',
    title: 'Past Night',
    eventDate: isoDaysFromNow(-14),
    venue: { slug: 'mock-venue', name: 'Mock Venue' },
    genreTags: [],
  }
  await page.route(/\/api\/venues\//, (route) => json(route, venueBody))
  // The two feeds hit one endpoint and differ only by the archive's `to` bound.
  await page.route(eventsFeed, (route) =>
    route.request().url().includes('to=')
      ? json(route, { ...emptyEventPage, content: [pastEvent], totalElements: 1 })
      : json(route, emptyEventPage),
  )

  await page.goto('/venues/mock-venue')

  await page.getByText('Past events').click()
  await expect(page.getByRole('link', { name: /Past Night/ })).toBeVisible()
  await expect(page.getByText(/archive starts when we started watching/i)).toBeVisible()
})

test('links nested entities and navigates from an event to its venue', async ({ page }) => {
  const errors = collectPageErrors(page)
  await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, eventBody))
  await page.route(/\/api\/venues\//, (route) => json(route, venueBody))

  await page.goto('/events/mock-event')

  // Nested data is bound into working router links, locale-prefixed (ADR-013 §Decision 2).
  await expect(page.getByRole('link', { name: 'Mock Artist' })).toHaveAttribute(
    'href',
    '/en/artists/mock-artist',
  )
  const venueLink = page.getByRole('link', { name: 'Mock Venue' })
  // In-app links are locale-prefixed (ADR-013 §Decision 2).
  await expect(venueLink).toHaveAttribute('href', '/en/venues/mock-venue')

  await venueLink.click()

  await expect(page).toHaveURL(/\/venues\/mock-venue$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Mock Venue' })).toBeVisible()
  expect(errors, 'unexpected uncaught exceptions').toEqual([])
})
