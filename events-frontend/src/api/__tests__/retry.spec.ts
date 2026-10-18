import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import { isTransient, RETRY_WINDOW_MS, retryDelay, retryTransient } from '@/api/retry'

describe('isTransient', () => {
  it.each([
    ['a 503', new ApiError(503, 'x'), true],
    ['a 500', new ApiError(500, 'x'), true],
    ['rate limiting', new ApiError(429, 'x'), true],
    ['a timeout', new DOMException('slow', 'TimeoutError'), true],
    ['no connection', new TypeError('Failed to fetch'), true],
    ['a 404', new ApiError(404, 'x'), false],
    ['a 400', new ApiError(400, 'x'), false],
    ['an abort', new DOMException('gone', 'AbortError'), false],
    ['a plain error', new Error('bug'), false],
  ])('%s → %s', (_name, error, expected) => {
    expect(isTransient(error)).toBe(expected)
  })
})

describe('retryTransient', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('waits 5, 10 and 20 seconds, then 30 seconds apart', () => {
    expect([0, 1, 2, 3, 4].map(retryDelay)).toEqual([5_000, 10_000, 20_000, 30_000, 30_000])
  })

  it('answers once the server is back', async () => {
    const loader = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new ApiError(503, 'down'))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValue('berghain')
    const onRetry = vi.fn<() => void>()

    const result = retryTransient(loader, { onRetry })
    await vi.advanceTimersByTimeAsync(5_000)
    expect(loader).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(10_000)

    await expect(result).resolves.toBe('berghain')
    expect(loader).toHaveBeenCalledTimes(3)
    expect(onRetry).toHaveBeenCalledTimes(2)
  })

  it('gives up with the last error once the window is over', async () => {
    const loader = vi.fn<() => Promise<string>>().mockRejectedValue(new ApiError(502, 'down'))

    const settled = retryTransient(loader).catch((e: unknown) => e)
    await vi.advanceTimersByTimeAsync(RETRY_WINDOW_MS)

    expect(await settled).toMatchObject({ status: 502 })

    // 0, 5, 15, 35, 65, 95, 125, 155 s: the next one, at 185 s, is past the window.
    expect(loader).toHaveBeenCalledTimes(8)
  })

  it('does not retry a 404', async () => {
    const loader = vi.fn<() => Promise<string>>().mockRejectedValue(new ApiError(404, 'gone'))

    await expect(retryTransient(loader)).rejects.toMatchObject({ status: 404 })
    expect(loader).toHaveBeenCalledTimes(1)
  })

  it('stops when a newer request replaced this one', async () => {
    let stale = false
    const loader = vi.fn<() => Promise<string>>().mockRejectedValue(new ApiError(503, 'down'))

    const settled = retryTransient(loader, { isStale: () => stale }).catch((e: unknown) => e)
    stale = true
    await vi.advanceTimersByTimeAsync(5_000)

    expect(await settled).toMatchObject({ status: 503 })

    expect(loader).toHaveBeenCalledTimes(1)
  })
})
