import { expect, type Page, test } from '@playwright/test'
import { displaySetting, sectionLink } from './header-nav'

/**
 * The theme lives in the app shell's display settings (App.vue), which is not remounted by
 * in-app router navigation, so the choice persists as you move between routes. The theme
 * is stored in localStorage and re-applied before paint (inline script in index.html),
 * so it survives a full page reload too. New visitors (no stored choice) default to dark;
 * an explicit choice always wins. All three contracts are asserted below.
 */

const html = (page: Page) => page.locator('html')
const theme = (page: Page, option: 'Light' | 'Dark') => displaySetting(page, 'Theme', option)

test('defaults to dark for a new visitor', async ({ page }) => {
  // Fresh context = no stored preference, so the pre-paint script opts into dark.
  await page.goto('/')
  await expect(html(page)).toHaveClass(/dark/)
  await expect(await theme(page, 'Dark')).toHaveAttribute('aria-pressed', 'true')
})

test('theme choice persists across in-app navigation', async ({ page }) => {
  await page.goto('/')
  await expect(html(page)).toHaveClass(/dark/)

  // Switch to light and confirm it sticks across routes.
  await (await theme(page, 'Light')).click()
  await expect(html(page)).not.toHaveClass(/dark/)
  await page.keyboard.press('Escape')

  await (await sectionLink(page, 'Events')).click()
  await expect(page).toHaveURL(/\/events$/)
  await expect(html(page)).not.toHaveClass(/dark/)

  await (await sectionLink(page, 'About')).click()
  await expect(page).toHaveURL(/\/about$/)
  await expect(html(page)).not.toHaveClass(/dark/)

  // Switching back to dark also persists across navigation.
  await (await theme(page, 'Dark')).click()
  await expect(html(page)).toHaveClass(/dark/)
  await page.keyboard.press('Escape')

  // The logo is the home link. Home is the locale root — `/en`, not `/` (ADR-013 §Decision 2).
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Event Junkie', exact: true })
    .click()
  await expect(page).toHaveURL(/\/en$/)
  await expect(html(page)).toHaveClass(/dark/)
})

test('an explicit light choice persists across a full page reload', async ({ page }) => {
  await page.goto('/')
  // Opt out of the dark default…
  await (await theme(page, 'Light')).click()
  await expect(html(page)).not.toHaveClass(/dark/)

  await page.reload()

  // …and the stored light choice wins over the dark default after a reload.
  await expect(html(page)).not.toHaveClass(/dark/)
  await expect(await theme(page, 'Light')).toHaveAttribute('aria-pressed', 'true')
})
