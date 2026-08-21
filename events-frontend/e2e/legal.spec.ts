import { expect, test } from '@playwright/test'

/**
 * The legal pages have to be *reachable*, not merely present: German practice expects the imprint
 * within a couple of clicks from any page, which for this site means the footer on every route.
 * See docs/LEGAL.md §6.
 */

test('reaches the imprint in one click from the footer, on any route', async ({ page }) => {
  await page.goto('/venues')

  await page.getByRole('contentinfo').getByRole('link', { name: 'Imprint' }).click()

  await expect(page).toHaveURL(/\/legal\/imprint$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Imprint' })).toBeVisible()
})

test('reaches the privacy notice in one click from the footer', async ({ page }) => {
  await page.goto('/')

  await page.getByRole('contentinfo').getByRole('link', { name: 'Privacy' }).click()

  await expect(page).toHaveURL(/\/legal\/privacy$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Privacy' })).toBeVisible()
})

test('scrolls to the top when opening a legal page from a scrolled position', async ({ page }) => {
  // Without a scrollBehavior the router keeps the previous offset, so a footer link opens the
  // imprint somewhere in its middle — which reads as a broken page.
  await page.goto('/about')
  // `window.scrollTo` rather than `mouse.wheel`: the latter is a no-op on touch-emulating projects
  // (Mobile Safari), so the page was never scrolled and the test passed vacuously there.
  await page.evaluate(() => window.scrollTo(0, 2000))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(100)

  await page.getByRole('contentinfo').getByRole('link', { name: 'Imprint' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Imprint' })).toBeVisible()

  expect(await page.evaluate(() => window.scrollY)).toBeLessThan(50)
})

test('the imprint carries the § 5 DDG essentials', async ({ page }) => {
  await page.goto('/legal/imprint')
  const main = page.getByRole('main')

  await expect(main).toContainText('Norman Lange')
  await expect(main).toContainText('§ 18 (2) MStV')
  await expect(main).toContainText('without warranty as to accuracy')
  await expect(main.getByRole('link', { name: /@/ })).toBeVisible()
})

test('the privacy notice states its legal basis, rights and supervisory authority', async ({
  page,
}) => {
  await page.goto('/legal/privacy')
  const main = page.getByRole('main')

  await expect(main).toContainText('Art. 6 (1) (f) GDPR')
  await expect(main).toContainText('Right to object (Art. 21 GDPR)')
  await expect(main).toContainText('Berliner Beauftragte')
  await expect(main).toContainText('§ 25 (2) 2 TDDDG')
})

// Renamed 2026-08-21. The contact details stopped being placeholders when the Postflex address was
// rented; the banner stayed, because nothing is deployed. Two flags, and only one of them moved.
test('legal pages still say they are not final, because nothing is deployed', async ({ page }) => {
  for (const path of ['/legal/imprint', '/legal/privacy']) {
    await page.goto(path)
    await expect(page.getByRole('main')).toContainText('This page is not final')
    await expect(page.getByRole('main')).not.toContainText('placeholder')
  }
})

// The address is only useful if it renders whole. The c/o line carries the customer number that
// routes the post, so an imprint showing the street without it is undeliverable while looking fine.
test('the imprint renders the full rented address, c/o line included', async ({ page }) => {
  await page.goto('/legal/imprint')
  const main = page.getByRole('main')
  await expect(main).toContainText('Norman Lange')
  await expect(main).toContainText('c/o POSTFLEX')
  await expect(main).toContainText('Emsdettener Straße 10')
  await expect(main).toContainText('48268 Greven')
})

test('sets a document title for each legal route', async ({ page }) => {
  const titles = [
    ['/legal/imprint', 'Imprint · Event Junkie'],
    ['/legal/privacy', 'Privacy · Event Junkie'],
    ['/legal/notices', 'Open-source notices · Event Junkie'],
  ] as const

  for (const [path, title] of titles) {
    await page.goto(path)
    await expect(page).toHaveTitle(title)
  }
})

test('reaches the open-source notices from the footer and lists real components', async ({
  page,
}) => {
  await page.goto('/')

  await page.getByRole('contentinfo').getByRole('link', { name: 'Open-source notices' }).click()

  await expect(page).toHaveURL(/\/legal\/notices$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Open-source notices' })).toBeVisible()

  // MIT is by far the largest group, so it is the stable one to assert on. The em dash keeps this
  // off "MIT-0", which is its own group.
  await expect(page.locator('summary').filter({ hasText: /^\s*MIT\s+—/ })).toBeVisible()
})

test('expands a licence group to reveal its components', async ({ page }) => {
  await page.goto('/legal/notices')

  const group = page
    .locator('details')
    .filter({ has: page.locator('summary').filter({ hasText: /^\s*MIT\s+—/ }) })

  // Collapsed by default — several hundred rows open at once would make the page unusable.
  await expect(group.locator('li').first()).toBeHidden()

  await group.locator('summary').click()

  await expect(group.locator('li').first()).toBeVisible()
  await expect(group.getByRole('link', { name: /Read the MIT licence/ })).toBeVisible()
})

test('states what the notices list does and does not cover', async ({ page }) => {
  // The list is generated from the dependency graph, which is broader than the shipped bundle and
  // does not reproduce full licence texts. Claiming otherwise would be the inaccuracy to avoid.
  await page.goto('/legal/notices')

  await expect(page.getByRole('main')).toContainText('broader than what is served to your browser')
  await expect(page.getByRole('main')).toContainText('rather than reproducing the full')
})
