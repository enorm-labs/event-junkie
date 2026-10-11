import { computed } from 'vue'
import { languageFilterApplies } from '@/lib/spokenLanguages'
import { useQueryParams } from './useListQuery'
import type { EventDateRange, EventFilterValues } from './useEvents'

/**
 * Reads and writes the event filters that live in the URL query, so any view showing the filter
 * bar is shareable and survives back/forward. The bar itself and the views that consume the
 * result both call this — the URL is the single source of truth, so no state is passed between
 * them.
 */
export function useEventFilters() {
  const { queryString, queryList, applyFilters } = useQueryParams()

  const filters = computed<EventFilterValues>(() => {
    const eventTypes = queryList('eventType')
    const families = queryList('family')
    const districts = queryList('district')
    const venueTypes = queryList('venueType')
    const timesOfDay = queryList('timeOfDay')
    const features = queryList('feature')
    // Sent only while the bar offers it, so a hidden control never narrows the list (#2524).
    const languages = languageFilterApplies(eventTypes) ? queryList('language') : []
    return {
      q: queryString('q') || undefined,
      eventType: eventTypes.length ? eventTypes : undefined,
      venue: queryString('venue') || undefined,
      district: districts.length ? districts : undefined,
      venueType: venueTypes.length ? venueTypes : undefined,
      genre: queryString('genre') || undefined,
      family: families.length ? families : undefined,
      minPrice: queryString('minPrice') ? Number(queryString('minPrice')) : undefined,
      maxPrice: queryString('maxPrice') ? Number(queryString('maxPrice')) : undefined,
      excludeSoldOut: queryString('excludeSoldOut') === 'true' || undefined,
      free: queryString('free') === 'true' || undefined,
      timeOfDay: timesOfDay.length ? timesOfDay : undefined,
      language: languages.length ? languages : undefined,
      feature: features.length ? features : undefined,
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

  return { queryString, queryList, filters, dateRange, applyFilters }
}
