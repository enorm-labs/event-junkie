<script lang="ts" setup>
import { LocateFixed, X } from '@lucide/vue'
import { computed, defineAsyncComponent, ref, shallowRef, useTemplateRef, watch } from 'vue'
import { type LocationQueryRaw, RouterLink, useRoute, useRouter } from 'vue-router'
import type { VenueSummary } from '@/api/types'
import { describeError } from '@/api/client'
import { Button } from '@/components/ui/button'
import BaseInput from '@/components/BaseInput.vue'
import MultiSelectFilter from '@/components/MultiSelectFilter.vue'
import PaginationControls from '@/components/PaginationControls.vue'
import SortControl, { type SortOption } from '@/components/SortControl.vue'
import VenueCard from '@/components/VenueCard.vue'
import VenueFeaturesEmpty from '@/components/VenueFeaturesEmpty.vue'
import VenueRow from '@/components/VenueRow.vue'
import { useCompactView } from '@/composables/useCompactView'
import { useLocalePath } from '@/composables/useLocalePath'
import { useLocation } from '@/composables/useLocation'
import { usePagedList } from '@/composables/usePagedList'
import { useSearchDraft } from '@/composables/useSearchDraft'
import { useFilterOptions } from '@/composables/useFilterOptions'
import { fetchAllVenues, useVenueSearch, type VenueSearchParams } from '@/composables/useVenues'
import { districtLabel } from '@/lib/districts'
import { feedbackMailto } from '@/lib/feedback'
import { formatDistance, type Position } from '@/lib/geo'
import { CONTROLLER } from '@/lib/legal'
import {
  DEFAULT_RADIUS,
  type MapPin,
  nearby,
  RADII,
  radiusFromQuery,
  venuePin,
} from '@/lib/mapPins'
import { useI18n } from 'vue-i18n'
import { CARD_GRID_CLASS, CARD_LIST_CLASS, PANEL_CLASS, RESULTS_BAR_CLASS } from '@/lib/utils'

const PAGE_SIZE = 24

// Loaded only when the map is shown: MapLibre is the heaviest dependency the site has.
const VenueMap = defineAsyncComponent(() => import('@/components/VenueMap.vue'))
const venueMap = useTemplateRef<{ center(): Position | null; focusPin(slug: string): void }>(
  'venueMap',
)

const route = useRoute()
const router = useRouter()

// Filters live in the URL query so the list is shareable and survives back/forward.
function queryString(key: string): string {
  const value = route.query[key]
  return typeof value === 'string' ? value : ''
}

/** The name order is the API's default and stays out of the URL, as on the promoters list. */
const SORT_UPCOMING = 'upcomingEvents,desc'
const sort = computed(() => (queryString('sort') === SORT_UPCOMING ? SORT_UPCOMING : ''))

function queryList(key: string): string[] {
  const value = route.query[key]
  return (Array.isArray(value) ? value : [value]).filter(
    (v): v is string => typeof v === 'string' && v !== '',
  )
}

/** The multi-value filters, undefined when empty so they drop out of the request. */
function listParam(key: string): string[] | undefined {
  const values = queryList(key)
  return values.length ? values : undefined
}

const params = computed<VenueSearchParams>(() => ({
  q: queryString('q') || undefined,
  district: listParam('district'),
  sort: sort.value ? [sort.value] : undefined,

  type: listParam('type'),
  family: listParam('family'),
  eventType: listParam('eventType'),
  character: listParam('character'),
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = useVenueSearch(() => params.value)

const { search, applySearch } = useSearchDraft()

// Paging, the clamp on an out-of-range `?page=`, and the reload on any query change.
const { currentPage, totalPages, goToPage } = usePagedList(page, run)

function applyFilters(patch: LocationQueryRaw) {
  // Any filter change resets to the first page; empty values drop out of the URL.
  const next: LocationQueryRaw = { ...route.query, ...patch, page: undefined }
  for (const key of Object.keys(next)) {
    const value = next[key]
    if (value === '' || value === undefined || (Array.isArray(value) && !value.length))
      delete next[key]
  }
  router.push({ query: next })
}

/** Whether anything narrows the list, which is what a "clear" control has to have to offer. */
const isFiltered = computed(() =>
  Object.keys(route.query).some((key) => !['page', 'view', 'radius', 'sort'].includes(key)),
)

// Features combine with AND (#2670): with two or more chosen, an empty list names them as the cause.
const features = computed(() => queryList('character'))

function removeFeature(feature: string) {
  applyFilters({ character: features.value.filter((value) => value !== feature) })
}

function clearSearch() {
  search.value = ''
  // The order is not a filter, so clearing keeps it.
  router.push({ query: { view: showMap.value ? 'map' : undefined, sort: sort.value || undefined } })
}

// The map is a second view of the same search, in the URL so a shared link opens on it.
const showMap = computed(() => queryString('view') === 'map')

function setView(view: 'list' | 'map') {
  router.push({
    query: { ...route.query, view: view === 'map' ? 'map' : undefined, page: undefined },
  })
}

/** The list's filters without its paging: the map shows every match. */
const mapFilters = computed(() => ({
  q: params.value.q,
  district: params.value.district,
  type: params.value.type,
  family: params.value.family,
  eventType: params.value.eventType,
  character: params.value.character,
}))

const mapVenues = shallowRef<VenueSummary[]>([])
const mapLoading = ref(false)
const mapError = ref<string | null>(null)
const mapUnavailable = ref(false)
const selected = ref<string | null>(null)

async function loadMap() {
  if (!showMap.value) return
  mapLoading.value = true
  try {
    mapVenues.value = await fetchAllVenues(mapFilters.value)
    mapError.value = null
  } catch (e) {
    mapVenues.value = []
    mapError.value = describeError(e, 'errors.subject.venues')
  } finally {
    mapLoading.value = false
  }
}

watch(() => [showMap.value, JSON.stringify({ ...params.value, page: undefined })], loadMap, {
  immediate: true,
})

const { t, locale } = useI18n()

const sortOptions = computed<SortOption[]>(() => [
  { value: '', label: t('common.sort.name') },
  { value: SORT_UPCOMING, label: t('common.sort.upcoming') },
])

const {
  venueTypeOptions,
  venueCharacterOptions,
  familyOptions,
  eventTypeOptions,
  districtOptions,
} = useFilterOptions()
// OTHER is no type a venue is chosen by; the derivation never stores it.
const hostOptions = computed(() => eventTypeOptions.value.filter(({ value }) => value !== 'OTHER'))

// The same origin as the events map's "near me": a position chosen there is still chosen here.
const { origin, state: locateState, locate, clear: clearOrigin } = useLocation()
const radiusKm = computed(() => radiusFromQuery(queryString('radius')))

/** The map's venues within the radius, nearest first; null until the visitor picks an origin. */
const near = computed(() =>
  origin.value
    ? nearby(
        mapVenues.value.map((venue) => ({ venue })),
        origin.value,
        radiusKm.value,
      )
    : null,
)
const nearSlugs = computed(() => new Set(near.value?.map(({ venue }) => venue.slug)))

const pins = computed<MapPin[]>(() =>
  mapVenues.value
    .map((venue) =>
      venuePin(venue, venue.name ?? '', undefined, {
        dimmed: !!near.value && !nearSlugs.value.has(venue.slug),
      }),
    )
    .filter((pin): pin is MapPin => pin !== null),
)

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

function distance(km: number): string {
  return formatDistance(km, locale.value)
}

/** Venues without a coordinate: named, so a venue missing from the map is not taken for one we lack. */
const unpinnedCount = computed(() => mapVenues.value.length - pins.value.length)

const selectedVenue = computed(
  () => mapVenues.value.find((venue) => venue.slug === selected.value) ?? null,
)

const selectedLocation = computed(() => {
  const venue = selectedVenue.value
  return [venue?.address, districtLabel(venue?.district)].filter(Boolean).join(' · ')
})

// The panel goes with the click, so focus goes back to the pin that opened it.
function closePanel() {
  const slug = selected.value
  selected.value = null
  if (slug) venueMap.value?.focusPin(slug)
}

// The compact view is a global display preference — see `useCompactView`.
const { compact } = useCompactView()
const localePath = useLocalePath()
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('venues.title') }}</h1>
      <p class="text-muted-foreground">{{ t('venues.subtitle') }}</p>
      <p class="text-body text-muted-foreground">
        {{ t('venues.missing') }}
        <a
          :href="feedbackMailto(t('venues.missingSubject'), t('venues.missingBody'))"
          class="text-foreground underline underline-offset-4"
          >{{ CONTROLLER.email }}</a
        >
      </p>
    </header>

    <div :class="PANEL_CLASS">
      <form role="search" @submit.prevent="applySearch">
        <BaseInput
          v-model="search"
          :placeholder="t('venues.searchPlaceholder')"
          class="px-3"
          type="search"
          @change="applySearch"
        />
      </form>

      <MultiSelectFilter
        :all-label="t('venues.allDistricts')"
        :clear-label="t('venues.clearDistricts')"
        :count-label="(n) => t('venues.districtsSelected', { n })"
        :label="t('venues.byDistrict')"
        :options="districtOptions"
        :selected="queryList('district')"
        @change="applyFilters({ district: $event })"
      />

      <MultiSelectFilter
        :all-label="t('venues.allTypes')"
        :clear-label="t('venues.clearTypes')"
        :count-label="(n) => t('venues.typesSelected', { n })"
        :label="t('venues.byType')"
        :options="venueTypeOptions"
        :selected="queryList('type')"
        @change="applyFilters({ type: $event })"
      />
      <MultiSelectFilter
        :all-label="t('venues.allCharacters')"
        :clear-label="t('venues.clearCharacters')"
        :count-label="(n) => t('venues.charactersSelected', { n })"
        :hint="t('venues.characterHint')"
        :label="t('venues.byCharacter')"
        :options="venueCharacterOptions"
        :selected="features"
        @change="applyFilters({ character: $event })"
      />
      <MultiSelectFilter
        :all-label="t('venues.allGenres')"
        :clear-label="t('venues.clearFamilies')"
        :count-label="(n) => t('venues.familiesSelected', { n })"
        :label="t('venues.byGenre')"
        :options="familyOptions"
        :selected="queryList('family')"
        @change="applyFilters({ family: $event })"
      />
      <MultiSelectFilter
        :all-label="t('venues.allEventTypes')"
        :clear-label="t('venues.clearEventTypes')"
        :count-label="(n) => t('venues.eventTypesSelected', { n })"
        :label="t('venues.byEventType')"
        :options="hostOptions"
        :selected="queryList('eventType')"
        @change="applyFilters({ eventType: $event })"
      />

      <div :aria-label="t('venues.view.label')" class="flex gap-2" role="group">
        <Button
          :aria-pressed="!showMap"
          :variant="showMap ? 'outline' : 'default'"
          size="sm"
          type="button"
          @click="setView('list')"
        >
          {{ t('venues.view.list') }}
        </Button>
        <Button
          :aria-pressed="showMap"
          :variant="showMap ? 'default' : 'outline'"
          size="sm"
          type="button"
          @click="setView('map')"
        >
          {{ t('venues.view.map') }}
        </Button>
      </div>
    </div>

    <template v-if="showMap">
      <p v-if="mapLoading" class="text-body text-muted-foreground">
        {{ t('common.states.loadingVenues') }}
      </p>
      <p v-else-if="mapError" class="text-body text-destructive">{{ mapError }}</p>
      <div v-else-if="!pins.length" class="space-y-3">
        <VenueFeaturesEmpty
          v-if="features.length > 1"
          :features="features"
          @remove="removeFeature"
        />
        <p v-else class="text-body text-muted-foreground">{{ t('venues.empty') }}</p>
        <Button v-if="isFiltered" variant="outline" @click="clearSearch">
          {{ t('common.actions.clearSearch') }}
        </Button>
      </div>
      <p v-else class="text-body text-muted-foreground">
        {{ t('venues.resultCount', { count: pins.length }) }}
        <template v-if="unpinnedCount">
          ·
          <button class="text-primary hover:underline" type="button" @click="setView('list')">
            {{ t('venues.unpinned', { count: unpinnedCount }) }}
          </button>
        </template>
      </p>

      <section :aria-label="t('map.near.label')" class="space-y-3">
        <div class="flex flex-wrap items-center gap-2 text-body">
          <Button
            :disabled="locateState === 'locating'"
            size="sm"
            type="button"
            variant="outline"
            @click="locate"
          >
            <LocateFixed aria-hidden="true" />
            {{ t('map.near.useLocation') }}
          </Button>
          <template v-if="origin">
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
            <Button size="sm" type="button" variant="ghost" @click="clearOrigin">
              <X aria-hidden="true" />
              {{ t('map.near.clear') }}
            </Button>
          </template>
        </div>
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
          {{ t(`venues.near.${locateState}`) }}
        </p>
        <p v-if="coarse" class="text-body text-muted-foreground">
          {{ t('map.near.coarse', { accuracy: coarse }) }}
        </p>
      </section>

      <div v-if="mapUnavailable" class="space-y-3">
        <p class="text-body text-muted-foreground">{{ t('map.unavailable') }}</p>
        <Button variant="outline" @click="setView('list')">{{ t('map.showList') }}</Button>
      </div>
      <VenueMap
        v-else
        ref="venueMap"
        v-model:selected="selected"
        :origin="origin"
        :pins="pins"
        :radius-km="origin ? radiusKm : null"
        @unavailable="mapUnavailable = true"
      >
        <!-- Over the map, as on the events map: the filters and "near me" above push the map's
             lower edge to the fold, so a panel under it changed nothing in view (#2391). -->
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
              <p v-if="selectedLocation" class="truncate text-meta text-muted-foreground">
                {{ selectedLocation }}
              </p>
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
          <RouterLink
            :to="{ path: localePath('/map'), query: { venue: selectedVenue.slug } }"
            class="inline-block text-body text-primary hover:underline"
          >
            {{ t('venues.whatsOn') }}
          </RouterLink>
        </section>
      </VenueMap>

      <p
        v-if="!selectedVenue && pins.length && !mapUnavailable && !near"
        class="text-body text-muted-foreground"
      >
        {{ t('venues.pickPin') }}
      </p>

      <section v-if="near && !mapLoading && !mapError" aria-live="polite" class="space-y-2">
        <h2 class="text-section font-bold tracking-tight">
          {{ t('map.near.heading', { radius: distance(radiusKm) }) }}
        </h2>
        <p class="text-body text-muted-foreground">
          {{
            near.length
              ? t('venues.near.resultCount', { count: near.length })
              : t('venues.near.empty')
          }}
        </p>
        <div v-if="near.length" :class="CARD_LIST_CLASS">
          <VenueRow
            v-for="item in near"
            :key="item.venue.slug"
            :distance="distance(item.distanceKm)"
            :venue="item.venue"
            as="h3"
          />
        </div>
      </section>
    </template>

    <p v-else-if="loading" class="text-body text-muted-foreground">
      {{ t('common.states.loadingVenues') }}
    </p>
    <p v-else-if="error" class="text-body text-destructive">{{ error }}</p>
    <!-- An empty result offers a control, not only a sentence (#1266). -->
    <div v-else-if="!page?.content?.length" class="space-y-3">
      <VenueFeaturesEmpty v-if="features.length > 1" :features="features" @remove="removeFeature" />
      <p v-else class="text-body text-muted-foreground">{{ t('venues.empty') }}</p>
      <Button v-if="isFiltered" variant="outline" @click="clearSearch">
        {{ t('common.actions.clearSearch') }}
      </Button>
    </div>
    <template v-else>
      <div :class="RESULTS_BAR_CLASS">
        <p class="text-body text-muted-foreground">
          {{ t('venues.resultCount', { count: page.totalElements }) }}
        </p>
        <SortControl
          :model-value="sort"
          :options="sortOptions"
          @update:model-value="applyFilters({ sort: $event })"
        />
      </div>
      <!-- Second level of the outline: nothing sits between the page `h1` and this grid. -->
      <div v-if="compact" :class="CARD_LIST_CLASS">
        <VenueRow v-for="venue in page.content" :key="venue.slug" :venue="venue" as="h2" />
      </div>
      <div v-else :class="CARD_GRID_CLASS">
        <VenueCard v-for="venue in page.content" :key="venue.slug" :venue="venue" as="h2" />
      </div>

      <PaginationControls :current-page="currentPage" :total-pages="totalPages" @goto="goToPage" />
    </template>
  </main>
</template>
