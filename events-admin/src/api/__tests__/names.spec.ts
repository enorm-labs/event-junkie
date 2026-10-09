import { describe, expect, it, vi } from 'vitest'

import { fetchNames, SEARCH_LIMIT, searchByName } from '../names'

describe('searchByName', () => {
  it('asks for the first page of names that contain the trimmed term', async () => {
    const fetchFn = vi.fn<typeof fetch>(
      async () =>
        new Response(
          JSON.stringify({
            content: [{ id: 3, name: 'The Adicts', slug: 'the-adicts', description: 'Punk' }],
            totalElements: 14,
          }),
        ),
    )

    const result = await searchByName('artists', '  adicts & co ', fetchFn)

    const url = new URL(String(fetchFn.mock.calls[0]![0]), 'http://admin')
    expect(url.pathname).toBe('/api/admin/artists')
    expect(url.searchParams.get('name')).toBe('adicts & co')
    expect(url.searchParams.get('size')).toBe(String(SEARCH_LIMIT))
    expect(url.searchParams.get('sort')).toBe('name')
    expect(result).toEqual({ rows: [{ id: 3, name: 'The Adicts', slug: 'the-adicts' }], total: 14 })
  })

  it('reports a refused search', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(null, { status: 503 }))

    await expect(searchByName('promoters', 'x', fetchFn)).rejects.toThrow('HTTP 503')
  })
})

describe('fetchNames', () => {
  it('reads each id once and maps one that fails to null', async () => {
    const fetchFn = vi.fn<typeof fetch>(async (input) =>
      String(input).endsWith('/9')
        ? new Response(null, { status: 404 })
        : new Response(JSON.stringify({ id: 7, name: '36 Concerts', slug: '36-concerts' })),
    )

    const names = await fetchNames('promoters', [7, 9, 7], fetchFn)

    expect(fetchFn).toHaveBeenCalledTimes(2)
    expect(names.get(7)?.name).toBe('36 Concerts')
    expect(names.get(9)).toBeNull()
  })
})
