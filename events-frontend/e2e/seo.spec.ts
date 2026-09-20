import { expect, test } from '@playwright/test'

/**
 * The crawler-facing surface: `/sitemap.xml`, `/robots.txt`, and the per-route head annotations.
 * The two files come from a Vite plugin (`scripts/seoFiles.ts`) that also serves them in dev, so
 * these pass under `npm run dev` and `npm run preview` alike. The head tags need a real browser:
 * they are written after the router resolves, which a unit test cannot prove.
 */

test('serves a sitemap listing every static page in both locales', async ({ request }) => {
  const response = await request.get('/sitemap.xml')

  expect(response.status()).toBe(200)
  expect(response.headers()['content-type']).toContain('xml')

  const xml = await response.text()
  expect(xml).toContain('<loc>https://event-junkie.de/en</loc>')
  expect(xml).toContain('<loc>https://event-junkie.de/de/legal/imprint</loc>')
  // 10 static pages × 2 locales. A change here is a real change to what gets indexed.
  expect(xml.match(/<url>/g)).toHaveLength(20)
})

test('serves a robots.txt that points at the sitemap', async ({ request }) => {
  const response = await request.get('/robots.txt')

  expect(response.status()).toBe(200)
  expect(await response.text()).toContain('Sitemap: https://event-junkie.de/sitemap.xml')
})

test('annotates a page with a canonical URL and reciprocal alternates', async ({ page }) => {
  await page.goto('/de/events')

  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    'href',
    'https://event-junkie.de/de/events',
  )
  await expect(page.locator('link[rel="alternate"][hreflang="en"]')).toHaveAttribute(
    'href',
    'https://event-junkie.de/en/events',
  )
  // The self-reference: without it the annotation is one-way and gets ignored entirely.
  await expect(page.locator('link[rel="alternate"][hreflang="de"]')).toHaveAttribute(
    'href',
    'https://event-junkie.de/de/events',
  )
  await expect(page.locator('link[rel="alternate"][hreflang="x-default"]')).toHaveAttribute(
    'href',
    'https://event-junkie.de/en/events',
  )
})

test('tags the Open Graph locale and its alternate', async ({ page }) => {
  await page.goto('/de/about')

  await expect(page.locator('meta[property="og:locale"]')).toHaveAttribute('content', 'de_DE')
  await expect(page.locator('meta[property="og:locale:alternate"]')).toHaveAttribute(
    'content',
    'en_GB',
  )
  await expect(page.locator('meta[property="og:url"]')).toHaveAttribute(
    'content',
    'https://event-junkie.de/de/about',
  )
})

test('drops the query string from the canonical URL', async ({ page }) => {
  // Filters are a client-side refinement of the same HTML; indexing each combination would be
  // near-duplicate content.
  await page.goto('/en/events?type=CONCERT')

  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    'href',
    'https://event-junkie.de/en/events',
  )
})

test('rewrites the annotations on in-app navigation without accumulating them', async ({
  page,
}) => {
  // An SPA never clears the head between routes. Two canonical tags are worse than none.
  await page.goto('/en')
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Venues' }).click()
  await expect(page).toHaveURL(/\/en\/venues$/)

  await expect(page.locator('link[rel="canonical"]')).toHaveCount(1)
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    'href',
    'https://event-junkie.de/en/venues',
  )
  await expect(page.locator('link[rel="alternate"]')).toHaveCount(3)
  await expect(page.locator('meta[property="og:locale:alternate"]')).toHaveCount(1)
})

test('annotates the destination of a redirect, not the URL that was requested', async ({
  page,
}) => {
  // `/venues` redirects to `/en/venues`; annotating the pre-redirect URL names a redirect as canonical.
  await page.goto('/venues')

  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    'href',
    'https://event-junkie.de/en/venues',
  )
})

/**
 * Structured data in a real browser: the unit tests prove the documents, these prove they reach
 * the page through the async load, the router and the head. The event is mocked.
 */

const EVENT_SLUG = '2026-06-12-lido-test-act'

const EVENT = {
  slug: EVENT_SLUG,
  title: 'Test Act',
  eventType: 'CONCERT',
  status: 'SCHEDULED',
  eventDate: '2026-06-12',
  startTime: '20:00',
  description: 'A night of something.',
  imageUrl: 'https://example.test/poster.jpg',
  ticketUrl: 'https://tickets.test/buy',
  pricePresale: 38,
  priceCurrency: 'EUR',
  venue: { slug: 'lido', name: 'Lido', address: 'Cuvrystr. 7', city: 'Berlin' },
  lineup: [{ artist: { slug: 'test-act', name: 'Test Act' }, role: 'HEADLINER', billingOrder: 0 }],
}

/** Every ld+json block on the page, parsed. Fails loudly if any of them is not valid JSON. */
async function jsonLd(page: import('@playwright/test').Page): Promise<unknown[]> {
  const blocks = await page.locator('script[type="application/ld+json"]').allTextContents()
  return blocks.flatMap((block) => {
    const parsed: unknown = JSON.parse(block)
    return Array.isArray(parsed) ? parsed : [parsed]
  })
}

test('an event page publishes a valid Event document', async ({ page }) => {
  await page.route(`**/api/events/${EVENT_SLUG}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(EVENT) }),
  )

  await page.goto(`/en/events/${EVENT_SLUG}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Test Act' })).toBeVisible()

  const documents = (await jsonLd(page)) as Record<string, unknown>[]
  const event = documents.find((document) => document['@type'] === 'MusicEvent')!

  expect(event).toBeDefined()
  expect(event.name).toBe('Test Act')
  // Local time with the summer offset — the value a hardcoded offset would get wrong.
  expect(event.startDate).toBe('2026-06-12T20:00:00+02:00')
  expect(event.location).toMatchObject({ '@type': 'Place', name: 'Lido' })
  expect(event.url).toBe(`https://event-junkie.de/en/events/${EVENT_SLUG}`)
})

test('an event page publishes a breadcrumb trail alongside it', async ({ page }) => {
  await page.route(`**/api/events/${EVENT_SLUG}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(EVENT) }),
  )

  await page.goto(`/en/events/${EVENT_SLUG}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Test Act' })).toBeVisible()

  const documents = (await jsonLd(page)) as Record<string, unknown>[]
  const crumbs = documents.find((document) => document['@type'] === 'BreadcrumbList')!

  expect(crumbs).toBeDefined()
  expect(crumbs.itemListElement).toHaveLength(3)
})

test('the home page identifies the site', async ({ page }) => {
  await page.goto('/en')

  const documents = (await jsonLd(page)) as Record<string, unknown>[]
  expect(documents.some((document) => document['@type'] === 'WebSite')).toBe(true)
  // The imprint says a private individual runs this, not a company. Do not claim otherwise here.
  expect(documents.some((document) => document['@type'] === 'Organization')).toBe(false)
})

test('leaves no structured data behind when the view changes', async ({ page }) => {
  // The home page publishes a WebSite document; the About page has none. A leftover block would
  // describe the wrong page.
  await page.goto('/en')
  expect(await jsonLd(page)).not.toEqual([])

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'About' }).click()
  await expect(page).toHaveURL(/\/en\/about$/)

  expect(await jsonLd(page)).toEqual([])
})

/**
 * Per-page title, description and image, through the router and the async load. The staleness
 * cases matter most: nothing clears the head between routes except this code.
 */

const meta = (page: import('@playwright/test').Page, selector: string) =>
  page.locator(`head ${selector}`).getAttribute('content')

test('an event page describes itself rather than the site', async ({ page }) => {
  await page.route(`**/api/events/${EVENT_SLUG}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(EVENT) }),
  )

  await page.goto(`/en/events/${EVENT_SLUG}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Test Act' })).toBeVisible()

  await expect(page).toHaveTitle('Test Act · Event Junkie')
  // Facts first, blurb second — what someone deciding whether to open a shared link wants.
  const description = 'Fri, 12 Jun 2026 · Lido, Berlin — A night of something.'
  expect(await meta(page, 'meta[name="description"]')).toBe(description)
  expect(await meta(page, 'meta[property="og:description"]')).toBe(description)
  expect(await meta(page, 'meta[property="og:image"]')).toBe('https://example.test/poster.jpg')
})

test('the same event describes itself in German under /de', async ({ page }) => {
  await page.route(`**/api/events/${EVENT_SLUG}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(EVENT) }),
  )

  await page.goto(`/de/events/${EVENT_SLUG}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Test Act' })).toBeVisible()

  // Tolerant of the comma: WebKit's ICU renders `Fr. 12. Juni 2026`, Chromium's and Firefox's
  // `Fr., 12. Juni 2026`. The unit tests pin the exact string against Node's one ICU.
  expect(await meta(page, 'meta[property="og:description"]')).toMatch(/Fr\.,? 12\. Juni 2026/)
})

test('leaves no description or image behind when the view changes', async ({ page }) => {
  // The failure this prevents: the imprint describing itself as the club night you just looked at,
  // with the event poster still attached.
  await page.route(`**/api/events/${EVENT_SLUG}`, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(EVENT) }),
  )

  await page.goto(`/en/events/${EVENT_SLUG}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Test Act' })).toBeVisible()

  await page.goto('/en/legal/imprint')
  await expect(page.getByRole('heading', { level: 1, name: 'Imprint' })).toBeVisible()

  expect(await meta(page, 'meta[name="description"]')).toContain('§ 5 DDG')
  await expect(page.locator('head meta[property="og:image"]')).toHaveCount(0)
})

test('every static page carries its own description', async ({ page }) => {
  // Previously all of them served the one site-level description.
  const seen = new Set<string>()

  for (const path of ['/en', '/en/events', '/en/venues', '/en/about', '/en/legal/privacy']) {
    await page.goto(path)
    const description = await meta(page, 'meta[name="description"]')
    expect(description, `${path} has no description`).toBeTruthy()
    seen.add(description!)
  }

  expect(seen.size, 'static pages share a description').toBe(5)
})
