import { computed, type Ref } from 'vue'
import type { EventSummary } from '@/api/types'
import type { FilterOption } from '@/components/MultiSelectFilter.vue'
import { useQueryParams } from '@/composables/useListQuery'
import { DISTRICTS } from '@/lib/districts'

/**
 * The filters of the Christmas markets page (#5004), kept in the URL so a filtered view can be shared. The page
 * loads every market at once, so they filter on the device. Each filter is one entry in `filters`: a later one
 * adds its query key and its test there, and the list, the count and the map follow it.
 */
export function useMarketFilters(markets: Ref<readonly EventSummary[]>) {
  const { queryString, queryList, applyFilters } = useQueryParams()

  // `true`, as the events list writes it.
  const free = computed(() => queryString('free') === 'true')
  const districts = computed(() => queryList('district'))

  const filters = computed(() => ({
    free: free.value ? (market: EventSummary) => market.free === true : null,
    district: districts.value.length
      ? (market: EventSummary) => districts.value.includes(market.venue?.district ?? '')
      : null,
  }))

  type FilterKey = keyof typeof filters.value

  function matching(skip?: FilterKey): EventSummary[] {
    const tests = Object.entries(filters.value)
      .filter(([key, test]) => key !== skip && test)
      .map(([, test]) => test as (market: EventSummary) => boolean)
    return markets.value.filter((market) => tests.every((test) => test(market)))
  }

  const filtered = computed(() => matching())

  /** How many markets each district adds when ticked, counted under the other filters. */
  const districtCounts = computed(() => {
    const counts: Record<string, number> = {}
    for (const market of matching('district')) {
      const district = market.venue?.district
      if (district) counts[district] = (counts[district] ?? 0) + 1
    }
    return counts
  })

  /** Only the districts a market is in, and a chosen one from the URL so it can be unticked. */
  const districtOptions = computed<FilterOption[]>(() => {
    const present = new Set(markets.value.map((market) => market.venue?.district))
    return DISTRICTS.filter(({ slug }) => present.has(slug) || districts.value.includes(slug)).map(
      ({ slug, label }) => ({ value: slug, label }),
    )
  })

  function setFree(on: boolean) {
    applyFilters({ free: on ? 'true' : '' })
  }

  function setDistricts(values: string[]) {
    applyFilters({ district: values })
  }

  return {
    free,
    districts,
    filtered,
    districtCounts,
    districtOptions,
    setFree,
    setDistricts,
  }
}
