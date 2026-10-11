import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { type LocationQueryRaw, useRoute, useRouter } from 'vue-router'
import type { SortOption } from '@/components/SortControl.vue'

/**
 * Reads and writes filters kept in the URL query, so a list is shareable and survives
 * back/forward. The events filters and the venue and promoter lists share it.
 */
export function useQueryParams() {
  const route = useRoute()
  const router = useRouter()

  /** The raw query value for `key`, or `''` when absent or repeated (`?q=a&q=b`). */
  function queryString(key: string): string {
    const value = route.query[key]
    return typeof value === 'string' ? value : ''
  }

  /** Every non-empty value of `key`, whether it appears once or repeated (`?t=a&t=b`). */
  function queryList(key: string): string[] {
    const value = route.query[key]
    const values = Array.isArray(value) ? value : [value]
    return values.filter((v): v is string => typeof v === 'string' && v !== '')
  }

  function applyFilters(patch: LocationQueryRaw) {
    // Any filter change resets to the first page; empty values and lists drop out of the URL.
    const next: LocationQueryRaw = { ...route.query, ...patch, page: undefined }
    for (const key of Object.keys(next)) {
      const value = next[key]
      if (value === '' || value === undefined || (Array.isArray(value) && !value.length)) {
        delete next[key]
      }
    }
    router.push({ query: next })
  }

  return { queryString, queryList, applyFilters }
}

const SORT_NAME = 'name,asc'
const SORT_NAME_DESC = 'name,desc'
const SORT_UPCOMING = 'upcomingEvents,desc'

/**
 * The query helpers plus the name and upcoming-events orders of the venue and promoter lists.
 * A–Z stays out of the URL but is sent: without a sort the BFF lists a search by relevance, and
 * the pressed A–Z would not be what the list shows (#2694). The count order is
 * `sort=upcomingEvents,desc` (#1349).
 */
export function useListQuery() {
  const query = useQueryParams()
  const { t } = useI18n()

  /** The order in the URL, `''` for A–Z or anything unknown. */
  const sort = computed(() => {
    const requested = query.queryString('sort')
    return requested === SORT_NAME_DESC || requested === SORT_UPCOMING ? requested : ''
  })

  /** The order as the BFF takes it. */
  const sortParam = computed(() => [sort.value || SORT_NAME])

  const sortOptions = computed<SortOption[]>(() => [
    {
      directions: [
        { value: '', ascending: true, label: t('common.sort.az') },
        { value: SORT_NAME_DESC, ascending: false, label: t('common.sort.za') },
      ],
    },
    { label: t('common.sort.upcoming'), value: SORT_UPCOMING },
  ])

  return { ...query, sort, sortParam, sortOptions }
}
