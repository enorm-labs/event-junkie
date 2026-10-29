import { readFile } from 'node:fs/promises'

import { type BrowserContext, expect, test } from '@playwright/test'

/**
 * The seeded accounts of `fixtures/accounts.sql` (#4102), signed in through the real BFF, nginx and
 * Traefik. The mocked `following` and `notifications` specs prove the SPA; this proves the session
 * cookie, the CSRF pair and the rows survive the round trip.
 *
 * The cookie is set for `https://`, because `__Host-` needs Secure. Chromium counts `*.localhost` as
 * a secure origin, so it still sends it to the plain `http://…:8080` k3d serves.
 */

interface FixtureAccount {
  email: string
  session: string
}

const accounts: FixtureAccount[] = JSON.parse(
  await readFile(new URL('../../../fixtures/accounts.json', import.meta.url), 'utf8'),
).accounts
const ada = accounts.find((a) => a.email === 'ada@example.de')!
const bob = accounts.find((a) => a.email === 'bob@example.de')!

async function signIn(context: BrowserContext, account: FixtureAccount, baseURL: string) {
  await context.addCookies([
    {
      name: '__Host-ej_session',
      value: account.session,
      url: `https://${new URL(baseURL).hostname}/`,
      httpOnly: true,
      secure: true,
      sameSite: 'Lax',
    },
  ])
}

test.describe('Ada', () => {
  test.beforeEach(async ({ context, request, baseURL }) => {
    await signIn(context, ada, baseURL!)
    // A retry starts unfollowed. `request` has its own empty cookie jar, so the header is the only
    // cookie it sends; any XSRF pair passes, since the BFF compares cookie and header only.
    await request.delete('/api/me/follows/venue/jazzkeller-kreuzberg', {
      headers: {
        'X-XSRF-TOKEN': 'e2e',
        Cookie: `__Host-ej_session=${ada.session}; XSRF-TOKEN=e2e`,
      },
    })
  })

  test('follows a venue, finds it on Following, and unfollows it', async ({ page }) => {
    await page.goto('/en/venues/jazzkeller-kreuzberg')
    await page.getByRole('button', { name: 'Follow', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Following (press to unfollow)' })).toBeVisible()

    await page.goto('/en/following')
    // Kesselhaus Nord, Møbius Trio and Nachtschicht Kollektiv are seeded; Jazzkeller is the fourth.
    await page.getByRole('tab', { name: 'You follow (4)' }).click()
    await expect(page.getByRole('heading', { name: 'Venues (2)' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Jazzkeller $& Kreuzberg' })).toHaveAttribute(
      'href',
      '/en/venues/jazzkeller-kreuzberg',
    )

    await page.goto('/en/venues/jazzkeller-kreuzberg')
    await page.getByRole('button', { name: 'Following (press to unfollow)' }).click()
    await expect(page.getByRole('button', { name: 'Follow', exact: true })).toBeVisible()
  })

  test('her account answers carry private, no-store too', async ({ page }) => {
    const answer = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/me/follows')
    await page.goto('/en/following')
    const response = await answer
    expect(response.status()).toBe(200)
    expect(response.headers()['cache-control']).toBe('private, no-store')
  })
})

test.describe('Bob', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signIn(context, bob, baseURL!)
  })

  // Read only: opening the entry would mark it read, and a retry would find the badge gone.
  test('the bell holds the cancellation of the event he is going to', async ({ page }) => {
    await page.goto('/en/about')
    await page.getByRole('button', { name: 'Notifications, 1 unread' }).click()
    await expect(page.getByRole('link', { name: /Cancelled/ })).toContainText('Anna Kessel')
  })
})
