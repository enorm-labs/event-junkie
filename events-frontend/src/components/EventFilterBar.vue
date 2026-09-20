<script lang="ts" setup>
/**
 * The shared event filter bar for the events list and the calendar. Every control writes
 * straight to the URL query via `useEventFilters` and reads its value back from there, so the
 * component holds no filter state; the only local state is the two free-text drafts (search,
 * price range), applied on submit.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import BaseInput from '@/components/BaseInput.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import { useEventFilters } from '@/composables/useEventFilters'
import { useGenres } from '@/composables/useGenres'
import { useAllVenues } from '@/composables/useVenues'
import { DATE_PRESETS, type DateRange } from '@/lib/dateRanges'
import { DISTRICTS } from '@/lib/districts'
import { GENRE_FAMILIES } from '@/lib/genreFamilies'
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
 * one clears the range, the only way back to "any date" without emptying both inputs.
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

/**
 * The family the bar shows as chosen: the URL's, or, for a link from before families existed
 * carrying only `genre=`, the family of that style.
 */
const activeFamily = computed(() => {
  const family = queryString('family')
  if (family) return family
  const genre = queryString('genre')
  return (genres.data.value ?? []).find((tag) => tag.slug === genre)?.family ?? ''
})

/**
 * The styles inside the chosen family, the second level of the genre filter (#363). Empty when
 * no family is chosen, which hides the select; a tag without a family is offered nowhere.
 */
const stylesInFamily = computed(() => {
  if (!activeFamily.value) return []
  return (genres.data.value ?? []).filter((tag) => tag.family === activeFamily.value)
})

/** A new family invalidates the style, which is the one filter that depends on another. */
function applyFamily(family: string) {
  applyFilters({ family, genre: '' })
}

// Drafts are seeded from the URL and re-synced whenever it changes elsewhere.
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
        @change="applyFilters({ from: ($event.target as HTMLInputElement).value })"
        @click="openDatePicker"
      />
      <span class="text-sm text-muted-foreground">–</span>
      <BaseInput
        :aria-label="t('events.filters.latestDate')"
        :min="queryString('from') || undefined"
        :model-value="queryString('to')"
        type="date"
        @change="applyFilters({ to: ($event.target as HTMLInputElement).value })"
        @click="openDatePicker"
      />

      <!-- Shortcuts that set the same from/to the inputs do, so a preset is as shareable. -->
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

    <!--
      A funnel: when, then what, then where, then how much. Each pair wraps as one, since with real
      venue names the type select used to trail the date row. "Free only" is the price axis at
      zero, so it stays with the price range.
    -->
    <div class="flex flex-wrap gap-3">
      <BaseSelect
        :aria-label="t('events.filters.byType')"
        :model-value="queryString('eventType')"
        @change="applyFilters({ eventType: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">{{ t('events.filters.allTypes') }}</option>
        <!-- The value stays the raw enum; the label comes from the helper the cards use. -->
        <option v-for="type in EVENT_TYPES" :key="type" :value="type">
          {{ formatEventType(type) }}
        </option>
      </BaseSelect>

      <!--
        Genre is two levels: thirteen families, then the styles of the chosen one. The family list
        is the constant, so a family link never lands on an empty select; style options carry the
        tag slug, so older `genre=` links keep working.
      -->
      <BaseSelect
        :aria-label="t('events.filters.byGenre')"
        :model-value="activeFamily"
        @change="applyFamily(($event.target as HTMLSelectElement).value)"
      >
        <option value="">{{ t('events.filters.allGenres') }}</option>
        <option v-for="family in GENRE_FAMILIES" :key="family" :value="family">
          {{ t(`events.filters.families.${family}`) }}
        </option>
      </BaseSelect>

      <BaseSelect
        v-if="stylesInFamily.length"
        :aria-label="t('events.filters.bySubgenre')"
        :model-value="queryString('genre')"
        @change="applyFilters({ genre: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">{{ t('events.filters.allSubgenres') }}</option>
        <option v-for="tag in stylesInFamily" :key="tag.slug" :value="tag.slug ?? ''">
          {{ tag.name }}
        </option>
      </BaseSelect>
    </div>

    <div class="flex flex-wrap gap-3">
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
    </div>

    <div class="flex flex-wrap items-center gap-3">
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
          :checked="queryString('free') === 'true'"
          class="size-4 rounded border-border accent-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
          type="checkbox"
          @change="
            applyFilters({ free: ($event.target as HTMLInputElement).checked ? 'true' : '' })
          "
        />
        {{ t('events.filters.freeOnly') }}
      </label>
    </div>

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
  </div>
</template>
