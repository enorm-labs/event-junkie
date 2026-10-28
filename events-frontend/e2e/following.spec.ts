import AxeBuilder from '@axe-core/playwright'
import { expect, type Page, type Route, test } from '@playwright/test'
import { failUnmockedGets } from './unmocked'

/**
 * Following (#3889) with a mocked BFF: the Follow button on a venue page, signed out and signed in,
 * the header entry, and the Following page with its two tabs (#4080).
 *
 * Endpoints: GET /api/auth/session · GET /api/auth/csrf · GET /api/venues/:slug · GET /api/events?…
 * · GET|PUT|DELETE /api/me/follows/:kind/:slug · GET /api/me/follows · GET /api/me/following
 * · GET /api/me/following/today · GET /api/me/rsvps · GET /api/me · GET /api/me/passkeys
 * · GET /api/me/push
 * · GET|POST /api/me/searches · PATCH|DELETE /api/me/searches/:id
 */

const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }
const venue = { slug: 'mock-venue', name: 'Mock Venue', city: 'Berlin' }
const emptyPage = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

const fallthrough = failUnmockedGets()

/** Answers the BFF for one visitor, signed in or not, and records each write with its CSRF header. */
async function mockBff(
  page: Page,
  signedIn: boolean,
  feed: {
    today?: unknown[]
    /** The list, or a function of the request's query, for a filter (#4079). */
    upcoming?: unknown[] | ((query: URLSearchParams) => unknown[])
    following?: boolean
  } = {},
) {
  const writes: { method: string; path: string; csrf: string | null; body?: unknown }[] = []
  let following = feed.following ?? false
  let searches: { id: string; name: string; query: string }[] = []
  await page
    .context()
    .addCookies([{ name: 'XSRF-TOKEN', value: 'csrf-1', url: 'http://localhost' }])
  await page.route(/\/api\/events(\?|$)/, (route) => json(route, emptyPage))
  await page.route(/\/api\/venues\/mock-venue/, (route) => json(route, venue))
  await page.route(/\/api\/(auth|me)(\/|$|\?)/, (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') {
      writes.push({
        method: request.method(),
        path,
        csrf: request.headers()['x-xsrf-token'] ?? null,
        ...(path.startsWith('/api/me/searches') ? { body: request.postDataJSON() } : {}),
      })
    }
    if (path === '/api/auth/session') return json(route, { account: signedIn ? me : null })
    if (path === '/api/auth/csrf') return route.fulfill({ status: 204 })
    if (!signedIn) return json(route, { message: 'sign in' }, 401)
    if (path === '/api/me/follows/venue/mock-venue') {
      if (request.method() === 'PUT') following = true
      if (request.method() === 'DELETE') following = false
      return request.method() === 'GET'
        ? json(route, { following })
        : route.fulfill({ status: 204 })
    }
    if (path === '/api/me/follows') {
      return json(
        route,
        following ? [{ kind: 'venue', slug: 'mock-venue', name: 'Mock Venue', notify: 'off' }] : [],
      )
    }
    if (path === '/api/me/following/today') return json(route, feed.today ?? [])
    if (path === '/api/me/rsvps') return json(route, {})
    if (path === '/api/me/following') {
      const upcoming = feed.upcoming ?? []
      const content =
        typeof upcoming === 'function' ? upcoming(new URL(request.url()).searchParams) : upcoming
      return json(route, { ...emptyPage, content, totalElements: content.length, totalPages: 1 })
    }
    if (path === '/api/me/searches') {
      if (request.method() === 'POST') {
        const { name, query } = request.postDataJSON() as { name: string; query: string }
        searches = [{ id: 's1', name, query }]
        return json(route, searches[0])
      }
      return json(route, searches)
    }
    if (path === '/api/me/searches/s1' && request.method() === 'DELETE') {
      searches = []
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/me' && request.method() === 'GET') return json(route, me)
    if (path === '/api/me/passkeys') return json(route, [])
    if (path === '/api/me/push') return json(route, { publicKey: null })
    return fallthrough(route)
  })
  await page.route(/\/api\/genres/, (route) => json(route, []))
  await page.route(/\/api\/venues(\?|$)/, (route) =>
    json(route, { content: [venue], page: 0, size: 6, totalElements: 1, totalPages: 1 }),
  )
  return writes
}

test('signed out, Follow leads to sign-in, which keeps the page to return to', async ({ page }) => {
  await mockBff(page, false)
  await page.goto('/en/venues/mock-venue')

  await expect(page.getByRole('link', { name: 'Sign in', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Follow' }).click()

  await expect(page).toHaveURL(/\/en\/sign-in\?next=\/en\/venues\/mock-venue$/)
})

test('signed in, Follow follows with the CSRF header and then reads Following', async ({
  page,
}) => {
  const writes = await mockBff(page, true)
  await page.goto('/en/venues/mock-venue')

  await page.getByRole('button', { name: 'Follow', exact: true }).click()

  await expect(page.getByRole('button', { name: 'Following (press to unfollow)' })).toBeVisible()
  // The button turns at once; the write follows the CSRF call.
  await expect
    .poll(() => writes)
    .toEqual([{ method: 'PUT', path: '/api/me/follows/venue/mock-venue', csrf: 'csrf-1' }])
  await expect(page.getByRole('button', { name: 'Your account' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Shown on your Following page' })).toHaveAttribute(
    'href',
    '/en/following',
  )
})

test('the Following page suggests venues, and a follow from there joins the list', async ({
  page,
}) => {
  await mockBff(page, true)
  await page.goto('/en/following')

  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Following')
  await expect(page.getByText('You do not follow anything yet.')).toBeVisible()
  await page.getByRole('button', { name: 'Follow', exact: true }).click()

  await page.getByRole('tab', { name: 'You follow (1)' }).click()
  await expect(page.getByRole('heading', { name: 'Venues (1)' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Mock Venue' })).toHaveAttribute(
    'href',
    '/en/venues/mock-venue',
  )
})

test("the Following page shows today's events under Today, above Upcoming", async ({ page }) => {
  const day = (offset: number) =>
    new Date(Date.now() + offset * 86_400_000).toLocaleDateString('sv-SE', {
      timeZone: 'Europe/Berlin',
    })
  const event = (slug: string, title: string, eventDate: string) => ({
    slug,
    title,
    eventDate,
    venue: { slug: 'mock-venue', name: 'Mock Venue' },
  })
  await mockBff(page, true, {
    following: true,
    today: [event('tonight', 'Tonight at the venue', day(0))],
    upcoming: [event('later', 'Later at the venue', day(2))],
  })
  await page.goto('/en/following')

  const today = page.getByRole('heading', { name: 'Today' })
  const upcoming = page.getByRole('heading', { name: 'Upcoming events' })
  await expect(today).toBeVisible()
  await expect(upcoming).toBeVisible()
  await expect(page.getByText('Tonight at the venue').first()).toBeVisible()
  await expect(page.getByText('Later at the venue').first()).toBeVisible()
  const [todayBox, upcomingBox] = [await today.boundingBox(), await upcoming.boundingBox()]
  expect(todayBox!.y).toBeLessThan(upcomingBox!.y)
})

const day = (offset: number) =>
  new Date(Date.now() + offset * 86_400_000).toLocaleDateString('sv-SE', {
    timeZone: 'Europe/Berlin',
  })

/** Two followed events, one soon and one in a month; returns the queries the list was asked with. */
async function openWithTwoUpcoming(page: Page): Promise<URLSearchParams[]> {
  const event = (slug: string, title: string, eventDate: string) => ({
    slug,
    title,
    eventDate,
    venue: { slug: 'mock-venue', name: 'Mock Venue' },
  })
  const soon = event('soon', 'Soon at the venue', day(2))
  const later = event('later', 'Much later at the venue', day(30))
  const asked: URLSearchParams[] = []
  await mockBff(page, true, {
    following: true,
    upcoming: (query) => {
      asked.push(query)
      const to = query.get('to')
      return [soon, later].filter((e) => !to || e.eventDate <= to)
    },
  })
  await page.goto('/en/following')
  await expect(page.getByText('Much later at the venue').first()).toBeVisible()
  return asked
}

async function pickNextSevenDays(page: Page, asked: URLSearchParams[]) {
  // Upcoming starts tomorrow, so the bar leaves out Today, which would always empty it.
  await expect(page.getByRole('button', { name: 'Today', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Next 7 days' }).click()

  await expect(page).toHaveURL(new RegExp(`from=${day(0)}&to=${day(6)}`))
  await expect(page.getByText('Soon at the venue').first()).toBeVisible()
  await expect(page.getByText('Much later at the venue')).toHaveCount(0)
  // Under the bar, as on the events page.
  await expect(page.getByText('1 event found')).toBeVisible()
  // Upcoming still starts tomorrow: today has its own section.
  expect(asked.at(-1)?.get('from')).toBe(day(1))
}

test.describe('on a wide screen', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('the Following page narrows Upcoming by a preset behind the Filter button, with the range in the URL', async ({
    page,
  }) => {
    const asked = await openWithTwoUpcoming(page)
    const filter = page.getByRole('button', { name: 'Filter', exact: true })
    await expect(filter).toHaveAttribute('aria-expanded', 'false')
    await filter.click()

    await pickNextSevenDays(page, asked)
    await expect(page.getByRole('button', { name: 'Filter (1)' })).toBeVisible()
  })
})

test.describe('on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test("the Following page shows the bar at once, with the bar's Filters as its only filter button", async ({
    page,
  }) => {
    const asked = await openWithTwoUpcoming(page)
    await expect(page.getByRole('button', { name: 'Filter', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Filters', exact: true })).toBeVisible()

    await pickNextSevenDays(page, asked)
  })
})

test('a filter that empties Upcoming says so and clears back to the list', async ({ page }) => {
  await mockBff(page, true, {
    following: true,
    upcoming: (query) =>
      query.getAll('eventType').includes('PARTY')
        ? []
        : [
            {
              slug: 'band',
              title: 'Band at the venue',
              eventDate: '2099-01-01',
              venue: { slug: 'mock-venue', name: 'Mock Venue' },
            },
          ],
  })
  await page.goto('/en/following?eventType=PARTY')

  await expect(page.getByText('Nothing you follow matches these filters.')).toBeVisible()
  await expect(page.getByText('You do not follow anything yet.')).toHaveCount(0)
  await page.getByRole('button', { name: 'Clear all filters' }).click()

  await expect(page).toHaveURL(/\/en\/following$/)
  await expect(page.getByText('Band at the venue').first()).toBeVisible()
})

test('the Following page leaves Today out when nothing is on today', async ({ page }) => {
  await mockBff(page, true, { following: true })
  await page.goto('/en/following')

  await expect(page.getByRole('heading', { name: 'Upcoming events' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Today' })).toHaveCount(0)
})

test('one genre alone saves with a single press, and Following lists it with its count', async ({
  page,
}) => {
  const writes = await mockBff(page, true)
  const tonight = {
    slug: 'tonight',
    title: 'Tonight',
    eventDate: '2026-10-30',
    venue: { slug: 'mock-venue', name: 'Mock Venue' },
  }
  await page.route(/\/api\/events(\?|$)/, (route) =>
    json(route, { ...emptyPage, content: [tonight], totalElements: 7, totalPages: 1 }),
  )
  await page.goto('/en/events?genre=techno')

  await page.getByTestId('save-search').click()
  await expect(page.getByRole('status').filter({ hasText: 'Saved.' })).toBeVisible()
  expect(writes).toContainEqual({
    method: 'POST',
    path: '/api/me/searches',
    csrf: 'csrf-1',
    body: { name: 'techno', query: 'genre=techno' },
  })

  await page.getByRole('link', { name: 'See it on your Following page' }).click()
  await expect(page.getByRole('heading', { name: 'Saved searches (1)' })).toBeVisible()
  // The open tab's panel: the hidden Events tab names the count too, on its chip.
  await expect(page.getByRole('tabpanel').getByText('Saved search · 7 upcoming')).toBeVisible()
  await expect(page.getByRole('link', { name: 'techno', exact: true })).toHaveAttribute(
    'href',
    '/en/events?genre=techno',
  )

  // The Events tab runs it from a chip beside the filters (#4099).
  await page.getByRole('tab', { name: 'Events' }).click()
  await expect(
    page.getByRole('list', { name: 'Your saved searches' }).getByRole('link'),
  ).toHaveAttribute('href', '/en/events?genre=techno')

  await page.getByRole('tab', { name: 'You follow (1)' }).click()
  await page.getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByRole('heading', { name: 'Saved searches (1)' })).toHaveCount(0)
})

test('signed out, Save search leads to sign-in', async ({ page }) => {
  await mockBff(page, false)
  await page.route(/\/api\/events(\?|$)/, (route) =>
    json(route, {
      ...emptyPage,
      content: [{ slug: 'x', title: 'X', eventDate: '2026-10-30' }],
      totalElements: 1,
      totalPages: 1,
    }),
  )
  await page.goto('/en/events?genre=techno&free=true')

  await page.getByRole('link', { name: 'Save search' }).click()
  await expect(page).toHaveURL(/\/en\/sign-in\?next=\/en\/events$/)
})

test('signed in, the header icon opens the account menu, and sign-out leaves the account page', async ({
  page,
}) => {
  const writes = await mockBff(page, true)
  await page.goto('/en/following')

  await page.getByRole('button', { name: 'Your account' }).click()
  await expect(page.getByText('Signed in as ada@example.com')).toBeVisible()
  await page.getByRole('dialog').getByRole('link', { name: 'Your account' }).click()
  await expect(page).toHaveURL(/\/en\/account$/)

  await page.getByRole('button', { name: 'Your account' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Sign out' }).click()

  await expect(page).toHaveURL(/\/en$/)
  await expect(page.getByRole('link', { name: 'Sign in', exact: true })).toBeVisible()
  await expect
    .poll(() => writes)
    .toContainEqual({ method: 'POST', path: '/api/me/sign-out', csrf: 'csrf-1' })
})

test('the Following page passes axe', async ({ page }) => {
  await mockBff(page, true)
  await page.goto('/en/following')
  await expect(page.getByRole('heading', { name: 'Venues with the most events' })).toBeVisible()

  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .exclude('#__vue-devtools-container__')
    .analyze()
  expect(results.violations).toEqual([])
})

test('the Following page switches between its tabs, with the tab in the URL, and keeps an unfollowed row', async ({
  page,
}) => {
  const writes = await mockBff(page, true, { following: true })
  await page.goto('/en/following')

  const events = page.getByRole('tab', { name: 'Events' })
  const follows = page.getByRole('tab', { name: 'You follow (1)' })
  await expect(events).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('heading', { name: 'Upcoming events' })).toBeVisible()

  await follows.click()
  await expect(page).toHaveURL(/\/en\/following\?tab=follows$/)
  await expect(page.getByRole('tab', { name: 'You follow (1)' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByRole('heading', { name: 'Upcoming events' })).toBeHidden()
  // By id: the panel's name is its tab's, and the count in it drops with the unfollow.
  const panel = page.locator('#following-panel-follows')
  await panel.getByRole('button', { name: 'Following (press to unfollow)' }).click()
  await expect(panel.getByRole('button', { name: 'Follow', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Mock Venue' })).toBeVisible()
  await expect
    .poll(() => writes)
    .toContainEqual({ method: 'DELETE', path: '/api/me/follows/venue/mock-venue', csrf: 'csrf-1' })

  await page.getByRole('tab', { name: 'Events' }).press('Enter')
  await expect(page).toHaveURL(/\/en\/following$/)
  await page.getByRole('tab', { name: 'Events' }).press('ArrowRight')
  await expect(page).toHaveURL(/\/en\/following\?tab=follows$/)
  await expect(page.getByRole('tab', { name: 'You follow (0)' })).toBeFocused()
  await expect(
    page.getByText('You do not follow an artist, a venue or a promoter yet.'),
  ).toBeVisible()
})

test('the account page opens the Following page on the tab of what you follow', async ({
  page,
}) => {
  await mockBff(page, true, { following: true })
  await page.goto('/en/account')

  await page.getByRole('link', { name: 'What you follow' }).click()
  await expect(page).toHaveURL(/\/en\/following\?tab=follows$/)
  await expect(page.getByRole('tab', { name: 'You follow (1)' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
})

test('the Following page passes axe on the tab of what you follow', async ({ page }) => {
  await mockBff(page, true, { following: true })
  await page.goto('/en/following?tab=follows')
  await expect(page.getByRole('heading', { name: 'Venues (1)' })).toBeVisible()

  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .exclude('#__vue-devtools-container__')
    .analyze()
  expect(results.violations).toEqual([])
})
