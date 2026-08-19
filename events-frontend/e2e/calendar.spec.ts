import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Calendar view e2e tests with a mocked BFF.
 *
 * The calendar refetches `GET /api/events/calendar?from=&to=` whenever FullCalendar's visible range
 * changes, so the mock keys its response off the requested `from` — a deterministic event then
 * lands in the visible window whatever the machine's clock says. The feed matcher
 * (`/events/calendar?…`) and the detail matcher (`/events/calendar-gig`) cannot collide.
 *
 * What the assertions lean on: FullCalendar renders events carrying a URL as `<a>` links and the
 * view intercepts the click to navigate through vue-router, `eventDidMount` puts the full
 * "<title> @ <venue>" label on the link's native `title` (the cell clips the visible text), and the
 * toolbar controls are plain buttons — prev/next/today by aria-label, month/week/list by text. The
 * filter bar is covered on the list page (events-filters.spec.ts); here it is only asserted to
 * reach the feed and survive range navigation, since the calendar refetches on two triggers.
 */

function collectPageErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  return errors
}

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

const calendarFeed = /\/api\/events\/calendar(\?|$)/

/** Records the `from` param of every calendar feed request, to assert refetches. */
function collectCalendarFroms(page: Page): string[] {
  const froms: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname.endsWith('/events/calendar')) {
      const from = url.searchParams.get('from')
      if (from) froms.push(from)
    }
  })
  return froms
}

/** The native <select> that contains the given placeholder option. */
function selectWithOption(page: Page, optionName: string) {
  return page.locator('select', { has: page.getByRole('option', { name: optionName }) })
}

test.beforeEach(async ({ page }) => {
  // Populate the filter bar's venue dropdown so its options can be selected.
  await page.route(/\/api\/venues/, (route) =>
    json(route, {
      content: [
        { slug: 'lido', name: 'Lido' },
        { slug: 'berghain', name: 'Berghain' },
      ],
      page: 0,
      size: 500,
      totalElements: 2,
      totalPages: 1,
    }),
  )
  await page.route(/\/api\/genres/, (route) => json(route, [{ slug: 'techno', name: 'Techno' }]))

  // Place a single event on the first visible day of whatever range is requested, so it
  // renders in every view (month/week/list) without depending on the current date. The
  // title is keyed off the venue filter, so a rendered event proves which query was sent.
  await page.route(calendarFeed, (route) => {
    const query = new URL(route.request().url()).searchParams
    const from = query.get('from') ?? '2026-07-01'
    const [slug, title] =
      query.get('venue') === 'lido' ? ['lido-gig', 'Lido Gig'] : ['calendar-gig', 'Calendar Gig']
    return json(route, [
      { slug, title, eventDate: from, startTime: '20:00', venue: { slug: 'lido', name: 'Lido' } },
    ])
  })
})

test('renders events and opens the event detail on click', async ({ page }) => {
  const errors = collectPageErrors(page)
  await page.route(/\/api\/events\/calendar-gig/, (route) =>
    json(route, { slug: 'calendar-gig', title: 'Calendar Gig', eventDate: '2026-08-15' }),
  )

  await page.goto('/calendar')

  const eventLink = page.getByRole('link', { name: /Calendar Gig/ })
  await expect(eventLink).toBeVisible()

  await eventLink.click()

  await expect(page).toHaveURL(/\/events\/calendar-gig$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Calendar Gig' })).toBeVisible()
  expect(errors, 'unexpected uncaught exceptions').toEqual([])
})

test('refetches events when navigating to the next month', async ({ page }) => {
  const froms = collectCalendarFroms(page)
  await page.goto('/calendar')
  await expect(page.getByRole('link', { name: /Calendar Gig/ })).toBeVisible()

  const initialCount = froms.length
  const firstFrom = froms.at(-1) ?? ''

  await page.getByRole('button', { name: 'next' }).click()

  await expect.poll(() => froms.length).toBeGreaterThan(initialCount)
  expect(froms.at(-1)! > firstFrom, 'next month should request a later range').toBe(true)
  await expect(page.getByRole('link', { name: /Calendar Gig/ })).toBeVisible()
})

test('refetches when switching the calendar view', async ({ page }) => {
  const froms = collectCalendarFroms(page)
  await page.goto('/calendar')
  await expect(page.getByRole('link', { name: /Calendar Gig/ })).toBeVisible()

  const initialCount = froms.length

  // FullCalendar 7 renders the view switcher as a tablist, not a button group, so these are
  // role="tab" with an accessible name of "<View> view" — not role="button" named "list".
  await page.getByRole('tab', { name: 'List view' }).click()

  await expect.poll(() => froms.length).toBeGreaterThan(initialCount)
})

test('spells the clipped title out as a "<title> @ <venue>" tooltip', async ({ page }) => {
  await page.goto('/calendar')

  // The cell text is clipped by CSS; the native `title` attribute carries the full label.
  await expect(page.getByRole('link', { name: /Calendar Gig/ })).toHaveAttribute(
    'title',
    'Calendar Gig @ Lido',
  )
})

test('falls back to the bare title when the event has no venue', async ({ page }) => {
  await page.route(calendarFeed, (route) => {
    const from = new URL(route.request().url()).searchParams.get('from') ?? '2026-07-01'
    return json(route, [{ slug: 'venueless-gig', title: 'Venueless Gig', eventDate: from }])
  })

  await page.goto('/calendar')

  await expect(page.getByRole('link', { name: /Venueless Gig/ })).toHaveAttribute(
    'title',
    'Venueless Gig',
  )
})

test('omits the date-range filter, whose job the visible window already does', async ({ page }) => {
  await page.goto('/calendar')
  await expect(page.getByRole('link', { name: /Calendar Gig/ })).toBeVisible()

  // The rest of the shared bar is there; only the date range is suppressed here.
  await expect(selectWithOption(page, 'All venues')).toBeVisible()
  await expect(page.getByLabel('Earliest event date')).toHaveCount(0)
  await expect(page.getByLabel('Latest event date')).toHaveCount(0)
  // The presets are shortcuts for that same range, so they go with it.
  await expect(page.getByRole('button', { name: 'Tonight' })).toHaveCount(0)
})

test('refetches the visible range with a filter from the shared filter bar', async ({ page }) => {
  const errors = collectPageErrors(page)
  await page.goto('/calendar')
  await expect(page.getByRole('link', { name: /Calendar Gig/ })).toBeVisible()

  await selectWithOption(page, 'All venues').selectOption('lido')

  await expect(page).toHaveURL(/[?&]venue=lido\b/)
  await expect(page.getByRole('link', { name: /Lido Gig/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Calendar Gig/ })).toHaveCount(0)
  expect(errors, 'unexpected uncaught exceptions').toEqual([])
})

test('keeps the active filter when navigating to another month', async ({ page }) => {
  const froms = collectCalendarFroms(page)
  // Deep-linking a filter proves the bar reads its state back out of the URL.
  await page.goto('/calendar?venue=lido')
  await expect(page.getByRole('link', { name: /Lido Gig/ })).toBeVisible()
  await expect(selectWithOption(page, 'All venues')).toHaveValue('lido')

  const initialCount = froms.length
  await page.getByRole('button', { name: 'next' }).click()

  await expect.poll(() => froms.length).toBeGreaterThan(initialCount)
  await expect(page).toHaveURL(/[?&]venue=lido\b/)
  await expect(page.getByRole('link', { name: /Lido Gig/ })).toBeVisible()
})

test('shows an error state when the calendar feed fails', async ({ page }) => {
  await page.route(calendarFeed, (route) => json(route, { message: 'boom' }, 500))

  await page.goto('/calendar')

  // The Calendar heading still renders; the feed failure surfaces the describeError copy.
  await expect(page.getByRole('heading', { level: 1, name: 'Calendar' })).toBeVisible()
  await expect(page.getByText(/couldn't load the calendar/i)).toBeVisible()
})
