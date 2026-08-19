import { expect, type Page, test } from '@playwright/test'

/**
 * Resilient smoke suite.
 *
 * Deliberately shallow: it verifies the app boots, the router mounts each static view, and the
 * shared chrome renders — the cross-cutting breakage unit tests miss (blank screen, broken
 * lazy-loaded chunk, dead route). It asserts nothing about data-driven content, so it survives UI
 * churn while the frontend is still in flux. Detail routes (/events/:slug, /venues/:slug, …) are
 * omitted for the same reason and covered by detail-routes.spec.ts, which mocks the BFF.
 *
 * The BFF is not running during e2e, so `onMounted` API calls fail and log console and network
 * errors by design; the views render their error state. Assertions are therefore on uncaught
 * exceptions (`pageerror`) only — the true "the app broke" signal — rather than console output.
 */

/** Static routes and the stable <h1> each is expected to mount. */
// `path` is what a visitor types (unprefixed paths redirect); `url` is where they end up, since
// routes are locale-prefixed (ADR-013 §Decision 2) and home is the locale root itself — `/en`, not
// `/en/`. This suite is pinned to English deliberately: it tests behaviour, and English is simply
// the stable handle for it (see AGENTS.md §Testing — locale strategy).
// Listed in the order the header renders them, so the nav walk below also reads left to right.
const staticRoutes = [
  // `nav` is the accessible name of the nav link — home's is the brand logo, not "Home".
  { path: '/', url: '/en', name: 'home', nav: 'Event Junkie', heading: 'Event Junkie' },
  { path: '/events', url: '/en/events', name: 'events', nav: 'Events', heading: 'Events' },
  {
    path: '/calendar',
    url: '/en/calendar',
    name: 'calendar',
    nav: 'Calendar',
    heading: 'Calendar',
  },
  { path: '/venues', url: '/en/venues', name: 'venues', nav: 'Venues', heading: 'Venues' },
  { path: '/about', url: '/en/about', name: 'about', nav: 'About', heading: 'About' },
] as const

/** Attach an uncaught-exception collector before navigation. */
function collectPageErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  return errors
}

for (const route of staticRoutes) {
  test(`mounts the ${route.name} view without crashing`, async ({ page }) => {
    const errors = collectPageErrors(page)

    await page.goto(route.path)

    // View mounted: its landmark and heading are present.
    await expect(page.getByRole('main')).toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: route.heading })).toBeVisible()

    // Shared app shell rendered.
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()

    expect(errors, 'unexpected uncaught exceptions').toEqual([])
  })
}

test('navigates between static routes via the nav bar', async ({ page }) => {
  const errors = collectPageErrors(page)

  await page.goto('/')
  const nav = page.getByRole('navigation', { name: 'Main' })

  for (const route of staticRoutes) {
    await nav.getByRole('link', { name: route.nav, exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`${route.url.replaceAll('/', '\\/')}$`))
    await expect(page.getByRole('heading', { level: 1, name: route.heading })).toBeVisible()
  }

  expect(errors, 'unexpected uncaught exceptions').toEqual([])
})

test('header nav lists the sections in the intended order', async ({ page }) => {
  // The order is a product decision, not an accident: Events and Calendar are two views of the
  // same event data and belong together, Venues is a different entity, About is meta. Without this
  // assertion the sequence is invisible to every other test — they all address links by name — so
  // an edit could reshuffle it silently.
  await page.goto('/about')

  const nav = page.getByRole('navigation', { name: 'Main' })
  const labels = await nav.getByRole('link').allInnerTexts()

  // Filtered to the section links rather than compared whole: the header also carries the brand,
  // the beta badge, the locale links and an icon-only GitHub button, and pinning those here would
  // make this test fail for reasons that have nothing to do with the section order.
  const sections = ['Events', 'Calendar', 'Venues', 'About']
  const rendered = labels.map((label) => label.trim()).filter((label) => sections.includes(label))

  expect(rendered).toEqual(sections)
})

test('header nav fits its viewport without overflowing', async ({ page }) => {
  // The header packs a brand lockup, a beta badge, four nav links and two icon controls in; on a
  // ~390px screen one row overflowed and pushed the controls off-screen, so the nav wraps below
  // `sm`. Runs on every project — the two mobile ones are what this actually guards. The badge is
  // the most recent addition and the reason this check is not merely historical.
  //
  // Scoped to the nav rather than the whole document because this runs without a BFF, so the
  // data-driven routes render their error state. The document-level check lives in
  // home-feeds.spec.ts, where the feeds are mocked and real cards exist to overflow.
  await page.goto('/about')

  const nav = page.getByRole('navigation', { name: 'Main' })
  await expect(nav).toBeVisible()

  const box = await nav.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }))
  expect(box.scroll, 'nav content is wider than the nav').toBeLessThanOrEqual(box.client)

  // The right-most control must land inside the viewport, not merely inside a clipped nav.
  const toggle = page.getByRole('button', { name: /switch to (dark|light) mode/i })
  const toggleBox = await toggle.boundingBox()
  const viewport = page.viewportSize()
  expect(toggleBox && viewport && toggleBox.x + toggleBox.width).toBeLessThanOrEqual(
    viewport!.width,
  )
})

test('app shell marks the app as beta and explains what that means', async ({ page }) => {
  await page.goto('/venues')

  const badge = page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: /beta/i })
  await expect(badge).toBeVisible()
  // "beta" alone is a useless accessible name out of context, so the link carries a full sentence.
  await expect(badge).toHaveAttribute('aria-label', /data may be incomplete/i)
  await expect(badge).toHaveAttribute('title', /data may be incomplete/i)

  await badge.click()

  await expect(page).toHaveURL(/\/about#beta$/)
  await expect(page.getByRole('heading', { name: 'Why it says beta' })).toBeVisible()
})

test('app shell links to the source repository on GitHub', async ({ page }) => {
  await page.goto('/about')

  const link = page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Source code on GitHub' })
  await expect(link).toBeVisible()
  await expect(link).toHaveAttribute('href', 'https://github.com/enorm-labs/event-junkie')
  await expect(link).toHaveAttribute('title', 'Source code on GitHub')
})

test('app shell exposes a working dark-mode toggle', async ({ page }) => {
  await page.goto('/')

  const toggle = page.getByRole('button', { name: /switch to (dark|light) mode/i })
  await expect(toggle).toBeVisible()

  // New visitors start in dark (the default); toggling switches to light.
  await expect(page.locator('html')).toHaveClass(/dark/)
  await expect(toggle).toHaveAttribute('title', 'Switch to light mode')

  await toggle.click()

  await expect(page.locator('html')).not.toHaveClass(/dark/)
  // The tooltip tracks the theme alongside the accessible name — both read from one computed.
  await expect(toggle).toHaveAttribute('title', 'Switch to dark mode')
})
