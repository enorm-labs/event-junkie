import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { AdminEvent } from '@/api/events'
import PicksView from '@/views/PicksView.vue'

function event(id: number, title: string, eventDate: string, featuredUntil: string | null) {
  return {
    id,
    venueId: 7,
    title,
    subtitle: null,
    description: null,
    eventType: 'CONCERT',
    status: 'SCHEDULED',
    slug: `slug-${id}`,
    eventDate,
    doorsTime: null,
    startTime: '20:00:00',
    imageUrl: null,
    sourceUrl: null,
    sourceId: `test:${id}`,
    ticketUrl: null,
    facebookEventUrl: null,
    genre: ' Noise Rock',
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
    featuredUntil,
  } satisfies AdminEvent
}

let picks: AdminEvent[] = []
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  picks = [
    event(1, 'Die Nerven', '2026-10-23', '2026-10-24T21:59:00Z'),
    event(2, 'Tocotronic', '2026-10-30', '2026-10-31T22:59:00Z'),
  ]
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    const method = init?.method ?? 'GET'
    const id = Number(/^\/api\/admin\/events\/(\d+)$/.exec(url.pathname)?.[1])
    if (method === 'GET' && url.pathname === '/api/admin/events') {
      return json({ content: picks, totalElements: picks.length, totalPages: 1 })
    }
    if (method === 'GET' && url.pathname === '/api/admin/venues') {
      return json({ content: [{ id: 7, name: 'Badehaus' }], totalElements: 1, totalPages: 1 })
    }
    if (method === 'GET' && id) return json(picks.find((e) => e.id === id))
    if (method === 'PUT' && id) {
      return json({ ...picks.find((e) => e.id === id)!, ...JSON.parse(String(init?.body)) })
    }
    return json({ detail: `unexpected ${method} ${url.pathname}` }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  fetchMock.mockReset()
})

async function mountView() {
  const wrapper = mount(PicksView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
  await flushPromises()
  return wrapper
}

const calls = () =>
  fetchMock.mock.calls.map(([input, init]) => `${init?.method ?? 'GET'} ${String(input)}`)

describe('PicksView', () => {
  it('lists the current picks, earliest start first, with venue, start and until in Berlin time', async () => {
    const wrapper = await mountView()

    expect(calls()).toContain(
      'GET /api/admin/events?featured=true&size=100&sort=eventDate%2Casc&sort=startTime%2Casc',
    )
    expect(wrapper.get('[data-testid="pick-count"]').text()).toBe('2 picks')
    const rows = wrapper.findAll('[data-testid="pick-row"]')
    expect(rows.map((row) => row.get('[data-testid="pick-title"]').text())).toEqual([
      'Die Nerven',
      'Tocotronic',
    ])
    expect(rows[0]!.text()).toContain('Badehaus')
    expect(rows[0]!.text()).toContain('2026-10-23 20:00')
    // CEST before the change on 2026-10-25, CET after it.
    expect(rows.map((row) => row.get('[data-testid="pick-until"]').text())).toEqual([
      '2026-10-24 23:59',
      '2026-10-31 23:59',
    ])
  })

  it('says so when nothing is picked', async () => {
    picks = []
    const wrapper = await mountView()

    expect(wrapper.get('[data-testid="pick-count"]').text()).toBe('0 picks')
    expect(wrapper.get('[data-testid="no-picks"]').text()).toContain('No event is picked')
  })

  it('clears a pick with a PUT of the event as stored and featuredUntil null, and drops the row', async () => {
    const wrapper = await mountView()

    await wrapper.get('button[aria-label="Clear the pick on Die Nerven"]').trigger('click')
    await flushPromises()

    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(String(put[0])).toBe('/api/admin/events/1')
    const body = JSON.parse(String(put[1]!.body))
    expect(body).toHaveProperty('featuredUntil', null)
    // The stored genre goes back untrimmed, so the clear pins nothing.
    expect(body).toMatchObject({ title: 'Die Nerven', genre: ' Noise Rock', eventType: 'CONCERT' })
    expect(calls()).toContain('GET /api/admin/events/1')
    expect(wrapper.findAll('[data-testid="pick-title"]').map((t) => t.text())).toEqual([
      'Tocotronic',
    ])
    expect(wrapper.get('[data-testid="pick-count"]').text()).toBe('1 pick')
  })

  it('keeps the row and shows the error when the clear fails', async () => {
    const wrapper = await mountView()
    fetchMock.mockImplementation(async () => json({ detail: 'Event 1 not found' }, 404))

    await wrapper.get('button[aria-label="Clear the pick on Die Nerven"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('HTTP 404')
    expect(wrapper.findAll('[data-testid="pick-row"]')).toHaveLength(2)
  })

  it('drops the row when the editor saves the pick cleared, and keeps it when moved ahead', async () => {
    const wrapper = await mountView()

    async function saveWithPick(title: string, value: string) {
      await wrapper
        .findAll('[data-testid="pick-title"]')
        .find((t) => t.text() === title)!
        .trigger('click')
      await flushPromises()
      const input = document.body.querySelector<HTMLInputElement>('input[name="featuredUntil"]')!
      input.value = value
      input.dispatchEvent(new Event('input'))
      await flushPromises()
      document.body.querySelector('form')!.dispatchEvent(new Event('submit'))
      await flushPromises()
    }

    await saveWithPick('Tocotronic', '2099-01-01T12:00')
    expect(wrapper.findAll('[data-testid="pick-until"]').map((c) => c.text())).toContain(
      '2099-01-01 12:00',
    )

    await saveWithPick('Die Nerven', '')
    expect(wrapper.findAll('[data-testid="pick-title"]').map((t) => t.text())).toEqual([
      'Tocotronic',
    ])
  })

  it('shows the error when the picks cannot be read', async () => {
    fetchMock.mockImplementation(async () => json({ detail: 'down' }, 500))
    const wrapper = await mountView()

    expect(wrapper.get('[role="alert"]').text()).toContain('GET /api/admin/events: HTTP 500')
  })
})
