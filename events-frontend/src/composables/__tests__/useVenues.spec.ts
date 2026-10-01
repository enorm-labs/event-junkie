import { beforeEach, describe, expect, it, vi } from 'vitest'

type Query = { page: number; size: number; district?: string; q?: string }

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init: { params: { query: Query } }) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { fetchAllVenues, useAllVenues } = await import('@/composables/useVenues')

/** One page of the venue list, as the BFF answers it. */
function page(slugs: string[], number: number, totalPages: number) {
  return { content: slugs.map((slug) => ({ slug })), page: number, totalPages }
}

describe('fetchAllVenues', () => {
  // Braces: a function returned from `beforeEach` is run as a cleanup hook, and `mockReset` returns the mock.
  beforeEach(() => {
    getMock.mockReset()
  })

  it('reads every page, so no venue goes missing from the map', async () => {
    getMock.mockImplementation(async (_path, init) =>
      page([`venue-${init.params.query.page}`], init.params.query.page, 3),
    )

    const venues = await fetchAllVenues({ district: 'mitte' })

    expect(venues.map((venue) => venue.slug)).toEqual(['venue-0', 'venue-1', 'venue-2'])
    expect(getMock.mock.calls.map(([, init]) => init.params.query)).toEqual([
      { district: 'mitte', page: 0, size: 100 },
      { district: 'mitte', page: 1, size: 100 },
      { district: 'mitte', page: 2, size: 100 },
    ])
  })

  it('keeps a venue once when a shifted page repeats it', async () => {
    getMock.mockImplementation(async (_path, init) =>
      page(init.params.query.page === 0 ? ['astra', 'lido'] : ['lido', 'loge'], 0, 2),
    )
    expect((await fetchAllVenues({})).map((venue) => venue.slug)).toEqual(['astra', 'lido', 'loge'])
  })

  it('stops after one request when nothing matches', async () => {
    getMock.mockResolvedValue(page([], 0, 0))
    expect(await fetchAllVenues({ q: 'nothing' })).toEqual([])
    expect(getMock).toHaveBeenCalledTimes(1)
  })
})

describe('useAllVenues', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  it('fills the filter with every page, not only the first hundred (#2246)', async () => {
    getMock.mockImplementation(async (_path, init) =>
      page([`venue-${init.params.query.page}`], init.params.query.page, 2),
    )
    const venues = useAllVenues()

    await venues.run()

    expect(venues.data.value?.map((venue) => venue.slug)).toEqual(['venue-0', 'venue-1'])
    expect(getMock.mock.calls.every(([, init]) => init.params.query.size === 100)).toBe(true)
  })
})
