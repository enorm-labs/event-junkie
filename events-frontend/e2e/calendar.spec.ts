import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Calendar view e2e tests with a mocked BFF. The calendar refetches
 * `GET /api/events/calendar?from=&to=` whenever the visible range changes, so the mock keys its
 * response off the requested `from`, and a deterministic event lands in the window whatever the
 * clock says. The feed matcher and the detail matcher (`/events/calendar-gig`) cannot collide.
 *
 * FullCalendar renders events carrying a URL as `<a>` links, `eventDidMount` puts the full
 * "<title> @ <venue>" label on the link's native `title`, and the toolbar controls are plain
 * buttons. The filter bar is covered in events-filters.spec.ts; here it is only asserted to reach
 * the feed and survive range navigation.
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
  await page.route(/\/api\/genres/, (route) =>
    json(route, [{ slug: 'techno', name: 'Techno', family: 'electronic' }]),
  )

  // One event on the first visible day of whatever range is requested, so it renders in every view;
  // the title is keyed off the venue filter, so a rendered event proves which query was sent.
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

/** Today in Berlin — the clock the app reads (`todayIso` in lib/format), not the runner's. */
const todayInBerlin = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date())

test("marks today's events as live and last month's as past", async ({ page }) => {
  // One event on today, one on the first visible day, otherwise the same shape, so the class and
  // the label are proven to come from the date alone.
  await page.route(calendarFeed, (route) => {
    const from = new URL(route.request().url()).searchParams.get('from') ?? '2026-07-01'
    return json(route, [
      { slug: 'tonight-gig', title: 'Tonight Gig', eventDate: todayInBerlin(), startTime: '20:00' },
      { slug: 'range-gig', title: 'Range Gig', eventDate: from, startTime: '20:00' },
    ])
  })

  await page.goto('/calendar')

  // The pulse is CSS on a FullCalendar-internal dot; the DOM offers the class the view sets and the
  // screen-reader text `eventDidMount` appends, the same as EventCard's.
  const live = page.getByRole('link', { name: /Tonight Gig/ })
  await expect(live).toHaveClass(/\bfc-event-live\b/)
  await expect(live).toHaveAccessibleName(/Live tonight/)
  await expect(live).not.toHaveClass(/\bfc-event-past\b/)

  // Whatever the visible window's first day is, last month's is behind today.
  await page.getByRole('button', { name: 'prev' }).click()
  const past = page.getByRole('link', { name: /Range Gig/ })
  await expect(past).toHaveClass(/\bfc-event-past\b/)
  await expect(past).not.toHaveAccessibleName(/Live tonight/)
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

  // FullCalendar 7 renders the view switcher as a tablist: role="tab" named "<View> view".
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

test("marks today's day number in the month grid and the week header", async ({ page }) => {
  await page.goto('/calendar')

  // The class comes from FullCalendar's own `isToday` (EventCalendar.vue), so the number carrying it
  // is Berlin's day.
  const todayNumber = String(Number(todayInBerlin().slice(8)))
  await expect(page.locator('.fc-today-number', { hasText: todayNumber })).toBeVisible()

  await page.getByRole('tab', { name: 'Week view' }).click()
  await expect(page.locator('.fc-today-number')).toBeVisible()
})

test('folds a crowded day into a "+N more" popover instead of a timetable', async ({ page }) => {
  // Twelve events on one day, all at 20:00 — the shape a Berlin Saturday has.
  await page.route(calendarFeed, (route) => {
    const from = new URL(route.request().url()).searchParams.get('from') ?? '2026-07-01'
    return json(
      route,
      Array.from({ length: 12 }, (_, i) => ({
        slug: `gig-${i}`,
        title: `Gig ${i}`,
        eventDate: from,
        startTime: '20:00',
      })),
    )
  })

  await page.goto('/calendar')
  await page.getByRole('tab', { name: 'Week view' }).click()

  // The rest sit behind the link; sorted by title, "Gig 9" is the last of the twelve and never fits.
  const last = page.getByRole('link', { name: /Gig 9/ })
  // A narrow cell (the mobile projects) shortens the link to the bare `+N`.
  const more = page.getByRole('button', { name: /^\+\d+( more)?$/ })
  await expect(more).toBeVisible()
  await expect(last).toHaveCount(0)
  await more.click()
  await expect(last).toBeVisible()
})
