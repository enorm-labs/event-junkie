<script lang="ts" setup>
/**
 * The shared event filter bar for the events list, the calendar and the map. Every control writes
 * straight to the URL query via `useEventFilters` and reads its value back from there, so the
 * component holds no filter state; the local state is the search draft and whether "More filters"
 * is open. Below `sm` "More filters" is a bottom sheet holding the date inputs too (#2890).
 */
import { ChevronDown, X } from '@lucide/vue'
import { computed, onMounted, ref, useTemplateRef } from 'vue'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import DateRangeInputs from '@/components/DateRangeInputs.vue'
import EventFilterPanel from '@/components/EventFilterPanel.vue'
import SearchInput from '@/components/SearchInput.vue'
import { useEventFilters } from '@/composables/useEventFilters'
import { shareFilterLists } from '@/composables/useFilterLists'
import { useNarrowViewport } from '@/composables/useNarrowViewport'
import { useSearchDraft } from '@/composables/useSearchDraft'
import { activePresetKey, DATE_PRESETS, type DateRange } from '@/lib/dateRanges'
import { languageFilterApplies } from '@/lib/spokenLanguages'
import { useI18n } from 'vue-i18n'
import { PANEL_CLASS } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    showDateRange?: boolean
    /** The range the view shows when the URL names none; the preset that matches reads as pressed. */
    defaultRange?: DateRange
    /** Offers On now beside the presets; only a view that can filter to this moment opts in. */
    showOnNow?: boolean
    /** How many events the filters match, for the sheet's button; a view without a count omits it. */
    resultCount?: number
  }>(),
  { showDateRange: true, defaultRange: undefined, showOnNow: false, resultCount: undefined },
)

const { t } = useI18n()
const { queryString, queryList, applyFilters } = useEventFilters()

/**
 * A preset is the two date bounds, so it stays in the URL and is shareable. Clicking the active
 * one clears the range, the only way back to "any date" without emptying both inputs. Any date
 * choice ends On now, so one time button is pressed at most.
 */
function togglePreset(key: string, range: DateRange) {
  const active = isPresetActive(key)
  applyFilters({ from: active ? '' : range.from, to: active ? '' : range.to, now: '' })
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

const { genres, venues } = shareFilterLists()

const { search, applySearch } = useSearchDraft()

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
      queryList('feature').length,
      languageFilterApplies(queryList('eventType')) && queryList('language').length,
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
 * From `sm` up the second tier starts open when the URL sets one of its filters (#2347). Below it
 * the tier is a sheet that opens only on a tap: open by itself it covered the page (#2890).
 */
const narrow = useNarrowViewport()
const moreOpen = ref(!narrow.value && moreCount.value > 0)
const sheetOpen = ref(false)
const expanded = computed(() => (narrow.value ? sheetOpen.value : moreOpen.value))

// Safari does not focus a button on a click, so the dialog has nothing to return to on its own.
const toggle = useTemplateRef<{ $el: HTMLElement }>('toggle')
function focusToggle(event: Event) {
  event.preventDefault()
  toggle.value?.$el.focus()
}

function toggleMore() {
  if (narrow.value) sheetOpen.value = true
  else moreOpen.value = !moreOpen.value
}

// "More" is relative to the presets beside it, and a phone's first row has no room for it.
const moreLabel = computed(() => {
  const [plain, counted] = narrow.value
    ? ['events.filters.filters', 'events.filters.filtersCount']
    : ['events.filters.more', 'events.filters.moreCount']
  return moreCount.value ? t(counted, { n: moreCount.value }) : t(plain)
})

/**
 * The language filter leaves out every event whose listing does not name its language, so while it
 * narrows the list a line says so outside the closed menu (#2524). Hidden with the control.
 */
const languageNarrows = computed(
  () => languageFilterApplies(queryList('eventType')) && queryList('language').length > 0,
)

const showResults = computed(() =>
  props.resultCount === undefined
    ? t('events.filters.showResults')
    : t('events.filters.showResultsCount', { count: props.resultCount }),
)

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
        ref="toggle"
        :aria-controls="narrow ? undefined : 'more-filters'"
        :aria-expanded="expanded"
        :aria-haspopup="narrow ? 'dialog' : undefined"
        type="button"
        variant="outline"
        @click="toggleMore"
      >
        {{ moreLabel }}
        <ChevronDown
          :class="['transition-transform', { 'rotate-180': expanded }]"
          aria-hidden="true"
        />
      </Button>
      <ClearAllFilters :compact="narrow" />
    </div>

    <!-- Below `sm` the presets keep one row and scroll sideways; the date inputs are in the sheet. -->
    <div
      v-if="showDateRange"
      class="flex w-full flex-nowrap items-center gap-2 overflow-x-auto sm:w-auto sm:flex-wrap sm:overflow-visible"
    >
      <DateRangeInputs v-if="!narrow" />

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

    <!-- Rendered in one place at a time, so a control never exists twice. -->
    <div v-if="!narrow" v-show="moreOpen" id="more-filters" class="w-full">
      <EventFilterPanel />
    </div>
    <Sheet v-else v-model:open="sheetOpen">
      <SheetContent :aria-describedby="undefined" @close-auto-focus="focusToggle">
        <div class="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
          <SheetTitle>{{ t('events.filters.sheetTitle') }}</SheetTitle>
          <Button
            :aria-label="t('events.filters.closeSheet')"
            size="icon"
            type="button"
            variant="ghost"
            @click="sheetOpen = false"
          >
            <X aria-hidden="true" />
          </Button>
        </div>
        <div class="flex-1 space-y-3 overflow-y-auto p-4">
          <DateRangeInputs v-if="showDateRange" />
          <EventFilterPanel />
        </div>
        <div class="flex gap-3 border-t border-border p-4">
          <ClearAllFilters />
          <Button class="flex-1" type="button" @click="sheetOpen = false">
            {{ showResults }}
          </Button>
        </div>
      </SheetContent>
    </Sheet>

    <!-- Last in the bar, next to the results it narrows; it stays while the menu is closed. -->
    <p v-if="languageNarrows" class="w-full text-body text-muted-foreground">
      {{ t('events.filters.languageNarrows') }}
    </p>
  </div>
</template>
