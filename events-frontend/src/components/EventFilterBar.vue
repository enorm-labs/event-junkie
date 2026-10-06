<script lang="ts" setup>
/**
 * The shared event filter bar for the events list, the calendar and the map. Every control writes
 * straight to the URL query via `useEventFilters` and reads its value back from there, so the
 * component holds no filter state; the local state is the two free-text drafts and whether "More
 * filters" is open. Search applies as the visitor types, the price range on Enter or leaving the field.
 */
import { ChevronDown } from '@lucide/vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import BaseInput from '@/components/BaseInput.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import MultiSelectFilter from '@/components/MultiSelectFilter.vue'
import SearchInput from '@/components/SearchInput.vue'
import { useEventFilters } from '@/composables/useEventFilters'
import { activeFamilies as familiesShown, useFilterLists } from '@/composables/useFilterLists'
import { useSearchDraft } from '@/composables/useSearchDraft'
import { activePresetKey, DATE_PRESETS, type DateRange } from '@/lib/dateRanges'
import { useFilterOptions } from '@/composables/useFilterOptions'
import { useI18n } from 'vue-i18n'
import { PANEL_CLASS } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    showDateRange?: boolean
    /** The range the view shows when the URL names none; the preset that matches reads as pressed. */
    defaultRange?: DateRange
    /** Offers On now beside the presets; only a view that can filter to this moment opts in. */
    showOnNow?: boolean
  }>(),
  { showDateRange: true, defaultRange: undefined, showOnNow: false },
)

const route = useRoute()
const { queryString, queryList, applyFilters } = useEventFilters()

/**
 * Opens the browser's calendar on a click anywhere in the field; Chrome otherwise opens it from
 * the icon only. `showPicker` is absent on older browsers, hence the optional call.
 */
function openDatePicker(event: MouseEvent) {
  const input = event.currentTarget as HTMLInputElement & { showPicker?: () => void }
  input.showPicker?.()
}

/**
 * A preset is the two date bounds, so it stays in the URL and is shareable. Clicking the active
 * one clears the range, the only way back to "any date" without emptying both inputs. Any date
 * choice ends On now, so one time button is pressed at most.
 */
function togglePreset(key: string, range: DateRange) {
  const active = isPresetActive(key)
  applyFilters({ from: active ? '' : range.from, to: active ? '' : range.to, now: '' })
}

function applyDate(bound: 'from' | 'to', value: string) {
  applyFilters({ [bound]: value, now: '' })
}

/** On now already means today, so it takes the place of the range rather than narrowing it. */
const onNow = computed(() => queryString('now') === '1')

function toggleOnNow() {
  applyFilters(onNow.value ? { now: '' } : { now: '1', from: '', to: '' })
}

/**
 * True when the URL's range reads as this preset — it renders as the pressed button. With no
 * range in the URL, the view's own default stands in, so the map's "today" shows Tonight pressed.
 */
function isPresetActive(key: string): boolean {
  if (onNow.value) return false
  const from = queryString('from')
  const to = queryString('to')
  const shown = !from && !to && props.defaultRange ? props.defaultRange : { from, to }
  return activePresetKey(shown) === key
}

const { genres, venues } = useFilterLists()

/**
 * A native select is as wide as its longest option, and the venue list arrives after the first
 * paint. On a phone the grown select would wrap its row and drop the page (#1830). Two equal
 * columns there make every width the viewport's; from `sm` up the rows have the room.
 */
const SELECT_ROW_CLASS = 'grid w-full grid-cols-2 gap-3 sm:flex sm:w-auto sm:flex-wrap'
const SELECT_CLASS = 'w-full min-w-0 truncate sm:w-auto'

const { t } = useI18n()
const {
  eventTypeOptions: typeOptions,
  familyOptions,
  districtOptions,
  venueTypeOptions,
  timeOfDayOptions,
} = useFilterOptions()

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

const { search, applySearch } = useSearchDraft()

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

/**
 * How many of the filters behind "More filters" are set, so a closed section still says it is
 * narrowing the list. Each counts once, however many values it holds. Read from the URL alone: a
 * count that waited for the genre list would open the section late, or not at all.
 */
const moreCount = computed(
  () =>
    [
      queryList('timeOfDay').length,
      queryList('eventType').length,
      queryList('family').length || queryString('genre'),
      queryString('venue'),
      queryList('district').length,
      queryList('venueType').length,
      queryString('minPrice') || queryString('maxPrice'),
      queryString('free'),
      queryString('excludeSoldOut'),
    ].filter(Boolean).length,
)

/**
 * On a phone the bar stood about 500 px tall before the first event, and the map's own row pushed
 * the map below the fold (#2347). The second tier starts closed unless the URL already sets one of
 * its filters. Open on a desktop it took the bar from three rows to five, so it starts closed there
 * too.
 */
const moreOpen = ref(moreCount.value > 0)

onMounted(() => {
  genres.run()
  venues.run()
})
</script>

<template>
  <div :class="PANEL_CLASS">
    <div class="flex w-full flex-wrap items-center gap-2">
      <form class="min-w-0 flex-1 basis-48" role="search" @submit.prevent="applySearch">
        <SearchInput
          v-model="search"
          :placeholder="t('events.filters.searchPlaceholder')"
          @change="applySearch"
          @clear="applySearch"
        />
      </form>
      <Button
        :aria-expanded="moreOpen"
        aria-controls="more-filters"
        type="button"
        variant="outline"
        @click="moreOpen = !moreOpen"
      >
        {{ moreCount ? t('events.filters.moreCount', { n: moreCount }) : t('events.filters.more') }}
        <ChevronDown
          :class="['transition-transform', { 'rotate-180': moreOpen }]"
          aria-hidden="true"
        />
      </Button>
      <ClearAllFilters />
    </div>

    <!--
      Two native date inputs rather than a range picker: the value is already the ISO date the BFF
      wants, and `min`/`max` express "to cannot precede from". Neither bound is floored at today:
      past dates are the archive. The browser's calendar follows dark mode via the `color-scheme`
      on `:root`/`.dark` in main.css.
    -->
    <div v-if="showDateRange" class="flex flex-wrap items-center gap-2">
      <BaseInput
        :aria-label="t('events.filters.earliestDate')"
        :max="queryString('to') || undefined"
        :model-value="queryString('from')"
        type="date"
        @change="applyDate('from', ($event.target as HTMLInputElement).value)"
        @click="openDatePicker"
      />
      <span class="text-body text-muted-foreground">–</span>
      <BaseInput
        :aria-label="t('events.filters.latestDate')"
        :min="queryString('from') || undefined"
        :model-value="queryString('to')"
        type="date"
        @change="applyDate('to', ($event.target as HTMLInputElement).value)"
        @click="openDatePicker"
      />

      <!-- On now first: the time buttons widen from left to right. -->
      <Button
        v-if="showOnNow"
        :aria-pressed="onNow"
        :variant="onNow ? 'default' : 'outline'"
        size="sm"
        type="button"
        @click="toggleOnNow"
      >
        {{ t('dateRange.onNow') }}
      </Button>
      <!-- Shortcuts that set the same from/to the inputs do, so a preset is as shareable. -->
      <Button
        v-for="preset in DATE_PRESETS"
        :key="preset.key"
        :aria-pressed="isPresetActive(preset.key)"
        :variant="isPresetActive(preset.key) ? 'default' : 'outline'"
        size="sm"
        type="button"
        @click="togglePreset(preset.key, preset.range())"
      >
        {{ t(preset.key) }}
      </Button>
    </div>

    <!--
      A funnel: what kind of night, then where, then how much, one line each. Sharing lines, a select that grew
      when its options arrived re-wrapped the rows under it and moved the grid 44 px (#1830, #2347);
      on its own line it can only grow sideways.
    -->
    <div v-show="moreOpen" id="more-filters" class="flex w-full flex-col items-start gap-3">
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
          @change="applyFilters({ eventType: $event })"
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
  </div>
</template>
