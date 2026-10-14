import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import type { MusicBrainzCandidate, ReviewArtist } from '@/api/artists'
import type { AdminEvent } from '@/api/events'
import ArtistsView from '@/views/ArtistsView.vue'

const LONG_NAME =
  'The Extraordinarily Long-Named Gentlemen of the Late-Night Neukölln Brass and String Collective'

function artist(id: number, name: string): ReviewArtist {
  return { id, name, slug: name.toLowerCase().replace(/\W+/g, '-'), musicbrainzMatch: 'AMBIGUOUS' }
}

function event(overrides: Partial<AdminEvent>): AdminEvent {
  return {
    id: 1,
    venueId: 3,
    title: 'Accept live',
    subtitle: null,
    description: null,
    eventType: 'CONCERT',
    status: 'SCHEDULED',
    slug: '2026-10-20-accept-live',
    eventDate: '2026-10-20',
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
    artists: [{ artistId: 9, role: 'HEADLINER', billingOrder: 0, stage: null }],
    promoterIds: [],
    pinnedFields: [],
    ...overrides,
  }
}

function candidate(overrides: Partial<MusicBrainzCandidate>): MusicBrainzCandidate {
  return {
    musicbrainzId: 'mb-1',
    name: 'Accept',
    nameMatch: 'NAME',
    type: 'Group',
    disambiguation: 'German heavy metal band',
    country: 'DE',
    area: 'Solingen',
    founded: '1976',
    dissolved: null,
    url: 'https://musicbrainz.org/artist/mb-1',
    ...overrides,
  }
}

let listed: ReviewArtist[] = []
let totalPages = 1
let billed: AdminEvent[] = []
let candidates: MusicBrainzCandidate[] = []
let candidateStatus = 200
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-14T10:00:00Z'))
  listed = [artist(9, 'Accept'), artist(10, LONG_NAME)]
  totalPages = 1
  billed = []
  candidates = []
  candidateStatus = 200
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url.startsWith('/api/admin/artists?')) {
      return json({ content: listed, totalElements: 121, totalPages })
    }
    if (method === 'GET' && url.startsWith('/api/admin/venues?')) {
      return json({
        content: [{ id: 3, name: 'Columbia Theater', slug: 'columbia-theater', closedOn: null }],
        totalElements: 1,
      })
    }
    if (method === 'GET' && url.startsWith('/api/admin/events?')) {
      return json({ content: billed, totalElements: billed.length })
    }
    if (method === 'GET' && url === '/api/admin/artists/11') {
      return json({ id: 11, name: 'Sodom', slug: 'sodom' })
    }
    if (method === 'GET' && /^\/api\/admin\/artists\/\d+\/musicbrainz-candidates$/.test(url)) {
      if (candidateStatus === 503) {
        return json(
          { status: 503, detail: 'MusicBrainz did not answer. Try again in a minute.' },
          503,
        )
      }
      return json(candidates)
    }
    if (method === 'PUT' && url === '/api/admin/artists/9/musicbrainz-id') {
      return json({ ...listed[0], musicbrainzMatch: 'EXACT' })
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

async function mountView(path = '/artists') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/artists', component: ArtistsView }],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(ArtistsView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

type Wrapper = Awaited<ReturnType<typeof mountView>>['wrapper']

function button(wrapper: Wrapper, label: string) {
  const found = wrapper.find(`button[aria-label="${label}"]`)
  expect(found.exists(), `no button "${label}"`).toBe(true)
  return found
}

function calls() {
  return fetchMock.mock.calls.map(([input, init]) => `${init?.method ?? 'GET'} ${String(input)}`)
}

async function review(wrapper: Wrapper, name = 'Accept') {
  await button(wrapper, `Review ${name}`).trigger('click')
  await flushPromises()
}

describe('ArtistsView', () => {
  it('lists the AMBIGUOUS artists of the next 14 days with the total left', async () => {
    const { wrapper } = await mountView()

    const rows = wrapper.findAll('[data-testid="review-artist"]')
    expect(rows.map((r) => r.find('h2').text())).toEqual(['Accept', LONG_NAME])
    expect(rows[0]!.find('a').attributes('href')).toBe('https://event-junkie.de/en/artists/accept')
    expect(wrapper.find('[data-testid="left-count"]').text()).toBe('121 to review')
    expect(calls()[0]).toBe(
      'GET /api/admin/artists?musicbrainzMatch=AMBIGUOUS&upcomingWithinDays=14&page=0&size=20&sort=name%2Casc',
    )
    // Nothing is asked of MusicBrainz until a row opens.
    expect(calls().some((c) => c.includes('musicbrainz-candidates'))).toBe(false)
  })

  it('pages through the list by the query string', async () => {
    totalPages = 7
    const { wrapper, router } = await mountView()
    expect(button(wrapper, 'Previous page').attributes('disabled')).toBeDefined()

    await button(wrapper, 'Next page').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.page).toBe('1')
    expect(
      calls()
        .filter((c) => c.startsWith('GET /api/admin/artists?'))
        .pop(),
    ).toContain('page=1')

    const last = await mountView('/artists?page=6')
    expect(button(last.wrapper, 'Next page').attributes('disabled')).toBeDefined()
  })

  it('says so when nothing is left, and shows a failed list plainly', async () => {
    listed = []
    const empty = await mountView()
    expect(empty.wrapper.find('[data-testid="no-artists"]').exists()).toBe(true)
    empty.wrapper.unmount()

    fetchMock.mockImplementationOnce(async () => json({ detail: 'importer down' }, 502))
    const failed = await mountView()
    expect(failed.wrapper.find('[role="alert"]').text()).toContain('HTTP 502 — importer down')
  })

  it('shows the events from today on with the date, venue, title and the other acts', async () => {
    billed = [
      event({
        id: 2,
        eventDate: '2026-10-21',
        title: 'Metal Night',
        slug: '2026-10-21-metal-night',
        artists: [
          { artistId: 11, role: 'HEADLINER', billingOrder: 0, stage: null },
          { artistId: 9, role: 'SUPPORT', billingOrder: 1, stage: null },
        ],
      }),
      // Starts at 23:30 today and ends after midnight: listed under today.
      event({ id: 1, eventDate: '2026-10-14', startTime: '23:30:00', venueId: 404 }),
      event({ id: 0, eventDate: '2026-10-13', title: 'Yesterday' }),
    ]
    const { wrapper } = await mountView()

    await review(wrapper)

    const rows = wrapper.findAll('[data-testid="billed-event"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('2026-10-14')
    expect(rows[0]!.find('[data-testid="start-time"]').text()).toBe('23:30')
    expect(rows[0]!.text()).toContain('Venue #404')
    expect(rows[0]!.find('[data-testid="other-acts"]').text()).toBe('No other act')
    expect(rows[1]!.text()).toContain('2026-10-21')
    expect(rows[1]!.text()).toContain('Columbia Theater')
    expect(rows[1]!.text()).toContain('Metal Night')
    expect(rows[1]!.find('[data-testid="other-acts"]').text()).toBe('With Sodom')
    expect(calls()).toContain(
      'GET /api/admin/events?artistId=9&page=0&size=100&sort=eventDate%2Cdesc',
    )
  })

  it('shows each candidate with its facts and link, and none chosen', async () => {
    candidates = [
      candidate({}),
      candidate({
        musicbrainzId: 'mb-2',
        name: 'Accept',
        nameMatch: 'OTHER_NAME',
        type: null,
        disambiguation: null,
        country: null,
        area: null,
        founded: null,
      }),
    ]
    const { wrapper } = await mountView()

    await review(wrapper)

    const rows = wrapper.findAll('[data-testid="candidate"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('German heavy metal band')
    expect(rows[0]!.find('[data-testid="candidate-facts"]').text()).toBe(
      'Group · DE · Solingen · 1976–',
    )
    expect(rows[0]!.find('a').attributes('href')).toBe('https://musicbrainz.org/artist/mb-1')
    expect(rows[1]!.text()).toContain('alias')
    expect(rows[1]!.find('[data-testid="candidate-facts"]').text()).toBe('No type')
    expect(wrapper.find('[data-testid="confirm"]').exists()).toBe(false)
  })

  it('says so when MusicBrainz finds no candidate', async () => {
    const { wrapper } = await mountView()
    await review(wrapper)
    expect(wrapper.find('[data-testid="no-candidates"]').text()).toContain('stays AMBIGUOUS')
  })

  it('shows the importer message when MusicBrainz does not answer', async () => {
    candidateStatus = 503
    const { wrapper } = await mountView()

    await review(wrapper)

    expect(wrapper.find('[role="alert"]').text()).toContain(
      'HTTP 503 — MusicBrainz did not answer. Try again in a minute.',
    )
  })

  it('asks for a confirmation, then PUTs the MBID and drops the row', async () => {
    candidates = [candidate({}), candidate({ musicbrainzId: 'mb-2', name: 'Accept (US)' })]
    const { wrapper } = await mountView()
    await review(wrapper)

    await button(wrapper, 'Choose Accept for Accept').trigger('click')
    expect(wrapper.find('[data-testid="confirm"]').text()).toContain('German heavy metal band')
    expect(calls().some((c) => c.startsWith('PUT'))).toBe(false)

    await wrapper.find('[data-testid="confirm"] button').trigger('click')
    await flushPromises()

    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(String(put[0])).toBe('/api/admin/artists/9/musicbrainz-id')
    expect(JSON.parse(String(put[1]?.body))).toEqual({ musicbrainzId: 'mb-1' })
    expect(
      wrapper.findAll('[data-testid="review-artist"]').map((r) => r.find('h2').text()),
    ).toEqual([LONG_NAME])
    expect(wrapper.find('[role="status"]').text()).toBe(
      'Accept is matched to Accept on MusicBrainz.',
    )
    expect(wrapper.find('[data-testid="left-count"]').text()).toBe('120 to review')
  })

  it('Cancel drops the choice and writes nothing', async () => {
    candidates = [candidate({})]
    const { wrapper } = await mountView()
    await review(wrapper)

    await button(wrapper, 'Choose Accept for Accept').trigger('click')
    const cancel = wrapper.findAll('[data-testid="confirm"] button')[1]!
    await cancel.trigger('click')

    expect(wrapper.find('[data-testid="confirm"]').exists()).toBe(false)
    expect(calls().some((c) => c.startsWith('PUT'))).toBe(false)
    expect(wrapper.findAll('[data-testid="review-artist"]')).toHaveLength(2)
  })
})
