import { computed } from 'vue'
import { type LocationQueryRaw, useRoute, useRouter } from 'vue-router'
import type { EventDateRange, EventFilterValues } from './useEvents'

/**
 * Reads and writes the event filters that live in the URL query, so any view showing the filter
 * bar is shareable and survives back/forward. The bar itself and the views that consume the
 * result both call this — the URL is the single source of truth, so no state is passed between
 * them.
 */
export function useEventFilters() {
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

  const filters = computed<EventFilterValues>(() => {
    const eventTypes = queryList('eventType')
    return {
      q: queryString('q') || undefined,
      eventType: eventTypes.length ? eventTypes : undefined,
      venue: queryString('venue') || undefined,
      district: queryString('district') || undefined,
      genre: queryString('genre') || undefined,
      family: queryString('family') || undefined,
      minPrice: queryString('minPrice') ? Number(queryString('minPrice')) : undefined,
      maxPrice: queryString('maxPrice') ? Number(queryString('maxPrice')) : undefined,
      excludeSoldOut: queryString('excludeSoldOut') === 'true' || undefined,
      free: queryString('free') === 'true' || undefined,
    }
  })

  /**
   * The date range, returned separately from {@link filters} so only views that own their dates
   * can opt into it. The calendar derives `from`/`to` from its visible window and must never
   * merge these in; the events list spreads both.
   */
  const dateRange = computed<EventDateRange>(() => ({
    from: queryString('from') || undefined,
    to: queryString('to') || undefined,
  }))

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

  return { queryString, queryList, filters, dateRange, applyFilters }
}
