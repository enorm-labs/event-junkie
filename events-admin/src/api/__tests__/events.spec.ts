import { describe, expect, it, vi } from 'vitest'

import {
  type AdminEvent,
  createEvent,
  emptyNewEventForm,
  fetchEventsOn,
  manualSourceId,
  type NewEventForm,
  parsePrice,
  slugify,
  toCreateRequest,
  toEventRequest,
  unpinField,
  updateEvent,
} from '../events'

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

describe('slugify', () => {
  it('strips accents and keeps letters NFD cannot split, as SlugGenerator does', () => {
    expect(slugify('Die Ärzte & Co.')).toBe('die-arzte-co')
    expect(slugify('Kėkė Søl')).toBe('keke-sol')
    expect(slugify('Revaler Straße')).toBe('revaler-strasse')
    expect(slugify('  --Open Decks!!-- ')).toBe('open-decks')
  })
})

describe('manualSourceId', () => {
  it('is manual:<venueSlug>:<eventDate>-<title slug>', () => {
    expect(manualSourceId('kulturhaus-x', '2026-11-14', 'Ørlög live')).toBe(
      'manual:kulturhaus-x:2026-11-14-orlog-live',
    )
  })

  it('stays within the 255 characters EventRequest allows', () => {
    const id = manualSourceId('venue', '2026-11-14', 'a b '.repeat(200))
    expect(id.length).toBeLessThanOrEqual(255)
    expect(id).toMatch(/^manual:venue:2026-11-14-a-b-a/)
    expect(id.endsWith('-')).toBe(false)
  })
})

describe('parsePrice', () => {
  it('reads a decimal comma or point, and blank as null', () => {
    expect(parsePrice('12,50')).toBe(12.5)
    expect(parsePrice(' 8 ')).toBe(8)
    expect(parsePrice('')).toBeNull()
  })

  it('refuses text that is not a price', () => {
    expect(() => parsePrice('ten')).toThrow('"ten" is not a price.')
    expect(() => parsePrice('-3')).toThrow('"-3" is not a price.')
  })
})

function newEventForm(overrides: Partial<NewEventForm> = {}): NewEventForm {
  return {
    ...emptyNewEventForm(),
    venueId: 7,
    title: ' Open Decks ',
    eventDate: '2026-11-14',
    ...overrides,
  }
}

describe('toCreateRequest', () => {
  it('builds a full EventRequest from the form', () => {
    const request = toCreateRequest(
      newEventForm({
        doorsTime: '19:00',
        startTime: '20:00',
        eventType: 'PARTY',
        genre: ' Techno ',
        ticketUrl: 'https://tickets.example.org/1',
        sourceUrl: 'https://www.instagram.com/p/abc/',
        pricePresale: '10',
        priceBoxOffice: '12,5',
      }),
      'kulturhaus-x',
    )

    expect(request).toEqual({
      venueId: 7,
      title: 'Open Decks',
      subtitle: null,
      description: null,
      eventType: 'PARTY',
      status: 'SCHEDULED',
      eventDate: '2026-11-14',
      doorsTime: '19:00',
      startTime: '20:00',
      imageUrl: null,
      sourceUrl: 'https://www.instagram.com/p/abc/',
      sourceId: 'manual:kulturhaus-x:2026-11-14-open-decks',
      ticketUrl: 'https://tickets.example.org/1',
      facebookEventUrl: null,
      genre: 'Techno',
      pricePresale: 10,
      priceBoxOffice: 12.5,
      priceCurrency: 'EUR',
      priceNote: null,
      soldOut: false,
      free: false,
      artists: [],
      promoterIds: [],
    })
  })

  it('sends blank optional fields as null', () => {
    const request = toCreateRequest(newEventForm({ free: true }), 'kulturhaus-x')

    expect(request).toMatchObject({
      doorsTime: null,
      startTime: null,
      genre: null,
      ticketUrl: null,
      sourceUrl: null,
      pricePresale: null,
      priceBoxOffice: null,
      free: true,
    })
  })

  it('refuses a form without a venue, a title or a date', () => {
    expect(() => toCreateRequest(newEventForm({ venueId: null }), 'x')).toThrow('Pick a venue.')
    expect(() => toCreateRequest(newEventForm({ title: ' ' }), 'x')).toThrow('Enter a title.')
    expect(() => toCreateRequest(newEventForm({ eventDate: '' }), 'x')).toThrow('Enter a date.')
  })
})

describe('createEvent', () => {
  const request = () => toCreateRequest(newEventForm(), 'kulturhaus-x')

  it('POSTs the request as JSON', async () => {
    const saved = adminEvent({ id: 9001 })
    const fetchFn = vi.fn<typeof fetch>(
      async () => new Response(JSON.stringify(saved), { status: 201 }),
    )

    await expect(createEvent(request(), fetchFn)).resolves.toEqual(saved)

    const [url, init] = fetchFn.mock.calls[0]!
    expect(url).toBe('/api/admin/events')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual(request())
  })

  it('names the detail of a 409', async () => {
    const fetchFn = vi.fn<typeof fetch>(
      async () =>
        new Response(
          JSON.stringify({ detail: 'A record with the same source ID already exists.' }),
          { status: 409 },
        ),
    )

    await expect(createEvent(request(), fetchFn)).rejects.toThrow(
      'POST /api/admin/events: HTTP 409 — A record with the same source ID already exists.',
    )
  })

  it('lists the field errors of a failed validation', async () => {
    const fetchFn = vi.fn<typeof fetch>(
      async () =>
        new Response(
          JSON.stringify({
            detail: 'Validation failed',
            errors: [{ field: 'title', message: 'Event title must not be blank' }],
          }),
          { status: 400 },
        ),
    )

    await expect(createEvent(request(), fetchFn)).rejects.toThrow(
      'HTTP 400 — Validation failed (title: Event title must not be blank)',
    )
  })
})

describe('fetchEventsOn', () => {
  it('asks for one venue on one date and returns the page content', async () => {
    const event = adminEvent()
    const fetchFn = vi.fn<typeof fetch>(
      async () => new Response(JSON.stringify({ content: [event], totalElements: 1 })),
    )

    const events = await fetchEventsOn(7, '2026-09-12', fetchFn)

    expect(events).toEqual([event])
    const url = new URL(String(fetchFn.mock.calls[0]![0]), 'http://admin.test')
    expect(url.pathname).toBe('/api/admin/events')
    expect(Object.fromEntries(url.searchParams)).toEqual({
      venueId: '7',
      date: '2026-09-12',
      size: '100',
      sort: 'startTime,asc',
    })
  })

  it('throws the Problem Detail of a refused request', async () => {
    const fetchFn = vi.fn<typeof fetch>(
      async () =>
        new Response(
          JSON.stringify({ detail: "Invalid value '12.09.2026': expected a valid LocalDate." }),
          { status: 400 },
        ),
    )

    await expect(fetchEventsOn(7, '12.09.2026', fetchFn)).rejects.toThrow(
      "GET /api/admin/events: HTTP 400 — Invalid value '12.09.2026'",
    )
  })
})
