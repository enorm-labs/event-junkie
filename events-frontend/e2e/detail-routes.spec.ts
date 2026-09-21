import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Detail-route e2e tests with a fully mocked BFF: Playwright's request routing intercepts it, so
 * the happy and the not-found path run with no backend. Endpoints per page:
 * /events/:slug     GET /api/events/:slug
 * /venues/:slug     GET /api/venues/:slug   + GET /api/events?venue=… (feed)
 * /artists/:slug    GET /api/artists/:slug  + GET /api/events?artist=… (feed)
 * /promoters/:slug  GET /api/promoters/:slug + GET /api/events?promoter=… (feed)
 *
 * Regexes rather than globs, because the search URL carries a query string. The detail and
 * search matchers do not overlap, so registration order is free.
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
  // Anchored on Berlin's calendar date, which the app computes from (`todayIso` in lib/format):
  // between 22:00 and midnight UTC the runner's date is a day behind. Midnight-UTC arithmetic so no
  // DST hour can move it.
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
const artistBody = {
  slug: 'mock-artist',
  name: 'Mock Artist',
  bandcampUrl: 'https://mock-artist.bandcamp.com/',
  residentAdvisorUrl: 'https://ra.co/dj/mock-artist',
  musicbrainzUrl: 'https://musicbrainz.org/artist/41f4d85a-0bd7-4602-a3e3-8c47f36efb0a',
}
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
  // Venue/artist/promoter pages also load an upcoming-events feed; stub it empty so nothing falls
  // through to the network.
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

      // A 500 is the `error` branch, not `notFound`: the describeError message opens with
      // "Couldn't load …", which distinguishes it from success and the 404 empty state.
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

  // Links shared in a group chat outlive the event; this stops a deletion policy (#350) turning
  // them into 404s without failing a test first.
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

  test('points at what is coming up at the venue', async ({ page }) => {
    // A search engine keeps sending people here after the night (#293), and the page offers the
    // venue's own site as the onward link (#1268).
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, pastEventBody))

    await page.goto('/events/mock-event')

    const onward = page.getByRole('link', { name: /coming up at Mock Venue/i })
    await expect(onward).toBeVisible()
    await onward.click()
    await expect(page).toHaveURL(/\/venues\/mock-venue$/)
  })

  test('falls back to the list when the event has no venue', async ({ page }) => {
    await page.route(/\/api\/events\/[^/?]+/, (route) =>
      json(route, { ...pastEventBody, venue: undefined }),
    )

    await page.goto('/events/mock-event')

    await expect(page.getByRole('link', { name: /what is on now/i })).toBeVisible()
  })

  test('an upcoming event still offers its tickets', async ({ page }) => {
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, eventBody))

    await page.goto('/events/mock-event')

    await expect(page.getByRole('link', { name: 'Buy tickets' })).toBeVisible()
    await expect(page.getByText('This event has already taken place.')).toHaveCount(0)
  })

  test('an all-headliner co-bill lists its acts without role labels', async ({ page }) => {
    // A venue that bills `A + B + C` names no order, so three "Headliner" tags would only repeat
    // the list.
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
    // `space-y-8` puts its margin on the element before the gap, and the cached-image <picture> is
    // `display: contents`, so without a wrapper the poster sat flush against the description.
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

  test('marks the language of a description the page locale does not match', async ({ page }) => {
    // A German text under English chrome is what the venue wrote; the `lang` attribute is the part
    // we can get wrong.
    const german = {
      ...eventBody,
      description: 'Ein Abend mit Aussicht.',
      descriptionLanguage: 'de',
    }
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, german))

    await page.goto('/en/events/mock-event')

    await expect(page.getByText('Ein Abend mit Aussicht.')).toHaveAttribute('lang', 'de')
  })

  test('discloses a machine translation and links to the original', async ({ page }) => {
    const translated = {
      ...eventBody,
      description: 'Ein Abend mit Aussicht.',
      descriptionLanguage: 'de',
      descriptionAlt: 'An evening with a view.',
      descriptionAltLanguage: 'en',
      descriptionAltOrigin: 'MACHINE',
      sourceUrl: 'https://example.test/event',
    }
    await page.route(/\/api\/events\/[^/?]+/, (route) => json(route, translated))

    await page.goto('/en/events/mock-event')

    await expect(page.getByText('An evening with a view.')).toHaveAttribute('lang', 'en')
    await expect(page.getByText('Machine-translated.', { exact: false })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Original text' })).toHaveAttribute(
      'href',
      'https://example.test/event',
    )
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

test('serves the venue description in the page language', async ({ page }) => {
  // Our own prose in both languages (#1210), neither disclosed as machine-made; `lang` is what a
  // screen reader reads it with.
  const bilingual = {
    ...venueBody,
    description: 'A former cinema on the canal.',
    descriptionLanguage: 'en',
    descriptionAlt: 'Ein früheres Kino am Kanal.',
    descriptionAltLanguage: 'de',
  }
  await page.route(/\/api\/venues\//, (route) => json(route, bilingual))
  await page.route(eventsFeed, (route) => json(route, emptyEventPage))

  await page.goto('/de/venues/mock-venue')
  await expect(page.getByText('Ein früheres Kino am Kanal.')).toHaveAttribute('lang', 'de')
  await expect(page.getByText('Machine-translated.', { exact: false })).toHaveCount(0)

  await page.goto('/en/venues/mock-venue')
  await expect(page.getByText('A former cinema on the canal.')).toHaveAttribute('lang', 'en')
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

test('an artist page links the profiles MusicBrainz filled, and MusicBrainz itself', async ({
  page,
}) => {
  await page.route(/\/api\/artists\//, (route) => json(route, artistBody))

  await page.goto('/artists/mock-artist')

  await expect(page.getByRole('link', { name: 'Bandcamp' })).toHaveAttribute(
    'href',
    'https://mock-artist.bandcamp.com/',
  )
  await expect(page.getByRole('link', { name: 'Resident Advisor' })).toHaveAttribute(
    'href',
    'https://ra.co/dj/mock-artist',
  )
  // The correction path (ADR-031): a wrong match is fixed at MusicBrainz, and a visitor can see it.
  await expect(page.getByRole('link', { name: 'MusicBrainz' })).toHaveAttribute(
    'href',
    'https://musicbrainz.org/artist/41f4d85a-0bd7-4602-a3e3-8c47f36efb0a',
  )
  // A column the enrichment did not fill draws no link.
  await expect(page.getByRole('link', { name: 'Spotify' })).toHaveCount(0)
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
