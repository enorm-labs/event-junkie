import { expect, type Page, test } from '@playwright/test'
import { sectionLink } from './header-nav'

/**
 * The dark-mode toggle lives in the app shell (App.vue), which is not remounted by
 * in-app router navigation, so the choice persists as you move between routes. The theme
 * is stored in localStorage and re-applied before paint (inline script in index.html),
 * so it survives a full page reload too. New visitors (no stored choice) default to dark;
 * an explicit choice always wins. All three contracts are asserted below.
 */

const html = (page: Page) => page.locator('html')
const toggle = (page: Page) => page.getByRole('button', { name: /switch to (dark|light) mode/i })

test('defaults to dark for a new visitor', async ({ page }) => {
  // Fresh context = no stored preference, so the pre-paint script opts into dark.
  await page.goto('/')
  await expect(html(page)).toHaveClass(/dark/)
  await expect(page.getByRole('button', { name: /switch to light mode/i })).toBeVisible()
})

test('theme choice persists across in-app navigation', async ({ page }) => {
  await page.goto('/')
  await expect(html(page)).toHaveClass(/dark/)

  // Toggle to light and confirm it sticks across routes.
  await toggle(page).click()
  await expect(html(page)).not.toHaveClass(/dark/)

  await (await sectionLink(page, 'Events')).click()
  await expect(page).toHaveURL(/\/events$/)
  await expect(html(page)).not.toHaveClass(/dark/)

  await (await sectionLink(page, 'About')).click()
  await expect(page).toHaveURL(/\/about$/)
  await expect(html(page)).not.toHaveClass(/dark/)

  // Toggling back to dark also persists across navigation.
  await toggle(page).click()
  await expect(html(page)).toHaveClass(/dark/)

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
  await toggle(page).click()
  await expect(html(page)).not.toHaveClass(/dark/)

  await page.reload()

  // …and the stored light choice wins over the dark default after a reload.
  await expect(html(page)).not.toHaveClass(/dark/)
  await expect(page.getByRole('button', { name: /switch to dark mode/i })).toBeVisible()
})
