import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Coming back to a list with the browser's back gesture has to land where the visitor left it,
 * not at the top (#1111). Two halves are under test: the router returning `savedPosition` on a
 * history traversal, and the list repainting from `useAsync`'s cache before the router scrolls —
 * a list that mounts on its loading state is too short to scroll to. Found on a phone, which is
 * why this runs on the mobile projects too.
 */

function json(route: Route, body: unknown): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

const slugify = (title: string) => title.toLowerCase().replace(/\s+/g, '-')

/** Enough cards to push the one opened below well past the fold on every viewport. */
const titles = Array.from({ length: 40 }, (_, i) => `Long List Gig ${i + 1}`)
const events = titles.map((title) => ({ slug: slugify(title), title, eventDate: '2026-08-15' }))
const opened = events[29]

const eventsFeed = /\/api\/events(\?|$)/
const eventDetail = /\/api\/events\/[^/?]+/

const scrollY = (page: Page) => page.evaluate(() => window.scrollY)

test.beforeEach(async ({ page }) => {
  await page.route(eventsFeed, (route) =>
    json(route, { content: events, page: 0, size: 40, totalElements: 40, totalPages: 1 }),
  )
  await page.route(/\/api\/events\/today/, (route) => json(route, events.slice(0, 20)))
  await page.route(eventDetail, (route) =>
    json(route, {
      ...opened,
      startTime: '20:00',
      status: 'SCHEDULED',
      venue: { slug: 'mock-venue', name: 'Mock Venue', city: 'Berlin' },
    }),
  )
  await page.route(/\/api\/genres/, (route) => json(route, []))
  await page.route(/\/api\/venues/, (route) =>
    json(route, { content: [], page: 0, size: 500, totalElements: 0, totalPages: 0 }),
  )
})

/**
 * The offset once it has stopped moving. The stamp artwork above the fold is a lazy chunk, and
 * the browser's scroll anchoring shifts the offset when content above the viewport grows — the
 * number to come back to is the one after that, so a reading counts only when two more agree.
 */
async function settledScrollY(page: Page): Promise<number> {
  let previous = Number.NaN
  let stableFor = 0
  await expect
    .poll(
      async () => {
        const current = await scrollY(page)
        stableFor = current === previous ? stableFor + 1 : 0
        previous = current
        return stableFor
      },
      { intervals: [100] },
    )
    .toBeGreaterThanOrEqual(2)
  return previous
}

/** Scrolls the card into view the way a visitor would before tapping it, and reports the offset. */
async function bringIntoView(page: Page, card: ReturnType<Page['getByRole']>): Promise<number> {
  await card.scrollIntoViewIfNeeded()
  const left = await settledScrollY(page)
  expect(left).toBeGreaterThan(500)
  return left
}

async function openAndComeBack(page: Page, card: ReturnType<Page['getByRole']>, left: number) {
  await card.click()
  await expect(page.getByRole('heading', { level: 1, name: opened.title })).toBeVisible()
  // A push still opens the new page at the top.
  expect(await scrollY(page)).toBeLessThan(50)

  await page.goBack()
  await expect(card).toBeVisible()
  await expect.poll(async () => Math.abs((await scrollY(page)) - left)).toBeLessThan(5)
}

test('returns to where the visitor left the events list', async ({ page }) => {
  await page.goto('/events')
  await expect(page.getByRole('heading', { level: 2, name: 'Long List Gig 40' })).toBeVisible()

  const card = page.getByRole('link', { name: /Long List Gig 30\b/ })
  const left = await bringIntoView(page, card)
  await openAndComeBack(page, card, left)
})

test('returns to where the visitor left the home feed', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 3, name: 'Long List Gig 40' })).toBeVisible()

  // Only in the Upcoming feed: Tonight carries the first twenty.
  const card = page.getByRole('link', { name: /Long List Gig 30\b/ })
  const left = await bringIntoView(page, card)
  await openAndComeBack(page, card, left)
})
