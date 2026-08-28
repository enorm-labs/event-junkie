import { expect, test } from '@playwright/test'

/**
 * Locale routing and the content that is genuinely locale-specific.
 *
 * The other suites are pinned to `/en` deliberately (AGENTS.md §Testing — locale strategy): they are
 * behaviour tests that happen to use English accessible names as stable handles, and re-running
 * them in German would double the matrix to re-assert the same behaviour. This file carries what
 * only exists in a second language — the URL contract, the redirects, the switcher, date formats,
 * and the four long-form pages that have a separate component per language.
 */

test('the bare root redirects to a locale', async ({ page }) => {
  await page.goto('/')

  await expect(page).toHaveURL(/\/en$/)
  await expect(page.getByRole('main')).toBeVisible()
})

test('an unprefixed path keeps its route through the redirect', async ({ page }) => {
  // The redirect must preserve where the visitor was going, not just drop them on the home page.
  await page.goto('/venues')

  await expect(page).toHaveURL(/\/en\/venues$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Venues' })).toBeVisible()
})

test('an unprefixed path preserves its query string and hash', async ({ page }) => {
  await page.goto('/events?type=CONCERT')
  await expect(page).toHaveURL(/\/en\/events\?type=CONCERT$/)

  await page.goto('/about#beta')
  await expect(page).toHaveURL(/\/en\/about#beta$/)
  await expect(page.getByRole('heading', { name: 'Why it says beta' })).toBeVisible()
})

test('html lang matches the locale in the URL', async ({ page }) => {
  // WCAG 3.1.1. It was `lang=""` before the footer work; this is what keeps it from drifting back.
  await page.goto('/en/about')

  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
})

test('an unknown path under a published locale does not loop', async ({ page }) => {
  // The catch-all prefixes unprefixed paths. Without a guard, an unmatched *prefixed* path would
  // be prefixed again — `/en/en/nonsense` — and redirect forever. It lands on the locale home.
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))

  await page.goto('/en/nonsense')

  await expect(page).toHaveURL(/\/en$/)
  await expect(page.getByRole('main')).toBeVisible()
  expect(errors, 'redirect loop or router error').toEqual([])
})

test('an unpublished locale is treated as an unknown path, not as a locale', async ({ page }) => {
  // French is not in LOCALES, so `/fr/events` must not resolve — a route that renders another
  // language under a French URL is worse than no French URL at all. It is prefixed as an ordinary
  // unknown path, matches nothing, and settles on the locale home. Two hops, no loop.
  await page.goto('/fr/events')

  await expect(page).toHaveURL(/\/en$/)
  await expect(page.getByRole('main')).toBeVisible()
})

test('navigating within the app keeps the locale prefix', async ({ page }) => {
  await page.goto('/en')

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Venues' }).click()

  await expect(page).toHaveURL(/\/en\/venues$/)
})

test('a German URL renders German', async ({ page }) => {
  await page.goto('/de/venues')

  await expect(page.locator('html')).toHaveAttribute('lang', 'de')
  await expect(page.getByRole('heading', { level: 1, name: 'Locations' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Haupt' })).toBeVisible()
  await expect(page.getByRole('contentinfo')).toContainText('Von Berlin kriegst du nie genug')
})

test('the locale switcher keeps you on the same page', async ({ page }) => {
  // Switching language on the venues list must not dump you on the home page — the whole reason
  // the switcher rewrites only the locale segment.
  await page.goto('/en/venues')

  await page
    .getByRole('navigation', { name: 'Language' })
    .getByRole('link', { name: 'Deutsch' })
    .click()

  await expect(page).toHaveURL(/\/de\/venues$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Locations' })).toBeVisible()
})

test('the switcher marks the active language and offers real links', async ({ page }) => {
  await page.goto('/de/about')
  const switcher = page.getByRole('navigation', { name: 'Sprache' })

  // Real hrefs, not JS handlers: middle-click, "copy link" and crawlers all depend on them.
  await expect(switcher.getByRole('link', { name: 'English' })).toHaveAttribute('href', '/en/about')
  await expect(switcher.getByRole('link', { name: 'Deutsch' })).toHaveAttribute(
    'aria-current',
    'true',
  )
})

test('dates render in the locale format', async ({ page }) => {
  // The most visible difference between the two locales, and the reason formatDate takes a locale.
  await page.route('**/api/events/today', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          slug: 'x',
          title: 'Test Event',
          eventDate: '2026-06-12',
          venue: { slug: 'v', name: 'V' },
        },
      ]),
    }),
  )

  await page.goto('/en')
  await expect(page.getByText(/12 Jun 2026/)).toBeVisible()

  await page.goto('/de')
  await expect(page.getByText(/12\. Juni 2026/)).toBeVisible()
})

test('the header carries a compact locale switcher', async ({ page }) => {
  await page.goto('/en/venues')

  const header = page.getByRole('navigation', { name: 'Main' })
  const toGerman = header.getByRole('link', { name: 'Deutsch' })

  // `DE` is the visible label; the accessible name is the full native language name, because "DE"
  // alone tells a screen-reader user nothing.
  await expect(toGerman).toHaveText('DE')
  await expect(toGerman).toHaveAttribute('href', '/de/venues')

  await toGerman.click()
  await expect(page).toHaveURL(/\/de\/venues$/)
  await expect(page.locator('html')).toHaveAttribute('lang', 'de')
})

test('the header switcher adds no second Language landmark', async ({ page }) => {
  // Two navigation landmarks with the same accessible name are indistinguishable in a screen
  // reader's landmark list — and ambiguous to any selector addressing them by name. The compact
  // switcher lives inside the header's own nav instead.
  await page.goto('/en')

  await expect(page.getByRole('navigation', { name: 'Language' })).toHaveCount(1)
  await expect(
    page.getByRole('contentinfo').getByRole('navigation', { name: 'Language' }),
  ).toHaveCount(1)
})

test('both switchers mark the active language', async ({ page }) => {
  await page.goto('/de/about')

  // "Haupt", not "Main": the landmark's accessible name is itself translated, so a German reader
  // hears a German landmark list. Selectors addressing landmarks by name are locale-dependent —
  // which is why the other suites are pinned to /en.
  const header = page.getByRole('navigation', { name: 'Haupt' })
  await expect(header.getByRole('link', { name: 'Deutsch' })).toHaveAttribute(
    'aria-current',
    'true',
  )
  await expect(header.getByRole('link', { name: 'English' })).not.toHaveAttribute(
    'aria-current',
    'true',
  )
})

/**
 * The long-form pages — About and the three legal ones — are a separate component per language
 * rather than translated strings (src/views/localisedView.ts). That makes one failure mode
 * possible that the message catalogue's key-parity test cannot see: the route resolving to the
 * wrong language version, or to none. These are the tests that would catch it.
 */

test('the German imprint is German, not the English page under a German URL', async ({ page }) => {
  await page.goto('/de/legal/imprint')
  const main = page.getByRole('main')

  await expect(page.getByRole('heading', { level: 1, name: 'Impressum' })).toBeVisible()
  await expect(main).toContainText('Angaben gemäß § 5 DDG')
  await expect(main).toContainText('§ 18 Abs. 2 MStV')
  // The tell for a silent fallback: the English headings, under /de.
  await expect(main).not.toContainText('Service provider')
})

test('the German privacy notice carries the Art. 13 elements in German form', async ({ page }) => {
  // Not a re-run of the unit checklist — this proves the *route* serves the German document, with
  // its German citations, in a real browser. `Art. 6 (1) (f) GDPR` here would mean the fallback.
  await page.goto('/de/legal/privacy')
  const main = page.getByRole('main')

  await expect(main).toContainText('Art. 6 Abs. 1 lit. f DSGVO')
  await expect(main).toContainText('Widerspruchsrecht (Art. 21 DSGVO)')
  await expect(main).toContainText('§ 25 Abs. 2 Nr. 2 TDDDG')
  await expect(main).toContainText('Berliner Beauftragte')
})

test('both language versions name the German one as authoritative', async ({ page }) => {
  await page.goto('/en/legal/imprint')
  await expect(page.getByRole('main')).toContainText('the German version prevails')

  await page.goto('/de/legal/imprint')
  await expect(page.getByRole('main')).toContainText('deutsche Fassung maßgeblich')
})

test('the About page and its beta anchor are German under /de', async ({ page }) => {
  // The header's beta badge links to `#beta` in whichever locale you are in, so the anchor id has
  // to survive translation even though every heading around it changes.
  await page.goto('/de/about#beta')

  await expect(page.getByRole('heading', { level: 1, name: 'Über uns' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Warum da beta steht' })).toBeVisible()
})

test('the notices page counts components in German too', async ({ page }) => {
  await page.goto('/de/legal/notices')

  await expect(page.getByRole('heading', { level: 1, name: 'Open-Source-Lizenzen' })).toBeVisible()
  await expect(page.locator('summary').filter({ hasText: /^\s*MIT\s+—/ })).toContainText(
    'Komponenten',
  )
})

test('the venue opt-out page is German under /de, route and all', async ({ page }) => {
  // The audience is a Berlin venue operator, so the German version is the one that actually gets
  // read — and a silent fallback would hand them the opt-out route in the wrong language.
  await page.goto('/de/legal/for-venues')
  const main = page.getByRole('main')

  await expect(page.getByRole('heading', { level: 1, name: 'Für Locations' })).toBeVisible()
  await expect(main).toContainText('schalten die Quelle ab')
  await expect(main).toContainText('innerhalb von sieben Tagen')
  await expect(main).not.toContainText('disable the source')
})

test('switching language on a legal page stays on that page', async ({ page }) => {
  // The case the switcher most needs to get right: someone reading the privacy notice in the wrong
  // language should land on the *notice*, not on the home page.
  await page.goto('/en/legal/privacy')

  await page
    .getByRole('navigation', { name: 'Language' })
    .getByRole('link', { name: 'Deutsch' })
    .click()

  await expect(page).toHaveURL(/\/de\/legal\/privacy$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Datenschutz' })).toBeVisible()
})

test('sets a German document title for each legal route', async ({ page }) => {
  const titles = [
    ['/de/legal/imprint', 'Impressum · Event Junkie'],
    ['/de/legal/privacy', 'Datenschutz · Event Junkie'],
    ['/de/legal/notices', 'Open-Source-Lizenzen · Event Junkie'],
    ['/de/legal/for-venues', 'Für Locations · Event Junkie'],
  ] as const

  for (const [path, title] of titles) {
    await page.goto(path)
    await expect(page).toHaveTitle(title)
  }
})

/**
 * Detail-view chrome, which localisation Phase 2 missed.
 *
 * The entity label, the empty-feed copy and the not-found copy were English literals passed as
 * props, so a German visitor met English on three pages. They are catalogue-backed now; these are
 * the tests that would notice if one drifted back, which is easy because the strings live in the
 * *calling* view rather than in the shared component that renders them.
 */

test('a German venue page says it is not found, in German', async ({ page }) => {
  await page.route('**/api/venues/nope', (route) =>
    route.fulfill({ status: 404, contentType: 'application/json', body: '{}' }),
  )

  await page.goto('/de/venues/nope')

  // "Location nicht gefunden" — the negation comes last in German, which is why the heading is one
  // interpolated message rather than a label concatenated with "not found".
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Location nicht gefunden')
  await expect(page.getByRole('main')).toContainText('kleinen schwarzen Buch')
})

test('a German venue page with no upcoming events says so in German', async ({ page }) => {
  await page.route('**/api/venues/lido', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ slug: 'lido', name: 'Lido', city: 'Berlin' }),
    }),
  )
  await page.route('**/api/events?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
    }),
  )

  await page.goto('/de/venues/lido')

  await expect(page.getByRole('heading', { level: 1, name: 'Lido' })).toBeVisible()
  await expect(page.getByRole('main')).toContainText('Hier steht noch nichts an')
})

test('German detail pages label the entity kind in German', async ({ page }) => {
  for (const [path, kind] of [
    ['/de/artists/nope', 'Act'],
    ['/de/promoters/nope', 'Veranstalter'],
  ] as const) {
    await page.route(`**/api${path.replace('/de', '')}`, (route) =>
      route.fulfill({ status: 404, contentType: 'application/json', body: '{}' }),
    )

    await page.goto(path)

    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`${kind} nicht gefunden`)
  }
})

/**
 * The list pages' result count and pagination were hardcoded English — including an
 * English plural rule (`n === 1 ? 'event' : 'events'`) that no other language shares.
 * Both are message keys now, so this checks the German side, which is the half a
 * pinned-to-`/en` suite can never see.
 *
 * The counts are deliberately 1 and 2: one exercises the singular form of the plural
 * message, the other the plural, and getting them from the same mock keeps the two
 * assertions honest about which branch rendered.
 */
const listPage = (content: unknown[], totalElements: number, totalPages: number) =>
  JSON.stringify({ content, page: 0, size: 20, totalElements, totalPages })

test('the events list counts its results in German, singular and plural', async ({ page }) => {
  await page.route('**/api/events?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: listPage([{ slug: 'a', title: 'Ein Konzert', eventDate: '2026-08-15' }], 1, 1),
    }),
  )
  await page.goto('/de/events')
  // Exact, not `toContainText`: an unpluralised render is the literal message with both branches
  // ("1 Event gefunden | 1 Events gefunden"), which *contains* the singular and would pass.
  await expect(page.getByText('1 Event gefunden', { exact: true })).toBeVisible()

  await page.route('**/api/events?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: listPage(
        [
          { slug: 'a', title: 'Ein Konzert', eventDate: '2026-08-15' },
          { slug: 'b', title: 'Noch ein Konzert', eventDate: '2026-08-16' },
        ],
        2,
        2,
      ),
    }),
  )
  await page.reload()
  await expect(page.getByText('2 Events gefunden', { exact: true })).toBeVisible()
  await expect(page.getByText('Seite 1 von 2', { exact: true })).toBeVisible()
})

test('the venues list counts its results in German', async ({ page }) => {
  await page.route('**/api/venues?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: listPage([{ slug: 'lido', name: 'Lido', city: 'Berlin' }], 1, 1),
    }),
  )
  await page.goto('/de/venues')
  // "Location", not "Venue" — the German catalogue calls a venue a Location throughout.
  await expect(page.getByText('1 Location gefunden', { exact: true })).toBeVisible()
})
