import { describe, expect, it, vi } from 'vitest'

import { fetchWorklist, QUALITY_ISSUES } from '../dataQuality'

function respond(body: unknown, status = 200) {
  return vi.fn<typeof fetch>(async () => new Response(JSON.stringify(body), { status }))
}

describe('fetchWorklist', () => {
  it('asks for one issue, one page, and no source when none is picked', async () => {
    const fetchFn = respond({ issue: 'missingGenre', source: null, count: 0, entries: [] })

    await fetchWorklist({ issue: 'missingGenre' }, fetchFn)

    expect(String(fetchFn.mock.calls[0]?.[0])).toBe(
      '/api/admin/data-quality/worklist?issue=missingGenre&limit=50&offset=0',
    )
  })

  it('passes the source and the offset', async () => {
    const fetchFn = respond({ issue: 'missingGenre', source: 'manual', count: 0, entries: [] })

    await fetchWorklist({ issue: 'eventsTypedOther', source: 'manual', offset: 100 }, fetchFn)

    expect(String(fetchFn.mock.calls[0]?.[0])).toBe(
      '/api/admin/data-quality/worklist?issue=eventsTypedOther&limit=50&offset=100&source=manual',
    )
  })

  it('reports the HTTP status of a refused request', async () => {
    await expect(fetchWorklist({ issue: 'missingGenre' }, respond({}, 400))).rejects.toThrow(
      'HTTP 400',
    )
  })

  it('offers every QualityIssue key once', () => {
    const keys = QUALITY_ISSUES.map((i) => i.key)
    expect(new Set(keys).size).toBe(keys.length)
    expect(keys).toHaveLength(10)
  })
})
