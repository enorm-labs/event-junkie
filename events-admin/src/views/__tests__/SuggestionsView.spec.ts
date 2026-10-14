import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import type { AdminEvent } from '@/api/events'
import type { Suggestion } from '@/api/suggestions'
import SuggestionsView from '@/views/SuggestionsView.vue'

function suggestion(overrides: Partial<Suggestion>): Suggestion {
  return {
    id: 1,
    eventId: 7,
    eventTitle: 'Ash Rivers',
    eventSlug: '2026-10-24-ash-rivers',
    eventDate: '2026-10-24',
    venueName: 'Funkloch',
    field: 'startTime',
    storedValue: null,
    proposedValue: '20:00',
    evidence: 'Einlass 19 Uhr, Beginn 20 Uhr',
    confidence: 0.92,
    checkName: 'event-classification',
    model: 'claude-haiku-4-5',
    status: 'OPEN',
    createdAt: '2026-10-14T08:00:00Z',
    ...overrides,
  }
}

const event: AdminEvent = {
  id: 7,
  venueId: 3,
  title: 'Ash Rivers',
  subtitle: null,
  description: 'Einlass 19 Uhr, Beginn 20 Uhr',
  eventType: 'CONCERT',
  status: 'SCHEDULED',
  slug: '2026-10-24-ash-rivers',
  eventDate: '2026-10-24',
  doorsTime: null,
  startTime: null,
  imageUrl: null,
  sourceUrl: null,
  sourceId: 'venue:a',
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
}

let listed: Suggestion[] = []
let acceptStatus = 200
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  listed = []
  acceptStatus = 200
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url.startsWith('/api/admin/data-quality/suggestions?')) {
      return json({ content: listed, totalElements: listed.length, totalPages: 1 })
    }
    if (method === 'GET' && url === '/api/admin/events/7') return json(event)
    if (method === 'PUT' && url === '/api/admin/events/7') {
      return json({ ...event, ...JSON.parse(String(init?.body)), pinnedFields: ['startTime'] })
    }
    const decided = /^\/api\/admin\/data-quality\/suggestions\/(\d+)\/(accept|dismiss)$/.exec(url)
    if (method === 'POST' && decided) {
      if (decided[2] === 'accept' && acceptStatus !== 200) {
        return json({ detail: 'Suggestion with id 1 is dismissed and cannot be accepted' }, 409)
      }
      return json({ ...listed.find((s) => s.id === Number(decided[1])), status: 'X' })
    }
    return json({ detail: `unexpected ${method} ${url}` }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  fetchMock.mockReset()
})

async function mountView(path = '/suggestions') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/suggestions', component: SuggestionsView }],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(SuggestionsView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountView>>

function button(wrapper: Wrapper, label: string) {
  const found = wrapper.find(`button[aria-label="${label}"]`)
  expect(found.exists(), `no button "${label}"`).toBe(true)
  return found
}

function methods() {
  return fetchMock.mock.calls.map(([input, init]) => `${init?.method ?? 'GET'} ${String(input)}`)
}

describe('SuggestionsView', () => {
  it('lists each open suggestion with its event, values, evidence, confidence, check and model', async () => {
    listed = [
      suggestion({}),
      suggestion({
        id: 2,
        eventId: 8,
        eventTitle: 'Nova Lane',
        field: 'artists',
        storedValue: 'Nova Lane Tour',
        proposedValue: 'Nova Lane',
        confidence: 0.8,
        model: null,
      }),
    ]

    const wrapper = await mountView()

    const cards = wrapper.findAll('[data-testid="suggestion"]')
    expect(cards).toHaveLength(2)
    const first = cards[0]!.text()
    for (const part of [
      'Ash Rivers',
      '2026-10-24',
      'Funkloch',
      'startTime',
      'Beginn 20 Uhr',
      '92 %',
      'event-classification',
      'claude-haiku-4-5',
    ]) {
      expect(first).toContain(part)
    }
    expect(cards[0]!.find('[data-testid="stored"]').text()).toBe('empty')
    expect(cards[0]!.find('[data-testid="proposed"]').text()).toBe('20:00')
    expect(cards[0]!.find('a').attributes('href')).toBe(
      'https://event-junkie.de/en/events/2026-10-24-ash-rivers',
    )
    expect(cards[1]!.find('[data-testid="stored"]').text()).toBe('Nova Lane Tour')
    expect(cards[1]!.text()).toContain('no model')
    expect(wrapper.find('[data-testid="open-count"]').text()).toBe('2 open')
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe(
      '/api/admin/data-quality/suggestions?status=OPEN&page=0&size=20',
    )
  })

  it('passes the check filter from the query string', async () => {
    await mountView('/suggestions?check=event-classification&page=1')
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe(
      '/api/admin/data-quality/suggestions?status=OPEN&page=1&size=20&checkName=event-classification',
    )
  })

  it('says so when nothing is open, and shows a failed list plainly', async () => {
    const empty = await mountView()
    expect(empty.find('[data-testid="no-suggestions"]').exists()).toBe(true)
    empty.unmount()

    fetchMock.mockImplementationOnce(async () => json({ detail: 'importer down' }, 502))
    const failed = await mountView()
    expect(failed.find('[role="alert"]').text()).toContain('HTTP 502 — importer down')
  })

  it('Accept PUTs the event with the proposed value, then accepts, and drops the card', async () => {
    listed = [suggestion({})]
    const wrapper = await mountView()

    await button(wrapper, 'Accept startTime for Ash Rivers').trigger('click')
    await flushPromises()

    expect(methods().slice(1)).toEqual([
      'GET /api/admin/events/7',
      'PUT /api/admin/events/7',
      'POST /api/admin/data-quality/suggestions/1/accept',
    ])
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')
    expect(JSON.parse(String(put![1]!.body)).startTime).toBe('20:00')
    expect(wrapper.findAll('[data-testid="suggestion"]')).toHaveLength(0)
    expect(wrapper.find('[role="status"]').text()).toContain('saved and pinned')
    expect(wrapper.find('[data-testid="open-count"]').text()).toBe('0 open')
  })

  it('shows a refused accept in its card and keeps the card', async () => {
    listed = [suggestion({})]
    acceptStatus = 409
    const wrapper = await mountView()

    await button(wrapper, 'Accept startTime for Ash Rivers').trigger('click')
    await flushPromises()

    const card = wrapper.find('[data-testid="suggestion"]')
    expect(card.find('[data-testid="failure"]').text()).toContain(
      'HTTP 409 — Suggestion with id 1 is dismissed and cannot be accepted',
    )
  })

  it('Reject dismisses the suggestion and writes nothing to the event', async () => {
    listed = [suggestion({}), suggestion({ id: 2, eventTitle: 'Nova Lane' })]
    const wrapper = await mountView()

    await button(wrapper, 'Reject startTime for Ash Rivers').trigger('click')
    await flushPromises()

    expect(methods().slice(1)).toEqual(['POST /api/admin/data-quality/suggestions/1/dismiss'])
    const cards = wrapper.findAll('[data-testid="suggestion"]')
    expect(cards).toHaveLength(1)
    expect(cards[0]!.text()).toContain('Nova Lane')
  })

  it('turns Accept on for a features suggestion', async () => {
    listed = [suggestion({ field: 'features', proposedValue: 'flinta-only' })]
    const wrapper = await mountView()

    const accept = button(wrapper, 'Accept features for Ash Rivers')
    expect(accept.attributes('disabled')).toBeUndefined()
    expect(accept.attributes('aria-describedby')).toBeUndefined()
  })
})
