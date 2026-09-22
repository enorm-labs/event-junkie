import { expect, test } from '@playwright/test'

/**
 * The bundle, served by nginx, through Traefik, from the image the chart runs (#1699). Every other
 * suite here mocks the BFF and starts a dev server, so none of them can fail on a rewritten asset
 * path, a missing SPA fallback or a middleware that rejects the request. These are the failures that
 * otherwise reach staging first and a person second.
 *
 * Nothing in this directory may call `page.route`: the deployment is the thing under test.
 */

/** Every request the page made that came back 4xx or 5xx, which for assets means nginx never had it. */
function collectFailures(page: import('@playwright/test').Page): string[] {
  const failures: string[] = []
  page.on('response', (response) => {
    if (response.status() >= 400) failures.push(`${response.status()} ${response.url()}`)
  })
  return failures
}

test('the site root is served as HTML with its bundle', async ({ page }) => {
  const failures = collectFailures(page)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))

  const response = await page.goto('/')
  expect(response?.status()).toBe(200)
  expect(response?.headers()['content-type']).toContain('text/html')

  // The router mounts and the shell renders, which no static check of the image can say.
  await expect(page.getByRole('banner')).toBeVisible()
  await expect(page).toHaveURL(/\/(en|de)$/)

  expect(failures, 'assets the deployment did not serve').toEqual([])
  expect(errors, 'uncaught exceptions').toEqual([])
})

test('a deep link is answered by the SPA fallback, not by a 404', async ({ page }) => {
  // Typed into the address bar rather than reached by a click: nginx has to answer a path that is
  // not a file with index.html, and the ingress has to route it. A dev server does this for free.
  const response = await page.goto('/en/events')
  expect(response?.status()).toBe(200)
  await expect(page.getByRole('heading', { level: 1, name: 'Events' })).toBeVisible()
})

test('the API answers on the same origin as the site', async ({ request }) => {
  // One Ingress, two paths (#846's `connect-src 'self'` depends on it). A separate origin would need
  // CORS and would break the policy the next spec asserts.
  const response = await request.get('/api/meta')
  expect(response.status()).toBe(200)
  expect(response.headers()['content-type']).toContain('application/json')
})
