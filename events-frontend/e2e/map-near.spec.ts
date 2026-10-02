import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * "Near me" and "on now" on the events map (#358), with a mocked BFF. Nothing here needs WebGL:
 * the nearby list below the map renders whether or not the map can draw.
 *
 * The clock sits at 22:00 Berlin on 2026-08-15. The 21:00 show at Lido is on now; the 23:30 one at
 * the far venue is not yet.
 */

const NOW = new Date('2026-08-15T20:00:00Z')

const LIDO = { slug: 'lido', name: 'Lido', latitude: 52.4995, longitude: 13.4448 }
const ASTRA = { slug: 'astra', name: 'Astra', latitude: 52.5072, longitude: 13.4518 }
/** In Spandau, far outside every radius from Kreuzberg. */
const FAR = { slug: 'far', name: 'Far Venue', latitude: 52.535, longitude: 13.2 }

const events = [
  {
    slug: 'lido-show',
    title: 'Lido Show',
    eventDate: '2026-08-15',
    startTime: '21:00',
    venue: LIDO,
  },
  {
    slug: 'astra-show',
    title: 'Astra Show',
    eventDate: '2026-08-15',
    startTime: '23:00',
    venue: ASTRA,
  },
  { slug: 'far-show', title: 'Far Show', eventDate: '2026-08-15', startTime: '23:30', venue: FAR },
]

function json(route: Route, body: unknown): Promise<void> {
  return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
}

/** Mocks the two endpoints the map reads, and returns every API URL the page requested. */
async function mockBff(page: Page): Promise<string[]> {
  const requested: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/')) requested.push(request.url())
  })
  await page.route(/\/api\/events\/calendar(\?|$)/, (route) => json(route, events))
  await page.route(/\/api\/venues(\?|$)/, (route) =>
    json(route, {
      content: [LIDO, ASTRA, FAR],
      page: 0,
      size: 100,
      totalElements: 3,
      totalPages: 1,
    }),
  )
  return requested
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(NOW)
})

test('near me lists the venues within the radius, nearest first, and sends the position nowhere', async ({
  page,
  context,
}) => {
  await context.grantPermissions(['geolocation'])
  // At Lido's door: Astra is about 1 km away, the far venue about 17 km.
  await context.setGeolocation({ latitude: 52.4995, longitude: 13.4448 })
  const requested = await mockBff(page)
  await page.goto('/en/map')
  await expect(page.getByText('3 events at 3 venues')).toBeVisible()

  await page.getByRole('button', { name: 'Use my location' }).click()

  const heading = page.getByRole('heading', { level: 2, name: 'Within 2 km' })
  await expect(heading).toBeVisible()
  await expect(page.getByRole('heading', { level: 3 })).toHaveText([
    /Lido\s*50 m/,
    /Astra\s*1 km/,
  ])
  await expect(page.locator('section', { has: heading }).getByText('Far Show')).toHaveCount(0)

  // A wider radius is a URL state; the position is not.
  await page.getByRole('button', { name: '5 km' }).click()
  await expect(page).toHaveURL(/radius=5/)
  await expect(page.getByRole('heading', { level: 2, name: 'Within 5 km' })).toBeVisible()

  expect(page.url()).not.toMatch(/52\.4995|13\.4448|lat|lng/)
  expect(requested.filter((url) => /52\.4995|13\.4448|lat|lng/.test(url))).toEqual([])
})

test('near a venue needs no permission', async ({ page }) => {
  await mockBff(page)
  await page.goto('/en/map')

  await page.getByRole('combobox', { name: 'Near a venue…' }).selectOption('astra')

  await expect(page.getByText('Around Astra')).toBeVisible()
  await expect(page.getByRole('heading', { level: 3 })).toHaveText([
    /Astra\s*50 m/,
    /Lido\s*1 km/,
  ])
})

test('on now keeps only what is running, and marks it', async ({ page }) => {
  await mockBff(page)
  await page.goto('/en/map')
  await page.getByRole('combobox', { name: 'Near a venue…' }).selectOption('astra')
  await expect(page.getByRole('heading', { level: 3 })).toHaveCount(2)

  await page.getByRole('button', { name: 'On now', exact: true }).click()

  await expect(page).toHaveURL(/now=1/)
  // Astra's 23:00 show has not started; Lido's 21:00 one has, and its row says so.
  await expect(page.getByRole('heading', { level: 3 })).toHaveText([/Lido/])
  await expect(page.getByRole('link', { name: /Lido Show/ })).toContainText('On now')
})

test('a show that ended earlier today leaves the map; the archive keeps it', async ({ page }) => {
  await mockBff(page)
  // Registered last, so it answers first. Over at 18:00, four hours before the clock.
  const ended = { ...events[1], slug: 'matinee', title: 'Matinee', endTime: '18:00' }
  await page.route(/\/api\/events\/calendar(\?|$)/, (route) => json(route, [...events, ended]))

  await page.goto('/en/map')
  await expect(page.getByText('3 events at 3 venues')).toBeVisible()

  await page.goto('/en/map?from=2026-08-14&to=2026-08-15')
  await expect(page.getByText('4 events at 3 venues')).toBeVisible()
})

test('a pin opens its venue over the map, three events deep, and closing returns to the pin', async ({
  page,
}) => {
  await mockBff(page)
  const late = ['One', 'Two', 'Three', 'Four'].map((n, i) => ({
    slug: `lido-late-${i}`,
    title: `Lido Late ${n}`,
    eventDate: '2026-08-15',
    startTime: '23:45',
    venue: LIDO,
  }))
  await page.route(/\/api\/events\/calendar(\?|$)/, (route) => json(route, [...events, ...late]))
  await page.goto('/en/map')

  // Five at Lido carry a count; Astra's single show keeps the pin's size without a digit.
  const pin = page.getByRole('button', { name: /^Lido: 5 events/ })
  const unavailable = page.getByText(/cannot draw the map/)
  await expect(pin.or(unavailable)).toBeVisible()
  test.skip(await unavailable.isVisible(), 'no WebGL in this browser')
  await expect(pin).toHaveText('5')
  await expect(page.getByRole('button', { name: 'Astra: 1 event', exact: true })).toHaveText('')

  await pin.click()
  const panel = page.locator('section', { has: page.getByRole('heading', { name: 'Lido' }) })
  await expect(panel.getByRole('heading', { level: 3 })).toHaveCount(3)
  // The list over the map's own day, not every date the venue has.
  const href = await panel.getByRole('link', { name: 'All 5 events here' }).getAttribute('href')
  const url = new URL(href ?? '', 'http://x')
  expect(url.pathname).toBe('/en/events')
  expect(Object.fromEntries(url.searchParams)).toEqual({
    venue: 'lido',
    from: '2026-08-15',
    to: '2026-08-15',
  })

  await panel.getByRole('button', { name: 'Close' }).click()
  await expect(panel).toHaveCount(0)
  await expect(pin).toBeFocused()
})

test('with no dates in the URL the map shows today, so Tonight reads as pressed', async ({
  page,
}) => {
  await mockBff(page)
  await page.goto('/en/map')
  await expect(page.getByText('3 events at 3 venues')).toBeVisible()

  await expect(page.getByRole('button', { name: 'Tonight' })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('button', { name: 'This weekend' })).toHaveAttribute(
    'aria-pressed',
    'false',
  )
  // "On now" is a time, so it sits with the presets rather than with the location controls.
  const times = page.locator('div', { has: page.getByRole('button', { name: 'Tonight' }) }).last()
  await expect(times.getByRole('button', { name: 'On now', exact: true })).toBeVisible()
})

test('a single event at a single venue reads in the singular, in both languages', async ({
  page,
}) => {
  // #2370: two counts shared one message, so one event read "1 events at 1 venues".
  await mockBff(page)
  await page.route(/\/api\/events\/calendar(\?|$)/, (route) => json(route, [events[0]]))

  await page.goto('/en/map')
  await expect(page.getByText('1 event at 1 venue', { exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: 'Near a venue…' }).selectOption('lido')
  await expect(page.getByText('1 event at 1 venue, nearest first')).toBeVisible()

  await page.goto('/de/map')
  await expect(page.getByText('1 Event in 1 Location', { exact: true })).toBeVisible()
})
