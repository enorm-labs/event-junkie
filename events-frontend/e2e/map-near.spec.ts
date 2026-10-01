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
