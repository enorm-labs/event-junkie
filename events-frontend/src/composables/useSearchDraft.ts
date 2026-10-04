import { onScopeDispose, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MIN_SEARCH_LENGTH } from './useGlobalSearch'

/** The term a draft searches for: trimmed, or `''` below the header search's minimum. */
function searchable(draft: string): string {
  const term = draft.trim()
  return term.length >= MIN_SEARCH_LENGTH ? term : ''
}

/**
 * A `?q=` search field that searches as the visitor types, like the header search.
 * Refining a search replaces the history entry, so Back leaves the search in one step.
 * The draft follows the URL, except when the URL only echoes what the visitor typed before a pause.
 */
export function useSearchDraft(debounceMs = 250) {
  const route = useRoute()
  const router = useRouter()
  const current = () => (typeof route.query.q === 'string' ? route.query.q : '')

  const search = ref(current())
  let timer: ReturnType<typeof setTimeout> | undefined
  let written: string | undefined

  function applySearch() {
    clearTimeout(timer)
    const q = searchable(search.value)
    const from = current()
    if (q === from) return
    written = q
    const query = { ...route.query, q: q || undefined, page: undefined }
    if (from) router.replace({ query })
    else router.push({ query })
  }

  watch(search, (value) => {
    clearTimeout(timer)
    if (value.trim() !== current()) timer = setTimeout(applySearch, debounceMs)
  })

  watch(current, (q) => {
    if (q !== written && q !== searchable(search.value)) search.value = q
    written = undefined
  })

  onScopeDispose(() => clearTimeout(timer))

  return { search, applySearch }
}
