import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Home page feed e2e tests with a mocked BFF.
 *
 * The home page loads two independent feeds on mount:
 *   Tonight  → GET /api/events/today        (an EventSummary[] array)
 *   Upcoming → GET /api/events?from=&size=12 (an EventPage; the view reads .content)
 *
 * Each feed has its own loading / error / empty / list state, so the tests exercise
 * them independently. The two matchers are non-overlapping (`/events/today` vs
 * `/events?…`) so route registration order does not matter.
 */

function collectPageErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  return errors
}

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

const slugify = (title: string) => title.toLowerCase().replace(/\s+/g, '-')

/** Build an EventPage payload from a list of event titles (for the Upcoming feed). */
function eventPage(titles: string[]) {
  return {
    content: titles.map((title) => ({ slug: slugify(title), title, eventDate: '2026-08-15' })),
    page: 0,
    size: 12,
    totalElements: titles.length,
    totalPages: titles.length ? 1 : 0,
  }
}

const todayFeed = /\/api\/events\/today/
const upcomingFeed = /\/api\/events(\?|$)/

const todayEvents = [
  { slug: 'tonight-show', title: 'Tonight Show', eventDate: '2026-07-01', startTime: '21:00' },
]

test.beforeEach(async ({ page }) => {
  await page.route(todayFeed, (route) => json(route, todayEvents))
  await page.route(upcomingFeed, (route) =>
    json(route, eventPage(['Upcoming One', 'Upcoming Two'])),
  )
})

const eventHeading = (page: Page, name: string) => page.getByRole('heading', { level: 3, name })

test('renders the tonight and upcoming feeds', async ({ page }) => {
  const errors = collectPageErrors(page)
  await page.goto('/')

  await expect(page.getByRole('heading', { level: 2, name: 'Tonight' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 2, name: 'Upcoming' })).toBeVisible()

  await expect(eventHeading(page, 'Tonight Show')).toBeVisible()
  await expect(eventHeading(page, 'Upcoming One')).toBeVisible()
  await expect(eventHeading(page, 'Upcoming Two')).toBeVisible()
  expect(errors, 'unexpected uncaught exceptions').toEqual([])
})

test('the hero leads to the events list first, with the calendar beside it', async ({ page }) => {
  await page.goto('/')

  // The primary way in is the list (#366): the feeds below are already a list, and the filters
  // are what a visitor who wants more of it needs. The calendar stays as the second button.
  const hero = page.getByRole('main')
  await expect(hero.getByRole('link', { name: 'Browse events' })).toHaveAttribute(
    'href',
    '/en/events',
  )
  await expect(hero.getByRole('link', { name: 'Browse calendar' })).toHaveAttribute(
    'href',
    '/en/calendar',
  )

  await hero.getByRole('link', { name: 'Browse events' }).click()
  await expect(page).toHaveURL(/\/en\/events$/)
})

test('the upcoming feed ends in a link to the full list', async ({ page }) => {
  await page.goto('/')

  await expect(eventHeading(page, 'Upcoming Two')).toBeVisible()
  await page.getByRole('link', { name: 'See all upcoming events' }).click()
  await expect(page).toHaveURL(/\/en\/events$/)
})

test('shows empty states when both feeds are empty', async ({ page }) => {
  await page.route(todayFeed, (route) => json(route, []))
  await page.route(upcomingFeed, (route) => json(route, eventPage([])))

  await page.goto('/')

  // Empty-state copy is in the brand voice; assert on a stable substring so wording can flex.
  await expect(page.getByText(/nothing on tonight/i)).toBeVisible()
  await expect(page.getByText(/nothing upcoming/i)).toBeVisible()
})

test('shows an error in one feed without affecting the other', async ({ page }) => {
  // Tonight fails; Upcoming keeps the default (successful) mock.
  await page.route(todayFeed, (route) => json(route, { message: 'boom' }, 500))

  await page.goto('/')

  await expect(page.getByText(/couldn't load tonight's events/i)).toBeVisible()
  await expect(eventHeading(page, 'Upcoming One')).toBeVisible()
})

test('navigates to an event detail from a feed card', async ({ page }) => {
  await page.route(/\/api\/events\/tonight-show/, (route) =>
    json(route, { slug: 'tonight-show', title: 'Tonight Show', eventDate: '2026-07-01' }),
  )

  await page.goto('/')

  await page.getByRole('link', { name: /Tonight Show/ }).click()

  await expect(page).toHaveURL(/\/events\/tonight-show$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Tonight Show' })).toBeVisible()
})

test('long titles truncate inside the feed instead of widening the page', async ({ page }) => {
  // The feed grid had no explicit `grid-cols-1`, so below `sm` its single track sized to
  // min-content — and the card's `truncate` heading is `white-space: nowrap`, whose min-content
  // is the whole untruncated string. One long title stretched the track to ~1600px and the
  // entire page scrolled sideways. Numbered `grid-cols-*` resolve to `minmax(0, 1fr)`, which
  // caps the track at the container. Needs real cards, hence the mocked feeds in this file.
  const longTitle = 'An Extraordinarily Long Event Title That Should Truncate Rather Than Expand'
  await page.route(todayFeed, (route) =>
    json(route, [
      {
        slug: 'long-one',
        title: longTitle,
        subtitle: 'A subtitle that is also considerably longer than any narrow viewport allows',
        eventDate: '2026-07-01',
        startTime: '21:00',
        venue: { slug: 'a-venue', name: 'A Venue With A Notably Long Name Attached To It' },
      },
    ]),
  )

  await page.goto('/')
  await expect(eventHeading(page, longTitle)).toBeVisible()

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow, 'page scrolls horizontally').toBe(0)

  // And the card itself stays inside the viewport rather than being clipped by an ancestor.
  const card = page.getByRole('link', { name: new RegExp(longTitle.slice(0, 30)) })
  const box = await card.boundingBox()
  const viewport = page.viewportSize()
  expect(box && viewport && box.x + box.width).toBeLessThanOrEqual(viewport!.width)
})
