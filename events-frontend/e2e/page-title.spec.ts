import { expect, type Route, test } from '@playwright/test'

/**
 * Page titles: every view sets `document.title` as `<page> · Event Junkie`, except home, which
 * shows the bare brand. Static views take it from route meta; detail views override it once
 * their entity loads, with a "… not found" title on a 404, so the BFF is mocked as in
 * detail-routes.spec.ts.
 */

const APP_NAME = 'Event Junkie'
const HOME_TITLE = `${APP_NAME} — Can't get enough of Berlin`

/** Fulfill a matched request with a JSON body. */
function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

/** Empty paged result for the upcoming-events feed on venue/artist/promoter pages. */
const emptyEventPage = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }

/** Matches the events search feed (`/api/events?…` or bare `/api/events`), not `/api/events/:slug`. */
const eventsFeed = /\/api\/events(\?|$)/

const staticRoutes = [
  { path: '/', name: 'home', title: HOME_TITLE },
  { path: '/events', name: 'events', title: `Events · ${APP_NAME}` },
  { path: '/calendar', name: 'calendar', title: `Calendar · ${APP_NAME}` },
  { path: '/about', name: 'about', title: `About · ${APP_NAME}` },
] as const

for (const route of staticRoutes) {
  test(`sets the ${route.name} view title`, async ({ page }) => {
    await page.goto(route.path)
    await expect(page).toHaveTitle(route.title)
  })
}

test('updates the title when navigating between static routes', async ({ page }) => {
  await page.goto('/')
  await expect(page).toHaveTitle(HOME_TITLE)

  const nav = page.getByRole('navigation', { name: 'Main' })
  await nav.getByRole('link', { name: 'Events', exact: true }).click()
  await expect(page).toHaveTitle(`Events · ${APP_NAME}`)

  await nav.getByRole('link', { name: 'Calendar', exact: true }).click()
  await expect(page).toHaveTitle(`Calendar · ${APP_NAME}`)
})

test('announces route changes in an aria-live region for screen readers', async ({ page }) => {
  await page.goto('/')

  // The live region stays silent on the initial load; role="status" is an implicit
  // aria-live="polite" region.
  const announcer = page.getByRole('status')
  await expect(announcer).toHaveText('')

  // A client-side navigation populates it with the new page title.
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', {
      name: 'Events',
      exact: true,
    })
    .click()
  await expect(announcer).toHaveText(`Events · ${APP_NAME}`)
})

test('keeps og:title and the meta description in sync', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('meta[property="og:title"]')).toHaveAttribute('content', HOME_TITLE)
  await expect(page.locator('meta[name="description"]')).toHaveAttribute('content', /Berlin/)

  // The social title tracks the active view, not just the static landing value.
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', {
      name: 'About',
      exact: true,
    })
    .click()
  await expect(page.locator('meta[property="og:title"]')).toHaveAttribute(
    'content',
    `About · ${APP_NAME}`,
  )
})

const detailRoutes = [
  {
    name: 'event',
    path: '/events/mock-event',
    matcher: /\/api\/events\/[^/?]+/,
    body: { slug: 'mock-event', title: 'Mock Fest', eventDate: '2026-08-15', status: 'SCHEDULED' },
    title: `Mock Fest · ${APP_NAME}`,
    notFoundTitle: `Event not found · ${APP_NAME}`,
  },
  {
    name: 'venue',
    path: '/venues/mock-venue',
    matcher: /\/api\/venues\//,
    body: { slug: 'mock-venue', name: 'Mock Venue', city: 'Berlin' },
    title: `Mock Venue · ${APP_NAME}`,
    notFoundTitle: `Venue not found · ${APP_NAME}`,
  },
  {
    name: 'artist',
    path: '/artists/mock-artist',
    matcher: /\/api\/artists\//,
    body: { slug: 'mock-artist', name: 'Mock Artist' },
    title: `Mock Artist · ${APP_NAME}`,
    notFoundTitle: `Artist not found · ${APP_NAME}`,
  },
  {
    name: 'promoter',
    path: '/promoters/mock-promoter',
    matcher: /\/api\/promoters\//,
    body: { slug: 'mock-promoter', name: 'Mock Promoter' },
    title: `Mock Promoter · ${APP_NAME}`,
    notFoundTitle: `Promoter not found · ${APP_NAME}`,
  },
] as const

test.describe('detail page titles', () => {
  test.beforeEach(async ({ page }) => {
    // Venue/artist/promoter pages also load an upcoming-events feed; stub it empty.
    await page.route(eventsFeed, (route) => json(route, emptyEventPage))
  })

  for (const detail of detailRoutes) {
    test(`sets the ${detail.name} title from the loaded entity`, async ({ page }) => {
      await page.route(detail.matcher, (route) => json(route, detail.body))

      await page.goto(detail.path)

      await expect(page).toHaveTitle(detail.title)
    })

    test(`sets the ${detail.name} not-found title on a 404`, async ({ page }) => {
      await page.route(detail.matcher, (route) => json(route, { message: 'not found' }, 404))

      await page.goto(detail.path)

      await expect(page).toHaveTitle(detail.notFoundTitle)
    })
  }
})
