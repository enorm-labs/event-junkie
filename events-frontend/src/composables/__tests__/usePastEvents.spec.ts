import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { shallowRef } from 'vue'

import type { EventPage, EventSummary } from '@/api/types'
import { todayIso, yesterdayIso } from '@/lib/format'

type Query = { page?: number; size?: number; venue?: string }

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init: { params: { query: Query } }) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { usePastEvents } = await import('@/composables/usePastEvents')

/** `total` past events, newest first, served 20 a page; `fail` pages reject. */
function serve(total: number, { first = [] as EventSummary[], fail = [] as number[] } = {}) {
  const all: EventSummary[] = [
    ...first,
    ...Array.from({ length: total - first.length }, (_, i) => ({
      slug: `past-${i}`,
      eventDate: '2026-09-01',
    })),
  ]
  getMock.mockImplementation((_path, { params: { query } }) => {
    const page = query.page ?? 0
    if (fail.includes(page)) return Promise.reject(new TypeError('offline'))
    const size = query.size ?? 20
    return Promise.resolve({
      content: all.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: total,
      totalPages: Math.ceil(total / size),
    } satisfies EventPage)
  })
}

// Each test uses its own slug: the first page goes through useAsync's module-wide cache.
let n = 0
async function pager(upcoming: EventPage | null = { content: [] }) {
  const { past, run } = usePastEvents(() => ({ venue: `venue-${++n}` }), shallowRef(upcoming))
  await run()
  await flushPromises()
  return past
}

const running: EventSummary = { slug: 'running', eventDate: '2026-09-20', endDate: todayIso() }

describe('usePastEvents', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-04T08:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
    getMock.mockReset()
  })

  it('asks for the newest 20 up to yesterday', async () => {
    serve(3)
    await pager()

    expect(getMock).toHaveBeenCalledWith('/api/events', {
      params: {
        query: { venue: `venue-${n}`, to: yesterdayIso(), size: 20, sort: ['eventDate,desc'] },
      },
    })
  })

  it('shows nothing and no button for no past events', async () => {
    serve(0)
    const past = await pager()

    expect(past.events).toEqual([])
    expect(past.count).toBe(0)
    expect(past.hasMore).toBe(false)
  })

  it('has no button when exactly one page of 20 exists', async () => {
    serve(20)
    const past = await pager()

    expect(past.events).toHaveLength(20)
    expect(past.count).toBe(20)
    expect(past.hasMore).toBe(false)
  })

  it('loads the 21st event as page 1, appends it and drops the button', async () => {
    serve(21)
    const past = await pager()
    expect(past.hasMore).toBe(true)

    const loading = past.loadMore()
    expect(past.loadingMore).toBe(true)
    await loading

    expect(getMock).toHaveBeenLastCalledWith('/api/events', {
      params: expect.objectContaining({ query: expect.objectContaining({ page: 1 }) }),
    })
    expect(past.events).toHaveLength(21)
    expect(past.events[20]?.slug).toBe('past-20')
    expect(past.count).toBe(21)
    expect(past.hasMore).toBe(false)
    expect(past.loadingMore).toBe(false)
  })

  it('leaves a running event out of the list and the count', async () => {
    serve(53, { first: [running] })
    const past = await pager({ content: [running] })

    expect(past.events.map((e) => e.slug)).not.toContain('running')
    expect(past.events).toHaveLength(19)
    expect(past.count).toBe(52)
    expect(past.hasMore).toBe(true)
  })

  it('passes later pages through withoutUpcoming too', async () => {
    const onPage1: EventSummary[] = Array.from({ length: 20 }, (_, i) => ({ slug: `p0-${i}` }))
    serve(25, { first: [...onPage1, running] })
    const past = await pager({ content: [running] })
    expect(past.count).toBe(25)

    await past.loadMore()

    expect(past.events.map((e) => e.slug)).not.toContain('running')
    expect(past.events).toHaveLength(24)
    expect(past.count).toBe(24)
    expect(past.hasMore).toBe(false)
  })

  it('keeps the shown events and the button after a failed page, and retries it', async () => {
    serve(45, { fail: [1] })
    const past = await pager()

    await past.loadMore()

    expect(past.events).toHaveLength(20)
    expect(past.hasMore).toBe(true)
    expect(past.loadingMore).toBe(false)
    expect(past.error).toContain('past events')

    serve(45)
    await past.loadMore()

    expect(past.error).toBeNull()
    expect(past.events).toHaveLength(40)
    expect(past.hasMore).toBe(true)
  })
})
