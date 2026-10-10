import { describe, expect, it, vi } from 'vitest'

import { fetchAllVenues, fetchNeedsReview, reviewVenue, runSiteCheck } from '../venues'

function venue(n: number) {
  return {
    id: n,
    name: `Venue ${n}`,
    slug: `venue-${n}`,
    city: 'Berlin',
    closedOn: null,
  }
}

function pages(total: number, cap = 100) {
  const all = Array.from({ length: total }, (_, i) => venue(i + 1))
  return vi.fn<typeof fetch>(async (input) => {
    const page = Number(new URL(String(input), 'http://localhost').searchParams.get('page'))
    const content = all.slice(page * cap, (page + 1) * cap)
    return new Response(JSON.stringify({ content, totalElements: total }))
  })
}

describe('fetchAllVenues', () => {
  it('reads past the 100-item page cap and keeps the fields the form reads', async () => {
    const fetchFn = pages(150)

    const venues = await fetchAllVenues(fetchFn)

    expect(venues).toHaveLength(150)
    expect(venues[0]).toEqual({ id: 1, name: 'Venue 1', slug: 'venue-1', closedOn: null })
    expect(String(fetchFn.mock.calls[1]?.[0])).toBe(
      '/api/admin/venues?page=1&size=100&sort=name,asc',
    )
  })

  it('refuses a partial listing', async () => {
    const fetchFn = vi.fn<typeof fetch>(
      async () => new Response(JSON.stringify({ content: [venue(1)], totalElements: 2 })),
    )
    // Page 1 repeats page 0, so the second venue never arrives.
    await expect(fetchAllVenues(fetchFn)).rejects.toThrow('Read 1 of 2 venues.')
  })

  it('reports a failed page', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(null, { status: 503 }))

    await expect(fetchAllVenues(fetchFn)).rejects.toThrow('GET /api/admin/venues page 0: HTTP 503')
  })
})

describe('fetchNeedsReview', () => {
  it('reads the list as the importer sends it', async () => {
    const rows = [{ venueId: 1, slug: 'a', outcome: 'DNS' }]
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(JSON.stringify(rows)))

    await expect(fetchNeedsReview(fetchFn)).resolves.toEqual(rows)
    expect(fetchFn).toHaveBeenCalledWith('/api/admin/venues/needs-review', expect.anything())
  })
})

describe('runSiteCheck', () => {
  it('posts site-check and accepts the 202', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(null, { status: 202 }))

    await runSiteCheck(fetchFn)

    expect(fetchFn).toHaveBeenCalledWith(
      '/api/admin/venues/site-check',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('reports a refused start', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(null, { status: 500 }))

    await expect(runSiteCheck(fetchFn)).rejects.toThrow(
      'POST /api/admin/venues/site-check: HTTP 500',
    )
  })
})

describe('reviewVenue', () => {
  it('reads the venue right before it writes, and sends no read-only field', async () => {
    const stored = {
      ...venue(7),
      slug: 'venue-7',
      programmeFamilies: ['rock'],
      programmeEventTypes: ['CONCERT'],
      createdAt: '2025-01-01T00:00:00Z',
      updatedAt: '2025-01-01T00:00:00Z',
    }
    const fetchFn = vi.fn<typeof fetch>(async (_input, init) =>
      init?.method === 'PUT'
        ? new Response(String(init.body))
        : new Response(JSON.stringify(stored)),
    )

    await reviewVenue(7, { closedOn: '2026-07-31' }, fetchFn)

    expect(fetchFn.mock.calls.map(([input, init]) => `${init?.method ?? 'GET'} ${input}`)).toEqual([
      'GET /api/admin/venues/7',
      'PUT /api/admin/venues/7',
    ])
    const body = JSON.parse(String(fetchFn.mock.calls[1]![1]!.body))
    expect(body).not.toHaveProperty('id')
    expect(body).not.toHaveProperty('slug')
    expect(body).not.toHaveProperty('programmeFamilies')
    expect(body).not.toHaveProperty('createdAt')
    expect(body.closedOn).toBe('2026-07-31')
  })

  it('writes nothing when the read fails', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(null, { status: 404 }))

    await expect(reviewVenue(7, { reviewedAt: '2026-10-10T08:00:00Z' }, fetchFn)).rejects.toThrow(
      'GET /api/admin/venues/7: HTTP 404',
    )
    expect(fetchFn).toHaveBeenCalledOnce()
  })
})
