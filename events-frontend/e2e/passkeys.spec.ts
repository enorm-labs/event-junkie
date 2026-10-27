import { expect, type Page, type Route, test } from '@playwright/test'
import { failUnmockedGets } from './unmocked'

/**
 * Passkeys (#3888) in a real browser, with a mocked BFF and Chromium's virtual authenticator: the account page
 * makes a passkey and posts it as `PublicKeyCredential.toJSON()` would, and the sign-in page signs in with it.
 * The BFF's verification is `PasskeyFlowTest`'s job; this spec covers the browser half.
 *
 * Endpoints: POST /api/me/passkeys/options · POST /api/me/passkeys · GET /api/me/passkeys
 * · POST /api/auth/passkey/options · POST /api/auth/passkey/sign-in, and the GETs of the account and Following pages
 */

// eslint-disable-next-line playwright/no-skipped-test
test.skip(({ browserName }) => browserName !== 'chromium', 'the virtual authenticator is a Chromium DevTools domain')

const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

const fallthrough = failUnmockedGets()

const b64 = (text: string) => Buffer.from(text).toString('base64url')

/** A platform authenticator with user verification, as a phone or a laptop has. */
async function authenticator(page: Page): Promise<void> {
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('WebAuthn.enable')
  await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  })
}

/** Answers the account and passkey endpoints, and records each write's path and body. */
async function mockBff(page: Page, signedIn: boolean): Promise<{ path: string; body: Record<string, unknown> }[]> {
  const writes: { path: string; body: Record<string, unknown> }[] = []
  const stored: unknown[] = []
  await page.context().addCookies([{ name: 'XSRF-TOKEN', value: 'csrf-1', url: 'http://localhost' }])
  await page.route(/\/api\/(auth|me)(\/|$|\?)/, (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET' && request.postData()) writes.push({ path, body: request.postDataJSON() })
    if (path === '/api/auth/csrf') return route.fulfill({ status: 204 })
    if (path === '/api/auth/session') return json(route, { account: signedIn ? me : null, signUpOpen: true })
    if (path === '/api/me' && request.method() === 'GET') return json(route, me)
    if (path === '/api/me/passkeys/options') {
      return json(route, {
        rp: { id: 'localhost', name: 'Event Junkie' },
        user: { id: b64('handle-1'), name: me.email, displayName: me.email },
        challenge: b64('challenge-register'),
        excludeCredentials: [],
        pubKeyCredParams: [{ alg: -7, type: 'public-key' }],
        timeout: 300000,
        authenticatorSelection: { residentKey: 'required', requireResidentKey: true, userVerification: 'required' },
        attestation: 'none',
      })
    }
    if (path === '/api/me/passkeys' && request.method() === 'POST') {
      stored.push({ id: 'p1', name: 'Phone', createdAt: '2026-10-27T12:00:00Z' })
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/me/passkeys') return json(route, stored)
    if (path === '/api/auth/passkey/options') {
      return json(route, { challenge: b64('challenge-sign-in'), rpId: 'localhost', timeout: 300000, userVerification: 'required' })
    }
    if (path === '/api/auth/passkey/sign-in') return json(route, me)
    if (path === '/api/me/following') return json(route, { content: [], totalPages: 0 })
    if (path === '/api/me/following/today') return json(route, [])
    if (path === '/api/me/follows') return json(route, [])
    if (path === '/api/me/push') return json(route, { publicKey: null })
    if (path === '/api/me/searches') return json(route, [])
    return fallthrough(route)
  })
  return writes
}

test('the account page makes a passkey and posts it whole', async ({ page }) => {
  await authenticator(page)
  const writes = await mockBff(page, true)
  await page.goto('/en/account')

  await page.getByRole('button', { name: 'Add a passkey' }).click()
  await expect(page.getByText('Phone')).toBeVisible()

  const registration = writes.find((w) => w.path === '/api/me/passkeys')
  expect(registration).toBeDefined()
  const credential = registration!.body.credential as { id: string; type: string; response: Record<string, unknown> }
  expect(credential.type).toBe('public-key')
  expect(credential.id).toMatch(/^[A-Za-z0-9_-]+$/)
  expect(credential.response.attestationObject).toEqual(expect.stringMatching(/^[A-Za-z0-9_-]+$/))
  expect(credential.response.clientDataJSON).toEqual(expect.any(String))
  const clientData = JSON.parse(Buffer.from(credential.response.clientDataJSON as string, 'base64url').toString())
  expect(clientData).toMatchObject({ type: 'webauthn.create', challenge: b64('challenge-register') })
})

/** Adds a passkey on the account page, then answers the sign-in page as a signed-out browser. */
async function passkeyThenSignedOut(page: Page) {
  await authenticator(page)
  await mockBff(page, true)
  await page.goto('/en/account')
  await page.getByRole('button', { name: 'Add a passkey' }).click()
  await expect(page.getByText('Phone')).toBeVisible()
  return mockBff(page, false)
}

function expectSignIn(writes: { path: string; body: Record<string, unknown> }[]) {
  const signIn = writes.find((w) => w.path === '/api/auth/passkey/sign-in')
  const response = (signIn!.body.credential as { response: Record<string, unknown> }).response
  expect(response.signature).toEqual(expect.stringMatching(/^[A-Za-z0-9_-]+$/))
  expect(response.userHandle).toBe(b64('handle-1'))
  const clientData = JSON.parse(Buffer.from(response.clientDataJSON as string, 'base64url').toString())
  expect(clientData).toMatchObject({ type: 'webauthn.get', challenge: b64('challenge-sign-in') })
}

test('the sign-in button signs in with the passkey and goes on to Following', async ({ page }) => {
  // Without the autofill, which the virtual authenticator would answer before the click.
  await page.addInitScript(() => {
    PublicKeyCredential.isConditionalMediationAvailable = async () => false
  })
  const writes = await passkeyThenSignedOut(page)
  await page.goto('/en/sign-in')
  await page.getByRole('button', { name: 'Sign in with a passkey' }).click()

  await expect(page).toHaveURL(/\/en\/following$/)
  expectSignIn(writes)
})

test('the autofill in the address field signs in with the passkey', async ({ page }) => {
  const writes = await passkeyThenSignedOut(page)
  await page.goto('/en/sign-in')

  // The virtual authenticator picks the passkey the autofill offers, as a visitor's tap would.
  await expect(page).toHaveURL(/\/en\/following$/)
  expectSignIn(writes)
})
