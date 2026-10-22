import { describe, expect, it, vi } from 'vitest'

import {
  checkEvent,
  checkSource,
  dismissWorklistRow,
  fetchDismissals,
  fetchWorklist,
  QUALITY_ISSUES,
  undoDismissal,
} from '../dataQuality'

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

describe('the classification check', () => {
  const run = {
    candidates: 1,
    checked: 1,
    unchanged: 0,
    failed: 0,
    overCap: 0,
    overBudget: 0,
    suggested: { startTime: 1 },
  }

  it('posts one event without force and answers the run', async () => {
    const fetchFn = respond(run)

    await expect(checkEvent(418, fetchFn)).resolves.toEqual(run)

    expect(fetchFn).toHaveBeenCalledExactlyOnceWith(
      '/api/admin/data-quality/check/event/418',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('posts one source without force', async () => {
    const fetchFn = respond({ message: 'started', sourceSlug: 'about blank' }, 202)

    await checkSource('about blank', fetchFn)

    expect(String(fetchFn.mock.calls[0]?.[0])).toBe(
      '/api/admin/data-quality/check/source/about%20blank',
    )
  })

  const conflict = () =>
    respond(
      {
        status: 409,
        detail:
          'The event-classification check is off: set app.data-quality.classification.enabled and a key',
      },
      409,
    )

  it.each([
    ['an event', () => checkEvent(418, conflict())],
    ['a source', () => checkSource('lido', conflict())],
  ])('carries the detail of a 409 for %s', async (_, call) => {
    await expect(call()).rejects.toThrow(
      /HTTP 409: The event-classification check is off: set app.data-quality.classification.enabled and a key$/,
    )
  })
})

describe('worklist dismissals', () => {
  it('posts the issue, the event and the fingerprint the page showed', async () => {
    const fetchFn = respond({ id: 7 }, 201)

    await dismissWorklistRow('missingGenre', { id: 418, fingerprint: 'abc' }, fetchFn)

    expect(fetchFn).toHaveBeenCalledExactlyOnceWith(
      '/api/admin/data-quality/worklist/dismissals',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ issue: 'missingGenre', eventId: 418, fingerprint: 'abc' }),
      }),
    )
  })

  it('carries the detail of a 409 for a row that changed', async () => {
    const fetchFn = respond(
      { detail: 'Event 418 changed since the missingGenre worklist was read' },
      409,
    )

    await expect(
      dismissWorklistRow('missingGenre', { id: 418, fingerprint: 'abc' }, fetchFn),
    ).rejects.toThrow(/HTTP 409: Event 418 changed/)
  })

  it('lists one issue and undoes by id', async () => {
    const list = respond([])
    await fetchDismissals('flaggedAtImport', list)
    expect(String(list.mock.calls[0]?.[0])).toBe(
      '/api/admin/data-quality/worklist/dismissals?issue=flaggedAtImport',
    )

    const undo = vi.fn<typeof fetch>(async () => new Response(null, { status: 204 }))
    await undoDismissal(7, undo)
    expect(undo).toHaveBeenCalledExactlyOnceWith(
      '/api/admin/data-quality/worklist/dismissals/7',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })
})
