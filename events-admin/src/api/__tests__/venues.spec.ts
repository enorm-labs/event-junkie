import { describe, expect, it, vi } from 'vitest'

import { fetchAllVenues } from '../venues'

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
