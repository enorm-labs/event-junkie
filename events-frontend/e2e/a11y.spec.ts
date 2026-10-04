import AxeBuilder from '@axe-core/playwright'
import { expect, type Page, type Route, test } from '@playwright/test'
import { displaySetting } from './header-nav'

/**
 * Automated accessibility sweep, the runtime half of the WCAG 2.1 AA target (docs/LEGAL.md §12);
 * `npm run test:a11y` runs it alone. axe sees what `eslint-plugin-vuejs-accessibility` cannot from
 * the source: contrast against the resolved tokens, focus order, landmarks, duplicate IDs. It
 * finds roughly a third of WCAG issues, and stops what is here from regressing silently.
 *
 * Two passes: static routes with no BFF, where data-driven views render their error state and
 * still exercise the shared chrome; and data-driven routes with the BFF mocked, because an error
 * state renders none of the cards, selects, pagination or detail layout. The mocks are small: axe
 * needs the elements to exist, not the data to be realistic.
 */

// Both locales: German is longer, so it is where an overflow or a contrast regression shows
// (AGENTS.md §Testing, locale strategy).
const PATHS = [
  '',
  '/events',
  '/venues',
  '/promoters',
  '/calendar',
  '/map',
  '/about',
  '/legal/imprint',
  '/legal/privacy',
  '/legal/notices',
  '/legal/for-venues',
]
const staticRoutes = ['en', 'de'].flatMap((locale) => PATHS.map((path) => `/${locale}${path}`))

/** The conformance target. `best-practice` is deliberately excluded: useful, but not the bar. */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

/**
 * The Vite dev server injects the `vite-plugin-vue-devtools` panel, whose button carries an ARIA
 * attribute axe rejects. Not our markup and not in a build; CI runs against `npm run preview`.
 */
function buildScan(page: Page): AxeBuilder {
  return new AxeBuilder({ page }).exclude('#__vue-devtools-container__').withTags(TAGS)
}

/**
 * Buttons carry `transition-all`, so one just pressed is mid-fade between its two colours for a
 * moment, and axe measures that intermediate value.
 */
async function transitionsSettled(page: Page) {
  await page.waitForFunction(() => document.getAnimations().length === 0)
}

for (const path of staticRoutes) {
  test(`${path} has no detectable accessibility violations`, async ({ page }) => {
    await page.goto(path)
    await expect(page.getByRole('main')).toBeVisible()

    const results = await buildScan(page).analyze()

    // Name the rules and elements in the failure message; a bare count is unactionable.
    expect(
      results.violations.map((v) => ({
        rule: v.id,
        impact: v.impact,
        help: v.help,
        nodes: v.nodes.map((n) => n.target.join(' ')),
      })),
    ).toEqual([])
  })
}

test('both themes pass contrast, not just the default', async ({ page }) => {
  // New visitors get dark; the display settings are the only way into light, a separate set of
  // tokens no other test exercises for contrast.
  await page.goto('/about')
  await (await displaySetting(page, 'Theme', 'Light')).click()
  await expect(page.locator('html')).not.toHaveClass(/dark/)
  await transitionsSettled(page)

  const results = await buildScan(page).analyze()

  expect(results.violations.map((v) => ({ rule: v.id, nodes: v.nodes.length }))).toEqual([])
})

test('the skip link is hidden until focused and moves focus to the content', async ({ page }) => {
  await page.goto('/about')

  const skipLink = page.getByRole('link', { name: 'Skip to content' })

  // Present but not occupying layout for sighted users who never tab.
  await expect(skipLink).toBeAttached()
  await expect(skipLink).not.toBeInViewport()

  await skipLink.focus()
  await expect(skipLink).toBeVisible()

  await skipLink.press('Enter')

  await expect(page.locator('#main-content')).toBeFocused()
})

/**
 * Pass 2, the data-driven routes with the BFF mocked. Payloads follow detail-routes.spec.ts and
 * events-filters.spec.ts, trimmed to what renders. English only: German's longer strings are
 * covered above on the routes where the layout is shared.
 */
function json(route: Route, body: unknown): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

const eventSummaries = [
  {
    slug: 'tonight-show',
    title: 'Tonight Show',
    subtitle: 'With a support act',
    eventDate: '2026-08-15',
    startTime: '21:00',
    venue: { slug: 'mock-venue', name: 'Mock Venue', latitude: 52.5, longitude: 13.45 },
  },
  { slug: 'second-show', title: 'Second Show', eventDate: '2026-08-16' },
]

const page1 = <T>(content: T[], size: number) => ({
  content,
  page: 0,
  size,
  totalElements: content.length,
  // Two pages, so the pagination controls render and get scanned.
  totalPages: 2,
})

/**
 * Every read endpoint the data-driven views touch, answered with something renderable. The
 * matchers are deliberately non-overlapping, hence the lookahead: `/events` has three
 * sub-resources, and Playwright consults handlers in reverse registration order, so an overlap
 * picks the one registered last. The failure is silent: a feed served an object renders an
 * empty state, and axe passes on markup that was never there.
 */
async function mockBff(page: Page): Promise<void> {
  await page.route(/\/api\/events\/today/, (route) => json(route, eventSummaries))
  // Events are placed on the requested `from` date, so the grid is populated whatever the clock
  // says, as calendar.spec.ts does.
  await page.route(/\/api\/events\/calendar(\?|$)/, (route) => {
    const from = new URL(route.request().url()).searchParams.get('from') ?? '2026-08-15'
    return json(
      route,
      eventSummaries.map((event) => ({ ...event, eventDate: from })),
    )
  })
  await page.route(/\/api\/events\/(?!today|calendar)[^/?]+/, (route) =>
    json(route, {
      slug: 'tonight-show',
      title: 'Tonight Show',
      eventDate: '2026-08-15',
      startTime: '21:00',
      status: 'SCHEDULED',
      venue: { slug: 'mock-venue', name: 'Mock Venue', address: 'Test Str. 1', city: 'Berlin' },
      lineup: [
        {
          artist: { slug: 'mock-artist', name: 'Mock Artist' },
          role: 'HEADLINER',
          billingOrder: 1,
          // A running order, so the sweep reaches its floor headings and time column (#2002).
          stage: 'Main Floor',
          setStart: '2026-08-15T21:00:00+02:00',
          setEnd: '2026-08-15T23:00:00+02:00',
        },
      ],
      promoters: [{ slug: 'mock-promoter', name: 'Mock Promoter' }],
    }),
  )
  await page.route(/\/api\/events(\?|$)/, (route) => json(route, page1(eventSummaries, 20)))
  await page.route(/\/api\/venues(\?|$)/, (route) =>
    json(
      route,
      page1(
        [
          {
            slug: 'mock-venue',
            name: 'Mock Venue',
            city: 'Berlin',
            district: 'kreuzberg',
            latitude: 52.5,
            longitude: 13.45,
          },
          { slug: 'other-venue', name: 'Other Venue', city: 'Berlin' },
        ],
        24,
      ),
    ),
  )
  await page.route(/\/api\/promoters(\?|$)/, (route) =>
    json(
      route,
      page1(
        [
          {
            slug: 'mock-promoter',
            name: 'Mock Promoter',
            websiteUrl: 'https://example.com/',
            description: 'Books the mock nights.',
            descriptionLanguage: 'en',
            upcomingEventCount: 3,
          },
          { slug: 'quiet-promoter', name: 'Quiet Promoter', upcomingEventCount: 0 },
        ],
        24,
      ),
    ),
  )
  await page.route(/\/api\/search(\?|$)/, (route) =>
    json(route, {
      events: { items: eventSummaries, total: 2 },
      venues: { items: [{ slug: 'mock-venue', name: 'Mock Venue', upcomingEventCount: 2 }], total: 1 },
      artists: { items: [{ slug: 'mock-artist', name: 'Mock Artist' }], total: 1 },
      promoters: { items: [{ slug: 'mock-promoter', name: 'Mock Promoter', upcomingEventCount: 3 }], total: 1 },
    }),
  )
  await page.route(/\/api\/genres/, (route) =>
    json(route, [
      { slug: 'techno', name: 'Techno', family: 'electronic' },
      { slug: 'jazz', name: 'Jazz', family: 'jazz-blues' },
    ]),
  )
}

const dataRoutes = [
  { name: 'home, with both feeds populated', path: '/en' },
  { name: 'events list, with results and the filter bar', path: '/en/events' },
  { name: 'venues list, with results', path: '/en/venues' },
  { name: 'promoters list, with results', path: '/en/promoters' },
  { name: 'an event detail page', path: '/en/events/tonight-show' },
  { name: 'search results, all four kinds', path: '/en/search?q=mock' },
  // The calendar is the only widget whose markup we do not write, and the static pass reaches it
  // with an empty grid, which scans almost nothing.
  { name: 'the calendar, with a populated month grid', path: '/en/calendar' },
]

for (const route of dataRoutes) {
  test(`${route.name} has no detectable accessibility violations`, async ({ page }) => {
    await mockBff(page)
    await page.goto(route.path)

    await expect(page.getByRole('main')).toBeVisible()
    // The scan must not race the fetch: an empty list renders no cards. The calendar renders events
    // as links rather than headings, hence the two shapes.
    await expect(
      page
        .getByRole('heading', { name: /Tonight Show|Mock Venue|Mock Promoter/ })
        .or(page.getByRole('link', { name: /Tonight Show/ }))
        .first(),
    ).toBeVisible()

    const results = await buildScan(page).analyze()

    expect(
      results.violations.map((v) => ({
        rule: v.id,
        impact: v.impact,
        help: v.help,
        nodes: v.nodes.map((n) => n.target.join(' ')),
      })),
    ).toEqual([])
  })
}

/** The header search, open over the page with every kind of result: a dialog the static pass never opens. */
test('the header search, open with results, has no detectable accessibility violations', async ({ page }) => {
  await mockBff(page)
  await page.goto('/en/events')
  await page.getByRole('button', { name: 'Search events, venues, artists and promoters' }).click()
  await page.getByRole('dialog').getByRole('textbox').fill('mock')
  await expect(page.getByRole('option', { name: /Mock Artist/ })).toBeVisible()

  const results = await buildScan(page).analyze()

  expect(
    results.violations.map((v) => ({
      rule: v.id,
      impact: v.impact,
      help: v.help,
      nodes: v.nodes.map((n) => n.target.join(' ')),
    })),
  ).toEqual([])
})

/** The header's language popover, open in German: the static pass never opens it. */
test('the header language menu, open, has no detectable accessibility violations', async ({ page }) => {
  await page.goto('/de/about')
  await page.getByRole('button', { name: 'Sprache' }).click()
  await expect(page.getByRole('link', { name: 'English' }).first()).toBeVisible()

  const results = await buildScan(page).analyze()

  expect(
    results.violations.map((v) => ({
      rule: v.id,
      impact: v.impact,
      help: v.help,
      nodes: v.nodes.map((n) => n.target.join(' ')),
    })),
  ).toEqual([])
})

/**
 * The two maps, each with a pin selected: the panel over the map is the markup a visitor reads, and
 * the pins are buttons MapLibre positions. Without WebGL the view shows its fallback instead, which
 * the static pass already covers.
 *
 * The clock sits at 18:00 Berlin on the mocked night. From 21:00 the Tonight Show is on now and the
 * pin's name gains ", 1 on now", so a real clock failed this test every evening.
 */
const mapRoutes = [
  { name: 'the events map', path: '/en/map', pin: 'Mock Venue: 1 event' },
  { name: 'the venues map', path: '/en/venues?view=map', pin: 'Mock Venue' },
]

for (const route of mapRoutes) {
  test(`${route.name}, with a pin selected, has no detectable accessibility violations`, async ({
    page,
  }) => {
    await page.clock.setFixedTime(new Date('2026-08-15T16:00:00Z'))
    await mockBff(page)
    await page.goto(route.path)

    const pin = page.getByRole('button', { name: route.pin, exact: true })
    const unavailable = page.getByText(/cannot draw the map/)
    await expect(pin.or(unavailable)).toBeVisible()
    test.skip(await unavailable.isVisible(), 'no WebGL in this browser')

    await pin.click()
    await expect(page.getByRole('heading', { level: 2, name: 'Mock Venue' })).toBeVisible()

    const results = await buildScan(page).analyze()

    expect(
      results.violations.map((v) => ({
        rule: v.id,
        impact: v.impact,
        help: v.help,
        nodes: v.nodes.map((n) => n.target.join(' ')),
      })),
    ).toEqual([])
  })
}

/**
 * "Near me" located (#358): the radius controls, the "you are here" marker, the nearby list, and a
 * pulsing pin. The clock sits at 22:00 Berlin on the mocked night, so the 21:00 show is on
 * now whatever the real time is.
 */
test('the events map near me, with a show on now, has no detectable accessibility violations', async ({
  page,
  context,
}) => {
  await context.grantPermissions(['geolocation'])
  await context.setGeolocation({ latitude: 52.501, longitude: 13.451 })
  await page.clock.setFixedTime(new Date('2026-08-15T20:00:00Z'))
  await mockBff(page)
  await page.goto('/en/map')

  const pin = page.getByRole('button', { name: 'Mock Venue: 1 event, 1 on now', exact: true })
  const unavailable = page.getByText(/cannot draw the map/)
  await expect(pin.or(unavailable)).toBeVisible()
  test.skip(await unavailable.isVisible(), 'no WebGL in this browser')

  await page.getByRole('button', { name: 'Use my location' }).click()
  await expect(page.getByRole('heading', { level: 2, name: 'Within 2 km' })).toBeVisible()

  const results = await buildScan(page).analyze()

  expect(
    results.violations.map((v) => ({
      rule: v.id,
      impact: v.impact,
      help: v.help,
      nodes: v.nodes.map((n) => n.target.join(' ')),
    })),
  ).toEqual([])
})

/**
 * The compact view is a second rendering of the same routes (#1371): rows carry the headings the
 * cards carried. The display settings stay open, so their pressed-state groups are scanned too.
 */
test('the compact view has no detectable accessibility violations', async ({ page }) => {
  await mockBff(page)
  await page.goto('/en/events')
  await (await displaySetting(page, 'Events', 'Compact')).click()

  await expect(page.getByRole('heading', { name: 'Tonight Show' })).toBeVisible()
  await expect(page.locator('img')).toHaveCount(0)
  await transitionsSettled(page)

  const results = await buildScan(page).analyze()

  expect(
    results.violations.map((v) => ({
      rule: v.id,
      impact: v.impact,
      help: v.help,
      nodes: v.nodes.map((n) => n.target.join(' ')),
    })),
  ).toEqual([])
})

/**
 * One `best-practice` rule promoted to a gate on the routes it was failing on. `heading-order`
 * reported one node per list page: `EventCard` / `VenueCard` rendered an `h3` under a list page's
 * bare `h1`. The cards take an `as` prop and the list pages pass `h2`. Pinned narrowly, one rule
 * and two routes, because the remaining `best-practice` finding is FullCalendar's
 * `empty-table-header` in markup we do not write. A regression here is invisible on screen.
 */
for (const path of [
  '/en/events',
  '/en/venues',
  '/en/promoters',
  '/de/events',
  '/de/venues',
  '/de/promoters',
  '/en/search?q=mock',
  '/de/search?q=mock',
]) {
  test(`${path} has a heading outline with no skipped levels`, async ({ page }) => {
    await mockBff(page)
    await page.goto(path)
    // Wait on the cards by name, not by level: waiting on `level: 2` would time out instead of
    // failing with the violation axe found.
    await expect(
      page.getByRole('heading', { name: /Tonight Show|Mock Venue|Mock Promoter/ }).first(),
    ).toBeVisible()

    const results = await new AxeBuilder({ page })
      .exclude('#__vue-devtools-container__')
      .withRules(['heading-order'])
      .analyze()

    expect(results.violations.flatMap((v) => v.nodes.map((n) => n.html))).toEqual([])
  })
}

/**
 * Informational pass: axe's `best-practice` rules, not part of the WCAG 2.1 AA bar above.
 * Non-failing, because they are recommendations, and gating on them ends with silencing them one
 * by one. Findings go to the report and the console; if one matters, fix it rather than promote
 * this pass to a gate.
 */
type BestPracticeFinding = { path: string; rule: string; help: string; nodes: number }

/**
 * Render the findings named, not counted. A module-level helper because
 * `playwright/no-conditional-in-test` is right about conditionals in a test body, and the
 * exception here is formatting.
 */
function summariseBestPractice(findings: BestPracticeFinding[]): string {
  const header = `axe best-practice: ${findings.length} finding(s) — informational, not a gate.`
  return [
    header,
    ...findings.map((f) => `  ${f.path}  ${f.rule} (${f.nodes} node(s)) — ${f.help}`),
  ].join('\n')
}

test('best-practice rules (informational — never fails the build)', async ({ page }, testInfo) => {
  await mockBff(page)

  const findings: BestPracticeFinding[] = []

  for (const path of ['/en', '/en/events', '/en/venues', '/en/calendar', '/en/about']) {
    await page.goto(path)
    await expect(page.getByRole('main')).toBeVisible()

    const results = await new AxeBuilder({ page })
      .exclude('#__vue-devtools-container__')
      .withTags(['best-practice'])
      .analyze()

    findings.push(
      ...results.violations.map((v) => ({
        path,
        rule: v.id,
        help: v.help,
        nodes: v.nodes.length,
      })),
    )
  }

  console.info(summariseBestPractice(findings))

  await testInfo.attach('axe-best-practice.json', {
    body: JSON.stringify(findings, null, 2),
    contentType: 'application/json',
  })
})

test('the skip link is the first thing Tab reaches', async ({ page, browserName }) => {
  // WebKit does not move focus to links on Tab unless macOS "Full Keyboard Access" is enabled, a
  // platform default. The test above covers the behaviour everywhere; this one pins the tab order,
  // so it runs where Tab reaches links. A conditional skip, not a disabled test.
  // eslint-disable-next-line playwright/no-skipped-test
  test.skip(browserName === 'webkit', 'WebKit excludes links from the Tab order by default')

  await page.goto('/about')
  await page.keyboard.press('Tab')

  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused()
})
