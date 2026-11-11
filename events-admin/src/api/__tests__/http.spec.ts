import { describe, expect, it, vi } from 'vitest'

import { fetchAllPages, failOnError, getJson, PAGE_SIZE } from '../http'
import { json } from '@/lib/__tests__/jsonResponse'

interface Row {
  id: number
}

const rows = (from: number, count: number): Row[] =>
  Array.from({ length: count }, (_, i) => ({ id: from + i }))

const query = { sort: 'name,asc', noun: 'rows', key: (row: Row) => row.id }

describe('fetchAllPages', () => {
  it('stops at totalElements and reads no page past it', async () => {
    const fetchFn = vi.fn<typeof fetch>(async (url) => {
      const page = Number(new URL(String(url), 'http://x').searchParams.get('page'))
      const content = page === 0 ? rows(0, PAGE_SIZE) : rows(PAGE_SIZE, 5)
      return json({ content, totalElements: PAGE_SIZE + 5, totalPages: 2 })
    })

    const all = await fetchAllPages('/api/admin/rows', query, fetchFn)

    expect(all).toHaveLength(PAGE_SIZE + 5)
    expect(fetchFn).toHaveBeenCalledTimes(2)
    expect(fetchFn.mock.calls.map(([url]) => url)).toEqual([
      '/api/admin/rows?page=0&size=100&sort=name,asc',
      '/api/admin/rows?page=1&size=100&sort=name,asc',
    ])
  })

  it('keeps a row that moved to the next page once', async () => {
    const fetchFn = vi.fn<typeof fetch>(async (url) => {
      const page = Number(new URL(String(url), 'http://x').searchParams.get('page'))
      return json({ content: page === 0 ? rows(0, 2) : rows(1, 2), totalElements: 3 })
    })

    const all = await fetchAllPages('/api/admin/rows', query, fetchFn)

    expect(all.map((row) => row.id)).toEqual([0, 1, 2])
  })

  it('refuses a partial listing', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => json({ content: [], totalElements: 4 }))

    await expect(fetchAllPages('/api/admin/rows', query, fetchFn)).rejects.toThrow(
      'Read 0 of 4 rows. A partial listing is not shown.',
    )
  })

  it('names the page and adds the Problem Detail of a refused read', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => json({ detail: 'Invalid sort' }, 400))

    await expect(fetchAllPages('/api/admin/rows', query, fetchFn)).rejects.toThrow(
      'GET /api/admin/rows page 0: HTTP 400 — Invalid sort',
    )
  })
})

describe('getJson', () => {
  it('answers the body of a success', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => json({ id: 7 }))

    expect(await getJson<Row>('/api/admin/rows/7', fetchFn)).toEqual({ id: 7 })
  })

  it('names the call and the status of a failure without a body', async () => {
    const fetchFn = vi.fn<typeof fetch>(async () => new Response(null, { status: 503 }))

    await expect(getJson('/api/admin/rows/7', fetchFn)).rejects.toThrow(
      /^GET \/api\/admin\/rows\/7: HTTP 503$/,
    )
  })
})

describe('failOnError', () => {
  it('lists the field errors of a failed validation', async () => {
    const response = json(
      { detail: 'Validation failed', errors: [{ field: 'title', message: 'must not be blank' }] },
      400,
    )

    await expect(failOnError(response, 'POST /api/admin/events')).rejects.toThrow(
      'POST /api/admin/events: HTTP 400 — Validation failed (title: must not be blank)',
    )
  })
})
