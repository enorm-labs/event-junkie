import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Venues overview e2e tests with a fully mocked BFF.
 *
 * The list route (`/venues`) is data-driven, so like the detail routes it needs the BFF
 * intercepted with Playwright's request routing — happy path, search, empty state, and
 * pagination are all exercised deterministically without a running backend.
 *
 * Endpoint: GET /api/venues?q=&district=&sort=&page=&size= → PageResponseVenueListItemResponse
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

/** Matches the venue list (`/api/venues?…` or bare `/api/venues`), not `/api/venues/:slug`. */
const venuesList = /\/api\/venues(\?|$)/

function venue(slug: string, name: string, upcomingEventCount = 0) {
  return { slug, name, city: 'Berlin', district: 'kreuzberg', upcomingEventCount }
}

function pageBody(content: ReturnType<typeof venue>[], page = 0, totalPages = 1) {
  return { content, page, size: 24, totalElements: content.length, totalPages }
}

test('lists venues returned by the API', async ({ page }) => {
  const errors = collectPageErrors(page)
  await page.route(venuesList, (route) =>
    json(route, pageBody([venue('lido', 'Lido'), venue('astra', 'Astra Kulturhaus')])),
  )

  await page.goto('/venues')

  await expect(page.getByRole('heading', { level: 1, name: 'Venues' })).toBeVisible()
  // In-app links are locale-prefixed (ADR-013 §Decision 2).
  await expect(page.getByRole('link', { name: /Lido/ })).toHaveAttribute('href', '/en/venues/lido')
  await expect(page.getByRole('link', { name: /Astra Kulturhaus/ })).toBeVisible()
  expect(errors, 'unexpected uncaught exceptions').toEqual([])
})

test('searching updates the URL query and re-requests', async ({ page }) => {
  await page.route(venuesList, (route) => {
    const q = new URL(route.request().url()).searchParams.get('q')
    json(route, pageBody(q === 'lido' ? [venue('lido', 'Lido')] : [venue('astra', 'Astra')]))
  })

  await page.goto('/venues')
  await page.getByPlaceholder('Search venues…').fill('lido')
  await page.getByPlaceholder('Search venues…').press('Enter')

  await expect(page).toHaveURL(/\/venues\?q=lido$/)
  await expect(page.getByRole('link', { name: /Lido/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Astra/ })).toHaveCount(0)
})

test('filtering by several districts updates the URL query and re-requests', async ({ page }) => {
  await page.route(venuesList, (route) => {
    const districts = new URL(route.request().url()).searchParams.getAll('district').join()
    const body = {
      mitte: [venue('berghain', 'Berghain')],
      'kreuzberg,mitte': [venue('berghain', 'Berghain'), venue('lido', 'Lido')],
    }[districts] ?? [venue('lido', 'Lido'), venue('astra', 'Astra')]
    json(route, pageBody(body))
  })

  await page.goto('/venues')
  await page.getByRole('button', { name: 'Filter by district: All districts' }).click()
  await page.getByRole('checkbox', { name: 'Mitte' }).check()

  await expect(page).toHaveURL(/\/venues\?district=mitte$/)
  await expect(page.getByRole('link', { name: /Berghain/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Lido/ })).toHaveCount(0)

  await page.getByRole('checkbox', { name: 'Kreuzberg' }).check()
  await expect(page).toHaveURL(/\/venues\?district=kreuzberg&district=mitte$/)
  await expect(page.getByRole('link', { name: /Lido/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Astra/ })).toHaveCount(0)
})

test('sorting by upcoming events puts the sort in the URL, sends it, and shows the counts', async ({
  page,
}) => {
  await page.route(venuesList, (route) => {
    const sort = new URL(route.request().url()).searchParams.get('sort')
    json(
      route,
      pageBody(
        sort === 'upcomingEvents,desc'
          ? [venue('lido', 'Lido', 12), venue('astra', 'Astra', 1)]
          : [venue('astra', 'Astra', 1), venue('lido', 'Lido', 12)],
      ),
    )
  })

  await page.goto('/venues')
  await expect(page.getByRole('heading', { level: 2 }).first()).toHaveText('Astra')
  await expect(page.getByText('12 upcoming events')).toBeVisible()

  const sort = page.getByRole('group', { name: 'Sort' })
  await sort.getByRole('button', { name: 'Most upcoming' }).click()

  await expect(page).toHaveURL(/\/venues\?sort=upcomingEvents,desc$/)
  await expect(page.getByRole('heading', { level: 2 }).first()).toHaveText('Lido')

  await sort.getByRole('button', { name: 'A–Z' }).click()
  await expect(page).toHaveURL(/\/venues$/)
})

test('filtering by venue type, genre and event type writes each to the URL and the request', async ({
  page,
}) => {
  await page.route(venuesList, (route) => {
    const params = new URL(route.request().url()).searchParams
    const narrowed = params.getAll('type').includes('club') && params.get('family') === 'electronic'
    json(route, pageBody(narrowed ? [venue('berghain', 'Berghain')] : [venue('lido', 'Lido')]))
  })

  await page.goto('/venues')
  await page.getByRole('button', { name: 'Filter by venue type: All venue types' }).click()
  await page.getByRole('checkbox', { name: 'Club' }).check()
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Filter by genre: All genres' }).click()
  await page.getByRole('checkbox', { name: 'Electronic' }).check()
  await page.keyboard.press('Escape')

  await expect(page).toHaveURL(/[?&]type=club\b/)
  await expect(page).toHaveURL(/[?&]family=electronic\b/)
  await expect(page.getByRole('link', { name: /Berghain/ })).toBeVisible()

  await page.getByRole('button', { name: 'Filter by event type: All event types' }).click()
  await expect(page.getByRole('checkbox', { name: 'Other' })).toHaveCount(0)
  await page.getByRole('checkbox', { name: 'Party' }).check()
  await expect(page).toHaveURL(/[?&]eventType=PARTY\b/)
})

test('shows an empty state when no venues match', async ({ page }) => {
  await page.route(venuesList, (route) => json(route, pageBody([])))

  await page.goto('/venues')

  await expect(page.getByText(/no venues match/i)).toBeVisible()
})

test('the empty state offers a way out of the search', async ({ page }) => {
  // A sentence with no control is a dead end, the same one the events list had (#1266).
  await page.route(venuesList, (route) => {
    const q = new URL(route.request().url()).searchParams.get('q')
    return json(route, q ? pageBody([]) : pageBody([venue('lido', 'Lido')]))
  })

  await page.goto('/venues?q=nothing')
  await expect(page.getByText(/no venues match/i)).toBeVisible()

  await page.getByRole('button', { name: 'Clear the search' }).click()

  await expect(page).toHaveURL(/\/venues$/)
  await expect(page.getByRole('link', { name: /Lido/ })).toBeVisible()
})

test('paginates when there is more than one page', async ({ page }) => {
  await page.route(venuesList, (route) => {
    const pageParam = Number(new URL(route.request().url()).searchParams.get('page') ?? '0')
    const body = pageBody([venue(`v${pageParam}`, `Venue ${pageParam}`)], pageParam, 2)
    // totalElements drives the counter; keep it above one page for realism.
    json(route, { ...body, totalElements: 2 })
  })

  await page.goto('/venues')
  await expect(page.getByText('Page 1 of 2')).toBeVisible()

  await page.getByRole('button', { name: 'Next' }).click()

  await expect(page).toHaveURL(/\/venues\?page=1$/)
  await expect(page.getByText('Page 2 of 2')).toBeVisible()
})

test('the venues map lists the venues near me, nearest first, and sends the position nowhere', async ({
  page,
  context,
}) => {
  const requested: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/')) requested.push(request.url())
  })
  await page.route(venuesList, (route) =>
    json(route, {
      content: [
        { ...venue('far', 'Far Venue'), latitude: 52.535, longitude: 13.2 },
        { ...venue('astra', 'Astra'), latitude: 52.5072, longitude: 13.4518 },
        { ...venue('lido', 'Lido'), latitude: 52.4995, longitude: 13.4448 },
      ],
      page: 0,
      size: 100,
      totalElements: 3,
      totalPages: 1,
    }),
  )
  await context.grantPermissions(['geolocation'])
  // At Lido's door: Astra is about 1 km away, the far venue about 17 km.
  await context.setGeolocation({ latitude: 52.4995, longitude: 13.4448 })
  await page.goto('/en/venues?view=map')

  await page.getByRole('button', { name: 'Use my location' }).click()

  await expect(page.getByRole('heading', { level: 2, name: 'Within 2 km' })).toBeVisible()
  await expect(page.getByText('2 venues, nearest first')).toBeVisible()
  await expect(page.getByRole('heading', { level: 3 })).toHaveText(['Lido', 'Astra'])
  await expect(page.getByText('50 m')).toBeVisible()

  await page.getByRole('button', { name: '5 km' }).click()
  await expect(page).toHaveURL(/radius=5/)
  await expect(page.getByRole('heading', { level: 3 })).toHaveCount(2)

  expect(page.url()).not.toMatch(/52\.4995|13\.4448|lat|lng/)
  expect(requested.filter((url) => /52\.4995|13\.4448|lat|lng/.test(url))).toEqual([])
})

test('a pin opens its venue over the map, and closing returns to the pin', async ({ page }) => {
  await page.route(venuesList, (route) =>
    json(route, {
      content: [
        {
          ...venue('lido', 'Lido'),
          address: 'Cuvrystraße 7',
          latitude: 52.4995,
          longitude: 13.4448,
        },
        { ...venue('astra', 'Astra'), latitude: 52.5072, longitude: 13.4518 },
      ],
      page: 0,
      size: 100,
      totalElements: 2,
      totalPages: 1,
    }),
  )
  await page.goto('/en/venues?view=map')

  const pin = page.getByRole('button', { name: 'Lido', exact: true })
  const unavailable = page.getByText(/cannot draw the map/)
  await expect(pin.or(unavailable)).toBeVisible()
  test.skip(await unavailable.isVisible(), 'no WebGL in this browser')
  await expect(page.getByText('Pick a pin to see the venue.')).toBeVisible()

  await pin.click()
  const panel = page.locator('section', { has: page.getByRole('heading', { name: 'Lido' }) })
  await expect(panel.getByRole('link', { name: 'Lido' })).toHaveAttribute('href', '/en/venues/lido')
  await expect(panel.getByText('Cuvrystraße 7 · Kreuzberg')).toBeVisible()
  await expect(panel.getByRole('link', { name: 'What is on here, on the map' })).toHaveAttribute(
    'href',
    '/en/map?venue=lido',
  )
  // Inside the map, not after it: the panel is what a pin click brings into view.
  await expect(page.locator('div:has(> .venue-map) section')).toHaveCount(1)
  await expect(page.getByText('Pick a pin to see the venue.')).toHaveCount(0)

  await panel.getByRole('button', { name: 'Close' }).click()
  await expect(panel).toHaveCount(0)
  await expect(pin).toBeFocused()
})
