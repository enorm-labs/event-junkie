import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import type { MusicBrainzCandidate, ReviewArtist } from '@/api/artists'
import type { AdminEvent } from '@/api/events'
import ArtistsView from '@/views/ArtistsView.vue'
import { adminEvent } from '@/lib/__tests__/adminEvent'
import { json } from '@/lib/__tests__/jsonResponse'

const LONG_NAME =
  'The Extraordinarily Long-Named Gentlemen of the Late-Night Neukölln Brass and String Collective'

function artist(id: number, name: string): ReviewArtist {
  return { id, name, slug: name.toLowerCase().replace(/\W+/g, '-'), musicbrainzMatch: 'AMBIGUOUS' }
}

function event(overrides: Partial<AdminEvent>): AdminEvent {
  return adminEvent({
    id: 1,
    venueId: 3,
    title: 'Accept live',
    slug: '2026-10-20-accept-live',
    eventDate: '2026-10-20',
    sourceId: 'venue:1',
    artists: [{ artistId: 9, role: 'HEADLINER', billingOrder: 0, stage: null }],
    ...overrides,
  })
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
    watchers: 0,
    ...overrides,
  }
}

let listed: ReviewArtist[] = []
let totalPages = 1
let billed: AdminEvent[] = []
let candidates: MusicBrainzCandidate[] = []
let candidateStatus = 200
let watchedRows: ReviewArtist[] = []
let matchedRows: ReviewArtist[] = []
let rejectStatus = 200
const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-14T10:00:00Z'))
  listed = [artist(9, 'Accept'), artist(10, LONG_NAME)]
  totalPages = 1
  billed = []
  candidates = []
  watchedRows = []
  candidateStatus = 200
  matchedRows = [
    { id: 1851, name: 'Wilco', slug: 'wilco', musicbrainzMatch: 'EXACT', musicbrainzId: 'band-1' },
  ]
  rejectStatus = 200
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url === '/api/admin/artists/watched') return json(watchedRows)
    if (
      method === 'GET' &&
      url.startsWith('/api/admin/artists?') &&
      url.includes('musicbrainzMatch=EXACT')
    ) {
      return json({ content: matchedRows, totalElements: matchedRows.length, totalPages: 1 })
    }
    if (method === 'DELETE' && url === '/api/admin/artists/1851/musicbrainz-id') {
      if (rejectStatus !== 200) return json({ detail: 'Artist 1851 not found' }, rejectStatus)
      return json({ ...matchedRows[0], musicbrainzMatch: 'AMBIGUOUS', musicbrainzId: null })
    }
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

  describe('the rows visitors wait for (#5214)', () => {
    it('lists them first with their count, and keeps them out of the regular list', async () => {
      watchedRows = [
        { ...artist(9, 'Accept'), watchers: 2 },
        { ...artist(30, 'Glasshouse'), watchers: 1 },
      ]
      const { wrapper } = await mountView()

      const watched = wrapper.find('[data-testid="watched"]')
      expect(watched.find('h2').text()).toBe('Visitors wait for these')
      const rows = watched.findAll('[data-testid="review-artist"]')
      expect(rows.map((r) => r.find('[data-testid="artist-watchers"]').text())).toEqual([
        '2 waiting',
        '1 waiting',
      ])
      const all = wrapper.findAll('[data-testid="review-artist"]').map((r) => r.find('a').text())
      expect(all).toEqual(['Accept', 'Glasshouse', LONG_NAME])
    })

    it('shows nothing extra when nobody waits, or on a later page', async () => {
      const { wrapper, router } = await mountView()
      expect(wrapper.find('[data-testid="watched"]').exists()).toBe(false)

      watchedRows = [{ ...artist(30, 'Glasshouse'), watchers: 1 }]
      totalPages = 3
      await router.push('/artists?page=1')
      await flushPromises()
      expect(wrapper.find('[data-testid="watched"]').exists()).toBe(false)
    })

    it('marks the candidate visitors picked', async () => {
      candidates = [candidate({}), candidate({ musicbrainzId: 'mb-2', watchers: 3 })]
      const { wrapper } = await mountView()

      await review(wrapper)

      const rows = wrapper.findAll('[data-testid="candidate"]')
      expect(rows[0]!.find('[data-testid="candidate-watchers"]').exists()).toBe(false)
      expect(rows[1]!.find('[data-testid="candidate-watchers"]').text()).toBe('3 waiting')
    })

    it('drops a watched row once it is matched, without lowering the count of the list', async () => {
      watchedRows = [{ ...artist(9, 'Accept'), watchers: 2 }]
      listed = [artist(10, LONG_NAME)]
      candidates = [candidate({})]
      const { wrapper } = await mountView()

      await review(wrapper)
      await wrapper.find('[data-testid="candidate"] button').trigger('click')
      await wrapper.find('[data-testid="confirm"] button').trigger('click')
      await flushPromises()

      expect(wrapper.find('[data-testid="watched"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="left-count"]').text()).toBe('121 to review')
    })
  })

  describe('matched artists', () => {
    async function searchWilco(wrapper: Wrapper) {
      await wrapper.find('#matched-name').setValue('wilco')
      await wrapper.find('[data-testid="matched-search"] form').trigger('submit')
      await flushPromises()
    }

    function deletes() {
      return fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')
    }

    it('lists the EXACT rows a name search finds, with both links', async () => {
      const { wrapper } = await mountView()

      await searchWilco(wrapper)

      const row = wrapper.find('[data-testid="matched-artist"]')
      expect(row.text()).toContain('Wilco')
      const links = row.findAll('a').map((a) => a.attributes('href'))
      expect(links[0]).toMatch(/\/en\/artists\/wilco$/)
      expect(links[1]).toBe('https://musicbrainz.org/artist/band-1')
      const search = fetchMock.mock.calls.find(([url]) => String(url).includes('EXACT'))!
      expect(new URL(String(search[0]), 'http://admin').searchParams.get('name')).toBe('wilco')
    })

    it('says so when the search finds nothing', async () => {
      matchedRows = []
      const { wrapper } = await mountView()

      await searchWilco(wrapper)

      expect(wrapper.find('[data-testid="no-matched"]').exists()).toBe(true)
    })

    it('asks for a confirmation, then DELETEs once and moves the row to the review list', async () => {
      const { wrapper } = await mountView()
      await searchWilco(wrapper)

      await wrapper.find('[data-testid="matched-artist"] button').trigger('click')
      expect(deletes()).toHaveLength(0)
      await wrapper.find('[data-testid="confirm-reject"] button').trigger('click')
      await flushPromises()

      expect(deletes()).toHaveLength(1)
      expect(wrapper.find('[data-testid="matched-artist"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="no-matched"]').exists()).toBe(false)
      expect(wrapper.findAll('[data-testid="review-artist"]')[0]!.text()).toContain('Wilco')
      expect(wrapper.find('[role="status"]').text()).toContain('Wilco is rejected')
      expect(wrapper.find('[data-testid="left-count"]').text()).toBe('122 to review')
    })

    it('Cancel sends nothing', async () => {
      const { wrapper } = await mountView()
      await searchWilco(wrapper)

      await wrapper.find('[data-testid="matched-artist"] button').trigger('click')
      await wrapper.findAll('[data-testid="confirm-reject"] button')[1]!.trigger('click')
      await flushPromises()

      expect(deletes()).toHaveLength(0)
      expect(wrapper.find('[data-testid="confirm-reject"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="matched-artist"]').exists()).toBe(true)
    })

    it('shows a failed rejection and keeps the row', async () => {
      rejectStatus = 404
      const { wrapper } = await mountView()
      await searchWilco(wrapper)

      await wrapper.find('[data-testid="matched-artist"] button').trigger('click')
      await wrapper.find('[data-testid="confirm-reject"] button').trigger('click')
      await flushPromises()

      expect(wrapper.find('[data-testid="confirm-reject"] [role="alert"]').text()).toContain(
        'Artist 1851 not found',
      )
      expect(wrapper.find('[data-testid="matched-artist"]').exists()).toBe(true)
    })
  })
})
