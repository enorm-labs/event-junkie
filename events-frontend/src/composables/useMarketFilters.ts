import { computed, type Ref, watch } from 'vue'
import type { EventSummary } from '@/api/types'
import type { FilterOption } from '@/components/MultiSelectFilter.vue'
import { useAsync } from '@/composables/useAsync'
import { useQueryParams } from '@/composables/useListQuery'
import {
  type ChristmasMarket,
  foldMarkets,
  isOpenOn,
  MARKET_NEAREST,
  MARKET_TODAY,
  MARKET_WEEKEND,
  marketDays,
} from '@/lib/christmasMarkets'
import { DISTRICTS } from '@/lib/districts'
import { MARKET_KIND_PREFIX, MARKET_KINDS } from '@/lib/eventFeatures'
import { todayIso } from '@/lib/format'

/**
 * The filters of the Christmas markets page (#5004, #5005, #5007), kept in the URL so a filtered view can be shared. The
 * page loads every market at once, so they filter on the device. A list carries no features, so the kind filter loads
 * the slugs of its kind's markets once and tests against them. Each filter is one entry in `filters`: a later one
 * adds its query key and its test there, and the list, the count and the map follow it. The filters test each event,
 * and a market entered as several events passes when one of them does. The order (#5010) is no filter:
 * `sort` only tells the view how to order what `filtered` returns.
 */
export function useMarketFilters(
  markets: Ref<readonly EventSummary[]>,
  /** The markets that carry a feature, for the kind filter. */
  loadFeature: (feature: string) => Promise<readonly EventSummary[]>,
) {
  const { queryString, queryList, applyFilters } = useQueryParams()

  // `true`, as the events list writes it.
  const free = computed(() => queryString('free') === 'true')
  const districts = computed(() => queryList('district'))

  const today = todayIso()
  // `today`, `weekend` or a date, as `day` in the URL; a value that names no day reads as no choice.
  const openDays = computed(() => marketDays(queryString('day'), today))
  const day = computed(() => (openDays.value.length ? queryString('day') : ''))
  /** The chosen date for the date input, `''` when the choice is a word or none. */
  const date = computed(() =>
    day.value === MARKET_TODAY || day.value === MARKET_WEEKEND ? '' : day.value,
  )

  /** `distance` for nearest first, `''` for name order. The URL never carries the origin. */
  const sort = computed(() => (queryString('sort') === MARKET_NEAREST ? MARKET_NEAREST : ''))

  // One of `MARKET_KINDS`, as `kind` in the URL; any other value reads as no choice.
  const kind = computed(() => {
    const value = queryString('kind')
    return MARKET_KINDS.includes(value) ? value : ''
  })
  const kindMarkets = useAsync(
    async () => {
      const chosen = kind.value
      const events = await loadFeature(MARKET_KIND_PREFIX + chosen)
      return { kind: chosen, slugs: new Set(events.flatMap((event) => event.slug ?? [])) }
    },
    'errors.subject.christmasMarkets',
    () => `christmas-markets?kind=${kind.value}&from=${today}`,
  )
  watch(
    kind,
    (value) => {
      if (value) void kindMarkets.run()
    },
    { immediate: true },
  )
  /** The slugs of the chosen kind's markets, `null` while they load or when none is chosen. */
  const kindSlugs = computed(() => {
    const loaded = kindMarkets.data.value
    return loaded && loaded.kind === kind.value ? loaded.slugs : null
  })
  /** True while the chosen kind's markets load: the page waits rather than list every market first. */
  const kindPending = computed(() => !!kind.value && !kindSlugs.value && !kindMarkets.error.value)

  const filters = computed(() => ({
    free: free.value ? (market: EventSummary) => market.free === true : null,
    district: districts.value.length
      ? (market: EventSummary) => districts.value.includes(market.venue?.district ?? '')
      : null,
    day: openDays.value.length
      ? (market: EventSummary) => openDays.value.some((open) => isOpenOn(market, open))
      : null,
    kind: kindSlugs.value
      ? (market: EventSummary) => !!market.slug && kindSlugs.value?.has(market.slug) === true
      : null,
  }))

  type FilterKey = keyof typeof filters.value

  function matching(skip?: FilterKey): ChristmasMarket<EventSummary>[] {
    const tests = Object.entries(filters.value)
      .filter(([key, test]) => key !== skip && test)
      .map(([, test]) => test as (market: EventSummary) => boolean)
    return foldMarkets(markets.value, today, (block) => tests.every((test) => test(block)))
  }

  const filtered = computed(() => matching())

  /** How many markets each district adds when ticked, counted under the other filters. */
  const districtCounts = computed(() => {
    const counts: Record<string, number> = {}
    for (const { shown } of matching('district')) {
      const district = shown.venue?.district
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

  /** The date input's bounds: today, and the last day a loaded market is open. */
  const dateBounds = computed(() => ({
    min: today,
    max: markets.value.reduce((last, market) => {
      const end = market.endDate ?? market.eventDate ?? ''
      return end > last ? end : last
    }, today),
  }))

  function setDay(value: string) {
    applyFilters({ day: value })
  }

  function setFree(on: boolean) {
    applyFilters({ free: on ? 'true' : '' })
  }

  function setDistricts(values: string[]) {
    applyFilters({ district: values })
  }

  function setSort(nearest: boolean) {
    applyFilters({ sort: nearest ? MARKET_NEAREST : '' })
  }

  function setKind(value: string) {
    applyFilters({ kind: value })
  }

  return {
    day,
    date,
    dateBounds,
    free,
    districts,
    filtered,
    districtCounts,
    districtOptions,
    sort,
    setDay,
    setFree,
    setDistricts,
    setSort,
    kind,
    kindError: kindMarkets.error,
    kindPending,
    setKind,
  }
}
