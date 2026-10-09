import { useI18n } from 'vue-i18n'
import type { EventFilterValues } from './useEvents'
import { activeFamilies, type FilterLists, useFilterLists } from './useFilterLists'
import { useFormat } from './useFormat'
import { districtLabel } from '@/lib/districts'

/**
 * The active filters as the labels the filter bar shows, to name a calendar subscription (#2772),
 * a time of night without its hours.
 * Prices and "hide sold out" stay out: they narrow a list without saying what it is. A venue or a
 * style the lists have not loaded yet shows its slug until they do.
 */
export function useFilterLabels(lists: FilterLists = useFilterLists()) {
  const { t } = useI18n()
  const { formatEventType, formatTimeOfDay, formatFamily, formatVenueType, formatSpokenLanguage } =
    useFormat()

  function filterLabels(filters: EventFilterValues): string[] {
    const tags = lists.genres.data.value ?? []
    const style =
      filters.genre && (tags.find((tag) => tag.slug === filters.genre)?.name ?? filters.genre)
    const venue =
      filters.venue &&
      ((lists.venues.data.value ?? []).find((v) => v.slug === filters.venue)?.name ?? filters.venue)
    return [
      ...(filters.eventType ?? []).map((type) => formatEventType(type)),
      ...(filters.language ?? []).map((code) => formatSpokenLanguage([code])),
      ...(filters.timeOfDay ?? []).map((slot) => formatTimeOfDay(slot).replace(/\s*\(.*\)$/, '')),
      ...activeFamilies(filters.family ?? [], filters.genre, tags).map((family) =>
        formatFamily(family),
      ),
      style,
      venue,
      ...(filters.venueType ?? []).map((type) => formatVenueType(type)),
      ...(filters.district ?? []).map((district) => districtLabel(district)),
      filters.free ? t('events.card.free') : undefined,
      filters.q ? t('events.filters.searchLabel', { q: filters.q }) : undefined,
    ].filter((label): label is string => !!label)
  }

  return { filterLabels }
}
