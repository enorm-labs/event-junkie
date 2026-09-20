import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Events list-page filtering with a mocked BFF. The view keeps every filter in the URL query and
 * re-fetches `GET /api/events?…` on change; the mock keys its response off the query params, so
 * asserting the rendered result and the URL proves the frontend sent the right filter. Results
 * render as an event title per card, `h2` here because this page has no section heading between
 * its `h1` and the grid (`EventCard`'s `as`).
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

/** Today in Berlin, the boundary the app treats as the start of "upcoming". */
const todayInBerlin = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date())

/** An ISO date offset from today, for ranges that have to stay relative to the clock. */
function isoDaysFromNow(days: number): string {
  // Anchored on Berlin's calendar date, which the app computes from (`todayIso` in lib/format):
  // between 22:00 and midnight UTC the runner's date is a day behind. Midnight-UTC arithmetic so no
  // DST hour can move it.
  const date = new Date(`${todayInBerlin()}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

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
 * Response keyed off the query params: each filter maps to a distinct result, so a rendered
 * title identifies which filter reached the BFF. The default spans two pages for pagination.
 */
function eventsResponseFor(sp: URLSearchParams) {
  if (sp.get('q') === 'nothing') return eventPage([])
  if (sp.get('q') === 'jazz') return eventPage(['Jazz Night'])
  if (sp.get('eventType') === 'FESTIVAL') return eventPage(['Big Festival'])
  if (sp.get('venue') === 'lido') return eventPage(['Lido Show'])
  if (sp.get('genre') === 'techno') return eventPage(['Techno Rave'])
  if (sp.get('family') === 'electronic') return eventPage(['Electronic Night'])
  if (sp.get('district') === 'neukoelln') return eventPage(['Neukölln Night'])
  if (sp.get('excludeSoldOut') === 'true') return eventPage(['Available Only'])
  if (sp.get('free') === 'true') return eventPage(['Free Show'])
  if (sp.get('minPrice') || sp.get('maxPrice')) return eventPage(['Cheap Gig'])
  const to = sp.get('to')
  if (to && to < todayInBerlin()) return eventPage(['Gig Last Month'])
  if (sp.get('from') && sp.get('to')) return eventPage(['Gig In Range'])
  if (sp.get('from')) return eventPage(['Gig From Date'])

  const page = Number(sp.get('page') ?? '0')
  // Past the last page the BFF answers with no content and the real totals, which distinguishes
  // an out-of-range page from an empty result (#1267).
  if (page >= 2) return eventPage([], { page, totalPages: 2, totalElements: 21 })
  return page >= 1
    ? eventPage(['Second Page Event'], { page: 1, totalPages: 2, totalElements: 21 })
    : eventPage(['Default Event A', 'Default Event B'], {
        page: 0,
        totalPages: 2,
        totalElements: 21,
      })
}

test.beforeEach(async ({ page }) => {
  // Populate the style dropdown so its options can be selected once a family is chosen.
  await page.route(/\/api\/genres/, (route) =>
    json(route, [
      { slug: 'techno', name: 'Techno', family: 'electronic' },
      { slug: 'jazz', name: 'Jazz', family: 'jazz-blues' },
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

test('filters by genre family, then by a style inside it', async ({ page }) => {
  await page.goto('/events')
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()
  // No family chosen: the style select does not exist yet.
  await expect(selectWithOption(page, 'All styles')).toHaveCount(0)

  await selectWithOption(page, 'All genres').selectOption('electronic')

  await expect(page).toHaveURL(/[?&]family=electronic\b/)
  await expect(eventHeading(page, 'Electronic Night')).toBeVisible()

  // Only the family's own styles are offered — Jazz belongs to another family.
  const styles = selectWithOption(page, 'All styles')
  await expect(styles.getByRole('option')).toHaveText(['All styles', 'Techno'])
  await styles.selectOption('techno')

  await expect(page).toHaveURL(/[?&]genre=techno\b/)
  await expect(eventHeading(page, 'Techno Rave')).toBeVisible()

  // A new family drops the style, which belonged to the old one.
  await selectWithOption(page, 'All genres').selectOption('jazz-blues')
  await expect(page).toHaveURL(/[?&]family=jazz-blues\b/)
  await expect(page).not.toHaveURL(/[?&]genre=/)
})

test('a link from before families carries only genre, and both selects still show it', async ({
  page,
}) => {
  await page.goto('/events?genre=techno')
  await expect(eventHeading(page, 'Techno Rave')).toBeVisible()

  await expect(selectWithOption(page, 'All genres')).toHaveValue('electronic')
  await expect(selectWithOption(page, 'All styles')).toHaveValue('techno')
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

test('bounds the date inputs so the range cannot invert', async ({ page }) => {
  await page.goto('/events?from=2099-09-01&to=2099-09-30')
  await expect(eventHeading(page, 'Gig In Range')).toBeVisible()

  // `min`/`max` come from the sibling bound, so the browser enforces from <= to for us.
  await expect(page.getByLabel('Earliest event date')).toHaveAttribute('max', '2099-09-30')
  await expect(page.getByLabel('Latest event date')).toHaveAttribute('min', '2099-09-01')
})

test('leaves both date bounds open into the past, so the archive is reachable', async ({
  page,
}) => {
  await page.goto('/events')

  // The default is still upcoming-only, but that is the BFF's doing when no range is sent.
  await expect(page.getByLabel('Earliest event date')).not.toHaveAttribute('min', /./)
  await expect(page.getByLabel('Latest event date')).not.toHaveAttribute('min', /./)
})

test('browses past events from an explicit range', async ({ page }) => {
  const errors = collectPageErrors(page)
  await page.goto(`/events?from=${isoDaysFromNow(-60)}&to=${isoDaysFromNow(-30)}`)

  await expect(eventHeading(page, 'Gig Last Month')).toBeVisible()
  expect(errors, 'unexpected uncaught exceptions').toEqual([])
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

test('the empty state offers a way out of the filters', async ({ page }) => {
  // The message alone was a dead end under a filter bar six rows tall on a phone (#1266).
  await page.goto('/events?q=nothing')
  await expect(page.getByText(/nothing matches/i)).toBeVisible()

  await expect(page.getByRole('link', { name: /tonight/i })).toBeVisible()
  await page.getByRole('button', { name: 'Clear filters' }).click()

  await expect(page).toHaveURL(/\/events$/)
  await expect(eventHeading(page, 'Default Event A')).toBeVisible()
})

test('a page past the last one lands on the last page, not on an empty state', async ({ page }) => {
  // The list shortens every night, so a shared link or a crawler's `?page=` can outlive its own
  // range; "nothing matches those filters" names the wrong cause and leaves no pager (#1267).
  await page.goto('/events?page=99999')

  await expect(page).toHaveURL(/[?&]page=1(&|$)/)
  await expect(eventHeading(page, 'Second Page Event')).toBeVisible()
  await expect(page.getByText(/nothing matches/i)).toHaveCount(0)
})

test('paginates through results, preserving no filter', async ({ page }) => {
  const errors = collectPageErrors(page)
  await page.goto('/events')

  // `name` is a substring match by default, which would also catch the "Next 7 days" preset.
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
  // Exact matches: an unpluralised message renders both branches ("1 event found | 1 events
  // found"), which contains the singular. The German side is in i18n.spec.ts. The unfiltered
  // feed reports 21 across two pages; `jazz` returns one.
  await page.goto('/events')
  await expect(page.getByText('21 events found', { exact: true })).toBeVisible()

  await page.getByRole('searchbox').fill('jazz')
  await page.getByRole('button', { name: 'Search' }).click()

  await expect(page.getByText('1 event found', { exact: true })).toBeVisible()
})
