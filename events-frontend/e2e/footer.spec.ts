import { expect, test } from '@playwright/test'

/**
 * The footer is part of the app shell, so it has to hold up on every route and at every width —
 * including the ~390px viewport that the header already had to be reworked for.
 * See docs/LEGAL.md §2.
 */

const routes = ['/', '/events', '/venues', '/calendar', '/about'] as const

for (const path of routes) {
  test(`renders the footer on ${path}`, async ({ page }) => {
    await page.goto(path)

    const footer = page.getByRole('contentinfo')
    await expect(footer).toBeVisible()
    await expect(footer).toContainText('© 2026 Event Junkie')
  })
}

test('states the data disclaimer without needing a click', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('contentinfo')).toContainText(
    'aggregated from public sources and provided without warranty',
  )
})

test('links to the repository, the issue tracker and the releases', async ({ page }) => {
  await page.goto('/about')
  const footer = page.getByRole('contentinfo')

  await expect(footer.getByRole('link', { name: 'Source on GitHub' })).toHaveAttribute(
    'href',
    'https://github.com/enorm-labs/event-junkie',
  )
  await expect(footer.getByRole('link', { name: 'Report an issue' })).toHaveAttribute(
    'href',
    /\/issues\/new/,
  )
  await expect(footer.getByRole('link', { name: 'Changelog' })).toHaveAttribute(
    'href',
    /\/releases$/,
  )
})

test('distinguishes the copyright from the code licence', async ({ page }) => {
  await page.goto('/about')
  const footer = page.getByRole('contentinfo')

  const licence = footer.getByRole('link', { name: 'Code under Apache-2.0' })
  await expect(licence).toHaveAttribute('href', /\/blob\/main\/LICENSE$/)
})

test('footer fits its viewport without overflowing', async ({ page }) => {
  // Same guard as the header's (smoke.spec.ts): the two mobile projects are what this protects.
  // Scoped to the footer because the data-driven routes render their error state without a BFF.
  await page.goto('/about')

  const footer = page.getByRole('contentinfo')
  await expect(footer).toBeVisible()

  const box = await footer.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }))
  expect(box.scroll, 'footer content is wider than the footer').toBeLessThanOrEqual(box.client)
})

test('shows the running version and commit once /meta answers', async ({ page }) => {
  await page.route('**/api/meta', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        version: '0.1.0',
        commit: '9f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3',
        commitShort: '9f1a2b3',
        buildTime: '2026-08-07T12:49:19Z',
      }),
    }),
  )

  await page.goto('/about')
  const footer = page.getByRole('contentinfo')

  await expect(footer.getByTestId('app-version')).toContainText('v0.1.0')
  await expect(footer.getByRole('link', { name: 'v0.1.0' })).toHaveAttribute(
    'href',
    /\/releases\/tag\/v0\.1\.0$/,
  )
  await expect(footer.getByRole('link', { name: '9f1a2b3' })).toHaveAttribute(
    'href',
    /\/commit\/9f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3$/,
  )
})

test('shows no version line when the backend is unreachable', async ({ page }) => {
  // Failure is forced rather than assumed. Relying on "no BFF runs during e2e" would make this
  // pass or fail depending on whether the developer happens to have the stack up locally.
  await page.route('**/api/meta', (route) => route.abort())

  await page.goto('/about')

  await expect(page.getByRole('contentinfo')).toContainText('© 2026 Event Junkie')
  await expect(page.getByRole('contentinfo').getByTestId('app-version')).toHaveCount(0)
})

test('sits at the bottom of the viewport on a short page', async ({ page }) => {
  // The About view is shorter than the viewport, so without the flex-column shell the footer
  // would float mid-screen with dead space beneath it.
  await page.goto('/about')

  const footer = page.getByRole('contentinfo')
  const box = await footer.boundingBox()
  const viewport = page.viewportSize()

  expect(box && viewport && box.y + box.height).toBeGreaterThanOrEqual(viewport!.height - 1)
})
