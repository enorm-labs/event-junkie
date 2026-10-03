import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'

type Init = { params: { query: { q: string; limit: number } }; signal?: AbortSignal }

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init: Init) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { useGlobalSearch } = await import('@/composables/useGlobalSearch')

function answer(q: string) {
  return { venues: { items: [{ slug: q, name: q }], total: 1 } }
}

describe('useGlobalSearch', () => {
  const scope = effectScope()

  beforeEach(() => {
    vi.useFakeTimers()
    getMock.mockReset()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  function setup() {
    const term = ref('')
    const search = scope.run(() => useGlobalSearch(term, 5, 250))!
    return { term, ...search }
  }

  it('asks nothing for a term under two characters', async () => {
    const { term, results, loading } = setup()
    term.value = ' b '
    await nextTick()
    await vi.runAllTimersAsync()

    expect(getMock).not.toHaveBeenCalled()
    expect(results.value).toBeNull()
    expect(loading.value).toBe(false)
  })

  it('sends one request once the visitor pauses, for the last term', async () => {
    getMock.mockImplementation(async (_path, init) => answer(init.params.query.q))
    const { term, results } = setup()
    for (const value of ['be', 'ber', 'berg']) {
      term.value = value
      await nextTick()
      await vi.advanceTimersByTimeAsync(100)
    }
    await vi.runAllTimersAsync()

    expect(getMock).toHaveBeenCalledTimes(1)
    expect(getMock.mock.calls[0]![1].params.query).toEqual({ q: 'berg', limit: 5 })
    expect(results.value?.venues?.items?.[0]?.slug).toBe('berg')
  })

  it('cancels a slow answer when the visitor types on, so it never paints', async () => {
    const signals: AbortSignal[] = []
    getMock.mockImplementation(
      (_path, init) =>
        new Promise((resolve, reject) => {
          signals.push(init.signal!)
          init.signal!.addEventListener('abort', () => reject(new DOMException('', 'AbortError')))
          setTimeout(() => resolve(answer(init.params.query.q)), 1000)
        }),
    )
    const { term, results, error } = setup()
    term.value = 'tres'
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    term.value = 'tresor'
    await nextTick()
    await vi.runAllTimersAsync()

    expect(signals[0]!.aborted).toBe(true)
    expect(results.value?.venues?.items?.[0]?.slug).toBe('tresor')
    expect(error.value).toBeNull()
  })

  it('reports a failure and drops the stale results', async () => {
    getMock.mockResolvedValueOnce(answer('lido')).mockRejectedValueOnce(new TypeError('offline'))
    const { term, results, error } = setup()
    term.value = 'lido'
    await nextTick()
    await vi.runAllTimersAsync()
    term.value = 'lidos'
    await nextTick()
    await vi.runAllTimersAsync()

    expect(results.value).toBeNull()
    expect(error.value).toMatch(/search results/)
  })
})
