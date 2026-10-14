import { describe, expect, it, vi } from 'vitest'

import {
  berlinToday,
  fetchCandidates,
  fetchReviewPage,
  fetchUpcomingEvents,
  lifeSpan,
  type MusicBrainzCandidate,
  setMusicBrainzId,
} from '../artists'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function candidate(overrides: Partial<MusicBrainzCandidate> = {}): MusicBrainzCandidate {
  return {
    musicbrainzId: 'a1',
    name: 'Accept',
    nameMatch: 'NAME',
    type: 'Group',
    disambiguation: null,
    country: 'DE',
    area: 'Solingen',
    founded: null,
    dissolved: null,
    url: 'https://musicbrainz.org/artist/a1',
    ...overrides,
  }
}

describe('fetchReviewPage', () => {
  it('asks for one page of the AMBIGUOUS artists billed in the next 14 days', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () =>
      json({ content: [], totalElements: 121, totalPages: 7 }),
    )

    const page = await fetchReviewPage(6, fetchFn)

    expect(page.totalElements).toBe(121)
    expect(String(fetchFn.mock.calls[0]?.[0])).toBe(
      '/api/admin/artists?musicbrainzMatch=AMBIGUOUS&upcomingWithinDays=14&page=6&size=20&sort=name%2Casc',
    )
  })
})

describe('fetchCandidates', () => {
  it('returns the candidates as the importer orders them', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () =>
      json([candidate(), candidate({ musicbrainzId: 'a2' })]),
    )

    const found = await fetchCandidates(9, fetchFn)

    expect(found.map((c) => c.musicbrainzId)).toEqual(['a1', 'a2'])
    expect(String(fetchFn.mock.calls[0]?.[0])).toBe('/api/admin/artists/9/musicbrainz-candidates')
  })

  it('throws the importer message on a 503', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () =>
      json({ status: 503, detail: 'MusicBrainz did not answer. Try again in a minute.' }, 503),
    )

    await expect(fetchCandidates(9, fetchFn)).rejects.toThrow(
      'HTTP 503 — MusicBrainz did not answer. Try again in a minute.',
    )
  })
})

describe('setMusicBrainzId', () => {
  it('PUTs only the MBID', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () =>
      json({ id: 9, name: 'Accept', slug: 'accept', musicbrainzMatch: 'EXACT' }),
    )

    const saved = await setMusicBrainzId(9, 'a1', fetchFn)

    expect(saved.musicbrainzMatch).toBe('EXACT')
    const [url, init] = fetchFn.mock.calls[0]!
    expect(String(url)).toBe('/api/admin/artists/9/musicbrainz-id')
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(String(init?.body))).toEqual({ musicbrainzId: 'a1' })
  })

  it('throws on a refusal', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => json({ detail: 'Artist 9 not found' }, 404))
    await expect(setMusicBrainzId(9, 'a1', fetchFn)).rejects.toThrow(
      'HTTP 404 — Artist 9 not found',
    )
  })
})

describe('fetchUpcomingEvents', () => {
  it('keeps the events from today on, soonest first, and counts an event over midnight on its start day', async () => {
    const event = (id: number, eventDate: string) => ({ id, eventDate, artists: [] })
    const fetchFn = vi.fn<typeof fetch>(async () =>
      json({
        content: [
          event(4, '2026-10-20'),
          // Starts at 23:00 on the 14th and ends after midnight: it is the 14th's.
          event(3, '2026-10-14'),
          // Started on the 13th and runs past midnight into today: it is the 13th's, so past.
          event(2, '2026-10-13'),
          event(1, '2026-09-01'),
        ],
        totalElements: 4,
      }),
    )

    const events = await fetchUpcomingEvents(9, '2026-10-14', fetchFn)

    expect(events.map((e) => e.id)).toEqual([3, 4])
    expect(String(fetchFn.mock.calls[0]?.[0])).toBe(
      '/api/admin/events?artistId=9&page=0&size=100&sort=eventDate%2Cdesc',
    )
  })
})

describe('berlinToday', () => {
  it('is the Berlin calendar day, not the UTC one', () => {
    expect(berlinToday(new Date('2026-10-14T22:30:00Z'))).toBe('2026-10-15')
    expect(berlinToday(new Date('2026-10-14T21:30:00Z'))).toBe('2026-10-14')
  })
})

describe('lifeSpan', () => {
  it('shows the dates MusicBrainz has, and nothing without them', () => {
    expect(lifeSpan(candidate({ founded: '1976', dissolved: '1989' }))).toBe('1976–1989')
    expect(lifeSpan(candidate({ founded: '1976' }))).toBe('1976–')
    expect(lifeSpan(candidate({ dissolved: '1989' }))).toBe('–1989')
    expect(lifeSpan(candidate())).toBeNull()
  })
})
