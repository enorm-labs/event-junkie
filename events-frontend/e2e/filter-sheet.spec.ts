import { expect, type Route, test } from '@playwright/test'

/**
 * The filter bar below `sm` (#2890): "More filters" is a bottom sheet holding the second tier and
 * the date inputs, and nothing opens by itself. Pinned to a phone width in every project.
 */

test.use({ viewport: { width: 412, height: 915 } })

function json(route: Route, body: unknown): Promise<void> {
  return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
}

function eventPage(titles: string[]) {
  return {
    content: titles.map((title) => ({
      slug: title.toLowerCase().replace(/\s+/g, '-'),
      title,
      eventDate: '2026-08-15',
    })),
    page: 0,
    size: 20,
    totalElements: titles.length,
    totalPages: 1,
  }
}

test.beforeEach(async ({ page }) => {
  await page.route(/\/api\/genres/, (route) => json(route, []))
  await page.route(/\/api\/venues/, (route) =>
    json(route, {
      content: [{ slug: 'lido', name: 'Lido' }],
      page: 0,
      size: 500,
      totalElements: 1,
      totalPages: 1,
    }),
  )
  await page.route(/\/api\/events(\?|$)/, (route) => {
    const venue = new URL(route.request().url()).searchParams.get('venue')
    return json(route, eventPage(venue === 'lido' ? ['Lido Show'] : ['Default A', 'Default B']))
  })
})

const heading = (page: import('@playwright/test').Page, name: string) =>
  page.getByRole('heading', { level: 2, name })

test('a filtered link keeps the sheet closed and the date inputs off the page', async ({
  page,
}) => {
  await page.goto('/en/events?venue=lido')
  await expect(heading(page, 'Lido Show')).toBeVisible()

  const toggle = page.getByRole('button', { name: 'Filters (1)' })
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByLabel('Earliest event date')).toHaveCount(0)
  // The presets stay on the page, in one row.
  const presets = ['On now', 'Tonight', 'This weekend', 'Next 7 days'].map((name) =>
    page.getByRole('button', { name, exact: true }),
  )
  const tops = await Promise.all(presets.map(async (p) => (await p.boundingBox())!.y))
  expect(new Set(tops).size).toBe(1)
})

test('sets a filter in the sheet, counts the results live, and shows them on close', async ({
  page,
}) => {
  await page.goto('/en/events')
  await expect(heading(page, 'Default A')).toBeVisible()

  await page.getByRole('button', { name: 'Filters', exact: true }).click()
  const sheet = page.getByRole('dialog', { name: 'Filters' })
  await expect(sheet).toBeVisible()
  await expect(sheet.getByLabel('Earliest event date')).toBeVisible()
  await expect(sheet.getByRole('button', { name: 'Show 2 events' })).toBeVisible()

  await sheet.getByLabel('Filter by venue', { exact: true }).selectOption('lido')
  await expect(page).toHaveURL(/[?&]venue=lido\b/)
  await sheet.getByRole('button', { name: 'Show 1 event' }).click()

  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(heading(page, 'Lido Show')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Filters (1)' })).toBeFocused()
})

test('Escape closes the sheet without touching the filters', async ({ page }) => {
  await page.goto('/en/events')
  await page.getByRole('button', { name: 'Filters', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page).toHaveURL(/\/en\/events$/)
})
