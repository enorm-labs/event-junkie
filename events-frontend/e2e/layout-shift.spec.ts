import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * A page must not move while it loads. The fetch is held back so the loading state paints first,
 * as a visitor on a slow line sees it. Without the screen-tall `#main-content` in `App.vue`, the
 * footer painted above the fold and then dropped, which scored 0.3 to 0.8 (#1207).
 */

// eslint-disable-next-line playwright/no-skipped-test -- the Layout Instability API is Chromium's alone
test.skip(({ browserName }) => browserName !== 'chromium', 'layout-shift entries are Chromium-only')

/** Google's line for a good score. */
const GOOD_CLS = 0.1

/** A 1x1 PNG, so every card renders a real `<picture>` without a backend or a fixture file. */
const PIXEL = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==',
  'base64',
)

const events = Array.from({ length: 20 }, (_, i) => ({
  slug: `shift-gig-${i + 1}`,
  title: `Shift Gig ${i + 1}`,
  eventDate: '2026-08-15',
  startTime: '20:00',
  imageUrl: `/api/images/shift-${i + 1}/512.png`,
  venue: { slug: 'mock-venue', name: 'Mock Venue', city: 'Berlin' },
}))

function json(route: Route, body: unknown, delayMs = 0): Promise<void> {
  const fulfil = () =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  return delayMs ? new Promise((resolve) => setTimeout(resolve, delayMs)).then(fulfil) : fulfil()
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    const scores = ((window as unknown as { __shifts: number[] }).__shifts = [])
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries() as (PerformanceEntry & {
        value: number
        hadRecentInput: boolean
      })[]) {
        if (!entry.hadRecentInput) scores.push(entry.value)
      }
    }).observe({ type: 'layout-shift', buffered: true })
  })
  await page.route('**/api/images/**', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: PIXEL }),
  )
  await page.route(/\/api\/events(\?|$)/, (route) =>
    json(route, { content: events, page: 0, size: 20, totalElements: 20, totalPages: 1 }, 500),
  )
  await page.route(/\/api\/events\/shift-gig-1$/, (route) =>
    json(route, { ...events[0], intrinsicWidth: 800, intrinsicHeight: 1000 }, 500),
  )
  await page.route(/\/api\/genres/, (route) => json(route, []))
  await page.route(/\/api\/venues/, (route) =>
    json(route, { content: [], page: 0, size: 500, totalElements: 0, totalPages: 0 }),
  )
})

/** The layout shift the page has scored, once two animation frames have passed after the content. */
async function cumulativeShift(page: Page): Promise<number> {
  return page.evaluate(
    () =>
      new Promise<number>((resolve) =>
        requestAnimationFrame(() =>
          requestAnimationFrame(() =>
            resolve(
              (window as unknown as { __shifts: number[] }).__shifts.reduce((a, b) => a + b, 0),
            ),
          ),
        ),
      ),
  )
}

test('the events list does not move when its results arrive', async ({ page }) => {
  await page.goto('/events')
  await expect(page.getByRole('heading', { level: 2, name: 'Shift Gig 20' })).toBeVisible()

  expect(await cumulativeShift(page)).toBeLessThan(GOOD_CLS)
})

test('an event page does not move when its event arrives', async ({ page }) => {
  await page.goto('/events/shift-gig-1')
  await expect(page.getByRole('heading', { level: 1, name: 'Shift Gig 1' })).toBeVisible()

  expect(await cumulativeShift(page)).toBeLessThan(GOOD_CLS)
})
