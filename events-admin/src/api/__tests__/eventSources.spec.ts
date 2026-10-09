import { describe, expect, it, vi } from 'vitest'

import {
  type EventSource,
  fetchAllSources,
  fetchSource,
  PAGE_SIZE,
  retrySource,
  triggerImport,
  updateSource,
} from '../eventSources'

function source(n: number): EventSource {
  return {
    id: n,
    slug: `source-${n}`,
    name: `Source ${n}`,
    url: `https://example.com/${n}`,
    sourceType: 'EXAMPLE',
    enabled: true,
    importIntervalMinutes: 1440,
    maxRetries: 3,
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

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

describe('one source', () => {
  it('reads a source by its slug', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => json(source(7)))

    await expect(fetchSource('source-7', fetchFn)).resolves.toMatchObject({ slug: 'source-7' })
    expect(String(fetchFn.mock.calls[0]?.[0])).toBe('/api/admin/event-sources/source-7')
  })

  it('posts an import, with force=true only when asked', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => json({ message: 'started' }, 202))

    await triggerImport('lido', false, fetchFn)
    await triggerImport('lido', true, fetchFn)

    expect(fetchFn.mock.calls.map(([url, init]) => [String(url), init?.method])).toEqual([
      ['/api/admin/event-sources/lido/import', 'POST'],
      ['/api/admin/event-sources/lido/import?force=true', 'POST'],
    ])
  })

  it('posts a retry and returns the reset source', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => json({ ...source(3), status: 'IDLE' }))

    await expect(retrySource('source-3', fetchFn)).resolves.toMatchObject({ status: 'IDLE' })
    expect(String(fetchFn.mock.calls[0]?.[0])).toBe('/api/admin/event-sources/source-3/retry')
    expect(fetchFn.mock.calls[0]?.[1]?.method).toBe('POST')
  })

  it("adds the importer's ProblemDetail detail to the HTTP status", async () => {
    const fetchFn = vi.fn<typeof fetch>(async () =>
      json({ status: 404, detail: "Event source not found: 'gone'" }, 404),
    )

    await expect(triggerImport('gone', false, fetchFn)).rejects.toThrow(
      "POST /api/admin/event-sources/gone/import: HTTP 404: Event source not found: 'gone'",
    )
  })

  it('patches only the given fields and returns the stored source', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () =>
      json({ ...source(3), enabled: false, importIntervalMinutes: 720 }),
    )

    await expect(
      updateSource('source-3', { enabled: false, importIntervalMinutes: 720 }, fetchFn),
    ).resolves.toMatchObject({ enabled: false, importIntervalMinutes: 720 })
    const [url, init] = fetchFn.mock.calls[0] ?? []
    expect(String(url)).toBe('/api/admin/event-sources/source-3')
    expect(init?.method).toBe('PATCH')
    expect(JSON.parse(String(init?.body))).toEqual({ enabled: false, importIntervalMinutes: 720 })
  })

  it("adds a validation 400's field messages to its detail", async () => {
    const fetchFn = vi.fn<typeof fetch>(async () =>
      json(
        {
          status: 400,
          detail: 'Validation failed',
          errors: [
            {
              field: 'importIntervalMinutes',
              message: 'Import interval must be at least 1 minute',
            },
          ],
        },
        400,
      ),
    )

    await expect(updateSource('lido', { importIntervalMinutes: 0 }, fetchFn)).rejects.toThrow(
      'PATCH /api/admin/event-sources/lido: HTTP 400: Validation failed (Import interval must be at least 1 minute)',
    )
  })

  it('reports the status alone when the error has no JSON body', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response('Bad Gateway', { status: 502 }))

    await expect(retrySource('lido', fetchFn)).rejects.toThrow(
      'POST /api/admin/event-sources/lido/retry: HTTP 502',
    )
  })
})
