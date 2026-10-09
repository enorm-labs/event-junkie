import { describe, expect, it, vi } from 'vitest'

import { type AdminEvent, toEventRequest, unpinField, updateEvent } from '../events'

function adminEvent(overrides: Partial<AdminEvent> = {}): AdminEvent {
  return {
    id: 418,
    venueId: 7,
    title: 'Die Nerven',
    subtitle: 'Tour 2026',
    description: 'Noise rock from Stuttgart.',
    eventType: 'OTHER',
    status: 'SCHEDULED',
    slug: '2026-09-12-badehaus-die-nerven',
    eventDate: '2026-09-12',
    doorsTime: '19:00:00',
    startTime: '20:00:00',
    imageUrl: 'https://example.org/poster.jpg',
    sourceUrl: 'https://example.org/event',
    sourceId: 'badehaus:die-nerven',
    ticketUrl: null,
    facebookEventUrl: null,
    genre: null,
    genreTags: [],
    pricePresale: 25,
    priceBoxOffice: 30.5,
    priceCurrency: 'EUR',
    priceNote: null,
    soldOut: false,
    free: false,
    artists: [{ artistId: 3, role: 'HEADLINER', billingOrder: 0, stage: null }],
    promoterIds: [9],
    pinnedFields: [],
    ...overrides,
  }
}

describe('toEventRequest', () => {
  it('sends every stored value back, with only the edited fields changed', () => {
    const event = adminEvent()

    const request = toEventRequest(event, { eventType: 'CONCERT', genre: '  Noise Rock ' })

    const { id: _id, slug: _slug, genreTags: _tags, pinnedFields: _pins, ...stored } = event
    expect(request).toEqual({ ...stored, eventType: 'CONCERT', genre: 'Noise Rock' })
  })

  it('sends a blank genre as null', () => {
    expect(
      toEventRequest(adminEvent({ genre: 'Punk' }), { eventType: 'OTHER', genre: ' ' }).genre,
    ).toBeNull()
  })

  it('sends the lineup with only the fields EventArtistRequest has', () => {
    const event = adminEvent()
    // The response also carries set times, which the request has no field for.
    Object.assign(event.artists[0]!, { setStart: '2026-09-12T19:00:00Z', setEnd: null })

    expect(toEventRequest(event, { eventType: 'OTHER', genre: '' }).artists).toEqual([
      { artistId: 3, role: 'HEADLINER', billingOrder: 0, stage: null },
    ])
  })
})

describe('toEventRequest with a lineup and promoter edit', () => {
  it('sends the edited lineup and promoters in place of the stored ones', () => {
    const request = toEventRequest(adminEvent(), {
      eventType: 'OTHER',
      genre: '',
      artists: [
        { artistId: 3, role: 'SUPPORT', billingOrder: 0, stage: null },
        { artistId: 12, role: 'HEADLINER', billingOrder: 1, stage: 'Panorama Bar' },
      ],
      promoterIds: [],
    })

    expect(request.artists).toEqual([
      { artistId: 3, role: 'SUPPORT', billingOrder: 0, stage: null },
      { artistId: 12, role: 'HEADLINER', billingOrder: 1, stage: 'Panorama Bar' },
    ])
    expect(request.promoterIds).toEqual([])
    expect(request.title).toBe('Die Nerven')
  })

  it('sends the stored lineup and promoters when the edit leaves them out', () => {
    const request = toEventRequest(adminEvent(), { eventType: 'OTHER', genre: '' })

    expect(request.artists).toEqual([
      { artistId: 3, role: 'HEADLINER', billingOrder: 0, stage: null },
    ])
    expect(request.promoterIds).toEqual([9])
  })
})

describe('updateEvent', () => {
  it('PUTs the request as JSON to the event', async () => {
    const saved = adminEvent({ eventType: 'CONCERT', pinnedFields: ['eventType'] })
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(JSON.stringify(saved)))
    const request = toEventRequest(adminEvent(), { eventType: 'CONCERT', genre: '' })

    await expect(updateEvent(418, request, fetchFn)).resolves.toEqual(saved)

    const [url, init] = fetchFn.mock.calls[0]!
    expect(url).toBe('/api/admin/events/418')
    expect(init?.method).toBe('PUT')
    expect(JSON.parse(String(init?.body))).toEqual(request)
  })

  it('names the Problem Detail of a refused edit', async () => {
    const fetchFn = vi.fn<typeof fetch>(
      async () =>
        new Response(JSON.stringify({ detail: 'Venue with id 7 not found' }), { status: 404 }),
    )
    const request = toEventRequest(adminEvent(), { eventType: 'OTHER', genre: '' })

    await expect(updateEvent(418, request, fetchFn)).rejects.toThrow(
      'PUT /api/admin/events/418: HTTP 404 — Venue with id 7 not found',
    )
  })
})

describe('unpinField', () => {
  it('DELETEs the pin by its field key', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(null, { status: 204 }))

    await unpinField(418, 'eventType', fetchFn)

    expect(fetchFn).toHaveBeenCalledWith('/api/admin/events/418/pins/eventType', {
      method: 'DELETE',
    })
  })

  it('reports an unknown field', async () => {
    const fetchFn = vi.fn<typeof fetch>(
      async () =>
        new Response(JSON.stringify({ detail: "Unknown pinned field 'x'" }), { status: 400 }),
    )

    await expect(unpinField(418, 'x', fetchFn)).rejects.toThrow("Unknown pinned field 'x'")
  })
})
