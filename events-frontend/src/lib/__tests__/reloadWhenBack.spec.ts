import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { isChunkLoadError, reloadWhenBack } from '@/lib/reloadWhenBack'

describe('isChunkLoadError', () => {
  it.each([
    'Failed to fetch dynamically imported module: https://event-junkie.de/assets/MapView-x.js',
    'error loading dynamically imported module: https://event-junkie.de/assets/MapView-x.js',
    'Importing a module script failed.',
  ])('knows %s', (message) => {
    expect(isChunkLoadError(new TypeError(message))).toBe(true)
  })

  it('leaves other errors to the router', () => {
    expect(isChunkLoadError(new Error('Navigation cancelled'))).toBe(false)
    expect(isChunkLoadError('dynamically imported module')).toBe(false)
  })
})

describe('reloadWhenBack', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', fetchMock)
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  it('loads the page at once when the server answers', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }))
    const navigate = vi.fn<(path: string) => void>()

    await reloadWhenBack('/en/map', navigate)

    expect(navigate).toHaveBeenCalledWith('/en/map')
    expect(fetchMock).toHaveBeenCalledWith('/', { method: 'HEAD', cache: 'no-store' })
  })

  it('waits for the server, then loads the page', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(new Response(null, { status: 502 }))
      .mockResolvedValue(new Response(null, { status: 200 }))
    const navigate = vi.fn<(path: string) => void>()

    const done = reloadWhenBack('/de/events/x', navigate)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(navigate).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(10_000)
    await done

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(navigate).toHaveBeenCalledWith('/de/events/x')
  })

  it('loads the page anyway after the window', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    const navigate = vi.fn<(path: string) => void>()

    const done = reloadWhenBack('/en', navigate)
    await vi.runAllTimersAsync()
    await done

    expect(navigate).toHaveBeenCalledWith('/en')
  })

  it('runs one wait at a time', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }))
    const navigate = vi.fn<(path: string) => void>()

    const first = reloadWhenBack('/en/map', navigate)
    await reloadWhenBack('/en/week', navigate)
    await vi.advanceTimersByTimeAsync(5_000)
    await first

    expect(navigate).toHaveBeenCalledTimes(1)
    expect(navigate).toHaveBeenCalledWith('/en/map')
  })
})
