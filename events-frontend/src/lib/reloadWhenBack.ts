import { RETRY_WINDOW_MS, retryDelay } from '@/api/retry'

/** The messages Chromium, Firefox and WebKit give a failed `import()`. */
const CHUNK_ERROR =
  /dynamically imported module|error loading dynamically imported module|Importing a module script failed/i

export function isChunkLoadError(e: unknown): boolean {
  return e instanceof Error && CHUNK_ERROR.test(e.message)
}

let waiting = false

/**
 * A route's chunk failed to load: the server is down, or a deploy replaced the chunk names. Waits
 * until the server answers, then loads `path` in full. A second `import()` of the same URL is not
 * tried, because a browser may keep the failed module (#3569). After the retry window the page
 * loads anyway, and the browser shows its own error.
 */
export async function reloadWhenBack(
  path: string,
  navigate: (path: string) => void = (target) => window.location.assign(target),
): Promise<void> {
  if (waiting) return
  waiting = true
  const started = Date.now()
  try {
    for (let attempt = 0; ; attempt++) {
      if (await answers()) break
      const delay = retryDelay(attempt)
      if (Date.now() - started + delay > RETRY_WINDOW_MS) break
      await new Promise((resolve) => setTimeout(resolve, delay))
    }
  } finally {
    waiting = false
  }
  navigate(path)
}

async function answers(): Promise<boolean> {
  try {
    return (await fetch('/', { method: 'HEAD', cache: 'no-store' })).ok
  } catch {
    return false
  }
}
