import { expect, test } from '@playwright/test'

/**
 * The response headers Traefik's middlewares add (#846, #286). They exist only in a deployment: the
 * preview server `CI=1` starts copies the CSP from `scripts/csp.ts` and nothing else, so a middleware
 * that stopped rendering would pass every other suite here.
 *
 * k3d runs `reportOnly: false` and `noindex: true`, the same values the DAST scan needs (#1421).
 */

test('the enforcing CSP is served, and not the report-only one', async ({ page }) => {
  const response = await page.goto('/')
  const headers = response!.headers()

  // Report-only collects violations and blocks nothing. Shipping it by accident is the failure this
  // asserts against, and the two headers are one word apart.
  expect(headers['content-security-policy-report-only']).toBeUndefined()
  const policy = headers['content-security-policy']
  expect(policy).toBeDefined()

  // The directives that decide whether the bundle runs at all.
  expect(policy).toContain("default-src 'self'")
  expect(policy).toContain("object-src 'none'")
  expect(policy).toContain("frame-ancestors 'none'")
  // The inline theme script is allowed by hash, never by `unsafe-inline` (#846).
  expect(policy).toMatch(/script-src 'self' 'sha256-[A-Za-z0-9+/=]+'/)
  expect(policy).not.toContain('unsafe-inline')
})

test('the other security headers are on the response', async ({ page }) => {
  const headers = (await page.goto('/'))!.headers()

  expect(headers['x-frame-options']).toBe('DENY')
  expect(headers['x-content-type-options']).toBe('nosniff')
  expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin')
  expect(headers['permissions-policy']).toContain('geolocation=()')
})

test('this environment tells crawlers to stay away, by header and by robots.txt', async ({ page, request }) => {
  // k3d stands in for every non-production environment here: `ingress.noindex` renders the header,
  // the disallow-all robots.txt and an empty sitemap, and all three have to agree (#286).
  const headers = (await page.goto('/'))!.headers()
  expect(headers['x-robots-tag']).toContain('noindex')

  const robots = await request.get('/robots.txt')
  expect(robots.status()).toBe(200)
  expect(await robots.text()).toContain('Disallow: /')
})
