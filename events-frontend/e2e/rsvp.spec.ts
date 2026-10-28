import { expect, type Page, type Route, test } from '@playwright/test'
import { failUnmockedGets } from './unmocked'

/**
 * "Interested" and "Going" (#3892) with a mocked BFF: the toggle on an event page, the mark it puts
 * on the cards, and "My calendar".
 *
 * Endpoints: GET /api/auth/session · GET /api/auth/csrf · GET /api/events/:slug · GET /api/events/:slug/related
 * · GET /api/me/rsvps · PUT|DELETE /api/me/rsvps/:slug · GET /api/me/rsvps/upcoming · GET /api/me/rsvps/past
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
const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

const fallthrough = failUnmockedGets()

/** Answers the BFF for one signed-in visitor, and keeps the RSVP their presses set. */
async function mockBff(page: Page) {
  const writes: { method: string; path: string; body: unknown }[] = []
  let status: string | undefined
  await page
    .context()
    .addCookies([{ name: 'XSRF-TOKEN', value: 'csrf-1', url: 'http://localhost' }])
  await page.route(/\/api\/events\/mock-night\/related/, (route) => json(route, []))
  await page.route(/\/api\/events\/mock-night(\?|$)/, (route) => json(route, event))
  await page.route(/\/api\/(auth|me)(\/|$|\?)/, (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/session') return json(route, { account: me, signUpOpen: true })
    if (path === '/api/me/notifications/unread-count') return json(route, { count: 0 })
    if (path === '/api/auth/csrf') return route.fulfill({ status: 204 })
    if (path === '/api/me/rsvps/mock-night') {
      writes.push({ method: request.method(), path, body: request.postDataJSON() })
      status =
        request.method() === 'PUT'
          ? (request.postDataJSON() as { status: string }).status
          : undefined
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/me/rsvps') return json(route, status ? { 'mock-night': status } : {})
    if (path === '/api/me/rsvps/upcoming') {
      const content = status ? [event] : []
      return json(route, { ...emptyPage, content, totalElements: content.length, totalPages: 1 })
    }
    if (path === '/api/me/rsvps/past') return json(route, emptyPage)
    return fallthrough(route)
  })
  return writes
}

test('Going on an event page puts the event on My calendar, marked, and a second press withdraws it', async ({
  page,
}) => {
  const writes = await mockBff(page)
  await page.goto('/en/events/mock-night')

  const going = page.getByTestId('rsvp-going')
  await going.click()
  await expect(going).toHaveAttribute('aria-pressed', 'true')
  // The button turns at once; the write follows the CSRF call.
  await expect
    .poll(() => writes)
    .toContainEqual({
      method: 'PUT',
      path: '/api/me/rsvps/mock-night',
      body: { status: 'going' },
    })

  await page.goto('/en/my-calendar')
  await expect(page.getByRole('heading', { name: 'Coming up' })).toBeVisible()
  await expect(page.getByTestId('rsvp-badge')).toHaveText('You are going')

  await page.goto('/en/events/mock-night')
  await page.getByTestId('rsvp-going').click()
  await expect(page.getByTestId('rsvp-going')).toHaveAttribute('aria-pressed', 'false')
  await expect
    .poll(() => writes.at(-1))
    .toMatchObject({ method: 'DELETE', path: '/api/me/rsvps/mock-night' })
})
