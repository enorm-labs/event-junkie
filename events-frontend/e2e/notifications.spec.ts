import { expect, type Page, type Route, test } from '@playwright/test'
import { failUnmockedGets } from './unmocked'

/**
 * The header's bell (#4078) with a mocked BFF: the badge, the menu by keyboard, an opened entry
 * turning read, "Mark all as read", and where display settings go on a phone when the bell needs
 * their place.
 *
 * Endpoints: GET /api/auth/session · GET /api/auth/csrf · GET /api/events/:slug · GET /api/events/:slug/related
 * · GET /api/me/notifications · GET /api/me/notifications/unread-count · POST /api/me/notifications/:id/read
 * · POST /api/me/notifications/read · GET /api/me/rsvps. The list pages by `limit`, `before` and `unread`.
 */

const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }
const day = (offset: number) =>
  new Date(Date.now() + offset * 86_400_000).toLocaleDateString('sv-SE', {
    timeZone: 'Europe/Berlin',
  })
const event = {
  slug: 'mock-night',
  title: 'Mock Night',
  eventDate: day(3),
  startTime: '22:00',
  eventType: 'PARTY',
  venue: { slug: 'mock-venue', name: 'Mock Venue', city: 'Berlin' },
}
const entry = (id: string, slug: string, aboutName: string) => ({
  id,
  kind: 'new_date',
  event: slug,
  title: `${aboutName} night`,
  date: day(7),
  aboutKind: 'venue',
  aboutName,
  createdAt: '2026-10-28T10:00:00Z',
  readAt: null as string | null,
})

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

const fallthrough = failUnmockedGets()

/** Answers the BFF for one visitor, signed in with two unread notifications, or signed out. */
async function mockBff(page: Page, signedIn = true, extra: ReturnType<typeof entry>[] = []) {
  const writes: string[] = []
  const entries = [
    entry('00000000-0000-4000-8000-000000000001', 'mock-night', 'Mock Venue'),
    entry('00000000-0000-4000-8000-000000000002', 'other-night', 'Other Club'),
    ...extra,
  ]
  await page
    .context()
    .addCookies([{ name: 'XSRF-TOKEN', value: 'csrf-1', url: 'http://localhost' }])
  await page.route(/\/api\/events\/mock-night\/related/, (route) => json(route, []))
  await page.route(/\/api\/events\/mock-night(\?|$)/, (route) => json(route, event))
  await page.route(/\/api\/(auth|me)(\/|$|\?)/, (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/session') return json(route, { account: signedIn ? me : null })
    if (path === '/api/auth/csrf') return route.fulfill({ status: 204 })
    if (path === '/api/me/notifications/unread-count') {
      return json(route, { count: entries.filter((e) => !e.readAt).length })
    }
    if (path === '/api/me/notifications') {
      // The BFF's keyset cursor stands in as an index: the page only hands it back.
      const query = new URL(request.url()).searchParams
      const listed = query.get('unread') === 'true' ? entries.filter((e) => !e.readAt) : entries
      const from = Number(query.get('before') ?? 0)
      const limit = Number(query.get('limit') ?? 20)
      const next = from + limit < listed.length ? String(from + limit) : null
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: next ? { 'Next-Cursor': next } : {},
        body: JSON.stringify(listed.slice(from, from + limit)),
      })
    }
    if (request.method() === 'POST' && path.startsWith('/api/me/notifications/')) {
      writes.push(path)
      const id = path.split('/')[4]
      entries.forEach((e) => {
        if (path.endsWith('/notifications/read') || e.id === id)
          e.readAt ??= new Date().toISOString()
      })
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/me/rsvps') return json(route, {})
    return fallthrough(route)
  })
  return writes
}

test('the bell counts the unread, and an entry opened by keyboard turns read', async ({ page }) => {
  const writes = await mockBff(page)
  await page.goto('/en/about')

  const bell = page.getByRole('button', { name: 'Notifications, 2 unread' })
  await expect(bell).toBeVisible()
  await expect(page.getByTestId('notification-badge')).toHaveText('2')

  await bell.focus()
  await page.keyboard.press('Enter')
  const first = page.getByRole('link', { name: /New date at Mock Venue/ })
  await expect(first).toContainText('Unread')
  await first.focus()
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL(/\/en\/events\/mock-night$/)
  await expect(page.getByRole('button', { name: 'Notifications, 1 unread' })).toBeVisible()
  expect(writes).toEqual(['/api/me/notifications/00000000-0000-4000-8000-000000000001/read'])
})

test('Mark all as read clears the badge', async ({ page }) => {
  const writes = await mockBff(page)
  await page.goto('/en/about')

  await page.getByRole('button', { name: 'Notifications, 2 unread' }).click()
  await page.getByRole('button', { name: 'Mark all as read' }).click()

  await expect(page.getByTestId('notification-badge')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Notifications', exact: true })).toBeVisible()
  expect(writes).toEqual(['/api/me/notifications/read'])
})

test('a change to an event marked as going names what changed', async ({ page }) => {
  const change = {
    ...entry('00000000-0000-4000-8000-000000000003', 'mock-night', 'Mock Night'),
    kind: 'change',
    aboutKind: 'rsvp',
    change: 'cancelled',
  }
  await mockBff(page, true, [change])
  await page.goto('/en/about')

  await page.getByRole('button', { name: 'Notifications, 3 unread' }).click()
  await expect(page.getByRole('link', { name: /Cancelled/ })).toHaveAttribute('href', '/en/events/mock-night')
})

test('Show all opens the page, Show more loads the next 50, and Unread keeps to the unread ones', async ({
  page,
}) => {
  const older = Array.from({ length: 58 }, (_, i) => ({
    ...entry(`00000000-0000-4000-8000-${String(100 + i).padStart(12, '0')}`, `night-${i}`, `Club ${i}`),
    createdAt: '2026-09-01T10:00:00Z',
    readAt: i % 2 === 0 ? '2026-09-02T10:00:00Z' : null,
  }))
  await mockBff(page, true, older)
  await page.goto('/en/about')

  await page.getByRole('button', { name: /^Notifications/ }).click()
  await page.getByRole('link', { name: 'Show all' }).click()

  await expect(page).toHaveURL(/\/en\/notifications$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Notifications' })).toBeVisible()
  await expect(page.getByTestId('notification')).toHaveCount(50)
  await page.getByRole('button', { name: 'Show more' }).click()
  await expect(page.getByTestId('notification')).toHaveCount(60)
  await expect(page.getByRole('button', { name: 'Show more' })).toHaveCount(0)

  await page.getByRole('button', { name: 'Unread', exact: true }).click()
  await expect(page).toHaveURL(/\/en\/notifications\?unread=1$/)
  await expect(page.getByTestId('notification')).toHaveCount(31)
  await page.reload()
  await expect(page.getByRole('button', { name: 'Unread', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByTestId('notification')).toHaveCount(31)
})

test('signed out, the page sends the visitor to sign-in', async ({ page }) => {
  await mockBff(page, false)
  await page.goto('/en/notifications')

  await expect(page).toHaveURL(/\/en\/sign-in\?next=/)
})

test.describe('on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('signed in, the bell takes the settings button place, and the settings are in the menu', async ({
    page,
  }) => {
    await mockBff(page)
    await page.goto('/en/about')

    await expect(page.getByRole('button', { name: 'Notifications, 2 unread' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Display settings' })).toBeHidden()

    await page.getByRole('button', { name: 'Menu' }).click()
    await expect(page.getByRole('group', { name: 'Theme' })).toBeVisible()
    await page.getByRole('group', { name: 'Theme' }).getByRole('button', { name: 'Dark' }).click()
    await expect(page.locator('html')).toHaveClass(/dark/)
  })

  test('signed out, the header keeps the settings button and has no bell', async ({ page }) => {
    await mockBff(page, false)
    await page.goto('/en/about')

    await expect(page.getByRole('button', { name: 'Display settings' })).toBeVisible()
    await expect(page.getByTestId('notification-bell')).toHaveCount(0)
  })
})
