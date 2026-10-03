import { readFile } from 'node:fs/promises'

import { expect, type Page, test } from '@playwright/test'

/**
 * Share and add-to-calendar on the event page, with the BFF mocked (#476). The share sheet and the
 * clipboard are stubbed per test, because whether a browser has either differs by engine.
 */

const todayInBerlin = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date())

function isoDaysFromNow(days: number): string {
  const date = new Date(`${todayInBerlin()}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

const eventBody = {
  slug: 'mock-event',
  title: 'Mock Fest',
  eventType: 'CONCERT',
  eventDate: isoDaysFromNow(30),
  startTime: '20:00',
  status: 'SCHEDULED',
  venue: { slug: 'mock-venue', name: 'Mock Venue', address: 'Test Str. 1', city: 'Berlin' },
}
const PAGE_URL = 'https://event-junkie.de/en/events/mock-event'

async function openEvent(page: Page, body: object = eventBody): Promise<void> {
  await page.route(/\/api\/events\/[^/?]+/, (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) }),
  )
  await page.goto('/en/events/mock-event')
  await expect(page.getByRole('heading', { level: 1, name: 'Mock Fest' })).toBeVisible()
}

/** Replaces the share sheet (absent unless `withShare`) and the clipboard with recorders. */
async function stubNavigator(page: Page, withShare: boolean): Promise<void> {
  await page.addInitScript((share) => {
    const calls: { shared: unknown[]; copied: string[] } = { shared: [], copied: [] }
    Object.assign(window, { __calls: calls })
    Object.defineProperty(Navigator.prototype, 'share', {
      configurable: true,
      value: share
        ? (data: unknown) => {
            calls.shared.push(data)
            return Promise.resolve()
          }
        : undefined,
    })
    Object.defineProperty(Navigator.prototype, 'clipboard', {
      configurable: true,
      get: () => ({
        writeText: (text: string) => {
          calls.copied.push(text)
          return Promise.resolve()
        },
      }),
    })
  }, withShare)
}

const calls = (page: Page) =>
  page.evaluate(
    () => (window as unknown as { __calls: { shared: unknown[]; copied: string[] } }).__calls,
  )

test('copies the page link and says so, with no share button where there is no sheet', async ({
  page,
}) => {
  await stubNavigator(page, false)
  await openEvent(page)

  await expect(page.getByRole('button', { name: 'Share' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Copy link' }).click()

  await expect(page.getByRole('status').filter({ hasText: 'Link copied.' })).toBeVisible()
  expect((await calls(page)).copied).toEqual([PAGE_URL])
})

test('opens the native share sheet where there is one', async ({ page }) => {
  await stubNavigator(page, true)
  await openEvent(page)

  await page.getByRole('button', { name: 'Share', exact: true }).click()

  await expect
    .poll(async () => (await calls(page)).shared)
    .toEqual([{ title: 'Mock Fest', url: PAGE_URL }])
  expect((await calls(page)).copied).toEqual([])
})

test('downloads an .ics file built in the browser', async ({ page }) => {
  const requests: string[] = []
  page.on('request', (request) => requests.push(request.url()))
  await openEvent(page)

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Add to calendar (.ics)' }).click(),
  ])

  expect(download.suggestedFilename()).toBe('mock-event.ics')
  const ics = await readFile((await download.path()) ?? '', 'utf8')
  expect(ics).toMatch(/^BEGIN:VCALENDAR\r\n/)
  expect(ics).toContain('SUMMARY:Mock Fest\r\n')
  expect(ics).toMatch(/DTSTART:\d{8}T\d{6}Z\r\n/)
  expect(ics.replace(/\r\n /g, '')).toContain('our estimate')
  await expect(page.getByText(/ends at our estimate/)).toBeVisible()
  // Nothing on another origin: the file is a Blob, not a request.
  const origin = new URL(page.url()).origin
  expect(requests.filter((url) => !url.startsWith(origin) && !url.startsWith('blob:'))).toEqual([])
})

test('links to Google Calendar without loading anything from Google', async ({ page }) => {
  await openEvent(page)

  const link = page.getByRole('link', { name: 'Google Calendar' })
  const url = new URL((await link.getAttribute('href')) ?? '')
  expect(url.host).toBe('calendar.google.com')
  expect(url.searchParams.get('text')).toBe('Mock Fest')
  expect(url.searchParams.get('dates')).toMatch(/^\d{8}T\d{6}Z\/\d{8}T\d{6}Z$/)
  expect(await link.getAttribute('rel')).toContain('noopener')
})

test('offers no calendar entry for a cancelled event, but still shares it', async ({ page }) => {
  await openEvent(page, { ...eventBody, status: 'CANCELLED' })

  await expect(page.getByRole('button', { name: 'Copy link' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add to calendar (.ics)' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Google Calendar' })).toHaveCount(0)
})
