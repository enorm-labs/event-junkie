import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import type { AdminEvent } from '@/api/events'
import EventEditor from '@/components/EventEditor.vue'
import EventsView from '@/views/EventsView.vue'

const LONG_TITLE =
  'An Extraordinarily Long Evening of Improvised Jazz, Spoken Word and Open Decks Until the Sun Comes Up Over the Spree'

function event(overrides: Partial<AdminEvent>): AdminEvent {
  return {
    id: 1,
    venueId: 3,
    title: 'Open Stage',
    subtitle: null,
    description: null,
    eventType: 'SHOW',
    status: 'SCHEDULED',
    slug: '2026-10-14-open-stage',
    eventDate: '2026-10-14',
    doorsTime: null,
    startTime: '20:00:00',
    imageUrl: null,
    sourceUrl: null,
    sourceId: 'venue:1',
    ticketUrl: null,
    facebookEventUrl: null,
    genre: null,
    genreTags: [],
    pricePresale: null,
    priceBoxOffice: null,
    priceCurrency: 'EUR',
    priceNote: null,
    soldOut: false,
    free: false,
    artists: [],
    promoterIds: [],
    pinnedFields: [],
    ...overrides,
  }
}

let found: AdminEvent[] = []
let totalPages = 1
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  // 23:30 in Berlin on 14 October: today there is still the 14th.
  vi.setSystemTime(new Date('2026-10-14T21:30:00Z'))
  found = [
    event({ id: 1 }),
    event({ id: 2, title: LONG_TITLE, venueId: 404, startTime: null, status: 'CANCELLED' }),
    event({ id: 3, eventDate: '2026-10-15', status: 'POSTPONED' }),
    event({ id: 4, eventDate: '2026-10-16', status: 'RELOCATED' }),
  ]
  totalPages = 1
  fetchMock.mockImplementation(async (input) => {
    const url = String(input)
    if (url.startsWith('/api/admin/venues?')) {
      return json({
        content: [
          { id: 3, name: 'Badehaus', slug: 'badehaus', closedOn: null },
          { id: 5, name: 'Wild at Heart', slug: 'wild-at-heart', closedOn: '2026-01-31' },
        ],
        totalElements: 2,
      })
    }
    if (url.startsWith('/api/admin/events?')) {
      return json({ content: found, totalElements: 41, totalPages })
    }
    return json({ detail: `unexpected ${url}` }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
})

async function mountView(path = '/events') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/events', component: EventsView }],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(EventsView, {
    attachTo: document.body,
    global: { plugins: [router], stubs: { EventEditor: true } },
  })
  await flushPromises()
  return { wrapper, router }
}

/** The query parameters of the last event search, in order. */
function lastSearch() {
  const call = fetchMock.mock.calls
    .map(([input]) => String(input))
    .filter((url) => url.startsWith('/api/admin/events?'))
    .pop()!
  return [...new URL(call, 'http://admin.test').searchParams]
}

const SORT = [
  ['size', '20'],
  ['sort', 'eventDate,asc'],
  ['sort', 'startTime,asc'],
]

/** With past events shown the newest come first. */
const PAST_SORT = [
  ['size', '20'],
  ['sort', 'eventDate,desc'],
  ['sort', 'startTime,desc'],
]

describe('EventsView', () => {
  it('lists upcoming events from today in Berlin with date, start, venue, title and status', async () => {
    const { wrapper } = await mountView()

    expect(lastSearch()).toEqual([['from', '2026-10-14'], ['page', '0'], ...SORT])
    const rows = wrapper.findAll('[data-testid="event-row"]')
    expect(rows.map((r) => r.findAll('td').map((td) => td.text()))).toEqual([
      ['2026-10-14', '20:00', 'Badehaus', 'Open Stage', 'SCHEDULED'],
      ['2026-10-14', '—', 'Venue #404', LONG_TITLE, 'CANCELLED'],
      ['2026-10-15', '20:00', 'Badehaus', 'Open Stage', 'POSTPONED'],
      ['2026-10-16', '20:00', 'Badehaus', 'Open Stage', 'RELOCATED'],
    ])
    expect(wrapper.find('[data-testid="result-count"]').text()).toBe('41 events')
  })

  it('searches the title on submit, not per keystroke', async () => {
    const { wrapper, router } = await mountView()
    const searches = () =>
      fetchMock.mock.calls.filter(([input]) => String(input).startsWith('/api/admin/events?'))
        .length

    await wrapper.find('#events-title').setValue('  50%_off ')
    await flushPromises()
    expect(searches()).toBe(1)

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ q: '50%_off' })
    expect(lastSearch()).toEqual([['q', '50%_off'], ['from', '2026-10-14'], ['page', '0'], ...SORT])
  })

  it('narrows to the picked venue with the typed title, and the switch shows past events', async () => {
    const { wrapper, router } = await mountView()
    await wrapper.find('#events-title').setValue('jam')

    await wrapper.find('#events-venue').setValue('5')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ q: 'jam', venue: '5' })
    expect(lastSearch()).toEqual([
      ['q', 'jam'],
      ['venueId', '5'],
      ['from', '2026-10-14'],
      ['page', '0'],
      ...SORT,
    ])
    expect(wrapper.find('#events-venue option:checked').text()).toBe(
      'Wild at Heart (closed 2026-01-31)',
    )

    await wrapper.find('#events-past').setValue(true)
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ q: 'jam', venue: '5', past: '1' })
    expect(lastSearch()).toEqual([['q', 'jam'], ['venueId', '5'], ['page', '0'], ...PAST_SORT])

    await wrapper.find('#events-venue').setValue('')
    await flushPromises()
    expect(lastSearch()).toEqual([['q', 'jam'], ['page', '0'], ...PAST_SORT])
  })

  it('reads the search from the query string', async () => {
    const { wrapper } = await mountView('/events?q=Jam&venue=99&past=1&page=2')

    expect(lastSearch()).toEqual([['q', 'Jam'], ['venueId', '99'], ['page', '2'], ...PAST_SORT])
    expect((wrapper.find('#events-title').element as HTMLInputElement).value).toBe('Jam')
    expect(wrapper.find('#events-venue option:checked').text()).toBe('Venue #99')
    expect((wrapper.find('#events-past').element as HTMLInputElement).checked).toBe(true)
  })

  it('pages through the results and keeps the search', async () => {
    totalPages = 3
    const { wrapper, router } = await mountView('/events?q=jam')
    const button = (label: string) => wrapper.find(`button[aria-label="${label}"]`)
    expect(button('Previous page').attributes('disabled')).toBeDefined()

    await button('Next page').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ q: 'jam', page: '1' })
    expect(lastSearch()).toContainEqual(['page', '1'])
    expect(button('Previous page').attributes('disabled')).toBeUndefined()

    await button('Next page').trigger('click')
    await flushPromises()
    expect(button('Next page').attributes('disabled')).toBeDefined()

    // A new search starts on the first page.
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ q: 'jam' })
  })

  it('opens the editor on a row and shows the saved event in its place', async () => {
    const { wrapper } = await mountView()
    const editor = wrapper.findComponent(EventEditor)
    expect(editor.props('eventId')).toBeNull()

    await wrapper.findAll('[data-testid="event-row"]')[2]!.find('button').trigger('click')
    expect(editor.props('eventId')).toBe(3)

    editor.vm.$emit('saved', event({ id: 3, eventDate: '2026-10-15', status: 'CANCELLED' }))
    editor.vm.$emit('close')
    await flushPromises()

    expect(editor.props('eventId')).toBeNull()
    const row = wrapper.findAll('[data-testid="event-row"]')[2]!
    expect(row.findAll('td')[4]!.text()).toBe('CANCELLED')
    // The save refreshes the row in place, without a new search.
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).startsWith('/api/admin/events?')),
    ).toHaveLength(1)
  })

  it('says so when nothing matches, and shows a failed search plainly', async () => {
    found = []
    const empty = await mountView()
    expect(empty.wrapper.find('[data-testid="no-events"]').exists()).toBe(true)
    empty.wrapper.unmount()

    fetchMock.mockImplementationOnce(async () => json({ detail: 'importer down' }, 502))
    const failed = await mountView()
    expect(failed.wrapper.find('[role="alert"]').text()).toContain('HTTP 502 — importer down')
  })
})
