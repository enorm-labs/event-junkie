import { onScopeDispose, type Ref, ref, shallowRef, watch } from 'vue'

import { api, describeError, unwrap } from '@/api/client'
import type { SearchResults } from '@/api/types'

/** The BFF's own floor: one letter matches most of the catalogue (#2514). */
export const MIN_SEARCH_LENGTH = 2

/** One request for the four kinds the header search shows; `signal` lets a newer term cancel it. */
export function fetchSearch(
  q: string,
  limit: number,
  signal?: AbortSignal,
): Promise<SearchResults> {
  return unwrap(api.GET('/api/search', { params: { query: { q, limit } }, signal }))
}

/**
 * Type-ahead over `/api/search`: waits until the visitor pauses, and drops the answer to a term
 * they have already typed past, so a slow response never paints over a newer one.
 */
export function useGlobalSearch(term: Ref<string>, limit = 5, debounceMs = 250) {
  const results = shallowRef<SearchResults | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  let timer: ReturnType<typeof setTimeout> | undefined
  let inFlight: AbortController | undefined

  function cancel() {
    clearTimeout(timer)
    inFlight?.abort()
    inFlight = undefined
  }

  async function load(q: string) {
    const controller = new AbortController()
    inFlight = controller
    try {
      results.value = await fetchSearch(q, limit, controller.signal)
      error.value = null
    } catch (e) {
      if (controller.signal.aborted) return
      results.value = null
      error.value = describeError(e, 'errors.subject.search')
    }
    loading.value = false
  }

  watch(term, (value) => {
    cancel()
    const q = value.trim()
    if (q.length < MIN_SEARCH_LENGTH) {
      results.value = null
      error.value = null
      loading.value = false
      return
    }
    loading.value = true
    timer = setTimeout(() => load(q), debounceMs)
  })

  onScopeDispose(cancel)

  return { results, loading, error }
}
