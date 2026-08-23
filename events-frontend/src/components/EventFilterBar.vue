<script lang="ts" setup>
/**
 * The shared event filter bar, used by both the events list and the calendar.
 *
 * Every control writes straight to the URL query via `useEventFilters`, and reads its current
 * value back from there — so the component holds no filter state of its own and the two views
 * stay in sync with the address bar without prop or event plumbing. The only local state is the
 * two free-text drafts (search box, price range) that are applied on submit rather than on every
 * keystroke; selects and checkboxes apply immediately.
 */
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import BaseInput from '@/components/BaseInput.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import { useEventFilters } from '@/composables/useEventFilters'
import { useGenres } from '@/composables/useGenres'
import { useAllVenues } from '@/composables/useVenues'
import { DATE_PRESETS, type DateRange } from '@/lib/dateRanges'
import { DISTRICTS } from '@/lib/districts'
import { todayIso } from '@/lib/format'
import { useFormat } from '@/composables/useFormat'
import { useI18n } from 'vue-i18n'
import { PANEL_CLASS } from '@/lib/utils'

const { formatEventType } = useFormat()

const EVENT_TYPES = [
  'CONCERT',
  'FESTIVAL',
  'PARTY',
  'QUIZ',
  'CLUB_NIGHT',
  'SHOW',
  'SCREENING',
  'EXHIBITION',
  'READING',
  'OTHER',
]

withDefaults(defineProps<{ showDateRange?: boolean }>(), { showDateRange: true })

const route = useRoute()
const { queryString, applyFilters } = useEventFilters()

/** Lower bound for the date pickers: the app is about upcoming events, so past dates are out. */
const today = todayIso()

/**
 * Opens the browser's calendar on a click anywhere in the field. Without this, Chrome only
 * opens it from the calendar icon and a click on the text just moves between date segments.
 * `showPicker` is absent on older browsers, where the icon still works — hence the optional call.
 */
function openDatePicker(event: MouseEvent) {
  const input = event.currentTarget as HTMLInputElement & { showPicker?: () => void }
  input.showPicker?.()
}

/**
 * A preset is just the two date bounds, so it stays in the URL like every other filter and a
 * preset link is shareable. Clicking the active one clears the range again, which is the only
 * way back to "any date" without emptying both inputs by hand.
 */
function togglePreset(range: DateRange) {
  const active = isPresetActive(range)
  applyFilters({ from: active ? '' : range.from, to: active ? '' : range.to })
}

/** True when the URL's range is exactly this preset — it renders as the pressed button. */
function isPresetActive(range: DateRange): boolean {
  return queryString('from') === range.from && queryString('to') === range.to
}

const genres = useGenres()
const venues = useAllVenues()

// Drafts are seeded from the URL and re-synced whenever it changes elsewhere (back/forward,
// a link with filters, another control resetting the query).
const search = ref(queryString('q'))
watch(
  () => route.query.q,
  () => {
    search.value = queryString('q')
  },
)

const minPrice = ref(queryString('minPrice'))
const maxPrice = ref(queryString('maxPrice'))
watch(
  () => [route.query.minPrice, route.query.maxPrice],
  () => {
    minPrice.value = queryString('minPrice')
    maxPrice.value = queryString('maxPrice')
  },
)

onMounted(() => {
  genres.run()
  venues.run()
})

const { t } = useI18n()
</script>

<template>
  <div :class="PANEL_CLASS">
    <form class="flex gap-2" @submit.prevent="applyFilters({ q: search })">
      <BaseInput
        v-model="search"
        :placeholder="t('events.filters.searchPlaceholder')"
        class="px-3"
        type="search"
      />
      <Button type="submit" variant="outline">{{ t('common.actions.search') }}</Button>
    </form>

    <!--
      Two native date inputs rather than a range-picker component: the browser supplies the
      calendar, the value is already the ISO `YYYY-MM-DD` the BFF wants, and `min`/`max` express
      "not in the past" and "to cannot precede from" without any code. They apply on change like
      the selects, so the bar keeps a single Apply button (the price range's).
      The browser's own calendar follows our dark mode via the `color-scheme` declared on
      `:root`/`.dark` in main.css, which covers every native control rather than just these two.
    -->
    <div v-if="showDateRange" class="flex flex-wrap items-center gap-2">
      <BaseInput
        :aria-label="t('events.filters.earliestDate')"
        :max="queryString('to') || undefined"
        :min="today"
        :model-value="queryString('from')"
        type="date"
        @change="applyFilters({ from: ($event.target as HTMLInputElement).value })"
        @click="openDatePicker"
      />
      <span class="text-sm text-muted-foreground">–</span>
      <BaseInput
        :aria-label="t('events.filters.latestDate')"
        :min="queryString('from') || today"
        :model-value="queryString('to')"
        type="date"
        @change="applyFilters({ to: ($event.target as HTMLInputElement).value })"
        @click="openDatePicker"
      />

      <!--
        Shortcuts for the ranges people actually ask for. They only set the same from/to the
        inputs do, so the two stay consistent and a preset is as shareable as any other filter.
      -->
      <Button
        v-for="preset in DATE_PRESETS"
        :key="preset.key"
        :aria-pressed="isPresetActive(preset.range())"
        :variant="isPresetActive(preset.range()) ? 'default' : 'outline'"
        size="sm"
        type="button"
        @click="togglePreset(preset.range())"
      >
        {{ t(preset.key) }}
      </Button>
    </div>

    <BaseSelect
      :aria-label="t('events.filters.byType')"
      :model-value="queryString('eventType')"
      @change="applyFilters({ eventType: ($event.target as HTMLSelectElement).value })"
    >
      <option value="">{{ t('events.filters.allTypes') }}</option>
      <!--
        The option value stays the raw enum the BFF filters on; only the label is humanised,
        through the same helper the event cards use — so picking "Club night" here and reading
        it off a card are the same words.
      -->
      <option v-for="type in EVENT_TYPES" :key="type" :value="type">
        {{ formatEventType(type) }}
      </option>
    </BaseSelect>

    <BaseSelect
      :aria-label="t('events.filters.byVenue')"
      :model-value="queryString('venue')"
      @change="applyFilters({ venue: ($event.target as HTMLSelectElement).value })"
    >
      <option value="">{{ t('events.filters.allVenues') }}</option>
      <option v-for="v in venues.data.value ?? []" :key="v.slug" :value="v.slug ?? ''">
        {{ v.name }}
      </option>
    </BaseSelect>

    <BaseSelect
      :aria-label="t('events.filters.byDistrict')"
      :model-value="queryString('district')"
      @change="applyFilters({ district: ($event.target as HTMLSelectElement).value })"
    >
      <option value="">{{ t('events.filters.allDistricts') }}</option>
      <option v-for="d in DISTRICTS" :key="d.slug" :value="d.slug">{{ d.label }}</option>
    </BaseSelect>

    <BaseSelect
      :aria-label="t('events.filters.byGenre')"
      :model-value="queryString('genre')"
      @change="applyFilters({ genre: ($event.target as HTMLSelectElement).value })"
    >
      <option value="">{{ t('events.filters.allGenres') }}</option>
      <option v-for="tag in genres.data.value ?? []" :key="tag.slug" :value="tag.slug ?? ''">
        {{ tag.name }}
      </option>
    </BaseSelect>

    <form class="flex items-center gap-2" @submit.prevent="applyFilters({ minPrice, maxPrice })">
      <BaseInput
        v-model="minPrice"
        :aria-label="t('events.filters.minPrice')"
        :placeholder="t('events.filters.minPricePlaceholder')"
        class="w-20"
        inputmode="decimal"
        min="0"
        step="0.01"
        type="number"
      />
      <span class="text-sm text-muted-foreground">–</span>
      <BaseInput
        v-model="maxPrice"
        :aria-label="t('events.filters.maxPrice')"
        :placeholder="t('events.filters.maxPricePlaceholder')"
        class="w-20"
        inputmode="decimal"
        min="0"
        step="0.01"
        type="number"
      />
      <Button type="submit" variant="outline">{{ t('common.actions.apply') }}</Button>
    </form>

    <label class="flex h-8 items-center gap-2 text-sm text-muted-foreground">
      <input
        :checked="queryString('excludeSoldOut') === 'true'"
        class="size-4 rounded border-border accent-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        type="checkbox"
        @change="
          applyFilters({
            excludeSoldOut: ($event.target as HTMLInputElement).checked ? 'true' : '',
          })
        "
      />
      {{ t('events.filters.hideSoldOut') }}
    </label>

    <label class="flex h-8 items-center gap-2 text-sm text-muted-foreground">
      <input
        :checked="queryString('free') === 'true'"
        class="size-4 rounded border-border accent-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        type="checkbox"
        @change="applyFilters({ free: ($event.target as HTMLInputElement).checked ? 'true' : '' })"
      />
      {{ t('events.filters.freeOnly') }}
    </label>
  </div>
</template>
