import { computed } from 'vue'
import type { FilterOption } from '@/components/MultiSelectFilter.vue'
import { useFormat } from '@/composables/useFormat'
import { DISTRICTS } from '@/lib/districts'
import { EVENT_TYPES } from '@/lib/eventTypes'
import { GENRE_FAMILIES } from '@/lib/genreFamilies'
import { PARTY_FEATURES } from '@/lib/partyFeatures'
import { FILTER_LANGUAGES } from '@/lib/spokenLanguages'
import { TIMES_OF_DAY } from '@/lib/timesOfDay'
import { VENUE_CHARACTERS } from '@/lib/venueCharacters'
import { VENUE_TYPES } from '@/lib/venueTypes'

/** The labelled options of the multi-value filters, shared by the event bar and the venue list. */
export function useFilterOptions() {
  const {
    formatEventType,
    formatFamily,
    formatPartyFeature,
    formatSpokenLanguage,
    formatTimeOfDay,
    formatVenueCharacter,
    formatVenueType,
  } = useFormat()
  return {
    eventTypeOptions: computed<FilterOption[]>(() =>
      EVENT_TYPES.map((type) => ({ value: type, label: formatEventType(type) })),
    ),
    timeOfDayOptions: computed<FilterOption[]>(() =>
      TIMES_OF_DAY.map((slot) => ({ value: slot, label: formatTimeOfDay(slot) })),
    ),
    languageOptions: computed<FilterOption[]>(() =>
      FILTER_LANGUAGES.map((code) => ({ value: code, label: formatSpokenLanguage([code]) })),
    ),
    featureOptions: computed<FilterOption[]>(() =>
      PARTY_FEATURES.map((slug) => ({ value: slug, label: formatPartyFeature(slug) })),
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
