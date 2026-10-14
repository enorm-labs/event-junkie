import { afterEach, describe, expect, it, vi } from 'vitest'

import type { AdminEvent } from '../events'
import {
  applySuggestion,
  cannotApply,
  fetchSuggestions,
  listItems,
  type Suggestion,
  suggestionsUrl,
} from '../suggestions'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

// Every field the PUT carries holds a value, so a field the round trip drops fails a test.
const stored: AdminEvent = {
  id: 7,
  venueId: 3,
  title: 'Ash Rivers - Nova Lane',
  subtitle: 'Tour 2026',
  description: 'Einlass 19 Uhr, Beginn 20 Uhr. VVK 18 €.',
  eventType: 'OTHER',
  status: 'SCHEDULED',
  slug: '2026-10-24-ash-rivers',
  eventDate: '2026-10-24',
  doorsTime: '19:00:00',
  startTime: null,
  imageUrl: 'https://img.example/a.jpg',
  sourceUrl: 'https://venue.example/a',
  sourceId: 'venue:a',
  ticketUrl: 'https://tickets.example/a',
  facebookEventUrl: 'https://facebook.example/a',
  genre: ' Rock ',
  genreTags: ['Rock'],
  pricePresale: null,
  priceBoxOffice: 22,
  priceCurrency: 'EUR',
  priceNote: 'zzgl. Gebühren',
  soldOut: false,
  free: false,
  artists: [{ artistId: 11, role: 'HEADLINER', billingOrder: 0, stage: 'Hall' }],
  promoterIds: [5],
  pinnedFields: [],
}

function suggestion(overrides: Partial<Suggestion> = {}): Suggestion {
  return {
    id: 1,
    eventId: 7,
    eventTitle: stored.title,
    eventSlug: stored.slug,
    eventDate: stored.eventDate,
    venueName: 'Funkloch',
    field: 'startTime',
    storedValue: null,
    proposedValue: '20:00',
    evidence: 'Beginn 20 Uhr',
    confidence: 0.92,
    checkName: 'event-classification',
    model: 'claude-haiku-4-5',
    status: 'OPEN',
    createdAt: '2026-10-14T08:00:00Z',
    ...overrides,
  }
}

/** A fake importer: the event, two artists, and a PUT that pins what changed. */
function importer(pins: (body: Record<string, unknown>) => string[]) {
  return vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    if (method === 'GET' && url === '/api/admin/events/7') return json(stored)
    if (method === 'GET' && url === '/api/admin/artists/11') {
      return json({ id: 11, name: 'Ash Rivers', slug: 'ash-rivers' })
    }
    if (method === 'GET' && url.startsWith('/api/admin/artists?')) {
      const name = new URL(url, 'http://x').searchParams.get('name')
      // A part-of-the-name match comes first, so only the exact one may be taken.
      const rows =
        name === 'Nova Lane'
          ? [
              { id: 20, name: 'Nova Lane Trio', slug: 'nova-lane-trio' },
              { id: 21, name: 'nova lane', slug: 'nova-lane' },
            ]
          : []
      return json({ content: rows, totalElements: rows.length })
    }
    if (method === 'POST' && url === '/api/admin/artists') {
      return json({ id: 30, name: JSON.parse(String(init?.body)).name, slug: 'x' }, 201)
    }
    if (method === 'PUT' && url === '/api/admin/events/7') {
      const body = JSON.parse(String(init?.body))
      return json({ ...stored, ...body, pinnedFields: pins(body) })
    }
    if (method === 'POST' && url === '/api/admin/data-quality/suggestions/1/accept') {
      return json(suggestion({ status: 'ACCEPTED' }))
    }
    return json({ detail: `unexpected ${method} ${url}` }, 404)
  })
}

function putBody(fetchFn: ReturnType<typeof importer>) {
  const puts = fetchFn.mock.calls.filter(([, init]) => init?.method === 'PUT')
  expect(puts).toHaveLength(1)
  return JSON.parse(String(puts[0]![1]!.body))
}

function calls(fetchFn: ReturnType<typeof importer>) {
  return fetchFn.mock.calls.map(([input, init]) => `${init?.method ?? 'GET'} ${String(input)}`)
}

afterEach(() => vi.restoreAllMocks())

describe('suggestionsUrl', () => {
  it('asks for the open suggestions of one page, and the check when one is picked', () => {
    expect(suggestionsUrl({})).toBe(
      '/api/admin/data-quality/suggestions?status=OPEN&page=0&size=20',
    )
    expect(suggestionsUrl({ page: 2, checkName: 'event-classification' })).toBe(
      '/api/admin/data-quality/suggestions?status=OPEN&page=2&size=20&checkName=event-classification',
    )
  })

  it('reports the status and the detail of a refused list', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => json({ detail: 'Unknown status' }, 400))
    await expect(fetchSuggestions({}, fetchFn)).rejects.toThrow('HTTP 400 — Unknown status')
  })
})

describe('cannotApply', () => {
  it('accepts features and the fields the PUT takes, and nothing else', () => {
    expect(cannotApply('genre')).toContain('cannot apply')
    for (const field of [
      'features',
      'eventType',
      'artists',
      'startTime',
      'pricePresale',
      'priceBoxOffice',
    ]) {
      expect(cannotApply(field)).toBeNull()
    }
  })

  it('reads an empty list value as none', () => {
    expect(listItems('')).toEqual([])
    expect(listItems('Ash Rivers; Nova Lane')).toEqual(['Ash Rivers', 'Nova Lane'])
  })
})

describe('applySuggestion', () => {
  it('PUTs the event with only the proposed field changed, then accepts', async () => {
    const fetchFn = importer(() => ['startTime'])

    const saved = await applySuggestion(suggestion(), fetchFn)

    const { id: _id, slug: _slug, genreTags: _tags, pinnedFields: _pins, ...request } = stored
    expect(putBody(fetchFn)).toEqual({ ...request, startTime: '20:00' })
    expect((saved as AdminEvent).pinnedFields).toContain('startTime')
    expect(calls(fetchFn).slice(-1)[0]).toBe('POST /api/admin/data-quality/suggestions/1/accept')
  })

  it('sends a price as a number and a type as a type', async () => {
    const price = importer(() => ['pricePresale'])
    await applySuggestion(suggestion({ field: 'pricePresale', proposedValue: '18.00' }), price)
    expect(putBody(price).pricePresale).toBe(18)

    const type = importer(() => ['eventType'])
    await applySuggestion(suggestion({ field: 'eventType', proposedValue: 'CONCERT' }), type)
    expect(putBody(type).eventType).toBe('CONCERT')
  })

  it('keeps a billed act, takes the exact name match, and creates an unknown act', async () => {
    const fetchFn = importer(() => ['lineup'])

    await applySuggestion(
      suggestion({ field: 'artists', proposedValue: 'Ash Rivers; Nova Lane; Mira Kett' }),
      fetchFn,
    )

    expect(putBody(fetchFn).artists).toEqual([
      { artistId: 11, role: 'HEADLINER', billingOrder: 0, stage: 'Hall' },
      { artistId: 21, role: 'SUPPORT', billingOrder: 1, stage: null },
      { artistId: 30, role: 'SUPPORT', billingOrder: 2, stage: null },
    ])
    expect(calls(fetchFn)).toContain('POST /api/admin/artists')
  })

  it('empties the lineup for an empty artists value', async () => {
    const fetchFn = importer(() => ['lineup'])
    await applySuggestion(suggestion({ field: 'artists', proposedValue: '' }), fetchFn)
    expect(putBody(fetchFn).artists).toEqual([])
  })

  it('leaves the suggestion open when the PUT pins nothing', async () => {
    const fetchFn = importer(() => [])

    await expect(applySuggestion(suggestion(), fetchFn)).rejects.toThrow('pinned no "startTime"')
    expect(calls(fetchFn).some((c) => c.endsWith('/accept'))).toBe(false)
  })

  it('refuses a malformed time without a PUT', async () => {
    const fetchFn = importer(() => ['startTime'])
    await expect(applySuggestion(suggestion({ proposedValue: '8 pm' }), fetchFn)).rejects.toThrow(
      'is no time',
    )
    expect(calls(fetchFn).some((c) => c.startsWith('PUT'))).toBe(false)
  })
})

describe('applySuggestion for features', () => {
  /** A fake importer whose event holds [stored] features. */
  function featureImporter(stored: string[]) {
    return vi.fn<typeof fetch>(async (input, init) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      if (url === '/api/admin/events/7/features') {
        const slugs = method === 'PUT' ? JSON.parse(String(init?.body)).features : stored
        const entries = slugs.map((feature: string) => ({ feature, phrase: '' }))
        return json({ eventId: 7, features: entries, uncertain: [], pinned: method === 'PUT' })
      }
      if (method === 'POST' && url === '/api/admin/data-quality/suggestions/1/accept') {
        return json(suggestion({ field: 'features', status: 'ACCEPTED' }))
      }
      return json({ detail: `unexpected ${method} ${url}` }, 404)
    })
  }

  function features(overrides: Partial<Suggestion>) {
    return suggestion({ field: 'features', storedValue: 'queer', ...overrides })
  }

  it('PUTs the proposed set, then accepts', async () => {
    const fetchFn = featureImporter(['queer'])

    await applySuggestion(features({ proposedValue: 'flinta-only; queer' }), fetchFn)

    expect(calls(fetchFn)).toEqual([
      'GET /api/admin/events/7/features',
      'PUT /api/admin/events/7/features',
      'POST /api/admin/data-quality/suggestions/1/accept',
    ])
    const put = fetchFn.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(String(put[1]!.body))).toEqual({ features: ['flinta-only', 'queer'] })
  })

  it('reads a null stored value as no features', async () => {
    const fetchFn = featureImporter([])
    await applySuggestion(features({ storedValue: null, proposedValue: 'open-end' }), fetchFn)
    expect(calls(fetchFn).slice(-1)[0]).toBe('POST /api/admin/data-quality/suggestions/1/accept')
  })

  it('leaves the suggestion open when the stored features changed', async () => {
    const fetchFn = featureImporter(['queer', 'day-party'])

    await expect(
      applySuggestion(features({ proposedValue: 'flinta-only; queer' }), fetchFn),
    ).rejects.toThrow('changed since the check ran')
    expect(calls(fetchFn)).toEqual(['GET /api/admin/events/7/features'])
  })

  it('refuses an unknown slug before it writes', async () => {
    const fetchFn = featureImporter(['queer'])

    await expect(
      applySuggestion(features({ proposedValue: 'queer; darkroom' }), fetchFn),
    ).rejects.toThrow('"darkroom" is no party feature')
    expect(calls(fetchFn).some((c) => !c.startsWith('GET'))).toBe(false)
  })
})
