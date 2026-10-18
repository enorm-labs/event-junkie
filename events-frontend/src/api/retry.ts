import { ApiError } from './client'

/**
 * Waits between attempts, then 30 s apart. Together with {@link RETRY_WINDOW_MS} this rides out
 * a node reboot, about two minutes, without the visitor reloading (#3569).
 */
const RETRY_DELAYS_MS = [5_000, 10_000, 20_000]
const RETRY_STEADY_MS = 30_000
export const RETRY_WINDOW_MS = 180_000

export function retryDelay(attempt: number): number {
  return RETRY_DELAYS_MS[attempt] ?? RETRY_STEADY_MS
}

/**
 * A failure that a later attempt can fix: rate limiting, a server error, a timeout, or a request
 * that never reached a server. A 404 or another 4xx answers the same way every time. An abort is
 * the caller's own cancellation.
 */
export function isTransient(e: unknown): boolean {
  if (e instanceof ApiError) return e.status === 429 || e.status >= 500
  if (e instanceof DOMException) return e.name === 'TimeoutError'
  return e instanceof TypeError
}

export interface RetryOptions {
  /** True once a newer request replaced this one; the chain then ends with the last error. */
  isStale?: () => boolean
  /** Called before each wait, so the view can say that it is retrying. */
  onRetry?: () => void
}

/** Runs `loader` until it succeeds, the failure is not transient, or the window is over. */
export async function retryTransient<T>(
  loader: () => Promise<T>,
  { isStale = () => false, onRetry }: RetryOptions = {},
): Promise<T> {
  const started = Date.now()
  for (let attempt = 0; ; attempt++) {
    try {
      return await loader()
    } catch (e) {
      const delay = retryDelay(attempt)
      if (!isTransient(e) || isStale() || Date.now() - started + delay > RETRY_WINDOW_MS) throw e
      onRetry?.()
      await new Promise((resolve) => setTimeout(resolve, delay))
      if (isStale()) throw e
    }
  }
}
