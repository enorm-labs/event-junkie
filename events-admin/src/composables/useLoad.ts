import { ref, shallowRef } from 'vue'

import { errorText } from '@/lib/errorText'

/**
 * Wraps a load in reactive `data` / `loading` / `error` state with a `reload()` trigger, as
 * `events-frontend`'s `useAsync` does. Only the latest `reload()` writes its answer, so a slow
 * response never paints over a newer one. A failed load sets `data` back to [initial].
 * `loading` starts true: every caller loads at once, and the first render shows the loading state.
 */
export function useLoad<T>(fn: () => Promise<T>, initial: T) {
  const data = shallowRef<T>(initial)
  const loading = ref(true)
  const error = ref<string | null>(null)
  let latest = 0

  async function reload() {
    const call = ++latest
    loading.value = true
    error.value = null
    try {
      const result = await fn()
      if (call === latest) data.value = result
    } catch (e) {
      if (call !== latest) return
      data.value = initial
      error.value = errorText(e)
    } finally {
      if (call === latest) loading.value = false
    }
  }

  return { data, loading, error, reload }
}
