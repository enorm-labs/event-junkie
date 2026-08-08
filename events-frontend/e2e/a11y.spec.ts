import AxeBuilder from '@axe-core/playwright'
import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Automated accessibility sweep — the runtime half of the WCAG 2.1 AA target
 * (docs/LEGAL.md §12). Run it on its own with `npm run test:a11y`.
 *
 * axe catches what `eslint-plugin-vuejs-accessibility` cannot see from the source: colour
 * contrast against the resolved theme tokens, focus order, landmark structure, and duplicate IDs.
 * It is not a conformance certificate — axe reliably finds roughly a third of WCAG issues — but
 * it is what stops the accessibility already in this codebase from regressing silently.
 *
 * Two passes, because they fail for different reasons:
 *
 *   1. **Static routes**, with no BFF. Data-driven views render their error state, which still
 *      exercises the shared chrome — skip link, header, footer — where the repeated content lives.
 *   2. **Data-driven routes, with the BFF mocked.** Without this pass the components that carry
 *      almost all of the interactive markup — the event and venue cards, the filter bar's selects
 *      and checkboxes, pagination, the detail layout — are never scanned at all, because an error
 *      state renders none of them. The mocks are deliberately small; axe needs the elements to
 *      exist, not the data to be realistic.
 */

// Both locales. German is reliably longer than English, so it is where a layout overflow or a
// contrast regression actually shows up — sweeping only `/en` would miss exactly the cases the
// translation introduces (see AGENTS.md §Testing — locale strategy).
const PATHS = [
  '',
  '/events',
  '/venues',
  '/calendar',
  '/about',
  '/legal/imprint',
  '/legal/privacy',
  '/legal/notices',
]
const staticRoutes = ['en', 'de'].flatMap((locale) => PATHS.map((path) => `/${locale}${path}`))

/** The conformance target. `best-practice` is deliberately excluded: useful, but not the bar. */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

/**
 * Locally the suite runs against the Vite dev server, which injects the `vite-plugin-vue-devtools`
 * floating panel; its button carries an ARIA attribute axe rejects. It is not our markup and never
 * reaches a build — CI already runs against `npm run preview`, where the anchor does not exist, so
 * excluding it costs no coverage and keeps the local and CI results identical.
 */
function buildScan(page: Page): AxeBuilder {
  return new AxeBuilder({ page }).exclude('#__vue-devtools-container__').withTags(TAGS)
}

for (const path of staticRoutes) {
  test(`${path} has no detectable accessibility violations`, async ({ page }) => {
    await page.goto(path)
    await expect(page.getByRole('main')).toBeVisible()

    const results = await buildScan(page).analyze()

    // Name the offending rules and elements in the failure message — a bare count is unactionable.
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
  // New visitors get dark; the toggle is the only way into light, and its palette is a separate
  // set of tokens that no other test exercises for contrast.
  await page.goto('/about')
  await page.getByRole('button', { name: /switch to light mode/i }).click()
  await expect(page.locator('html')).not.toHaveClass(/dark/)

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
 * Pass 2 — the data-driven routes, with the BFF mocked.
 *
 * Payloads are shaped after the ones in detail-routes.spec.ts / events-filters.spec.ts, trimmed to
 * what makes the components render. English only: this pass is about markup that the static pass
 * never reaches, and the German locale's contribution — longer strings — is already covered above
 * on the routes where the layout is shared.
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
    venue: { slug: 'mock-venue', name: 'Mock Venue' },
  },
  { slug: 'second-show', title: 'Second Show', eventDate: '2026-08-16' },
]

const page1 = <T>(content: T[], size: number) => ({
  content,
  page: 0,
  size,
  totalElements: content.length,
  // Two pages, so the pagination controls render and get scanned rather than being hidden by a
  // single-page short-circuit.
  totalPages: 2,
})

/**
 * Every read endpoint the data-driven views touch, answered with something renderable.
 *
 * The matchers are **deliberately non-overlapping**, which takes a lookahead to achieve: `/events`
 * has three sub-resources (`/today`, `/calendar`, `/{slug}`) and a naive `\/events\/[^/?]+` swallows
 * all three. Playwright consults route handlers in reverse registration order, so an overlap does
 * not merely pick the wrong body — it picks the one registered *last*, which is the opposite of
 * what reading the code top to bottom suggests. The failure is silent here: a feed served an object
 * instead of an array renders an empty state, and axe passes happily on markup that was never
 * there.
 */
async function mockBff(page: Page): Promise<void> {
  await page.route(/\/api\/events\/today/, (route) => json(route, eventSummaries))
  // FullCalendar asks for whatever window it is showing, so the events are placed on the requested
  // `from` date. That keeps a populated grid regardless of the machine's clock — the calendar opens
  // on the real "today" — which is the same trick calendar.spec.ts uses.
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
            district: 'friedrichshain-kreuzberg',
          },
          { slug: 'other-venue', name: 'Other Venue', city: 'Berlin' },
        ],
        24,
      ),
    ),
  )
  await page.route(/\/api\/genres/, (route) =>
    json(route, [
      { slug: 'techno', name: 'Techno' },
      { slug: 'jazz', name: 'Jazz' },
    ]),
  )
}

const dataRoutes = [
  { name: 'home, with both feeds populated', path: '/en' },
  { name: 'events list, with results and the filter bar', path: '/en/events' },
  { name: 'venues list, with results', path: '/en/venues' },
  { name: 'an event detail page', path: '/en/events/tonight-show' },
  // The calendar is the most complex widget on the site and the only one whose markup we do not
  // write: FullCalendar renders the grid, the toolbar and the event links. Third-party grid markup
  // is exactly where accessibility problems hide, and the static pass reaches this route with an
  // empty grid — which scans almost nothing.
  { name: 'the calendar, with a populated month grid', path: '/en/calendar' },
]

for (const route of dataRoutes) {
  test(`${route.name} has no detectable accessibility violations`, async ({ page }) => {
    await mockBff(page)
    await page.goto(route.path)

    await expect(page.getByRole('main')).toBeVisible()
    // The scan must not race the fetch: an empty list renders no cards, and axe would pass on
    // markup that was never there. Waiting on real content is what makes this pass meaningful.
    // The calendar renders its events as links rather than headings, hence the two shapes.
    await expect(
      page
        .getByRole('heading', { name: /Tonight Show|Mock Venue/ })
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

/**
 * One `best-practice` rule promoted to a gate on the routes it was actually failing on.
 *
 * This is the move the informational pass below prescribes — *"if a finding here turns out to
 * matter, the right move is to fix it"* — carried through to the end. `heading-order` reported one
 * node on each list page for as long as the pages existed: `EventCard` / `VenueCard` render an
 * `h3`, which is right under the home page's section `h2` and skips a level under a list page's
 * bare `h1`. The cards now take a `headingAs` prop and the two list pages pass `h2`.
 *
 * Pinned narrowly — one rule, the two routes it concerned — rather than by promoting the whole
 * pass, because the argument against gating on `best-practice` wholesale still stands: the
 * remaining finding is FullCalendar's `empty-table-header`, in third-party markup we do not write.
 * A regression here is a real outline defect, not a recommendation, and it is invisible on screen,
 * which is exactly the kind of thing that needs a test rather than a reviewer.
 */
for (const path of ['/en/events', '/en/venues', '/de/events', '/de/venues']) {
  test(`${path} has a heading outline with no skipped levels`, async ({ page }) => {
    await mockBff(page)
    await page.goto(path)
    // Wait on the cards by name, not by level: waiting on `level: 2` would make a regression
    // time out here instead of failing with the violation axe found, which is the useful message.
    await expect(page.getByRole('heading', { name: /Tonight Show|Mock Venue/ }).first()).toBeVisible()

    const results = await new AxeBuilder({ page })
      .exclude('#__vue-devtools-container__')
      .withRules(['heading-order'])
      .analyze()

    expect(results.violations.flatMap((v) => v.nodes.map((n) => n.html))).toEqual([])
  })
}

/**
 * Informational pass — axe's `best-practice` rules, which are **not** part of the WCAG 2.1 AA bar
 * the suite enforces above.
 *
 * Deliberately non-failing. These rules catch genuinely useful things (heading-order jumps, content
 * outside a landmark, unlabelled regions) but they are recommendations, not conformance criteria,
 * and gating a build on them means either fixing recommendations under deadline or, far more
 * likely, silencing them one by one until the whole pass is noise.
 *
 * Findings are attached to the Playwright report and printed to the console instead, so they are
 * there when someone goes looking. If a finding here turns out to matter, the right move is to fix
 * it — not to promote this pass to a gate.
 */
type BestPracticeFinding = { path: string; rule: string; help: string; nodes: number }

/**
 * Render the findings for the console — named, not just counted, because a bare total is
 * unactionable and the report attachment is one click further than anyone reading a CI log will go.
 *
 * A module-level helper rather than inline branching: `playwright/no-conditional-in-test` is right
 * that a conditional inside a test body usually hides an untested path, and the exception here is
 * formatting, not assertion.
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
  // WebKit does not move focus to links on Tab unless macOS "Full Keyboard Access" is enabled —
  // a platform default, not an app defect, and not something a page can or should override. The
  // functional behaviour is covered for every browser by the test above; this one only pins the
  // tab *order*, so it runs where Tab reaches links.
  // This is a conditional skip, not a disabled test.
  // eslint-disable-next-line playwright/no-skipped-test
  test.skip(browserName === 'webkit', 'WebKit excludes links from the Tab order by default')

  await page.goto('/about')
  await page.keyboard.press('Tab')

  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused()
})
