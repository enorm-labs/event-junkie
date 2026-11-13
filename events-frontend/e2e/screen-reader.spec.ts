import { expect, test } from './test'
import { mockBff } from './bff-mocks'

/**
 * What a screen reader reads on the three views #373 names: the accessibility tree of each, pinned
 * as an aria snapshot. axe checks rules; this catches a heading, label, landmark or live region
 * that changes or disappears without breaking one. A changed tree is updated with
 * `--update-snapshots` only when the change is intended.
 *
 * Chromium only, because each engine builds its own tree and one is enough to see a regression.
 * The page chrome is pinned once; each view pins only its `main`, so a header change fails one test.
 */

// eslint-disable-next-line playwright/no-skipped-test
test.skip(({ browserName, isMobile }) => browserName !== 'chromium' || isMobile, 'one engine pins the tree')

test.beforeEach(async ({ page }) => {
  // The calendar month, "today" and the relative dates on the cards all read the clock.
  await page.clock.setFixedTime(new Date('2026-08-15T16:00:00Z'))
  await mockBff(page)
})

const views = [
  { name: 'events-list', path: '/events', ready: { role: 'heading', name: /Tonight Show/ } },
  { name: 'event-detail', path: '/events/tonight-show', ready: { role: 'heading', name: /Tonight Show/ } },
  { name: 'calendar', path: '/calendar', ready: { role: 'link', name: /Tonight Show/ } },
] as const

for (const locale of ['en', 'de']) {
  for (const view of views) {
    test(`${locale} ${view.name} reads as pinned`, async ({ page }) => {
      await page.goto(`/${locale}${view.path}`)
      await expect(page.getByRole(view.ready.role, { name: view.ready.name }).first()).toBeVisible()

      await expect(page.getByRole('main')).toMatchAriaSnapshot({ name: `${locale}-${view.name}.aria.yml` })
    })
  }

  test(`${locale} page chrome reads as pinned`, async ({ page }) => {
    await page.goto(`/${locale}/events`)
    await expect(page.getByRole('heading', { name: /Tonight Show/ }).first()).toBeVisible()

    await expect(page.getByRole('banner')).toMatchAriaSnapshot({ name: `${locale}-banner.aria.yml` })
    await expect(page.getByRole('contentinfo')).toMatchAriaSnapshot({ name: `${locale}-footer.aria.yml` })
  })
}
