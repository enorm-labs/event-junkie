import { computed, onMounted, watch, type Ref } from 'vue'
import { type LocationQuery, type LocationQueryRaw, useRoute, useRouter } from 'vue-router'

/** The part of a paged BFF response this composable reads; every `*Page` type satisfies it. */
interface PagedResponse {
  page?: number
  totalPages?: number
}

/** `query` at page `target`, keeping every filter. Page 0 stays out of the URL. */
export function pageQuery(query: LocationQuery, target: number): LocationQueryRaw {
  const next: LocationQueryRaw = { ...query, page: String(target) }
  if (target <= 0) delete next.page
  return next
}

/**
 * Paging for a list view whose page number lives in the URL query, so a position is shareable and
 * survives back/forward. It also owns the reload, because the two cannot be separated: the page
 * number is a query parameter like any other, and the same watcher that reacts to a filter change
 * is what reacts to a page change.
 */
export function usePagedList<T extends PagedResponse>(
  page: Readonly<Ref<T | null>>,
  run: () => void,
) {
  const route = useRoute()
  const router = useRouter()

  const currentPage = computed(() => page.value?.page ?? 0)
  const totalPages = computed(() => page.value?.totalPages ?? 0)

  /**
   * A `page` past the last one is not an empty result, and saying so as "nothing matches those
   * filters" names a cause that is not the cause (#1267).
   *
   * It happens without anyone typing a number: a list shortens every night as events pass, so a
   * shared link or a crawler's `?page=` can outlive its own range. Clamping keeps one canonical
   * route, and `replace` keeps the dead number out of the history.
   */
  watch(page, (loaded) => {
    const last = (loaded?.totalPages ?? 0) - 1
    if (!loaded || last < 0 || currentPage.value <= last) return
    router.replace({ query: pageQuery(route.query, last) })
  })

  /**
   * The route of page `target` with the current filters. The pager renders links, not buttons, so a
   * page opens in a new tab and a crawler can follow it.
   */
  function pageLink(target: number): { query: LocationQueryRaw } {
    return { query: pageQuery(route.query, target) }
  }

  onMounted(run)
  watch(() => route.query, run, { deep: true })

  return { currentPage, totalPages, pageLink }
}
