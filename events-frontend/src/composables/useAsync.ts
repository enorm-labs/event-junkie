import { ref, shallowRef } from 'vue'

import { ApiError, describeError } from '@/api/client'
import type { ErrorSubjectKey } from '@/i18n'

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
 * answer synchronously and leaves `loading` false while the loader refreshes it.
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

  async function run() {
    const key = cacheKey?.()
    const remembered = key === undefined ? undefined : (recent.get(key) as T | undefined)
    if (remembered !== undefined) data.value = remembered
    loading.value = remembered === undefined
    error.value = null
    notFound.value = false
    try {
      const result = await loader()
      data.value = result
      if (key !== undefined) remember(key, result)
    } catch (e) {
      data.value = null
      notFound.value = e instanceof ApiError && e.status === 404
      error.value = describeError(e, subjectKey)
    } finally {
      loading.value = false
    }
  }

  return { data, error, notFound, loading, run }
}
