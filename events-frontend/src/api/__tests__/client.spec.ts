import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, describeError, fetchWithTimeout } from '@/api/client'

describe('describeError', () => {
  it('flags server errors and rate limiting as transient', () => {
    for (const status of [500, 503, 429]) {
      expect(describeError(new ApiError(status, 'boom'))).toContain('server is having trouble')
    }
  })

  it('gives a plain message for other HTTP errors', () => {
    const message = describeError(new ApiError(400, 'bad'))
    expect(message).toContain('Please try reloading')
    expect(message).not.toContain('server is having trouble')
  })

  it('describes a timeout', () => {
    const message = describeError(new DOMException('timed out', 'TimeoutError'))
    expect(message).toContain('timed out')
  })

  it('describes connectivity failures', () => {
    const message = describeError(new TypeError('Failed to fetch'))
    expect(message).toContain('check your connection')
  })

  it('names what failed to load via the label', () => {
    expect(describeError(new ApiError(500, 'boom'), 'errors.subject.tonightsEvents')).toContain(
      "tonight's events",
    )
  })

  it('falls back to a generic subject without a label', () => {
    expect(describeError(new ApiError(500, 'boom'))).toContain('this content')
  })
})

describe('fetchWithTimeout', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('aborts the request with a TimeoutError once the window elapses', async () => {
    // Stand-in fetch that only settles when its signal aborts.
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      (input) =>
        new Promise((_, reject) => {
          const { signal } = input as Request
          signal.addEventListener('abort', () => reject(signal.reason))
        }),
    )

    await expect(fetchWithTimeout(new Request('https://example.test/'), 5)).rejects.toMatchObject({
      name: 'TimeoutError',
    })
  })

  it('propagates an abort from the caller-supplied signal', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(
      (input) =>
        new Promise((_, reject) => {
          const { signal } = input as Request
          signal.addEventListener('abort', () => reject(signal.reason))
        }),
    )

    const controller = new AbortController()
    const request = new Request('https://example.test/', { signal: controller.signal })
    const pending = fetchWithTimeout(request, 10_000)
    controller.abort(new DOMException('cancelled', 'AbortError'))

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('resolves normally when fetch responds before the timeout', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('ok'))

    const response = await fetchWithTimeout(new Request('https://example.test/'), 10_000)
    expect(response.status).toBe(200)
  })
})
