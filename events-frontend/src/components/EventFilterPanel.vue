<script lang="ts" setup>
/**
 * The filters behind "More filters": what kind of night, then where, then how much. The bar shows
 * them inline from `sm` up and in a bottom sheet below it (#2890), never both.
 */
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import BaseInput from '@/components/BaseInput.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import MultiSelectFilter from '@/components/MultiSelectFilter.vue'
import { useEventFilters } from '@/composables/useEventFilters'
import { activeFamilies as familiesShown, useFilterLists } from '@/composables/useFilterLists'
import { useFilterOptions } from '@/composables/useFilterOptions'
import { languageFilterApplies } from '@/lib/spokenLanguages'

const route = useRoute()
const { t } = useI18n()
const { queryString, queryList, applyFilters } = useEventFilters()
const { genres, venues } = useFilterLists()

/**
 * A native select is as wide as its longest option, and the venue list arrives after the first
 * paint. On a phone the grown select would wrap its row and drop the page (#1830). Two equal
 * columns there make every width the viewport's; from `sm` up the rows have the room.
 */
const SELECT_ROW_CLASS = 'grid w-full grid-cols-2 gap-3 sm:flex sm:w-auto sm:flex-wrap'
const SELECT_CLASS = 'w-full min-w-0 truncate sm:w-auto'

const {
  eventTypeOptions: typeOptions,
  familyOptions,
  districtOptions,
  venueTypeOptions,
  timeOfDayOptions,
  languageOptions,
  featureOptions,
} = useFilterOptions()

/**
 * The language filter, offered only when the type filter selects only comedy, readings, quizzes or
 * shows: the listings of other types never say (#2524). A type change that leaves those drops the
 * language, so no hidden filter narrows the list.
 */
const languageShown = computed(() => languageFilterApplies(queryList('eventType')))

function applyEventTypes(types: string[]) {
  applyFilters({
    eventType: types,
    language: languageFilterApplies(types) ? queryList('language') : [],
  })
}

const activeFamilies = computed(() =>
  familiesShown(queryList('family'), queryString('genre'), genres.data.value ?? []),
)

/**
 * The styles inside the chosen family, the second level of the genre filter (#363). Offered only
 * for exactly one family: a style across several has no clear reading (#1996). A tag without a
 * family is offered nowhere.
 */
const soleFamily = computed(() =>
  activeFamilies.value.length === 1 ? activeFamilies.value[0] : undefined,
)
const stylesInFamily = computed(() =>
  (genres.data.value ?? []).filter((tag) => tag.family === soleFamily.value),
)

/** Any family change invalidates the style, which is the one filter that depends on another. */
function applyFamilies(families: string[]) {
  applyFilters({ family: families, genre: '' })
}

// The price drafts are seeded from the URL and re-synced whenever it changes elsewhere.
const minPrice = ref(queryString('minPrice'))
const maxPrice = ref(queryString('maxPrice'))
watch(
  () => [route.query.minPrice, route.query.maxPrice],
  () => {
    minPrice.value = queryString('minPrice')
    maxPrice.value = queryString('maxPrice')
  },
)

function applyPrice() {
  if (minPrice.value !== queryString('minPrice') || maxPrice.value !== queryString('maxPrice')) {
    applyFilters({ minPrice: minPrice.value, maxPrice: maxPrice.value })
  }
}

function toggleFlag(key: 'free' | 'excludeSoldOut') {
  applyFilters({ [key]: queryString(key) === 'true' ? '' : 'true' })
}
</script>

<template>
  <!--
    A funnel, one line each. Sharing lines, a select that grew when its options arrived re-wrapped
    the rows under it and moved the grid 44 px (#1830, #2347); on its own line it can only grow sideways.
  -->
  <div class="flex w-full flex-col items-start gap-3">
    <div :class="SELECT_ROW_CLASS">
      <MultiSelectFilter
        :all-label="t('events.filters.allTimes')"
        :class="SELECT_CLASS"
        :clear-label="t('events.filters.clearTimes')"
        :count-label="(n) => t('events.filters.timesSelected', { n })"
        :hint="t('events.filters.timeHint')"
        :label="t('events.filters.byTime')"
        :options="timeOfDayOptions"
        :selected="queryList('timeOfDay')"
        @change="applyFilters({ timeOfDay: $event })"
      />

      <MultiSelectFilter
        :all-label="t('events.filters.allTypes')"
        :class="SELECT_CLASS"
        :clear-label="t('events.filters.clearTypes')"
        :count-label="(n) => t('events.filters.typesSelected', { n })"
        :label="t('events.filters.byType')"
        :options="typeOptions"
        :selected="queryList('eventType')"
        @change="applyEventTypes"
      />

      <!-- What kind of night, as the event's own text says (#2631): every chosen feature must hold. -->
      <MultiSelectFilter
        :all-label="t('events.filters.allFeatures')"
        :class="SELECT_CLASS"
        :clear-label="t('events.filters.clearFeatures')"
        :count-label="(n) => t('events.filters.featuresSelected', { n })"
        :hint="t('events.filters.featureHint')"
        :label="t('events.filters.byFeature')"
        :options="featureOptions"
        :selected="queryList('feature')"
        @change="applyFilters({ feature: $event })"
      />

      <MultiSelectFilter
        v-if="languageShown"
        :all-label="t('events.filters.allLanguages')"
        :class="SELECT_CLASS"
        :clear-label="t('events.filters.clearLanguages')"
        :count-label="(n) => t('events.filters.languagesSelected', { n })"
        :hint="t('events.filters.languageHint')"
        :label="t('events.filters.byLanguage')"
        :options="languageOptions"
        :selected="queryList('language')"
        @change="applyFilters({ language: $event })"
      />

      <!--
        Genre is two levels: any of thirteen families, then the styles when exactly one is chosen.
        The family list is the constant, so a family link never lands on an empty list; style
        options carry the tag slug, so older `genre=` links keep working.
      -->
      <MultiSelectFilter
        :all-label="t('events.filters.allGenres')"
        :class="SELECT_CLASS"
        :clear-label="t('events.filters.clearFamilies')"
        :count-label="(n) => t('events.filters.familiesSelected', { n })"
        :label="t('events.filters.byGenre')"
        :options="familyOptions"
        :selected="activeFamilies"
        @change="applyFamilies"
      />

      <!-- Shown once the URL names one family, not once its styles load, so it cannot appear late. -->
      <BaseSelect
        v-if="soleFamily"
        :aria-label="t('events.filters.bySubgenre')"
        :class="[SELECT_CLASS, 'col-span-2']"
        :model-value="queryString('genre')"
        @change="applyFilters({ genre: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">{{ t('events.filters.allSubgenres') }}</option>
        <option v-for="tag in stylesInFamily" :key="tag.slug" :value="tag.slug ?? ''">
          {{ tag.name }}
        </option>
      </BaseSelect>
    </div>

    <div :class="SELECT_ROW_CLASS">
      <BaseSelect
        :aria-label="t('events.filters.byVenue')"
        :class="SELECT_CLASS"
        :model-value="queryString('venue')"
        @change="applyFilters({ venue: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">{{ t('events.filters.allVenues') }}</option>
        <option v-for="v in venues.data.value ?? []" :key="v.slug" :value="v.slug ?? ''">
          {{ v.name }}
        </option>
      </BaseSelect>

      <MultiSelectFilter
        :all-label="t('events.filters.allDistricts')"
        :class="SELECT_CLASS"
        :clear-label="t('events.filters.clearDistricts')"
        :count-label="(n) => t('events.filters.districtsSelected', { n })"
        :label="t('events.filters.byDistrict')"
        :options="districtOptions"
        :selected="queryList('district')"
        @change="applyFilters({ district: $event })"
      />

      <MultiSelectFilter
        :all-label="t('events.filters.allVenueTypes')"
        :class="SELECT_CLASS"
        :clear-label="t('events.filters.clearVenueTypes')"
        :count-label="(n) => t('events.filters.venueTypesSelected', { n })"
        :label="t('events.filters.byVenueType')"
        :options="venueTypeOptions"
        :selected="queryList('venueType')"
        @change="applyFilters({ venueType: $event })"
      />
    </div>

    <!--
      The price range applies when a bound is left or on Enter, so it needs no button. "Free
      only" is the price axis at zero, so it stays beside the range; both flags are toggles in
      the presets' style rather than checkboxes, as On now is.
    -->
    <div class="flex flex-wrap items-center gap-2">
      <BaseInput
        v-model="minPrice"
        :aria-label="t('events.filters.minPrice')"
        :placeholder="t('events.filters.minPricePlaceholder')"
        class="w-20"
        inputmode="decimal"
        min="0"
        step="0.01"
        type="number"
        @change="applyPrice"
        @keydown.enter="applyPrice"
      />
      <span class="text-body text-muted-foreground">–</span>
      <BaseInput
        v-model="maxPrice"
        :aria-label="t('events.filters.maxPrice')"
        :placeholder="t('events.filters.maxPricePlaceholder')"
        class="w-20"
        inputmode="decimal"
        min="0"
        step="0.01"
        type="number"
        @change="applyPrice"
        @keydown.enter="applyPrice"
      />
      <Button
        :aria-pressed="queryString('free') === 'true'"
        :variant="queryString('free') === 'true' ? 'default' : 'outline'"
        size="sm"
        type="button"
        @click="toggleFlag('free')"
      >
        {{ t('events.filters.freeOnly') }}
      </Button>
      <Button
        :aria-pressed="queryString('excludeSoldOut') === 'true'"
        :variant="queryString('excludeSoldOut') === 'true' ? 'default' : 'outline'"
        size="sm"
        type="button"
        @click="toggleFlag('excludeSoldOut')"
      >
        {{ t('events.filters.hideSoldOut') }}
      </Button>
    </div>
  </div>
</template>
