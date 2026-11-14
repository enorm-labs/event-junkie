<script lang="ts" setup>
/**
 * Berlin's Christmas markets in one list and on one map (#4972). Each market is an event entered by hand with the
 * `christmas-market` feature, which keeps it out of every other list (EVENT_SCOPE.md §3.6). Nearest first (#5010)
 * measures on the device: the origin stays in memory, and the URL carries only `sort=distance`.
 */
import { LocateFixed, MapPin as MapPinIcon } from '@lucide/vue'
import { computed, defineAsyncComponent, onMounted, ref, useId, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { EventSummary } from '@/api/types'
import BaseInput from '@/components/BaseInput.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import ErrorLine from '@/components/ErrorLine.vue'
import EventRow from '@/components/EventRow.vue'
import ListPage from '@/components/ListPage.vue'
import LocateStatus from '@/components/LocateStatus.vue'
import MapPanel from '@/components/MapPanel.vue'
import MultiSelectFilter from '@/components/MultiSelectFilter.vue'
import NearOriginControls from '@/components/NearOriginControls.vue'
import { Button } from '@/components/ui/button'
import { useAsync } from '@/composables/useAsync'
import { searchEvents } from '@/composables/useEvents'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useLocation } from '@/composables/useLocation'
import { useMarketFilters } from '@/composables/useMarketFilters'
import {
  CHRISTMAS_MARKET,
  MARKET_NEAREST,
  MARKET_TODAY,
  MARKET_WEEKEND,
  nearestFirst,
} from '@/lib/christmasMarkets'
import { MARKET_KIND_PREFIX, MARKET_KINDS } from '@/lib/eventFeatures'
import { todayIso, venueLocationLine } from '@/lib/format'
import { formatDistance, type Position } from '@/lib/geo'
import { type MapPin, venuePin, venuePosition } from '@/lib/mapPins'
import {
  CARD_LIST_CLASS,
  cn,
  FIELD_CLASS,
  PANEL_CLASS,
  SECTION_TITLE_CLASS,
  TEXT_LINK_CLASS,
} from '@/lib/utils'

const VenueMap = defineAsyncComponent(() => import('@/components/VenueMap.vue'))
const venueMap = useTemplateRef<{ center(): Position | null }>('venueMap')

/** The API's page cap. A season has about thirty markets. */
const PAGE_SIZE = 100

const { t, locale } = useI18n()
const { formatDayRange, formatEventFeature } = useFormat()
const localePath = useLocalePath()

const today = todayIso()

/** The running Christmas markets that also carry [features]. */
async function loadMarkets(...features: string[]): Promise<EventSummary[]> {
  const page = await searchEvents({
    feature: [CHRISTMAS_MARKET, ...features],
    from: today,
    running: true,
    size: PAGE_SIZE,
  })
  return page.content ?? []
}

const markets = useAsync<EventSummary[]>(
  () => loadMarkets(),
  'errors.subject.christmasMarkets',
  () => `/api/events?feature=${CHRISTMAS_MARKET}&from=${today}&running=true&size=${PAGE_SIZE}`,
)
onMounted(markets.run)

const events = computed(() => markets.data.value ?? [])

const {
  day,
  date,
  dateBounds,
  free,
  districts,
  filtered,
  districtCounts,
  districtOptions,
  kind,
  kindError,
  kindPending,
  setDay,
  setFree,
  setDistricts,
  sort,
  setSort,
  setKind,
} = useMarketFilters(events, loadMarkets)

const kindOptions = computed(() =>
  MARKET_KINDS.map((value) => ({ value, label: formatEventFeature(MARKET_KIND_PREFIX + value) })),
)

/** The markets the filters keep, by name: the list, the count and the map all read this one list. */
const listed = computed(() =>
  [...filtered.value].sort((a, b) =>
    (a.shown.title ?? '').localeCompare(b.shown.title ?? '', locale.value),
  ),
)

// The same origin as the events map's and the venues page's "near me".
const { origin, state: locateState, locate, set: setOrigin, clear: clearOrigin } = useLocation()
const nearest = computed(() => sort.value === MARKET_NEAREST)

/** The rows in the order shown: nearest first once there is an origin, else by name with no distance. */
const ordered = computed(() =>
  nearest.value && origin.value
    ? nearestFirst(listed.value, origin.value)
    : listed.value.map((market) => ({ market, distanceKm: null })),
)

/** One choice per market venue, by name, for an origin picked without the map. */
const marketVenues = computed(() => {
  const names = new Map<string, string>()
  for (const { venue, title } of events.value) {
    if (venue?.slug && venuePosition(venue) && !names.has(venue.slug)) {
      names.set(venue.slug, venue.name ?? title ?? '')
    }
  }
  return [...names]
    .map(([slug, name]) => ({ slug, name }))
    .sort((a, b) => a.name.localeCompare(b.name, locale.value))
})

const picking = ref(false)

function toggleNearest() {
  picking.value = false
  setSort(!nearest.value)
}

// Only "Use my location" asks the browser, as on the maps: its prompt is the consent (LEGAL.md §7.4a).
function useDevice() {
  picking.value = false
  locate()
}

function pick(position: Position) {
  setOrigin({ ...position, source: 'map' })
  picking.value = false
}

function pickCenter() {
  const position = venueMap.value?.center()
  if (position) pick(position)
}

function fromMarket(slug: string) {
  const venue = events.value.find((market) => market.venue?.slug === slug)?.venue
  const position = venuePosition(venue)
  if (!venue || !position) return
  picking.value = false
  setOrigin({ ...position, source: 'venue', label: venue.name ?? '', slug })
}

function clearNearest() {
  picking.value = false
  clearOrigin()
  setSort(false)
}

function distance(km: number | null): string | undefined {
  return km === null ? undefined : formatDistance(km, locale.value)
}

const dayWords = computed(() => [
  { value: MARKET_TODAY, label: t('christmasMarkets.filters.today') },
  { value: MARKET_WEEKEND, label: t('christmasMarkets.filters.weekend') },
])
const dateId = useId()

/** Opens the browser's calendar on a click anywhere in the field, as the events filter bar does. */
function openDatePicker(event: MouseEvent) {
  const input = event.currentTarget as HTMLInputElement & { showPicker?: () => void }
  input.showPicker?.()
}

const selected = ref<string | null>(null)
const mapUnavailable = ref(false)

const pins = computed<MapPin[]>(() =>
  listed.value
    .map(({ shown }) =>
      shown.venue
        ? venuePin(shown.venue, shown.title ?? '', undefined, { note: dayRange(shown) })
        : null,
    )
    .filter((pin): pin is MapPin => pin !== null),
)

/** A market sits at its own venue, so the selected pin names one market. */
const selectedMarket = computed(() =>
  listed.value.find(({ shown }) => shown.venue?.slug === selected.value),
)

/** Every block of the selected market, as "28.–29. Nov. · 5.–6. Dez.". */
const selectedBlocks = computed(() =>
  (selectedMarket.value?.blocks ?? []).map(dayRange).filter(Boolean).join(' · '),
)
const selectedDistance = computed(() =>
  distance(ordered.value.find(({ market }) => market === selectedMarket.value)?.distanceKm ?? null),
)

function dayRange(market: EventSummary): string {
  return market.eventDate
    ? formatDayRange(market.eventDate, market.endDate ?? market.eventDate)
    : ''
}
</script>

<template>
  <ListPage :subtitle="t('christmasMarkets.subtitle')" :title="t('christmasMarkets.title')">
    <p v-if="markets.loading.value" class="text-body text-muted-foreground">
      {{ markets.retrying.value ? t('errors.retrying') : t('common.states.loading') }}
    </p>
    <ErrorLine v-else-if="markets.error.value">{{ markets.error.value }}</ErrorLine>
    <p v-else-if="!events.length" class="text-body text-muted-foreground">
      {{ t('christmasMarkets.empty') }}
    </p>
    <template v-else>
      <div :class="PANEL_CLASS">
        <div class="flex flex-wrap items-center gap-2">
          <Button
            v-for="word in dayWords"
            :key="word.value"
            :aria-pressed="day === word.value"
            :variant="day === word.value ? 'default' : 'outline'"
            type="button"
            @click="setDay(day === word.value ? '' : word.value)"
          >
            {{ word.label }}
          </Button>
          <label
            :class="
              cn(
                FIELD_CLASS,
                'flex min-w-0 items-center gap-1.5 focus-within:ring-3 focus-within:ring-ring/50',
              )
            "
            :for="dateId"
          >
            <span class="text-muted-foreground">{{ t('christmasMarkets.filters.date') }}</span>
            <BaseInput
              :id="dateId"
              :max="dateBounds.max"
              :min="dateBounds.min"
              :model-value="date"
              class="h-full min-w-0 rounded-none border-0 bg-transparent px-0 focus-visible:ring-0"
              type="date"
              @change="setDay(($event.target as HTMLInputElement).value)"
              @click="openDatePicker"
            />
          </label>
        </div>
        <MultiSelectFilter
          :all-label="t('christmasMarkets.filters.allDistricts')"
          :clear-label="t('christmasMarkets.filters.clearDistricts')"
          :count-label="(n) => t('christmasMarkets.filters.districtsSelected', { n })"
          :counts="districtCounts"
          :label="t('christmasMarkets.filters.byDistrict')"
          :options="districtOptions"
          :selected="districts"
          @change="setDistricts"
        />
        <BaseSelect
          :aria-label="t('christmasMarkets.filters.byKind')"
          :model-value="kind"
          @change="setKind(($event.target as HTMLSelectElement).value)"
        >
          <option value="">{{ t('christmasMarkets.filters.allKinds') }}</option>
          <option v-for="option in kindOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </BaseSelect>
        <Button
          :aria-pressed="free"
          :variant="free ? 'default' : 'outline'"
          type="button"
          @click="setFree(!free)"
        >
          {{ t('christmasMarkets.filters.free') }}
        </Button>
        <ClearAllFilters />
      </div>

      <section :aria-label="t('christmasMarkets.near.label')" class="space-y-3">
        <div class="flex flex-wrap items-center gap-2 text-body">
          <Button
            :aria-pressed="nearest"
            :variant="nearest ? 'default' : 'outline'"
            type="button"
            @click="toggleNearest"
          >
            {{ t('christmasMarkets.nearest') }}
          </Button>
          <template v-if="nearest">
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
              v-if="!mapUnavailable"
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
              v-if="marketVenues.length"
              :aria-label="t('christmasMarkets.near.fromMarket')"
              :model-value="origin?.source === 'venue' ? (origin.slug ?? '') : ''"
              @change="fromMarket(($event.target as HTMLSelectElement).value)"
            >
              <option value="">{{ t('christmasMarkets.near.fromMarket') }}</option>
              <option v-for="venue in marketVenues" :key="venue.slug" :value="venue.slug">
                {{ venue.name }}
              </option>
            </BaseSelect>
          </template>
        </div>
        <p v-if="picking" class="flex flex-wrap items-center gap-2 text-body text-muted-foreground">
          {{ t('map.near.pickHint') }}
          <Button size="xs" type="button" variant="link" @click="pickCenter">
            {{ t('map.near.useCentre') }}
          </Button>
        </p>
        <LocateStatus :state="locateState" messages="christmasMarkets.near" />
        <div v-if="nearest && origin" class="flex flex-wrap items-center gap-2 text-body">
          <NearOriginControls :origin="origin" @clear="clearNearest" />
        </div>
        <p v-else-if="nearest && locateState === 'idle'" class="text-body text-muted-foreground">
          {{ t('christmasMarkets.near.needsOrigin') }}
        </p>
      </section>

      <p v-if="kindPending" class="text-body text-muted-foreground">
        {{ t('common.states.loading') }}
      </p>
      <ErrorLine v-else-if="kindError">{{ kindError }}</ErrorLine>
      <div v-else-if="!listed.length" class="space-y-3">
        <p class="text-body text-muted-foreground">{{ t('christmasMarkets.emptyFiltered') }}</p>
        <ClearAllFilters empty-state />
      </div>
      <template v-else>
        <p class="text-body text-muted-foreground">
          {{
            t(
              nearest && origin
                ? 'christmasMarkets.near.resultCount'
                : 'christmasMarkets.resultCount',
              { count: listed.length },
            )
          }}
        </p>

        <p v-if="mapUnavailable" class="text-body text-muted-foreground">
          {{ t('map.unavailable') }}
        </p>
        <VenueMap
          v-else-if="pins.length"
          ref="venueMap"
          v-model:selected="selected"
          :origin="nearest ? origin : null"
          :picking="picking"
          :pins="pins"
          @pick="pick"
          @unavailable="mapUnavailable = true"
        >
          <MapPanel
            v-if="selectedMarket"
            :subtitle="venueLocationLine(selectedMarket.shown.venue)"
            :title="selectedMarket.shown.title ?? ''"
            :to="localePath(`/events/${selectedMarket.shown.slug}`)"
            @close="selected = null"
          >
            <p class="text-body text-muted-foreground tabular-nums">
              {{ [selectedBlocks, selectedDistance].filter(Boolean).join(' · ') }}
            </p>
            <RouterLink
              :to="localePath(`/events/${selectedMarket.shown.slug}`)"
              :class="['inline-block text-body', TEXT_LINK_CLASS]"
            >
              {{ t('christmasMarkets.details') }}
            </RouterLink>
          </MapPanel>
        </VenueMap>

        <section class="space-y-2">
          <h2 :class="SECTION_TITLE_CLASS">{{ t('christmasMarkets.listHeading') }}</h2>
          <div :class="CARD_LIST_CLASS">
            <EventRow
              v-for="{ market, distanceKm } in ordered"
              :key="market.key"
              :distance="distance(distanceKm)"
              :event="market.shown"
              as="h3"
            />
          </div>
        </section>
      </template>
    </template>
  </ListPage>
</template>
