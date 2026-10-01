<script lang="ts" setup>
import { computed, defineAsyncComponent, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { EventSummary } from '@/api/types'
import { describeError } from '@/api/client'
import { Button } from '@/components/ui/button'
import EventFilterBar from '@/components/EventFilterBar.vue'
import EventRow from '@/components/EventRow.vue'
import { fetchCalendarEvents } from '@/composables/useEvents'
import { useEventFilters } from '@/composables/useEventFilters'
import { useLocalePath } from '@/composables/useLocalePath'
import { districtLabel } from '@/lib/districts'
import { addDays, todayIso } from '@/lib/format'
import { groupByVenue, type MapPin, venuePin } from '@/lib/mapPins'
import { CARD_LIST_CLASS } from '@/lib/utils'

// MapLibre is the heaviest dependency the site has; only this route and the venues map pay for it.
const VenueMap = defineAsyncComponent(() => import('@/components/VenueMap.vue'))

/** The BFF's calendar endpoint refuses a range longer than this. */
const MAX_RANGE_DAYS = 92

const route = useRoute()
const router = useRouter()
const { filters, dateRange } = useEventFilters()
const localePath = useLocalePath()
const { t } = useI18n()

/**
 * The URL's range, or today when it names none. Today is not written into the URL, so a shared
 * link without dates means "tonight" on the day it is opened.
 */
const range = computed(() => {
  const from = dateRange.value.from ?? todayIso()
  const to = dateRange.value.to ?? (dateRange.value.from ? addDays(from, MAX_RANGE_DAYS) : from)
  const max = addDays(from, MAX_RANGE_DAYS)
  return { from, to: to > max ? max : to }
})

const events = shallowRef<EventSummary[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const unavailable = ref(false)
const selected = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    events.value = await fetchCalendarEvents(range.value.from, range.value.to, filters.value)
    error.value = null
  } catch (e) {
    events.value = []
    error.value = describeError(e, 'errors.subject.map')
  } finally {
    loading.value = false
  }
}

watch(() => route.query, load, { deep: true, immediate: true })

const groups = computed(() => groupByVenue(events.value))
const pinnedEventCount = computed(() =>
  groups.value.reduce((sum, group) => sum + group.events.length, 0),
)
/** Events at a venue without a coordinate: said out loud, so a missing pin is not mistaken for no event. */
const unpinnedEventCount = computed(() => events.value.length - pinnedEventCount.value)

const pins = computed<MapPin[]>(() =>
  groups.value
    .map(({ venue, events: atVenue }) =>
      venuePin(
        venue,
        t('map.pinLabel', { venue: venue.name ?? '', count: atVenue.length }),
        String(atVenue.length),
      ),
    )
    .filter((pin): pin is MapPin => pin !== null),
)

// A selection that the new data no longer contains would show an empty panel.
watch(groups, (next) => {
  if (selected.value && !next.some((group) => group.venue.slug === selected.value)) {
    selected.value = null
  }
})

const selectedGroup = computed(
  () => groups.value.find((group) => group.venue.slug === selected.value) ?? null,
)

const location = computed(() => {
  const venue = selectedGroup.value?.venue
  return [venue?.address, districtLabel(venue?.district)].filter(Boolean).join(' · ')
})

const isFiltered = computed(() => Object.keys(route.query).length > 0)
const listLink = computed(() => ({ path: localePath('/events'), query: route.query }))
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('map.title') }}</h1>
      <p class="text-muted-foreground">{{ t('map.subtitle') }}</p>
    </header>

    <EventFilterBar />

    <p v-if="loading" class="text-sm text-muted-foreground">
      {{ t('common.states.loading') }}
    </p>
    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>
    <!-- An empty result offers a control, not only a sentence (#1266). -->
    <div v-else-if="!pins.length" class="space-y-3">
      <p class="text-sm text-muted-foreground">{{ t('map.empty') }}</p>
      <Button v-if="isFiltered" variant="outline" @click="router.push({ query: {} })">
        {{ t('common.actions.clearFilters') }}
      </Button>
    </div>
    <p v-else class="text-sm text-muted-foreground">
      {{ t('map.resultCount', { events: pinnedEventCount, venues: pins.length }) }}
      <template v-if="unpinnedEventCount">
        ·
        <RouterLink :to="listLink" class="text-primary hover:underline">
          {{ t('map.unpinned', { count: unpinnedEventCount }) }}
        </RouterLink>
      </template>
    </p>

    <div v-if="unavailable" class="space-y-3">
      <p class="text-sm text-muted-foreground">{{ t('map.unavailable') }}</p>
      <Button as-child variant="outline">
        <RouterLink :to="listLink">{{ t('map.showList') }}</RouterLink>
      </Button>
    </div>
    <VenueMap v-else v-model:selected="selected" :pins="pins" @unavailable="unavailable = true" />

    <section v-if="selectedGroup" aria-live="polite" class="space-y-2">
      <h2 class="text-section font-bold tracking-tight">
        <RouterLink
          :to="localePath(`/venues/${selectedGroup.venue.slug}`)"
          class="hover:text-primary"
        >
          {{ selectedGroup.venue.name }}
        </RouterLink>
      </h2>
      <p v-if="location" class="text-body text-muted-foreground">{{ location }}</p>
      <div :class="CARD_LIST_CLASS">
        <EventRow v-for="event in selectedGroup.events" :key="event.slug" :event="event" />
      </div>
    </section>
    <p v-else-if="pins.length && !unavailable" class="text-sm text-muted-foreground">
      {{ t('map.pickPin') }}
    </p>
  </main>
</template>
