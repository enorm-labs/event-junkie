<script lang="ts" setup>
import { LocateFixed, X } from '@lucide/vue'
import { computed, defineAsyncComponent, ref, shallowRef, useTemplateRef, watch } from 'vue'
import { type LocationQueryRaw, RouterLink, useRoute, useRouter } from 'vue-router'
import type { VenueListItem } from '@/api/types'
import { describeError } from '@/api/client'
import { Button } from '@/components/ui/button'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import ListFilterBar from '@/components/ListFilterBar.vue'
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
import { useFilterOptions } from '@/composables/useFilterOptions'
import {
  type FeatureCounts,
  fetchAllVenues,
  fetchVenueFeatureCounts,
  useVenueSearch,
  type VenueSearchParams,
} from '@/composables/useVenues'
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
import { CARD_GRID_CLASS, CARD_LIST_CLASS, RESULTS_BAR_CLASS } from '@/lib/utils'

const PAGE_SIZE = 24

/** The selects behind "More filters", laid out as `EventFilterBar` lays out its own (#1830). */
const SELECT_ROW_CLASS = 'grid w-full grid-cols-2 gap-3 sm:flex sm:w-auto sm:flex-wrap'
const SELECT_CLASS = 'w-full min-w-0 truncate sm:w-auto'

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

/**
 * A–Z stays out of the URL, as on the promoters list, but is sent: without a sort the BFF lists a
 * search by relevance, and the pressed A–Z would not be what the list shows (#2694).
 */
const SORT_NAME = 'name,asc'
const SORT_NAME_DESC = 'name,desc'
const SORT_UPCOMING = 'upcomingEvents,desc'
const sort = computed(() => {
  const requested = queryString('sort')
  return requested === SORT_NAME_DESC || requested === SORT_UPCOMING ? requested : ''
})

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

/**
 * The listing filter as the BFF takes it: one of the two options chosen is that side, none or both
 * is every venue (#2766).
 */
const LISTINGS = ['imported', 'not-imported'] as const
const imported = computed(() => {
  const chosen = queryList('listing')
  if (chosen.length !== 1) return undefined
  return chosen[0] === 'imported' ? true : chosen[0] === 'not-imported' ? false : undefined
})

const params = computed<VenueSearchParams>(() => ({
  q: queryString('q') || undefined,
  district: listParam('district'),
  sort: [sort.value || SORT_NAME],

  type: listParam('type'),
  family: listParam('family'),
  eventType: listParam('eventType'),
  character: listParam('character'),
  imported: imported.value,
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = useVenueSearch(() => params.value)

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

/** A filter beyond the name search, so an empty list can name the filters as its cause. */
const FILTER_KEYS = ['district', 'type', 'character', 'family', 'eventType', 'listing']
const hasFilters = computed(() => FILTER_KEYS.some((key) => queryList(key).length))
/** How many of them are set, for the "More filters" toggle; each counts once, however many values it holds. */
const moreCount = computed(() => FILTER_KEYS.filter((key) => queryList(key).length).length)

// Features combine with AND (#2670): with two or more chosen, an empty list names them as the cause.
const features = computed(() => queryList('character'))

function removeFeature(feature: string) {
  applyFilters({ character: features.value.filter((value) => value !== feature) })
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
  imported: params.value.imported,
}))

// How many venues each feature leaves (#2671). A failed request, such as a BFF without the
// endpoint during a rollout, leaves the filter without counts and every option enabled.
const featureCounts = shallowRef<FeatureCounts>()
let countsCall = 0

async function loadFeatureCounts() {
  const call = ++countsCall
  const counts = await fetchVenueFeatureCounts(mapFilters.value)
  if (call === countsCall) featureCounts.value = counts
}

watch(() => JSON.stringify(mapFilters.value), loadFeatureCounts, { immediate: true })

const mapVenues = shallowRef<VenueListItem[]>([])
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

const emptyText = computed(() => t(hasFilters.value ? 'venues.emptyFiltered' : 'venues.empty'))

const sortOptions = computed<SortOption[]>(() => [
  {
    directions: [
      { value: '', ascending: true, label: t('common.sort.az') },
      { value: SORT_NAME_DESC, ascending: false, label: t('common.sort.za') },
    ],
  },
  { label: t('common.sort.upcoming'), value: SORT_UPCOMING },
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
const listingOptions = computed(() =>
  LISTINGS.map((value) => ({
    value,
    label: t(value === 'imported' ? 'venues.listing.imported' : 'venues.listing.notImported'),
  })),
)

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
        // A venue we do not import has nothing to show here, so its pin is the muted one (#2766).
        quiet: venue.imported === false,
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
      <p class="text-body text-muted-foreground">{{ t('venues.subtitle') }}</p>
      <p class="text-body text-muted-foreground">
        {{ t('venues.missing') }}
        <a
          :href="feedbackMailto(t('venues.missingSubject'), t('venues.missingBody'))"
          class="text-foreground underline underline-offset-4"
          >{{ CONTROLLER.email }}</a
        >
      </p>
    </header>

    <ListFilterBar :more-count="moreCount" :placeholder="t('venues.searchPlaceholder')">
      <template #more>
        <div :class="SELECT_ROW_CLASS">
          <MultiSelectFilter
            :all-label="t('venues.allDistricts')"
            :class="SELECT_CLASS"
            :clear-label="t('venues.clearDistricts')"
            :count-label="(n) => t('venues.districtsSelected', { n })"
            :label="t('venues.byDistrict')"
            :options="districtOptions"
            :selected="queryList('district')"
            @change="applyFilters({ district: $event })"
          />
          <MultiSelectFilter
            :all-label="t('venues.allTypes')"
            :class="SELECT_CLASS"
            :clear-label="t('venues.clearTypes')"
            :count-label="(n) => t('venues.typesSelected', { n })"
            :label="t('venues.byType')"
            :options="venueTypeOptions"
            :selected="queryList('type')"
            @change="applyFilters({ type: $event })"
          />
          <MultiSelectFilter
            :all-label="t('venues.allCharacters')"
            :class="SELECT_CLASS"
            :clear-label="t('venues.clearCharacters')"
            :count-label="(n) => t('venues.charactersSelected', { n })"
            :counts="featureCounts"
            :hint="t('venues.characterHint')"
            :label="t('venues.byCharacter')"
            :options="venueCharacterOptions"
            :selected="features"
            @change="applyFilters({ character: $event })"
          />
          <MultiSelectFilter
            :all-label="t('venues.allGenres')"
            :class="SELECT_CLASS"
            :clear-label="t('venues.clearFamilies')"
            :count-label="(n) => t('venues.familiesSelected', { n })"
            :label="t('venues.byGenre')"
            :options="familyOptions"
            :selected="queryList('family')"
            @change="applyFilters({ family: $event })"
          />
          <MultiSelectFilter
            :all-label="t('venues.allEventTypes')"
            :class="SELECT_CLASS"
            :clear-label="t('venues.clearEventTypes')"
            :count-label="(n) => t('venues.eventTypesSelected', { n })"
            :label="t('venues.byEventType')"
            :options="hostOptions"
            :selected="queryList('eventType')"
            @change="applyFilters({ eventType: $event })"
          />
          <MultiSelectFilter
            :all-label="t('venues.allListings')"
            :class="SELECT_CLASS"
            :clear-label="t('venues.clearListings')"
            :count-label="(n) => t('venues.listingsSelected', { n })"
            :label="t('venues.byListing')"
            :options="listingOptions"
            :selected="queryList('listing')"
            @change="applyFilters({ listing: $event })"
          />
        </div>
      </template>
      <template #actions>
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
      </template>
    </ListFilterBar>

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
        <p v-else class="text-body text-muted-foreground">{{ emptyText }}</p>
        <ClearAllFilters empty-state />
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
          <p v-if="selectedVenue.imported === false" class="text-body text-muted-foreground">
            {{ t('venues.notImported') }}
          </p>
          <RouterLink
            v-else
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
      <p v-else class="text-body text-muted-foreground">{{ emptyText }}</p>
      <ClearAllFilters empty-state />
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
