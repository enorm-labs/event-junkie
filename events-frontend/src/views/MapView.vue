<script lang="ts" setup>
import { LocateFixed, MapPin as MapPinIcon, X } from '@lucide/vue'
import {
  computed,
  defineAsyncComponent,
  onMounted,
  ref,
  shallowRef,
  useTemplateRef,
  watch,
} from 'vue'
import { type LocationQueryRaw, RouterLink, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { EventSummary, VenueSummary } from '@/api/types'
import { describeError } from '@/api/client'
import BaseSelect from '@/components/BaseSelect.vue'
import { Button } from '@/components/ui/button'
import EventFilterBar from '@/components/EventFilterBar.vue'
import EventRow from '@/components/EventRow.vue'
import { fetchCalendarEvents } from '@/composables/useEvents'
import { useEventFilters } from '@/composables/useEventFilters'
import { useLocalePath } from '@/composables/useLocalePath'
import { useLocation } from '@/composables/useLocation'
import { fetchAllVenues } from '@/composables/useVenues'
import { districtLabel } from '@/lib/districts'
import { addDays, isOnNow, todayIso } from '@/lib/format'
import { formatDistance, type Position } from '@/lib/geo'
import { groupByVenue, type MapPin, nearby, venuePin, venuePosition } from '@/lib/mapPins'
import { CARD_LIST_CLASS } from '@/lib/utils'

// MapLibre is the heaviest dependency the site has; only this route and the venues map pay for it.
const VenueMap = defineAsyncComponent(() => import('@/components/VenueMap.vue'))

/** The BFF's calendar endpoint refuses a range longer than this. */
const MAX_RANGE_DAYS = 92
/** The radii "near" offers, in kilometres: a walk, a short ride, across a district. */
const RADII = [1, 2, 5] as const
const DEFAULT_RADIUS = 2
/** The map's own query keys. Not event filters: the list link and the API never see them. */
const MAP_KEYS = ['radius', 'now'] as const

const route = useRoute()
const router = useRouter()
const { filters, dateRange, queryString, applyFilters } = useEventFilters()
const localePath = useLocalePath()
const { t, locale } = useI18n()
const { origin, state: locateState, locate, set: setOrigin, clear: clearOrigin } = useLocation()
const venueMap = useTemplateRef<{ center(): Position | null }>('venueMap')

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

// Keyed on what the request depends on, so a radius or "on now" change filters without a reload.
watch(() => JSON.stringify([range.value, filters.value]), load, { immediate: true })

const radiusKm = computed(() => {
  const radius = Number(queryString('radius'))
  return (RADII as readonly number[]).includes(radius) ? radius : DEFAULT_RADIUS
})
const onNowOnly = computed(() => queryString('now') === '1')

/** "On now" narrows to what is running at this moment; the day's other events drop off the map. */
const shown = computed(() => (onNowOnly.value ? events.value.filter(isOnNow) : events.value))
const groups = computed(() => groupByVenue(shown.value))
const near = computed(() =>
  origin.value ? nearby(groups.value, origin.value, radiusKm.value) : null,
)
const nearSlugs = computed(() => new Set(near.value?.map((group) => group.venue.slug)))
const pinnedEventCount = computed(() =>
  groups.value.reduce((sum, group) => sum + group.events.length, 0),
)
/** Events at a venue without a coordinate: said out loud, so a missing pin is not mistaken for no event. */
const unpinnedEventCount = computed(() => shown.value.length - pinnedEventCount.value)

// The live dot is not the only carrier: the pin's name says "on now" in words.
const pins = computed<MapPin[]>(() =>
  groups.value
    .map(({ venue, events: atVenue }) => {
      const live = atVenue.filter(isOnNow).length
      const label = t('map.pinLabel', { venue: venue.name ?? '', count: atVenue.length })
      return venuePin(
        venue,
        live ? `${label}, ${t('map.pinOnNow', { count: live })}` : label,
        String(atVenue.length),
        { live: live > 0, dimmed: !!near.value && !nearSlugs.value.has(venue.slug) },
      )
    })
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
const listLink = computed(() => {
  const query: LocationQueryRaw = { ...route.query }
  for (const key of MAP_KEYS) delete query[key]
  return { path: localePath('/events'), query }
})

/** Every venue with a coordinate, by name, for "near a venue". */
const anchors = shallowRef<VenueSummary[]>([])
onMounted(async () => {
  try {
    const venues = await fetchAllVenues({})
    anchors.value = venues
      .filter((venue) => venuePosition(venue))
      .sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
  } catch {
    // The select stays hidden; the device and the map still set an origin.
  }
})

const picking = ref(false)

function pick(position: Position) {
  setOrigin({ ...position, source: 'map' })
  picking.value = false
}

function pickCenter() {
  const position = venueMap.value?.center()
  if (position) pick(position)
}

function nearVenue(slug: string) {
  const venue = anchors.value.find((candidate) => candidate.slug === slug)
  const position = venuePosition(venue)
  if (!venue || !position) return
  picking.value = false
  setOrigin({ ...position, source: 'venue', label: venue.name ?? '', slug })
}

function useDevice() {
  picking.value = false
  locate()
}

function clearNear() {
  picking.value = false
  clearOrigin()
}

const originLabel = computed(() => {
  if (!origin.value) return ''
  if (origin.value.source === 'device') return t('map.near.yourLocation')
  if (origin.value.source === 'map') return t('map.near.pickedPoint')
  return origin.value.label ?? ''
})

/** A fix coarser than the radius can put "near" venues anywhere in it; the view says so. */
const coarse = computed(() => {
  const accuracy = origin.value?.accuracyKm
  return accuracy && accuracy > radiusKm.value ? formatDistance(accuracy, locale.value) : null
})

const nearEventCount = computed(
  () => near.value?.reduce((sum, group) => sum + group.events.length, 0) ?? 0,
)

function distance(km: number): string {
  return formatDistance(km, locale.value)
}
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('map.title') }}</h1>
      <p class="text-muted-foreground">{{ t('map.subtitle') }}</p>
    </header>

    <EventFilterBar />

    <section :aria-label="t('map.near.label')" class="space-y-3">
      <div class="flex flex-wrap items-center gap-2">
        <Button
          :disabled="locateState === 'locating'"
          size="sm"
          type="button"
          variant="outline"
          @click="useDevice"
        >
          <LocateFixed aria-hidden="true" />
          {{ t('map.near.useLocation') }}
        </Button>
        <Button
          v-if="!unavailable"
          :aria-pressed="picking"
          :variant="picking ? 'default' : 'outline'"
          size="sm"
          type="button"
          @click="picking = !picking"
        >
          <MapPinIcon aria-hidden="true" />
          {{ t('map.near.pickOnMap') }}
        </Button>
        <BaseSelect
          v-if="anchors.length"
          :aria-label="t('map.near.nearVenue')"
          :model-value="origin?.slug ?? ''"
          @change="nearVenue(($event.target as HTMLSelectElement).value)"
        >
          <option value="">{{ t('map.near.nearVenue') }}</option>
          <option v-for="venue in anchors" :key="venue.slug" :value="venue.slug ?? ''">
            {{ venue.name }}
          </option>
        </BaseSelect>
        <Button
          :aria-pressed="onNowOnly"
          :variant="onNowOnly ? 'default' : 'outline'"
          size="sm"
          type="button"
          @click="applyFilters({ now: onNowOnly ? undefined : '1' })"
        >
          {{ t('map.onNowOnly') }}
        </Button>
      </div>

      <p v-if="picking" class="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
        {{ t('map.near.pickHint') }}
        <Button size="xs" type="button" variant="link" @click="pickCenter">
          {{ t('map.near.useCentre') }}
        </Button>
      </p>
      <p v-if="locateState === 'locating'" aria-live="polite" class="text-sm text-muted-foreground">
        {{ t('map.near.locating') }}
      </p>
      <p
        v-else-if="locateState === 'denied' || locateState === 'unavailable'"
        aria-live="polite"
        class="text-sm text-muted-foreground"
      >
        {{ t(`map.near.${locateState}`) }}
      </p>

      <div v-if="origin" class="flex flex-wrap items-center gap-2 text-sm">
        <span>{{ t('map.near.from', { origin: originLabel }) }}</span>
        <div :aria-label="t('map.near.radius')" class="flex gap-1" role="group">
          <Button
            v-for="radius in RADII"
            :key="radius"
            :aria-pressed="radius === radiusKm"
            :variant="radius === radiusKm ? 'default' : 'outline'"
            size="sm"
            type="button"
            @click="
              applyFilters({ radius: radius === DEFAULT_RADIUS ? undefined : String(radius) })
            "
          >
            {{ distance(radius) }}
          </Button>
        </div>
        <Button size="sm" type="button" variant="ghost" @click="clearNear">
          <X aria-hidden="true" />
          {{ t('map.near.clear') }}
        </Button>
      </div>
      <p v-if="coarse" class="text-sm text-muted-foreground">
        {{ t('map.near.coarse', { accuracy: coarse }) }}
      </p>
    </section>

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
    <VenueMap
      v-else
      ref="venueMap"
      v-model:selected="selected"
      :origin="origin"
      :picking="picking"
      :pins="pins"
      :radius-km="origin ? radiusKm : null"
      @pick="pick"
      @unavailable="unavailable = true"
    />

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
    <p v-else-if="pins.length && !unavailable && !near" class="text-sm text-muted-foreground">
      {{ t('map.pickPin') }}
    </p>

    <section v-if="near && !loading && !error" aria-live="polite" class="space-y-4">
      <h2 class="text-section font-bold tracking-tight">
        {{ t('map.near.heading', { radius: distance(radiusKm), origin: originLabel }) }}
      </h2>
      <p class="text-sm text-muted-foreground">
        {{
          near.length
            ? t('map.near.resultCount', { events: nearEventCount, venues: near.length })
            : t('map.near.empty')
        }}
      </p>
      <div v-for="group in near" :key="group.venue.slug" class="space-y-1">
        <h3 class="flex items-baseline gap-2 text-body font-semibold">
          <RouterLink :to="localePath(`/venues/${group.venue.slug}`)" class="hover:text-primary">
            {{ group.venue.name }}
          </RouterLink>
          <span class="text-meta font-normal text-muted-foreground tabular-nums">
            {{ distance(group.distanceKm) }}
          </span>
        </h3>
        <div :class="CARD_LIST_CLASS">
          <EventRow v-for="event in group.events" :key="event.slug" as="h4" :event="event" />
        </div>
      </div>
    </section>
  </main>
</template>
