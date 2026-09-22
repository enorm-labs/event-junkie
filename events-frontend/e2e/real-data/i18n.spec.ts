import { expect, test } from '@playwright/test'

/**
 * The locale contract behind a real ingress (ADR-013). `e2e/i18n.spec.ts` covers the same rules
 * against a dev server, where the redirect is Vite's and every response is the bundle. Here nginx
 * serves the fallback and Traefik's middlewares are in the path, which is where a redirect loop or a
 * dropped prefix would appear.
 *
 * The one suite in this directory that is not pinned to `/en`, per the locale strategy.
 */

test('an unprefixed path is redirected to a locale', async ({ page }) => {
  await page.goto('/')
  await expect(page).toHaveURL(/\/(en|de)$/)

  await page.goto('/events')
  await expect(page).toHaveURL(/\/(en|de)\/events$/)
})

test('German is served under its own prefix, with the real rows in it', async ({ page }) => {
  await page.goto('/de/events')

  // A translated landmark name: the shell is rendering in German, not just the URL saying so.
  // The heading is "Events" in both languages, so it says nothing here; the nav label does.
  await expect(page.getByRole('navigation', { name: 'Hauptnavigation' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Veranstalter' })).toBeVisible()
})

test('the two locales are alternates of each other', async ({ page }) => {
  // The hreflang pair is injected per page (ADR-014, #287), so it exists only where the sidecar ran.
  await page.goto('/en/events')

  await expect(page.locator('link[rel="alternate"][hreflang="de"]')).toHaveAttribute('href', /\/de\/events$/)
  await expect(page.locator('link[rel="alternate"][hreflang="en"]')).toHaveAttribute('href', /\/en\/events$/)
})
