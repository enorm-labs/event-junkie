import { expect, type Page, test } from '@playwright/test'

/**
 * Resilient smoke suite, deliberately shallow: the app boots, the router mounts each static view,
 * the shared chrome renders. Nothing about data-driven content, so it survives UI churn; detail
 * routes are covered by detail-routes.spec.ts, which mocks the BFF. The BFF is not running, so
 * `onMounted` API calls fail by design and the views render their error state; assertions are on
 * uncaught exceptions (`pageerror`) only, the true "the app broke" signal.
 */

/** Static routes and the stable <h1> each is expected to mount. */
// `path` is what a visitor types, `url` where they end up: routes are locale-prefixed (ADR-013
// §Decision 2) and home is `/en`, not `/en/`. Pinned to English as the stable handle (AGENTS.md
// §Testing, locale strategy). Listed in header order, so the nav walk reads left to right.
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
  {
    path: '/promoters',
    url: '/en/promoters',
    name: 'promoters',
    nav: 'Promoters',
    heading: 'Promoters',
  },
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
  // The order is a product decision: Events and Calendar are two views of the same data, Venues a
  // different entity, About is meta. Every other test addresses links by name, so without this an
  // edit could reshuffle it silently.
  await page.goto('/about')

  const nav = page.getByRole('navigation', { name: 'Main' })
  const labels = await nav.getByRole('link').allInnerTexts()

  // Filtered to the section links: the header also carries the brand, the beta badge, the locale
  // links and an icon-only GitHub button.
  const sections = ['Events', 'Calendar', 'Venues', 'Promoters', 'About']
  const rendered = labels.map((label) => label.trim()).filter((label) => sections.includes(label))

  expect(rendered).toEqual(sections)
})

// One row overflowed a ~390px screen, and later the 640–800px band where the nav had already
// switched to one row, which neither the desktop nor the phone project ever rendered. So the widths
// in between are set here, in both languages: the German labels are the longer ones. Scoped to the
// nav because this runs without a BFF; the document-level check lives in home-feeds.spec.ts, where
// real cards exist to overflow.
for (const locale of ['en', 'de']) {
  test(`header nav fits its viewport without overflowing (${locale})`, async ({ page }) => {
    await page.goto(`/${locale}/about`)
    const nav = page.getByRole('navigation', { name: locale === 'de' ? 'Hauptnavigation' : 'Main' })
    await expect(nav).toBeVisible()

    for (const width of [390, 640, 720, 800, 900, 1024, 1280]) {
      await page.setViewportSize({ width, height: 800 })
      const box = await nav.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }))
      expect(box.scroll, `nav content is wider than the nav at ${width}px`).toBeLessThanOrEqual(
        box.client,
      )

      // The right-most control must land inside the viewport, not merely inside a clipped nav.
      const toggle = nav.getByRole('button').last()
      const toggleBox = await toggle.boundingBox()
      expect(
        toggleBox!.x + toggleBox!.width,
        `last control off-screen at ${width}px`,
      ).toBeLessThanOrEqual(width)
    }
  })
}

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
