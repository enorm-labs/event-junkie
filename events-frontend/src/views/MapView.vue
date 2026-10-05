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
import { type LocationQueryRaw, RouterLink, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { EventSummary, VenueSummary } from '@/api/types'
import { api, describeError, unwrap } from '@/api/client'
import BaseSelect from '@/components/BaseSelect.vue'
import { Button } from '@/components/ui/button'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import EventFilterBar from '@/components/EventFilterBar.vue'
import EventRow from '@/components/EventRow.vue'
import { fetchCalendarEvents } from '@/composables/useEvents'
import { useEventFilters } from '@/composables/useEventFilters'
import { useLocalePath } from '@/composables/useLocalePath'
import { useLocation } from '@/composables/useLocation'
import { beginContentLoad } from '@/composables/useViewLoaded'
import { fetchAllVenues } from '@/composables/useVenues'
import { districtLabel } from '@/lib/districts'
import { tonight } from '@/lib/dateRanges'
import { addDays, isOnNow, isPastEvent, todayIso } from '@/lib/format'
import { formatDistance, type Position } from '@/lib/geo'
import {
  DEFAULT_RADIUS,
  groupByVenue,
  MAP_KEYS,
  type MapPin,
  nearby,
  RADII,
  radiusFromQuery,
  venuePin,
  venuePosition,
} from '@/lib/mapPins'
import { CARD_LIST_CLASS } from '@/lib/utils'

// MapLibre is the heaviest dependency the site has; only this route and the venues map pay for it.
const VenueMap = defineAsyncComponent(() => import('@/components/VenueMap.vue'))

/** The BFF's calendar endpoint refuses a range longer than this. */
const MAX_RANGE_DAYS = 92

const route = useRoute()
const { filters, dateRange, queryString, applyFilters } = useEventFilters()
const onNowOnly = computed(() => queryString('now') === '1')
const localePath = useLocalePath()
const { t, locale } = useI18n()
const { origin, state: locateState, locate, set: setOrigin, clear: clearOrigin } = useLocation()
const venueMap = useTemplateRef<{ center(): Position | null; focusPin(slug: string): void }>(
  'venueMap',
)

/**
 * The URL's range, or today when it names none. Today is not written into the URL, so a shared
 * link without dates means "tonight" on the day it is opened. On now is today, whatever the range.
 */
const range = computed(() => {
  if (onNowOnly.value) return { from: todayIso(), to: todayIso() }
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
  const done = beginContentLoad()
  try {
    events.value = await fetchCalendarEvents(range.value.from, range.value.to, filters.value)
    error.value = null
  } catch (e) {
    events.value = []
    error.value = describeError(e, 'errors.subject.map')
  } finally {
    loading.value = false
    done()
  }
}

// Keyed on what the request depends on, so a radius or "on now" change filters without a reload.
watch(() => JSON.stringify([range.value, filters.value]), load, { immediate: true })

const radiusKm = computed(() => radiusFromQuery(queryString('radius')))

/**
 * The range reaches back to last night's spans, so a 01:00 finish still counted on a pin at noon
 * (#2347). A range that starts in the past is the archive and keeps them. "On now" narrows further,
 * to what is running at this moment.
 */
const current = computed(() =>
  range.value.from < todayIso()
    ? events.value
    : events.value.filter((event) => !isPastEvent(event)),
)
const shown = computed(() => (onNowOnly.value ? current.value.filter(isOnNow) : current.value))
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

// The pulse is not the only carrier: the pin's name says "on now" in words.
const eventPins = computed<MapPin[]>(() =>
  groups.value
    .map(({ venue, events: atVenue }) => {
      const live = atVenue.filter(isOnNow).length
      const label = t('map.pinLabel', { venue: venue.name ?? '', count: atVenue.length })
      // A lone "1" on most of 72 pins said nothing; one event keeps the pin's size, not the digit.
      return venuePin(
        venue,
        live ? `${label}, ${t('map.pinOnNow', { count: live })}` : label,
        atVenue.length > 1 ? String(atVenue.length) : '',
        { live: live > 0, dimmed: !!near.value && !nearSlugs.value.has(venue.slug) },
      )
    })
    .filter((pin): pin is MapPin => pin !== null),
)

/** Every venue with a coordinate, by name, for "near a venue". */
const anchors = shallowRef<VenueSummary[]>([])

/**
 * The venue a venue page's "On the map" names. The map centres on it and opens its pin once, so
 * closing it stays closed. With nothing in the range it still gets a pin, a quiet one, because a
 * map centred on an empty street reads as a wrong address.
 */
const focusSlug = computed(() => queryString('focus'))
/** The focused venue from its own endpoint, when the venue list did not have it. */
const fetchedFocus = shallowRef<VenueSummary | null>(null)
const focusVenue = computed(() => {
  const slug = focusSlug.value
  if (!slug) return null
  return (
    groups.value.find((group) => group.venue.slug === slug)?.venue ??
    anchors.value.find((venue) => venue.slug === slug) ??
    (fetchedFocus.value?.slug === slug ? fetchedFocus.value : null)
  )
})
const focusPosition = computed(() => venuePosition(focusVenue.value ?? undefined))
const quietFocus = computed(() => {
  const venue = focusVenue.value
  if (loading.value || error.value || !venue?.slug) return null
  return groups.value.some((group) => group.venue.slug === venue.slug) ? null : venue
})

const pins = computed<MapPin[]>(() => {
  const venue = quietFocus.value
  const pin = venue
    ? venuePin(venue, t('map.pinNothingOn', { venue: venue.name ?? '' }), '', { quiet: true })
    : null
  return pin ? [...eventPins.value, pin] : eventPins.value
})

// A selection that the new pins no longer contain would show an empty panel.
watch(pins, (next) => {
  if (selected.value && !next.some((pin) => pin.slug === selected.value)) selected.value = null
})

let focusOpened = ''
watch(pins, (next) => {
  const slug = focusSlug.value
  if (slug && slug !== focusOpened && next.some((pin) => pin.slug === slug)) {
    selected.value = slug
    focusOpened = slug
  }
})

async function fetchFocus(slug: string) {
  try {
    const venue = await unwrap(api.GET('/api/venues/{slug}', { params: { path: { slug } } }))
    fetchedFocus.value = { ...venue, slug: venue.slug ?? slug }
  } catch {
    // No venue, no pin: the map frames the range as it does without a focus.
  }
}

const selectedGroup = computed(
  () => groups.value.find((group) => group.venue.slug === selected.value) ?? null,
)
/** The selected venue, which may be the quiet one with nothing in the range. */
const selectedVenue = computed(
  () =>
    selectedGroup.value?.venue ??
    (quietFocus.value?.slug === selected.value ? quietFocus.value : null),
)

const location = computed(() => {
  const venue = selectedVenue.value
  return [venue?.address, districtLabel(venue?.district)].filter(Boolean).join(' · ')
})

/** What the panel over the map lists; the rest is one link away. */
const PANEL_PREVIEW = 3
const preview = computed(() => selectedGroup.value?.events.slice(0, PANEL_PREVIEW) ?? [])

/** The venue's events over the map's own range, in the list; On now carries over as itself. */
const venueListLink = computed(() => ({
  ...listLink.value,
  query: {
    ...listLink.value.query,
    venue: selectedVenue.value?.slug,
    ...(onNowOnly.value ? {} : { from: range.value.from, to: range.value.to }),
  },
}))

// The panel goes with the click, so focus goes back to the pin that opened it.
function closePanel() {
  const slug = selected.value
  selected.value = null
  if (slug) venueMap.value?.focusPin(slug)
}

const listLink = computed(() => {
  const query: LocationQueryRaw = { ...route.query }
  for (const key of MAP_KEYS) delete query[key]
  return { path: localePath('/events'), query }
})

onMounted(async () => {
  try {
    const venues = await fetchAllVenues({})
    anchors.value = venues
      .filter((venue) => venuePosition(venue))
      .sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
  } catch {
    // The select stays hidden; the device and the map still set an origin.
  }
  // The venue list is the map's own data; only a venue missing from it costs a request, once.
  const slug = focusSlug.value
  if (slug && !anchors.value.some((venue) => venue.slug === slug)) await fetchFocus(slug)
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

/** Two counts in one line: each is pluralised on its own, since a message picks one plural form. */
function counts(events: number, venues: number) {
  return {
    events: t('map.eventCount', { count: events }),
    venues: t('map.venueCount', { count: venues }),
  }
}

function distance(km: number): string {
  return formatDistance(km, locale.value)
}
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('map.title') }}</h1>
      <p class="text-body text-muted-foreground">{{ t('map.subtitle') }}</p>
    </header>

    <!-- With no dates in the URL the map shows today, so Tonight reads as pressed. -->
    <EventFilterBar :default-range="tonight()" show-on-now />

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
      </div>

      <p v-if="picking" class="flex flex-wrap items-center gap-2 text-body text-muted-foreground">
        {{ t('map.near.pickHint') }}
        <Button size="xs" type="button" variant="link" @click="pickCenter">
          {{ t('map.near.useCentre') }}
        </Button>
      </p>
      <p
        v-if="locateState === 'locating'"
        aria-live="polite"
        class="text-body text-muted-foreground"
      >
        {{ t('map.near.locating') }}
      </p>
      <p
        v-else-if="locateState === 'denied' || locateState === 'unavailable'"
        aria-live="polite"
        class="text-body text-muted-foreground"
      >
        {{ t(`map.near.${locateState}`) }}
      </p>

      <div v-if="origin" class="flex flex-wrap items-center gap-2 text-body">
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
      <p v-if="coarse" class="text-body text-muted-foreground">
        {{ t('map.near.coarse', { accuracy: coarse }) }}
      </p>
    </section>

    <p v-if="loading" class="text-body text-muted-foreground">
      {{ t('common.states.loading') }}
    </p>
    <p v-else-if="error" class="text-body text-destructive">{{ error }}</p>
    <!-- An empty result offers a control, not only a sentence (#1266). -->
    <div v-else-if="!eventPins.length" class="space-y-3">
      <p class="text-body text-muted-foreground">{{ t('map.empty') }}</p>
      <ClearAllFilters empty-state />
    </div>
    <p v-else class="text-body text-muted-foreground">
      {{ t('map.resultCount', counts(pinnedEventCount, eventPins.length)) }}
      <template v-if="unpinnedEventCount">
        ·
        <RouterLink :to="listLink" class="text-primary hover:underline">
          {{ t('map.unpinned', { count: unpinnedEventCount }) }}
        </RouterLink>
      </template>
    </p>

    <div v-if="unavailable" class="space-y-3">
      <p class="text-body text-muted-foreground">{{ t('map.unavailable') }}</p>
      <Button as-child variant="outline">
        <RouterLink :to="listLink">{{ t('map.showList') }}</RouterLink>
      </Button>
    </div>
    <VenueMap
      v-else
      ref="venueMap"
      v-model:selected="selected"
      :focus="focusPosition"
      :origin="origin"
      :picking="picking"
      :pins="pins"
      :radius-km="origin ? radiusKm : null"
      @pick="pick"
      @unavailable="unavailable = true"
    >
      <!-- Over the map, not below it: at 1280×900 the map ends near the fold, so a pin's events
           under it changed nothing a visitor could see (#2347). -->
      <section
        v-if="selectedVenue"
        aria-live="polite"
        class="absolute inset-x-2 bottom-8 z-20 max-h-56 space-y-2 overflow-y-auto rounded-lg border border-border bg-background/95 p-3 sm:inset-x-auto sm:bottom-3 sm:left-3 sm:max-h-80 sm:w-96"
      >
        <div class="flex items-start gap-2">
          <div class="min-w-0 flex-1">
            <h2 class="truncate text-card-title font-bold tracking-tight">
              <RouterLink
                :to="localePath(`/venues/${selectedVenue.slug}`)"
                class="hover:text-primary"
              >
                {{ selectedVenue.name }}
              </RouterLink>
            </h2>
            <p v-if="location" class="truncate text-meta text-muted-foreground">{{ location }}</p>
          </div>
          <Button
            :aria-label="t('map.closePanel')"
            :title="t('map.closePanel')"
            size="icon-xs"
            type="button"
            variant="ghost"
            @click="closePanel"
          >
            <X aria-hidden="true" />
          </Button>
        </div>
        <div v-if="selectedGroup" :class="CARD_LIST_CLASS">
          <EventRow v-for="event in preview" :key="event.slug" :event="event" />
        </div>
        <template v-else>
          <p class="text-body text-muted-foreground">{{ t('map.nothingInRange') }}</p>
          <RouterLink
            :to="localePath(`/venues/${selectedVenue.slug}`)"
            class="inline-block text-body text-primary hover:underline"
          >
            {{ t('map.venuePage') }}
          </RouterLink>
        </template>
        <RouterLink
          v-if="selectedGroup && selectedGroup.events.length > preview.length"
          :to="venueListLink"
          class="inline-block text-body text-primary hover:underline"
        >
          {{ t('map.allEvents', { count: selectedGroup.events.length }) }}
        </RouterLink>
      </section>
    </VenueMap>

    <p
      v-if="!selectedVenue && eventPins.length && !unavailable && !near"
      class="text-body text-muted-foreground"
    >
      {{ t('map.pickPin') }}
    </p>

    <section v-if="near && !loading && !error" aria-live="polite" class="space-y-4">
      <h2 class="text-section font-bold tracking-tight">
        {{ t('map.near.heading', { radius: distance(radiusKm), origin: originLabel }) }}
      </h2>
      <p class="text-body text-muted-foreground">
        {{
          near.length
            ? t('map.near.resultCount', counts(nearEventCount, near.length))
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
