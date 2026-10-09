import { expect, type Locator, type Page, type Route, test } from '@playwright/test'

/**
 * Each main flow completed with the keyboard alone (#2989), the half of #373 a test can do. No
 * click, no `fill`, no `focus()`: every step is a key press, and every element a flow stops on is
 * asserted to show where focus is (WCAG 2.4.7), not only to hold it.
 *
 * "Visible" is read from the computed style, because Playwright has no notion of a focus ring:
 * the element matches `:focus-visible` and draws an outline, a ring (a non-transparent box-shadow,
 * which shadcn's buttons use) or a background it did not have before it was focused (the
 * language menu's links). Buttons fade the ring in over `transition-all`, so the check polls.
 *
 * Desktop engines only. WebKit leaves links out of the Tab order unless macOS "Full Keyboard
 * Access" is on (see a11y.spec.ts), and the phone projects have no keyboard to speak of; their
 * layouts move the header links and the filters into sheets, which other specs cover.
 */

// eslint-disable-next-line playwright/no-skipped-test
test.skip(
  ({ browserName }) => browserName === 'webkit',
  'WebKit excludes links from the Tab order by default',
)
// eslint-disable-next-line playwright/no-skipped-test
test.skip(({ isMobile }) => isMobile, 'a keyboard flow is a desktop flow')

function json(route: Route, body: unknown): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

const events = [
  {
    slug: 'tonight-show',
    title: 'Tonight Show',
    eventDate: '2026-08-15',
    startTime: '21:00',
    venue: { slug: 'mock-venue', name: 'Mock Venue' },
  },
  { slug: 'second-show', title: 'Second Show', eventDate: '2026-08-16' },
]

/** More events on one day than the month grid shows, so the day folds into a "+N more" link. */
const CROWDED_DAY = 8

/**
 * The BFF, answered with just enough to render. The matchers do not overlap (see a11y.spec.ts on
 * why that matters); the detail answer takes its title from the slug, so the page a flow lands on
 * names the event it opened.
 */
async function mockBff(page: Page): Promise<void> {
  await page.route(/\/api\/events\/calendar(\?|$)/, (route) => {
    const from = new URL(route.request().url()).searchParams.get('from') ?? '2026-08-15'
    return json(
      route,
      Array.from({ length: CROWDED_DAY }, (_, i) => ({
        slug: `gig-${i}`,
        title: `Gig ${i}`,
        eventDate: from,
        startTime: '20:00',
      })),
    )
  })
  await page.route(/\/api\/events\/(?!calendar|today)([^/?]+)/, (route) => {
    const slug = new URL(route.request().url()).pathname.split('/').pop() ?? ''
    const title = events.find((event) => event.slug === slug)?.title ?? slug.replace('gig-', 'Gig ')
    return json(route, {
      slug,
      title,
      eventDate: '2026-08-15',
      startTime: '21:00',
      status: 'SCHEDULED',
    })
  })
  await page.route(/\/api\/events(\?|$)/, (route) =>
    json(route, { content: events, page: 0, size: 20, totalElements: 2, totalPages: 1 }),
  )
  await page.route(/\/api\/venues(\?|$)/, (route) =>
    json(route, {
      content: [{ slug: 'mock-venue', name: 'Mock Venue' }],
      page: 0,
      size: 500,
      totalElements: 1,
      totalPages: 1,
    }),
  )
  await page.route(/\/api\/genres/, (route) =>
    json(route, [{ slug: 'techno', name: 'Techno', family: 'electronic' }]),
  )
  await page.route(/\/api\/search(\?|$)/, (route) =>
    json(route, {
      events: { items: [events[0]], total: 1 },
      venues: { items: [], total: 0 },
      artists: { items: [], total: 0 },
      promoters: { items: [], total: 0 },
    }),
  )
}

/**
 * Whether the element, as it is styled now, draws a focus indicator. `before` is its unfocused
 * background, where it was read; without it only an outline or a ring counts.
 */
function showsFocus(target: Locator, before: string | null): Promise<boolean> {
  return target.evaluate((el, background) => {
    const style = getComputedStyle(el)
    const transparent = /rgba\([^)]*,\s*0\)|\/\s*0\)|transparent/
    const outline =
      style.outlineStyle !== 'none' &&
      Number.parseFloat(style.outlineWidth) > 0 &&
      !transparent.test(style.outlineColor)
    const ring = style.boxShadow
      .split(/,(?![^(]*\))/)
      .some((layer) => !transparent.test(layer) && /[1-9][\d.]*px/.test(layer))
    const shaded = background !== null && style.backgroundColor !== background
    return el.matches(':focus-visible') && (outline || ring || shaded)
  }, before)
}

/**
 * Presses `key` until `target` holds focus, then asserts the focus shows. Fails rather than loops
 * when the target is not in the Tab order, so a control that drops out of it is caught here.
 */
async function pressUntilFocused(
  page: Page,
  target: Locator,
  key = 'Tab',
  max = 60,
): Promise<void> {
  await expect(target).toBeVisible()
  const before = await target.evaluate((el) => getComputedStyle(el).backgroundColor)
  const focused = () => target.evaluate((el) => el === document.activeElement)
  for (let presses = 0; presses < max && !(await focused()); presses++) {
    await page.keyboard.press(key)
  }
  await expect(target).toBeFocused()
  await expectFocusShown(target, before)
}

async function expectFocusShown(target: Locator, before: string | null = null): Promise<void> {
  await expect
    .poll(() => showsFocus(target, before), { message: 'focus is held but not shown' })
    .toBe(true)
}

test.beforeEach(async ({ page }) => {
  await mockBff(page)
})

test('opens an event from the events list', async ({ page }) => {
  await page.goto('/en/events')

  const card = page.getByRole('main').getByRole('link', { name: /Tonight Show/ })
  await pressUntilFocused(page, card)
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL('/en/events/tonight-show')
  await expect(page.getByRole('heading', { level: 1, name: 'Tonight Show' })).toBeVisible()
})

test('sets a filter and clears it again', async ({ page }) => {
  await page.goto('/en/events')

  const preset = page.getByRole('button', { name: 'Next 7 days', exact: true })
  await pressUntilFocused(page, preset)
  await page.keyboard.press('Enter')

  await expect(preset).toHaveAttribute('aria-pressed', 'true')
  await expect(page).toHaveURL(/\/en\/events\?from=[\d-]+&to=[\d-]+$/)

  // "Clear all" appears with the first filter, back towards the search field.
  const clearAll = page.getByRole('button', { name: 'Clear all', exact: true })
  await pressUntilFocused(page, clearAll, 'Shift+Tab')
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL('/en/events')
  await expect(preset).toHaveAttribute('aria-pressed', 'false')
})

test('moves through the calendar and opens a day and an event in it', async ({ page }) => {
  await page.goto('/en/calendar')
  // The toolbar's month, the only `h2` FullCalendar renders; v7 hashes its class names.
  const title = page.getByRole('main').getByRole('heading', { level: 2 }).first()
  await expect(page.getByRole('link', { name: /Gig 0/ })).toBeVisible()
  const thisMonth = await title.innerText()

  const next = page.getByRole('button', { name: 'Next Month' })
  await pressUntilFocused(page, next)
  await page.keyboard.press('Enter')
  await expect(title).not.toHaveText(thisMonth)

  // The first visible day holds more events than a month cell shows; "+N more" opens that day.
  const more = page.getByRole('button', { name: /\+\d+ more/ })
  await pressUntilFocused(page, more)
  await page.keyboard.press('Enter')

  const lastGig = `Gig ${CROWDED_DAY - 1}`
  const inDay = page.getByRole('dialog').getByRole('link', { name: new RegExp(lastGig) })
  await pressUntilFocused(page, inDay)
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL(`/en/events/gig-${CROWDED_DAY - 1}`)
  await expect(page.getByRole('heading', { level: 1, name: lastGig })).toBeVisible()
})

test('searches and opens a result', async ({ page }) => {
  await page.goto('/en/events')

  await pressUntilFocused(
    page,
    page.getByRole('button', { name: 'Search events, venues, artists and promoters' }),
  )
  await page.keyboard.press('Enter')

  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('textbox')).toBeFocused()
  await page.keyboard.type('tonight')

  // The palette is a combobox: focus stays in the field and the highlight marks the option.
  const option = dialog.getByRole('option', { name: /Tonight Show/ })
  await expect(option).toBeVisible()
  const unhighlighted = await option.evaluate((el) => getComputedStyle(el).backgroundColor)
  await page.keyboard.press('ArrowDown')
  await expect(option).toHaveAttribute('data-highlighted', '')
  await expect
    .poll(() => option.evaluate((el) => getComputedStyle(el).backgroundColor))
    .not.toBe(unhighlighted)
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL('/en/events/tonight-show')
  await expect(page.getByRole('heading', { level: 1, name: 'Tonight Show' })).toBeVisible()
})

test('switches the language, then the theme', async ({ page }) => {
  await page.goto('/en/events')

  await pressUntilFocused(page, page.getByRole('button', { name: 'Language', exact: true }))
  await page.keyboard.press('Enter')
  await pressUntilFocused(
    page,
    page.getByRole('list', { name: 'Language' }).getByRole('link', { name: 'Deutsch' }),
  )
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL('/de/events')
  await expect(page.locator('html')).toHaveAttribute('lang', 'de')

  // New visitors get dark, so Light is the change.
  await expect(page.locator('html')).toHaveClass(/\bdark\b/)
  const settings = page.getByRole('button', { name: 'Anzeige', exact: true })
  await pressUntilFocused(page, settings)
  await page.keyboard.press('Enter')
  const light = page
    .getByRole('dialog', { name: 'Anzeige' })
    .getByRole('group', { name: 'Farbschema' })
    .getByRole('button', { name: 'Hell', exact: true })
  await pressUntilFocused(page, light)
  await page.keyboard.press('Enter')

  await expect(page.locator('html')).not.toHaveClass(/\bdark\b/)
  await expect(light).toHaveAttribute('aria-pressed', 'true')

  // Escape closes the popover and hands focus back to the button that opened it.
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: 'Anzeige' })).toBeHidden()
  await expect(settings).toBeFocused()
  await expectFocusShown(settings)
})
