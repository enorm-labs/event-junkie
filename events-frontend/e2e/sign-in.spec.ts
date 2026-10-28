import AxeBuilder from '@axe-core/playwright'
import { expect, type Page, type Route, test } from '@playwright/test'
import { failUnmockedGets } from './unmocked'

/**
 * Sign-in by e-mail link and the account page (#3887) with a mocked BFF: the address goes only into
 * a POST body, the token leaves the address bar, and every write carries the CSRF header.
 *
 * Endpoints: GET /api/auth/csrf · GET /api/auth/session · POST /api/auth/link · POST /api/auth/sign-in
 * · GET /api/me · DELETE /api/me, and the GETs of the account and Following pages
 */

const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

const fallthrough = failUnmockedGets()

/** Answers the account endpoints, and records each write with the CSRF header it carried. */
async function mockAccount(page: Page): Promise<{ path: string; csrf: string | null; body: string | null }[]> {
  const writes: { path: string; csrf: string | null; body: string | null }[] = []
  await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'csrf-1', url: 'http://localhost' }])
  await page.route(/\/api\/(auth|me)(\/|$|\?)/, (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') {
      writes.push({ path, csrf: request.headers()['x-xsrf-token'] ?? null, body: request.postData() })
    }
    if (path === '/api/auth/csrf') return route.fulfill({ status: 204 })
    if (path === '/api/auth/session') return json(route, { account: null })
    if (path === '/api/me/notifications/unread-count') return json(route, { count: 0 })
    if (path === '/api/auth/link') return route.fulfill({ status: 202 })
    if (path === '/api/auth/sign-in') return json(route, me)
    if (path === '/api/me' && request.method() === 'GET') return json(route, me)
    if (path === '/api/me/following') return json(route, { content: [], totalPages: 0 })
    if (path === '/api/me/following/today') return json(route, [])
    if (path === '/api/me/follows') return json(route, [])
    if (path === '/api/me/passkeys') return json(route, [])
    if (path === '/api/me/push') return json(route, { publicKey: null })
    if (path === '/api/me/searches') return json(route, [])
    return fallthrough(route)
  })
  return writes
}

test('a link request sends the address in the body only, then says to check the inbox', async ({ page }) => {
  const writes = await mockAccount(page)
  await page.goto('/en/sign-in')

  await page.getByLabel('E-mail address').fill('ada@example.com')
  await page.getByRole('button', { name: 'Send link' }).click()

  await expect(page).toHaveURL(/\/en\/sign-in\/check-mail$/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Check your inbox')
  // Chromium offers passkeys in the autofill, so the page also asks for a challenge (#3888).
  expect(writes.filter((w) => w.path !== '/api/auth/passkey/options')).toEqual([
    { path: '/api/auth/link', csrf: 'csrf-1', body: JSON.stringify({ email: 'ada@example.com', locale: 'en' }) },
  ])
  expect(page.url()).not.toContain('ada')
})

test('the link from the mail signs in on the click, not on the load', async ({ page }) => {
  const writes = await mockAccount(page)
  await page.goto('/en/sign-in/confirm#token=abc_-123')

  await expect(page).toHaveURL(/\/en\/sign-in\/confirm$/)
  expect(writes).toEqual([])

  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/en\/following$/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Following')
  expect(writes).toEqual([{ path: '/api/auth/sign-in', csrf: 'csrf-1', body: JSON.stringify({ token: 'abc_-123' }) }])
})

test('the account is deleted only after the confirmation', async ({ page }) => {
  const writes = await mockAccount(page)
  await page.goto('/de/account')

  await page.getByRole('button', { name: 'Konto löschen' }).click()
  const dialog = page.getByRole('alertdialog', { name: 'Konto löschen?' })
  await expect(dialog).toBeVisible()
  expect(writes).toEqual([])

  await dialog.getByRole('button', { name: 'Endgültig löschen' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Dein Konto ist gelöscht.' })).toBeVisible()
  expect(writes).toEqual([{ path: '/api/me', csrf: 'csrf-1', body: null }])
})

test('the privacy link on the sign-in page opens the account section', async ({ page }) => {
  await mockAccount(page)
  await page.goto('/de/sign-in')
  await page.getByRole('link', { name: 'Mehr dazu in der Datenschutzerklärung' }).click()

  await expect(page).toHaveURL(/\/de\/legal\/privacy#account$/)
  await expect(page.getByRole('heading', { name: '6. Dein Konto' })).toBeInViewport()
})

test('the account pages tell crawlers to stay away', async ({ page }) => {
  await mockAccount(page)
  for (const path of ['/en/sign-in', '/de/account']) {
    await page.goto(path)
    await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', /noindex/)
  }
})

// a11y.spec.ts sweeps these pages without a BFF, which shows the account's error state only.
test('the signed-in account page and its delete dialog have no detectable accessibility violations', async ({ page }) => {
  await mockAccount(page)
  await page.goto('/en/account')
  await expect(page.getByText('Signed in as ada@example.com')).toBeVisible()
  // The same scan as a11y.spec.ts, the dev server's devtools panel excluded.
  const scan = async () => {
    await page.waitForFunction(() => document.getAnimations().length === 0)
    return new AxeBuilder({ page })
      .exclude('#__vue-devtools-container__')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
  }
  expect((await scan()).violations).toEqual([])

  await page.getByRole('button', { name: 'Delete account' }).click()
  await expect(page.getByRole('alertdialog')).toBeVisible()
  // The pointer would otherwise rest on the trigger, whose dark hover shade measures 4.37:1 in WebKit.
  await page.mouse.move(0, 0)
  expect((await scan()).violations).toEqual([])
})
