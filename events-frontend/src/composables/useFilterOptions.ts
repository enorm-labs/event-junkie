import { computed } from 'vue'
import type { FilterOption } from '@/components/MultiSelectFilter.vue'
import { useFormat } from '@/composables/useFormat'
import { DISTRICTS } from '@/lib/districts'
import { EVENT_TYPES } from '@/lib/eventTypes'
import { GENRE_FAMILIES } from '@/lib/genreFamilies'
import { VENUE_CHARACTERS } from '@/lib/venueCharacters'
import { VENUE_TYPES } from '@/lib/venueTypes'

/** The labelled options of the multi-value filters, shared by the event bar and the venue list. */
export function useFilterOptions() {
  const { formatEventType, formatFamily, formatVenueCharacter, formatVenueType } = useFormat()
  return {
    eventTypeOptions: computed<FilterOption[]>(() =>
      EVENT_TYPES.map((type) => ({ value: type, label: formatEventType(type) })),
    ),
    familyOptions: computed<FilterOption[]>(() =>
      GENRE_FAMILIES.map((family) => ({ value: family, label: formatFamily(family) })),
    ),
    venueTypeOptions: computed<FilterOption[]>(() =>
      VENUE_TYPES.map((type) => ({ value: type, label: formatVenueType(type) })),
    ),
    venueCharacterOptions: computed<FilterOption[]>(() =>
      VENUE_CHARACTERS.map((slug) => ({ value: slug, label: formatVenueCharacter(slug) })),
    ),
    districtOptions: DISTRICTS.map((d): FilterOption => ({ value: d.slug, label: d.label })),
  }
}
