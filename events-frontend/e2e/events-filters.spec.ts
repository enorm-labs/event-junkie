import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Events list-page filtering e2e tests with a mocked BFF.
 *
 * The Events view keeps every filter in the URL query and re-fetches
 * `GET /api/events?…` whenever the query changes. We mock that endpoint with a
 * handler that keys its response off the incoming query params: asserting both
 * the rendered result and the resulting URL therefore proves the frontend
 * serialized and sent the right filter, end to end, without a real backend.
 *
 * Results render as an event title per card, so tests assert on those headings; the empty state
 * and pagination controls are asserted by their copy. The level is `h2` here — `EventCard` titles
 * itself `h3` by default, but this page has no section heading between its `h1` and the grid, so
 * it overrides the level to keep the outline from skipping one (see `EventCard`'s `as`).
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

/** Build an EventPage payload from a list of event titles. */
function eventPage(
  titles: string[],
  opts: { page?: number; totalPages?: number; totalElements?: number } = {},
) {
  return {
    content: titles.map((title) => ({ slug: slugify(title), title, eventDate: '2026-08-15' })),
    page: opts.page ?? 0,
    size: 20,
    totalElements: opts.totalElements ?? titles.length,
    totalPages: opts.totalPages ?? (titles.length ? 1 : 0),
  }
}

/**
 * Response keyed off the search query params. Each filter maps to a distinct
 * result, so a rendered title uniquely identifies which filter reached the BFF.
 * The unfiltered default spans two pages so pagination can be exercised.
 */
function eventsResponseFor(sp: URLSearchParams) {
  if (sp.get('q') === 'nothing') return eventPage([])
  if (sp.get('q') === 'jazz') return eventPage(['Jazz Night'])
  if (sp.get('eventType') === 'FESTIVAL') return eventPage(['Big Festival'])
  if (sp.get('venue') === 'lido') return eventPage(['Lido Show'])
  if (sp.get('genre') === 'techno') return eventPage(['Techno Rave'])
  if (sp.get('district') === 'neukoelln') return eventPage(['Neukölln Night'])
  if (sp.get('excludeSoldOut') === 'true') return eventPage(['Available Only'])
  if (sp.get('free') === 'true') return eventPage(['Free Show'])
  if (sp.get('minPrice') || sp.get('maxPrice')) return eventPage(['Cheap Gig'])
  if (sp.get('from') && sp.get('to')) return eventPage(['Gig In Range'])
  if (sp.get('from')) return eventPage(['Gig From Date'])

  return Number(sp.get('page') ?? '0') >= 1
    ? eventPage(['Second Page Event'], { page: 1, totalPages: 2, totalElements: 21 })
    : eventPage(['Default Event A', 'Default Event B'], {
        page: 0,
        totalPages: 2,
        totalElements: 21,
      })
}

test.beforeEach(async ({ page }) => {
  // Populate the genre dropdown so its options can be selected.
  await page.route(/\/api\/genres/, (route) =>
    json(route, [
      { slug: 'techno', name: 'Techno' },
      { slug: 'jazz', name: 'Jazz' },
    ]),
  )
  // Populate the venue dropdown so its options can be selected.
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
  // Serve the search feed based on the query params the frontend sends.
  await page.route(/\/api\/events(\?|$)/, (route) =>
    json(route, eventsResponseFor(new URL(route.request().url()).searchParams)),
  )
})

/** The native <select> that contains the given placeholder option. */
function selectWithOption(page: Page, optionName: string) {
  return page.locator('select', { has: page.getByRole('option', { name: optionName }) })
}

const eventHeading = (page: Page, name: string) => page.getByRole('heading', { level: 2, name })

test('filters by search query', async ({ page }) => {
  const errors = collectPageErrors(page)
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await page.getByRole('searchbox').fill('jazz')
  await page.getByRole('button', { name: 'Search' }).click()

  await expect(page).toHaveURL(/[?&]q=jazz\b/)
  await expect(eventHeading(page, 'Jazz Night')).toBeVisible()
  await expect(eventHeading(page, 'Default Event A')).toHaveCount(0)
  expect(errors, 'unexpected uncaught exceptions').toEqual([])
})

test('filters by event type', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await selectWithOption(page, 'All types').selectOption('FESTIVAL')

  await expect(page).toHaveURL(/[?&]eventType=FESTIVAL\b/)
  await expect(eventHeading(page, 'Big Festival')).toBeVisible()
})

test('filters by venue', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await selectWithOption(page, 'All venues').selectOption('lido')

  await expect(page).toHaveURL(/[?&]venue=lido\b/)
  await expect(eventHeading(page, 'Lido Show')).toBeVisible()
})

test('filters by genre', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await selectWithOption(page, 'All genres').selectOption('techno')

  await expect(page).toHaveURL(/[?&]genre=techno\b/)
  await expect(eventHeading(page, 'Techno Rave')).toBeVisible()
})

test('filters by district', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await selectWithOption(page, 'All districts').selectOption('neukoelln')

  await expect(page).toHaveURL(/[?&]district=neukoelln\b/)
  await expect(eventHeading(page, 'Neukölln Night')).toBeVisible()
})

test('filters by price range', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await page.getByLabel('Minimum presale price').fill('10')
  await page.getByLabel('Maximum presale price').fill('30')
  await page.getByRole('button', { name: 'Apply' }).click()

  await expect(page).toHaveURL(/[?&]minPrice=10\b/)
  await expect(page).toHaveURL(/[?&]maxPrice=30\b/)
  await expect(eventHeading(page, 'Cheap Gig')).toBeVisible()
})

test('filters by a date range, applying each bound as it is picked', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  // Native date inputs apply on change, so the earliest bound alone already narrows the list.
  await page.getByLabel('Earliest event date').fill('2026-09-01')

  await expect(page).toHaveURL(/[?&]from=2026-09-01\b/)
  await expect(eventHeading(page, 'Gig From Date')).toBeVisible()

  await page.getByLabel('Latest event date').fill('2026-09-30')

  await expect(page).toHaveURL(/[?&]to=2026-09-30\b/)
  await expect(eventHeading(page, 'Gig In Range')).toBeVisible()
  await expect(eventHeading(page, 'Default Event A')).toHaveCount(0)
})

test('applies a date preset, marks it pressed, and clears it on a second click', async ({
  page,
}) => {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date())
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  const tonight = page.getByRole('button', { name: 'Tonight' })
  await tonight.click()

  // A preset is nothing but the two bounds, so it lands in the URL like any other filter.
  await expect(page).toHaveURL(new RegExp(`[?&]from=${today}\\b`))
  await expect(page).toHaveURL(new RegExp(`[?&]to=${today}\\b`))
  await expect(eventHeading(page, 'Gig In Range')).toBeVisible()
  await expect(tonight).toHaveAttribute('aria-pressed', 'true')
  // The date inputs and the preset are two views of the same state.
  await expect(page.getByLabel('Earliest event date')).toHaveValue(today)

  await tonight.click()

  await expect(page).not.toHaveURL(/[?&]from=/)
  await expect(tonight).toHaveAttribute('aria-pressed', 'false')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()
})

test('each preset sends its own range and only one reads as pressed', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await page.getByRole('button', { name: 'Next 7 days' }).click()

  await expect(page.getByRole('button', { name: 'Next 7 days' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await expect(page.getByRole('button', { name: 'Tonight' })).toHaveAttribute(
    'aria-pressed',
    'false',
  )

  // "Next 7 days" spans a week, so its bounds differ — unlike Tonight's single day.
  const url = new URL(page.url())
  expect(url.searchParams.get('from')).not.toBe(url.searchParams.get('to'))
})

test('bounds the date inputs so the range cannot invert or reach into the past', async ({
  page,
}) => {
  await page.goto('/events?from=2026-09-01&to=2026-09-30')
  await expect(eventHeading(page, 'Gig In Range')).toBeVisible()

  // `min`/`max` come from the sibling bound, so the browser enforces from <= to for us.
  await expect(page.getByLabel('Earliest event date')).toHaveAttribute('max', '2026-09-30')
  await expect(page.getByLabel('Latest event date')).toHaveAttribute('min', '2026-09-01')
  // The lower bound is today: this app is about upcoming events.
  await expect(page.getByLabel('Earliest event date')).toHaveAttribute(
    'min',
    new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date()),
  )
})

test('hides sold-out events when the toggle is checked', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await page.getByLabel('Hide sold out').check()

  await expect(page).toHaveURL(/[?&]excludeSoldOut=true\b/)
  await expect(eventHeading(page, 'Available Only')).toBeVisible()
  await expect(eventHeading(page, 'Default Event A')).toHaveCount(0)
})

test('shows only free events when the toggle is checked', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await page.getByLabel('Free only').check()

  await expect(page).toHaveURL(/[?&]free=true\b/)
  await expect(eventHeading(page, 'Free Show')).toBeVisible()
  await expect(eventHeading(page, 'Default Event A')).toHaveCount(0)
})

test('shows the empty state when no events match', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()

  await page.getByRole('searchbox').fill('nothing')
  await page.getByRole('button', { name: 'Search' }).click()

  // Brand-voice empty state; match a stable substring so the wording can flex.
  await expect(page.getByText(/nothing matches/i)).toBeVisible()
  await expect(eventHeading(page, 'Default Event A')).toHaveCount(0)
})

test('paginates through results, preserving no filter', async ({ page }) => {
  const errors = collectPageErrors(page)
  await page.goto('/events')

  // `name` is a substring match by default, which would also catch the "Next 7 days" date
  // preset — so the pagination control has to be pinned by its exact accessible name.
  const nextButton = page.getByRole('button', { name: 'Next', exact: true })

  await expect(eventHeading(page, 'Default Event A')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Previous' })).toBeDisabled()

  await nextButton.click()

  await expect(page).toHaveURL(/[?&]page=1\b/)
  await expect(eventHeading(page, 'Second Page Event')).toBeVisible()
  await expect(page.getByText('Page 2 of 2', { exact: true })).toBeVisible()
  await expect(nextButton).toBeDisabled()
  expect(errors, 'unexpected uncaught exceptions').toEqual([])
})

test('counts the results, with the plural agreeing with the count', async ({ page }) => {
  // Exact matches throughout: an unpluralised message renders both branches separated by a pipe
  // ("1 event found | 1 events found"), which contains the singular and would pass a substring
  // assertion. The German side of the same two keys is covered in i18n.spec.ts.
  // The unfiltered feed reports 21 across two pages; the `jazz` query returns exactly one.
  await page.goto('/events')
  await expect(page.getByText('21 events found', { exact: true })).toBeVisible()

  await page.getByRole('searchbox').fill('jazz')
  await page.getByRole('button', { name: 'Search' }).click()

  await expect(page.getByText('1 event found', { exact: true })).toBeVisible()
})
