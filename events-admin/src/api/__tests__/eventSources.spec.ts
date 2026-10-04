import { describe, expect, it, vi } from 'vitest'

import { type EventSource, fetchAllSources, PAGE_SIZE } from '../eventSources'

function source(n: number): EventSource {
  return {
    id: n,
    slug: `source-${n}`,
    name: `Source ${n}`,
    url: `https://example.com/${n}`,
    sourceType: 'EXAMPLE',
    enabled: true,
    status: 'SUCCESS',
    lastImportAt: null,
    lastSuccessAt: null,
    lastEventCount: null,
    lastFailureReason: null,
    flaggedAt: null,
  }
}

function pages(total: number, cap = PAGE_SIZE) {
  const all = Array.from({ length: total }, (_, i) => source(i + 1))
  return vi.fn<typeof fetch>(async (input) => {
    const page = Number(new URL(String(input), 'http://localhost').searchParams.get('page'))
    const content = all.slice(page * cap, (page + 1) * cap)
    return new Response(JSON.stringify({ content, totalElements: total }))
  })
}

describe('fetchAllSources', () => {
  it('reads past the 100-item page cap until totalElements', async () => {
    const fetchFn = pages(111)

    const sources = await fetchAllSources(fetchFn)

    expect(sources).toHaveLength(111)
    expect(fetchFn).toHaveBeenCalledTimes(2)
    expect(String(fetchFn.mock.calls[0]?.[0])).toBe(
      '/api/admin/event-sources?page=0&size=100&sort=name,asc',
    )
  })

  it('stops after one request when everything fits on one page', async () => {
    const fetchFn = pages(42)

    await expect(fetchAllSources(fetchFn)).resolves.toHaveLength(42)
    expect(fetchFn).toHaveBeenCalledTimes(1)
  })

  it('refuses a listing that ends short of totalElements', async () => {
    const fetchFn = vi.fn<typeof fetch>(
      async () => new Response(JSON.stringify({ content: [], totalElements: 111 })),
    )

    await expect(fetchAllSources(fetchFn)).rejects.toThrow('Read 0 of 111 sources')
  })

  it('reports the HTTP status when the forward answers with an error', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response('', { status: 502 }))

    await expect(fetchAllSources(fetchFn)).rejects.toThrow('HTTP 502')
  })
})
