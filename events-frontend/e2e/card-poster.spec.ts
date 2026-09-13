import { expect, type Route, test } from '@playwright/test'

/**
 * The card poster on a phone: it reaches both edges, and it reveals itself without a pointer.
 *
 * Both are invisible to a unit test. The bleed is geometry the page shell's padding decides, and
 * the reveal is `@media (hover: hover)` — Tailwind 4 compiles every hover variant inside it, so on
 * a touch device the grayscale rule never matches and `useViewportFocus` is what answers instead.
 *
 * The venues list is the subject because it is the shortest data-driven page with cards on it.
 */

const venuesList = /\/api\/venues(\?|$)/

/** A 1x1 PNG, so a photo card renders a real `<picture>` without a backend or a fixture file. */
const PIXEL = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==',
  'base64',
)

function venue(slug: string, name: string, imageUrl?: string) {
  return { slug, name, city: 'Berlin', district: 'kreuzberg', imageUrl }
}

function pageBody(content: ReturnType<typeof venue>[]) {
  return { content, page: 0, size: 24, totalElements: content.length, totalPages: 1 }
}

function json(route: Route, body: unknown): Promise<void> {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

/** The poster box is the card link's first child — see `CARD_POSTER_CLASS`. */
function poster(slug: string, page: import('@playwright/test').Page) {
  return page.locator(`a[href="/en/venues/${slug}"] > div`).first()
}

test('both kinds of poster reach both edges of a phone viewport, and the text does not', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 780 })
  await page.route('**/api/images/**', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: PIXEL }),
  )
  await page.route(venuesList, (route) =>
    json(
      route,
      pageBody([venue('lido', 'Lido', '/api/images/lido/192.png'), venue('astra', 'Astra')]),
    ),
  )

  await page.goto('/venues')
  await expect(page.getByRole('heading', { level: 2, name: 'Lido' })).toBeVisible()

  const photo = await poster('lido', page).boundingBox()
  expect(photo?.x).toBe(0)
  expect(photo?.width).toBe(390)

  // The shell's `p-4` still holds for the card's own text, so the column keeps reading as one.
  const heading = await page.getByRole('heading', { level: 2, name: 'Lido' }).boundingBox()
  expect(heading?.x).toBeGreaterThanOrEqual(16)

  // A card with no flyer is a normal card, not a fallback, so its title poster gets the same edge.
  const title = await poster('astra', page).boundingBox()
  expect(title?.x).toBe(0)
  expect(title?.width).toBe(390)
})

test('a card in the middle of a touch viewport reveals its poster', async ({ page }) => {
  // The trigger is the device, not the test: on a pointer device `:hover` answers instead, and
  // there is nothing here to assert.
  // eslint-disable-next-line playwright/no-skipped-test -- the mobile projects are where it runs
  test.skip(
    await page.evaluate(() => matchMedia('(hover: hover)').matches),
    'a pointer device is left to :hover, which is what this replaces',
  )

  const venues = Array.from({ length: 8 }, (_, index) => venue(`venue-${index}`, `Venue ${index}`))
  await page.route(venuesList, (route) => json(route, pageBody(venues)))

  await page.goto('/venues')
  const target = poster('venue-5', page)
  await target.scrollIntoViewIfNeeded()
  await target.evaluate((element) => element.scrollIntoView({ block: 'center' }))

  await expect(target).toHaveAttribute('data-focus', 'true')
  // The card at the top of the page is out of the band, so only one poster is revealed at a time.
  await expect(poster('venue-0', page)).not.toHaveAttribute('data-focus', 'true')
})
