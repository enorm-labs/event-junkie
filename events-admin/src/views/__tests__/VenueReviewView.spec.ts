import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { NeedsReviewVenue, VenueDetail } from '@/api/venues'
import VenueReviewView from '@/views/VenueReviewView.vue'

function row(overrides: Partial<NeedsReviewVenue>): NeedsReviewVenue {
  return {
    venueId: 1,
    slug: 'x',
    name: 'X',
    websiteUrl: 'https://x.example/',
    programmeUrl: null,
    reviewedAt: null,
    checkedAt: '2026-10-01T03:00:00Z',
    url: 'https://x.example/',
    outcome: 'DNS',
    httpStatus: null,
    consecutiveFailures: 3,
    failingSince: '2026-08-01T03:00:00Z',
    ...overrides,
  }
}

// Every field the PUT carries holds a value, so a field the round trip drops fails the test.
const stored: VenueDetail = {
  id: 42,
  slug: 'funkloch',
  name: 'Funkloch',
  address: 'Revaler Str. 99',
  city: 'Berlin',
  postalCode: '10245',
  district: 'friedrichshain',
  latitude: 52.507242,
  longitude: 13.451803,
  websiteUrl: 'https://funkloch.example/',
  instagramUrl: 'https://www.instagram.com/funkloch/',
  facebookUrl: 'https://www.facebook.com/funkloch/',
  imageUrl: 'https://commons.wikimedia.org/funkloch.jpg',
  imageAttribution: 'A. Photographer, via Wikimedia Commons',
  imageLicenceId: 'CC-BY-SA-4.0',
  imageSourceUrl: 'https://commons.wikimedia.org/wiki/File:Funkloch.jpg',
  description: 'A club in a former bakery.',
  descriptionLanguage: 'en',
  descriptionAlt: 'Ein Club in einer früheren Bäckerei.',
  descriptionAltLanguage: 'de',
  venueTypes: ['club', 'live-venue'],
  capacity: 300,
  programmeUrl: 'https://funkloch.example/programm',
  reviewedAt: '2026-01-15T12:00:00Z',
  closedOn: null,
  programmeFamilies: ['electronic'],
  programmeEventTypes: ['PARTY'],
  createdAt: '2025-11-01T10:00:00Z',
  updatedAt: '2026-01-15T12:00:00Z',
}

/** The PUT body for [stored] as read: no read-only field, every other field as stored. */
const {
  id: _id,
  slug: _slug,
  programmeFamilies: _families,
  programmeEventTypes: _types,
  createdAt: _created,
  updatedAt: _updated,
  ...storedRequest
} = stored

let listed: NeedsReviewVenue[] = []
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url === '/api/admin/venues/needs-review') return json(listed)
    if (method === 'GET' && url === '/api/admin/venues/42') return json(stored)
    if (method === 'PUT' && url === '/api/admin/venues/42') {
      return json({ ...stored, ...JSON.parse(String(init?.body)) })
    }
    if (method === 'POST' && url === '/api/admin/venues/site-check') {
      return new Response(null, { status: 202 })
    }
    return json({ detail: `unexpected ${method} ${url}` }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
})

async function mountView() {
  const wrapper = mount(VenueReviewView, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

const cells = (wrapper: Awaited<ReturnType<typeof mountView>>) =>
  wrapper.findAll('[data-testid="review-row"]').map((tr) => tr.findAll('td').map((td) => td.text()))

function button(label: string) {
  const found = document.body.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)
  if (!found) throw new Error(`No button "${label}"`)
  return found
}

/** The body of the one PUT the page sent. */
function putBody() {
  const puts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')
  expect(puts).toHaveLength(1)
  return JSON.parse(String(puts[0]![1]!.body))
}

describe('VenueReviewView', () => {
  it('lists the venues longest-failing first, with an HTTP row and its status', async () => {
    listed = [
      row({ venueId: 2, slug: 'loge', name: 'Loge', failingSince: '2026-08-01T03:00:00Z' }),
      row({
        venueId: 42,
        slug: 'funkloch',
        name: 'Funkloch',
        url: 'https://funkloch.example/programm',
        outcome: 'HTTP',
        httpStatus: 404,
        consecutiveFailures: 5,
        failingSince: '2026-06-01T03:00:00Z',
      }),
    ]
    const wrapper = await mountView()

    expect(cells(wrapper)).toEqual([
      [
        'Funkloch',
        'https://funkloch.example/programm',
        'HTTP',
        '404',
        '2026-06-01 5 checks in a row',
        'Still open  Closed on…',
      ],
      [
        'Loge',
        'https://x.example/',
        'DNS',
        '—',
        '2026-08-01 3 checks in a row',
        'Still open  Closed on…',
      ],
    ])
    // The test build has no cluster mode, so the name links the production site.
    expect(wrapper.find('[data-testid="review-row"] a').attributes('href')).toBe(
      'https://event-junkie.de/en/venues/funkloch',
    )
  })

  it('says so when no venue needs a review', async () => {
    listed = []
    const wrapper = await mountView()

    expect(cells(wrapper)).toEqual([])
    expect(wrapper.text()).toContain('No venue needs a review.')
  })

  it('shows the error when the list does not load', async () => {
    fetchMock.mockImplementation(async () => json({ detail: 'down' }, 503))
    const wrapper = await mountView()

    expect(wrapper.find('[role="alert"]').text()).toBe(
      'GET /api/admin/venues/needs-review: HTTP 503: down',
    )
  })

  it('"Still open" sends the full venue with only reviewedAt changed', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-10T08:00:00Z'))
    listed = [row({ venueId: 42, slug: 'funkloch', name: 'Funkloch' })]
    const wrapper = await mountView()

    button('Funkloch is still open').click()
    await flushPromises()

    expect(putBody()).toEqual({ ...storedRequest, reviewedAt: '2026-10-10T08:00:00.000Z' })
    expect(cells(wrapper)).toEqual([])
    expect(wrapper.find('[role="status"]').text()).toContain('Funkloch is marked as still open.')
  })

  it('"Closed on…" asks for the last open day and sends only closedOn changed', async () => {
    // 22:30 UTC is the next day in Berlin, so the default is the Berlin day.
    listed = [
      row({
        venueId: 42,
        slug: 'funkloch',
        name: 'Funkloch',
        failingSince: '2026-07-14T22:30:00Z',
      }),
    ]
    const wrapper = await mountView()

    button('Funkloch closed on…').click()
    await flushPromises()
    const input = document.body.querySelector<HTMLInputElement>('input[name="closedOn"]')!
    expect(input.value).toBe('2026-07-15')
    // Nothing is written until the operator saves.
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)

    input.value = '2026-07-31'
    input.dispatchEvent(new Event('input'))
    await flushPromises()
    document.body.querySelector('form')!.dispatchEvent(new Event('submit'))
    await flushPromises()

    expect(putBody()).toEqual({ ...storedRequest, closedOn: '2026-07-31' })
    expect(cells(wrapper)).toEqual([])
    expect(document.body.querySelector('input[name="closedOn"]')).toBeNull()
  })

  it('keeps the row and shows the error when the PUT is refused', async () => {
    listed = [row({ venueId: 42, slug: 'funkloch', name: 'Funkloch' })]
    const wrapper = await mountView()
    const answer = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation(async (input, init) =>
      init?.method === 'PUT' ? json({ detail: 'Validation failed' }, 400) : answer(input, init),
    )

    button('Funkloch is still open').click()
    await flushPromises()

    expect(cells(wrapper)).toHaveLength(1)
    expect(wrapper.find('[role="alert"]').text()).toBe(
      'PUT /api/admin/venues/42: HTTP 400: Validation failed',
    )
  })

  it('"Check now" posts site-check and says the pass runs in the background', async () => {
    listed = []
    const wrapper = await mountView()

    const check = wrapper.findAll('button').find((b) => b.text() === 'Check now')!
    await check.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/venues/site-check',
      expect.objectContaining({ method: 'POST' }),
    )
    expect(wrapper.find('[role="status"]').text()).toContain(
      'The site check runs in the background.',
    )
  })
})
