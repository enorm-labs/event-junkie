import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * The header search (#2514) with a mocked BFF: the palette, its keyboard path, and the results page.
 *
 * Endpoint: GET /api/search?q=&limit= → SearchResponse, one group per kind.
 */

function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

const answer = {
  events: {
    items: [{ slug: 'klubnacht', title: 'Klubnacht', eventDate: '2026-10-10', venue: { slug: 'berghain', name: 'Berghain' } }],
    total: 1,
  },
  venues: { items: [{ slug: 'berghain', name: 'Berghain', district: 'friedrichshain', upcomingEventCount: 26 }], total: 1 },
  artists: { items: [{ slug: 'ben-klock', name: 'Ben Klock' }], total: 1 },
  promoters: { items: [], total: 0 },
}

/** Answers the search, and records each term it was asked for. */
async function mockSearch(page: Page): Promise<string[]> {
  const terms: string[] = []
  await page.route(/\/api\/search(\?|$)/, (route) => {
    terms.push(new URL(route.request().url()).searchParams.get('q') ?? '')
    return json(route, answer)
  })
  // The pages the search leads to; their content is not under test here.
  await page.route(/\/api\/(venues|events|artists|promoters|genres)/, (route) => json(route, {}, 404))
  return terms
}

const label = 'Search events, venues, artists and promoters'

test('a venue picked from the header search opens its page', async ({ page }) => {
  const terms = await mockSearch(page)
  await page.goto('/en/events')

  await page.getByRole('button', { name: label }).click()
  await page.getByRole('dialog').getByRole('textbox', { name: label }).pressSequentially('berghain')
  await expect(page.getByRole('group', { name: 'Venues' }).getByRole('option')).toHaveCount(1)
  await expect(page.getByRole('group', { name: 'Artists' })).toContainText('Ben Klock')
  await expect(page.getByRole('group', { name: 'Promoters' })).toHaveCount(0)
  // Debounced: one request for the whole word, not one per letter.
  expect(terms).toEqual(['berghain'])

  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL('/en/venues/berghain')
  await expect(page.getByRole('dialog')).toBeHidden()
})

test('Enter opens the results page, which lists every kind and is kept out of the index', async ({ page }) => {
  await mockSearch(page)
  await page.goto('/de')
  // Wait for the app, or the shortcut reaches a page that is not listening yet.
  await expect(page.getByRole('button', { name: 'Events, Locations, Acts und Veranstalter durchsuchen' })).toBeVisible()

  await page.keyboard.press('/')
  await page.getByRole('dialog').getByRole('textbox').fill('berghain')
  await expect(page.getByRole('option', { name: /Klubnacht/ })).toBeVisible()
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL('/de/search?q=berghain')
  await expect(page.getByRole('heading', { level: 1, name: 'Suche' })).toBeVisible()
  for (const name of [/Locations/, /Events/, /Acts/]) {
    await expect(page.getByRole('heading', { level: 2, name })).toBeVisible()
  }
  await expect(page.getByRole('link', { name: /Ben Klock/ })).toHaveAttribute('href', '/de/artists/ben-klock')
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', 'noindex')
})

test('the results page asks for a term before it searches', async ({ page }) => {
  const terms = await mockSearch(page)
  await page.goto('/en/search')

  await expect(page.getByText('Type at least two characters.')).toBeVisible()
  expect(terms).toEqual([])
})
