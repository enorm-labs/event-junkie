import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * The compact view (#1371): the lists as text rows, with the posters gone.
 *
 * The assertion that carries the feature is the network one. A view that only *hides* the posters
 * still downloads them, and the request was partly about bytes on a slow connection — so this
 * counts image requests rather than trusting the absence of an `<img>`.
 */

const PIXEL = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==',
  'base64',
)

function json(route: Route, body: unknown): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

function event(slug: string, title: string) {
  return {
    slug,
    title,
    eventDate: '2026-06-30',
    startTime: '20:00',
    imageUrl: `/api/images/${slug}/192.png`,
    imageSources: [{ type: 'image/png', srcset: `/api/images/${slug}/192.png 192w` }],
    venue: { slug: 'lido', name: 'Lido', city: 'Berlin' },
  }
}

const events = [event('one', 'Event One'), event('two', 'Event Two'), event('three', 'Event Three')]

/**
 * Every *poster* request the page makes, so "no posters" can be proved rather than assumed.
 *
 * Scoped to the image route rather than to `resourceType() === 'image'`: Firefox reports the
 * favicon and the apple-touch icon as images too, and the page is entitled to those.
 */
function collectPosterRequests(page: Page): string[] {
  const urls: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/images/')) urls.push(request.url())
  })
  return urls
}

/** WebKit attaches the posters a beat after the heading, so the assertion has to be retried. */
async function expectPosters(page: Page): Promise<void> {
  await expect(page.locator('img[src*="/api/images/"]').first()).toBeVisible()
}

async function mockBff(page: Page): Promise<void> {
  await page.route('**/api/images/**', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: PIXEL }),
  )
  await page.route(/\/api\/events(\?|$)/, (route) =>
    json(route, { content: events, page: 0, size: 24, totalElements: events.length, totalPages: 1 }),
  )
  await page.route(/\/api\/venues(\?|$)/, (route) =>
    json(route, {
      content: [
        {
          slug: 'lido',
          name: 'Lido',
          city: 'Berlin',
          district: 'kreuzberg',
          imageUrl: '/api/images/lido/192.png',
        },
      ],
      page: 0,
      size: 24,
      totalElements: 1,
      totalPages: 1,
    }),
  )
}

const toCompact = /switch to the compact list/i
const toPoster = /switch to the poster view/i

test('the toggle swaps the posters for text rows, and requests no images', async ({ page }) => {
  await mockBff(page)
  await page.goto('/en/events')
  await expect(page.getByRole('heading', { name: 'Event One' })).toBeVisible()
  await expectPosters(page)

  const posters = collectPosterRequests(page)
  await page.getByRole('button', { name: toCompact }).click()

  // Every event is still listed, and its link still works — this is a second view, not a filter.
  await expect(page.getByRole('heading', { name: 'Event One' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Event Three' })).toBeVisible()
  await expect(page.locator('img')).toHaveCount(0)
  await expect(page.getByRole('link', { name: /Event One/ })).toHaveAttribute(
    'href',
    '/en/events/one',
  )
  expect(posters, 'the compact view must not fetch posters').toEqual([])
})

test('the choice survives a reload and a navigation, and stays out of the URL', async ({ page }) => {
  await mockBff(page)
  await page.goto('/en/events')
  await page.getByRole('button', { name: toCompact }).click()
  await expect(page.locator('img')).toHaveCount(0)
  expect(new URL(page.url()).search, 'a display preference does not belong in a shared link').toBe(
    '',
  )

  const posters = collectPosterRequests(page)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Event One' })).toBeVisible()
  await expect(page.locator('img')).toHaveCount(0)
  // The pre-paint script is what makes this hold: no poster is drawn and then collapsed.
  expect(posters, 'a reload in the compact view must not fetch posters either').toEqual([])

  // The header link, not the footer's copy of it — this is the client-side navigation path.
  await page.getByRole('link', { name: 'Venues' }).first().click()
  await expect(page.getByRole('heading', { level: 2, name: 'Lido' })).toBeVisible()
  await expect(page.locator('img')).toHaveCount(0)
})

test('a first-time visitor gets the poster view', async ({ page }) => {
  await mockBff(page)
  await page.goto('/en/events')

  await expect(page.getByRole('button', { name: toCompact })).toBeVisible()
  await expectPosters(page)
})

test('the toggle reports its own state', async ({ page }) => {
  await mockBff(page)
  await page.goto('/en/events')

  const toggle = page.getByRole('button', { name: toCompact })
  await expect(toggle).toHaveAttribute('aria-pressed', 'false')
  await toggle.click()
  await expect(page.getByRole('button', { name: toPoster })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})
