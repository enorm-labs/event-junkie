import { ref, shallowRef } from 'vue'

import { ApiError, describeError } from '@/api/client'
import { retryTransient } from '@/api/retry'
import type { ErrorSubjectKey } from '@/i18n'

import { beginContentLoad } from './useViewLoaded'

/**
 * The last response per request key, so a view the visitor comes back to paints what it showed
 * before while it refetches. That is what lets the router restore the scroll position: a list
 * that mounts on its loading state is too short to scroll to where the visitor was (#1111).
 * Memory only and bounded — nothing is stored on the visitor's device.
 */
const recent = new Map<string, unknown>()
const RECENT_LIMIT = 50

function remember(key: string, value: unknown) {
  recent.delete(key)
  recent.set(key, value)
  if (recent.size > RECENT_LIMIT) recent.delete(recent.keys().next().value as string)
}

/**
 * Wraps an async loader in reactive `data` / `error` / `loading` state with a `run()` trigger.
 * `notFound` is true when the request failed with a 404, so detail pages can show a tailored
 * empty state instead of a generic error. `subjectKey` names what failed, for {@link describeError}.
 * `cacheKey` identifies the request: when it was answered before, `run()` sets `data` from that
 * answer synchronously and leaves `loading` false while the loader refreshes it. Only the latest
 * `run()` writes its answer, so a slow response never paints over a newer one.
 * A transient failure is retried for a few minutes (#3569). `retrying` is true meanwhile, and what
 * is on screen stays: the remembered answer, or the loading state, which then says it is retrying.
 */
export function useAsync<T>(
  loader: () => Promise<T>,
  subjectKey?: ErrorSubjectKey,
  cacheKey?: () => string,
) {
  const data = shallowRef<T | null>(null)
  const error = ref<string | null>(null)
  const notFound = ref(false)
  const loading = ref(false)
  const retrying = ref(false)
  let latest = 0

  async function run() {
    const call = ++latest
    const key = cacheKey?.()
    const remembered = key === undefined ? undefined : (recent.get(key) as T | undefined)
    if (remembered !== undefined) data.value = remembered
    loading.value = remembered === undefined
    const done = loading.value ? beginContentLoad() : undefined
    error.value = null
    notFound.value = false
    retrying.value = false
    try {
      const result = await retryTransient(loader, {
        isStale: () => call !== latest,
        // The page is as tall as it gets until the server is back, so the footer need not wait.
        onRetry: () => {
          retrying.value = true
          done?.()
        },
      })
      if (key !== undefined) remember(key, result)
      if (call !== latest) return
      data.value = result
    } catch (e) {
      if (call !== latest) return
      data.value = null
      notFound.value = e instanceof ApiError && e.status === 404
      error.value = describeError(e, subjectKey)
    } finally {
      if (call === latest) {
        loading.value = false
        retrying.value = false
      }
      done?.()
    }
  }

  return { data, error, notFound, loading, retrying, run }
}
