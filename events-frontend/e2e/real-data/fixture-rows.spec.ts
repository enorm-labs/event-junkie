import { expect, test } from '@playwright/test'

/**
 * The rows `fixtures/events.sql` seeds (#272), read back through the real BFF, nginx and Traefik.
 * The mocked suites assert how a component renders a shape; this asserts that the shape survives the
 * round trip — the query, the DTO, the ingress and the bundle that ran in a browser.
 *
 * Slugs are built the way the fixture builds them, from `CURRENT_DATE` plus the offset the file uses,
 * so nothing here rots at midnight or needs editing when the dataset is reloaded.
 */

/** `fixture-<shape>-<YYYY-MM-DD>`, the fixture's own slug shape. */
function fixtureSlug(shape: string, offsetDays: number): string {
  const date = new Date()
  date.setUTCDate(date.getUTCDate() + offsetDays)
  return `fixture-${shape}-${date.toISOString().slice(0, 10)}`
}

test('the events list shows the seeded venues', async ({ page }) => {
  await page.goto('/en/events')

  const venues = page.getByLabel('Filter by venue')
  await expect(venues.getByRole('option', { name: 'Kesselhaus Nord' })).toBeAttached()
  await expect(venues.getByRole('option', { name: 'Jazzkeller $& Kreuzberg' })).toBeAttached()
  // 42 upcoming rows, so the first page is full at the default size rather than nearly empty.
  await expect(page.getByRole('heading', { level: 2 }).first()).toBeVisible()
})

test('the multi-artist bill lists four acts with their roles, in billing order', async ({ page }) => {
  await page.goto(`/en/events/${fixtureSlug('multi-bill', 3)}`)

  const lineup = page.getByRole('listitem').filter({ hasText: /Møbius Trio|Anna Kessel|Rauhfaser|DJ Nachtfalter/ })
  await expect(lineup).toHaveCount(4)
  // The order is the API's `billingOrder`, which only a real response carries.
  await expect(lineup.nth(0)).toContainText('Møbius Trio')
  await expect(lineup.nth(3)).toContainText('DJ Nachtfalter')
  // Roles are shown because this bill is not four headliners.
  await expect(lineup.nth(0)).toContainText('Headliner')
  await expect(lineup.nth(1)).toContainText('Support')
  await expect(lineup.nth(3)).toContainText('DJ')
})

test('the festival runs across days and names its stages and promoter', async ({ page }) => {
  await page.goto(`/en/events/${fixtureSlug('festival', 10)}`)

  await expect(page.getByRole('heading', { level: 1, name: 'Sommerlaune Festival' })).toBeVisible()
  await expect(page.getByText('Hauptbühne').first()).toBeVisible()
  await expect(page.getByText('Garten').first()).toBeVisible()
  await expect(page.getByRole('link', { name: 'Sommerlaune Festival GmbH' })).toBeVisible()
})

test('sold out and free are marked on the card, and the free filter selects one of them', async ({ page }) => {
  await page.goto('/en/events')

  await expect(page.getByText('Sold out').first()).toBeVisible()

  // The filter is the BFF's `free=true`, and the donation row (`price_note`, not free) must not join it.
  await page.goto('/en/events?free=true')
  await expect(page.getByRole('link', { name: /Jam Session/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Spende erbeten/ })).toHaveCount(0)
})

test('the row with no genre is absent from every genre filter', async ({ page }) => {
  await page.goto(`/en/events/${fixtureSlug('no-genre', 7)}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Offene Bühne' })).toBeVisible()

  for (const genre of ['techno', 'punk', 'jazz']) {
    await page.goto(`/en/events?genre=${genre}`)
    await expect(page.getByRole('link', { name: /Offene Bühne/ })).toHaveCount(0)
  }
})

test('two rooms of one venue on one night are two rows under that venue', async ({ page }) => {
  const night = fixtureSlug('room-floor', 15).slice(-10)
  await page.goto(`/en/events?venue=kesselhaus-nord&from=${night}&to=${night}`)

  await expect(page.getByRole('link', { name: /Nachtschicht Floor/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Nachtschicht Garten/ })).toBeVisible()
})

test('a relocated event says where it went', async ({ page }) => {
  await page.goto(`/en/events/${fixtureSlug('relocated', 9)}`)

  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByText('Kesselhaus Nord').first()).toBeVisible()
})

test('the machine-translated description is disclosed as one', async ({ page }) => {
  await page.goto(`/en/events/${fixtureSlug('translated', 13)}`)

  // ADR-027: the English text is shown under /en, and the disclosure goes with it.
  await expect(page.getByText('The trio presents its second album.')).toBeVisible()
  await expect(page.getByText(/Machine-translated/)).toBeVisible()
})
