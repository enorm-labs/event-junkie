import { expect, type Page, type Route, test } from '@playwright/test'

/**
 * Promoters overview e2e tests with a fully mocked BFF (#1349), the shape of `venues.spec.ts`:
 * list, search, sort, empty state and pagination, each deterministic without a backend.
 *
 * Endpoint: GET /api/promoters?q=&sort=&page=&size= → PageResponsePromoterListItemResponse
 */

/** Collect uncaught exceptions — the "the app broke" signal, as in the smoke suite. */
function collectPageErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  return errors
}

/** Fulfill a matched request with a JSON body. */
function json(route: Route, body: unknown, status = 200): Promise<void> {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

/** Matches the promoter list (`/api/promoters?…` or bare `/api/promoters`), not `/api/promoters/:slug`. */
const promotersList = /\/api\/promoters(\?|$)/

function promoter(slug: string, name: string, upcomingEventCount = 0) {
  return {
    slug,
    name,
    upcomingEventCount,
    websiteUrl: 'https://www.example.com/agency',
    description: `${name} bucht die Abende.`,
    descriptionLanguage: 'de',
    descriptionAlt: `${name} books the nights.`,
    descriptionAltLanguage: 'en',
  }
}

function pageBody(content: ReturnType<typeof promoter>[], page = 0, totalPages = 1) {
  return { content, page, size: 24, totalElements: content.length, totalPages }
}

test('lists promoters returned by the API, with the count, the description and the site', async ({
  page,
}) => {
  const errors = collectPageErrors(page)
  await page.route(promotersList, (route) =>
    json(
      route,
      pageBody([promoter('trinity-music', 'Trinity Music', 12), promoter('goodlive', 'Goodlive')]),
    ),
  )

  await page.goto('/promoters')

  await expect(page.getByRole('heading', { level: 1, name: 'Promoters' })).toBeVisible()
  // In-app links are locale-prefixed (ADR-013 §Decision 2).
  await expect(page.getByRole('link', { name: 'Trinity Music' })).toHaveAttribute(
    'href',
    '/en/promoters/trinity-music',
  )
  await expect(page.getByText('12 upcoming events')).toBeVisible()
  await expect(page.getByText('No upcoming events')).toBeVisible()
  // The English text, because the page is English; the German original stays on the page itself.
  await expect(page.getByText('Trinity Music books the nights.')).toBeVisible()
  await expect(page.getByRole('link', { name: 'example.com' }).first()).toHaveAttribute(
    'href',
    'https://www.example.com/agency',
  )
  expect(errors, 'unexpected uncaught exceptions').toEqual([])
})

test('searching updates the URL query and re-requests', async ({ page }) => {
  await page.route(promotersList, (route) => {
    const q = new URL(route.request().url()).searchParams.get('q')
    json(
      route,
      pageBody(
        q === 'trinity'
          ? [promoter('trinity-music', 'Trinity Music')]
          : [promoter('goodlive', 'Goodlive')],
      ),
    )
  })

  await page.goto('/promoters')
  await page.getByPlaceholder('Search promoters…').fill('trinity')
  await page.getByPlaceholder('Search promoters…').press('Enter')

  await expect(page).toHaveURL(/\/promoters\?q=trinity$/)
  await expect(page.getByRole('link', { name: 'Trinity Music' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Goodlive' })).toHaveCount(0)
})

test('sorting by upcoming events puts the sort in the URL and sends it to the API', async ({
  page,
}) => {
  await page.route(promotersList, (route) => {
    const sort = new URL(route.request().url()).searchParams.get('sort')
    json(
      route,
      pageBody(
        sort === 'upcomingEvents,desc'
          ? [promoter('trinity-music', 'Trinity Music', 12), promoter('goodlive', 'Goodlive', 1)]
          : [promoter('goodlive', 'Goodlive', 1), promoter('trinity-music', 'Trinity Music', 12)],
      ),
    )
  })

  await page.goto('/promoters')
  await expect(page.getByRole('heading', { level: 2 }).first()).toHaveText('Goodlive')

  const sort = page.getByRole('group', { name: 'Sort' })
  await expect(sort.getByRole('button', { name: 'A–Z' })).toHaveAttribute('aria-pressed', 'true')
  await sort.getByRole('button', { name: 'Most upcoming' }).click()

  await expect(page).toHaveURL(/\/promoters\?sort=upcomingEvents,desc$/)
  await expect(page.getByRole('heading', { level: 2 }).first()).toHaveText('Trinity Music')
  await expect(sort.getByRole('button', { name: 'Most upcoming' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )

  // Back to the name order: the default leaves the URL clean.
  await sort.getByRole('button', { name: 'A–Z' }).click()
  await expect(page).toHaveURL(/\/promoters$/)
})

test('the empty state offers a way out of the search', async ({ page }) => {
  await page.route(promotersList, (route) => {
    const q = new URL(route.request().url()).searchParams.get('q')
    return json(route, q ? pageBody([]) : pageBody([promoter('goodlive', 'Goodlive')]))
  })

  await page.goto('/promoters?q=nothing')
  await expect(page.getByText(/no promoters match/i)).toBeVisible()

  await page.getByRole('button', { name: 'Clear the search' }).click()

  await expect(page).toHaveURL(/\/promoters$/)
  await expect(page.getByRole('link', { name: 'Goodlive' })).toBeVisible()
})

test('paginates when there is more than one page', async ({ page }) => {
  await page.route(promotersList, (route) => {
    const pageParam = Number(new URL(route.request().url()).searchParams.get('page') ?? '0')
    const body = pageBody([promoter(`p${pageParam}`, `Promoter ${pageParam}`)], pageParam, 2)
    json(route, { ...body, totalElements: 2 })
  })

  await page.goto('/promoters')
  await expect(page.getByText('Page 1 of 2')).toBeVisible()

  await page.getByRole('button', { name: 'Next' }).click()

  await expect(page).toHaveURL(/\/promoters\?page=1$/)
  await expect(page.getByText('Page 2 of 2')).toBeVisible()
})
