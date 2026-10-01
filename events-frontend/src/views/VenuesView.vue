<script lang="ts" setup>
import { LocateFixed, X } from '@lucide/vue'
import { computed, defineAsyncComponent, ref, shallowRef, watch } from 'vue'
import { type LocationQueryRaw, RouterLink, useRoute, useRouter } from 'vue-router'
import type { VenueSummary } from '@/api/types'
import { describeError } from '@/api/client'
import { Button } from '@/components/ui/button'
import BaseInput from '@/components/BaseInput.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import PaginationControls from '@/components/PaginationControls.vue'
import VenueCard from '@/components/VenueCard.vue'
import VenueRow from '@/components/VenueRow.vue'
import { useCompactView } from '@/composables/useCompactView'
import { useLocalePath } from '@/composables/useLocalePath'
import { useLocation } from '@/composables/useLocation'
import { usePagedList } from '@/composables/usePagedList'
import { fetchAllVenues, useVenueSearch, type VenueSearchParams } from '@/composables/useVenues'
import { DISTRICTS } from '@/lib/districts'
import { formatDistance } from '@/lib/geo'
import {
  DEFAULT_RADIUS,
  type MapPin,
  nearby,
  RADII,
  radiusFromQuery,
  venuePin,
} from '@/lib/mapPins'
import { useI18n } from 'vue-i18n'
import { CARD_GRID_CLASS, CARD_LIST_CLASS, PANEL_CLASS } from '@/lib/utils'

const PAGE_SIZE = 24

// Loaded only when the map is shown: MapLibre is the heaviest dependency the site has.
const VenueMap = defineAsyncComponent(() => import('@/components/VenueMap.vue'))

const route = useRoute()
const router = useRouter()

// Filters live in the URL query so the list is shareable and survives back/forward.
function queryString(key: string): string {
  const value = route.query[key]
  return typeof value === 'string' ? value : ''
}

const params = computed<VenueSearchParams>(() => ({
  q: queryString('q') || undefined,
  district: queryString('district') || undefined,
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = useVenueSearch(() => params.value)

// The search box is a local draft applied on submit, kept in sync with the URL.
const search = ref(queryString('q'))
watch(
  () => route.query.q,
  () => {
    search.value = queryString('q')
  },
)

// Paging, the clamp on an out-of-range `?page=`, and the reload on any query change.
const { currentPage, totalPages, goToPage } = usePagedList(page, run)

function applyFilters(patch: LocationQueryRaw) {
  // Any filter change resets to the first page; empty values drop out of the URL.
  const next: LocationQueryRaw = { ...route.query, ...patch, page: undefined }
  for (const key of Object.keys(next)) {
    if (next[key] === '' || next[key] === undefined) delete next[key]
  }
  router.push({ query: next })
}

/** Whether anything narrows the list, which is what a "clear" control has to have to offer. */
const isFiltered = computed(() =>
  Object.keys(route.query).some((key) => !['page', 'view', 'radius'].includes(key)),
)

function clearSearch() {
  search.value = ''
  router.push({ query: showMap.value ? { view: 'map' } : {} })
}

// The map is a second view of the same search, in the URL so a shared link opens on it.
const showMap = computed(() => queryString('view') === 'map')

function setView(view: 'list' | 'map') {
  router.push({
    query: { ...route.query, view: view === 'map' ? 'map' : undefined, page: undefined },
  })
}

const mapVenues = shallowRef<VenueSummary[]>([])
const mapLoading = ref(false)
const mapError = ref<string | null>(null)
const mapUnavailable = ref(false)
const selected = ref<string | null>(null)

async function loadMap() {
  if (!showMap.value) return
  mapLoading.value = true
  try {
    mapVenues.value = await fetchAllVenues({ q: params.value.q, district: params.value.district })
    mapError.value = null
  } catch (e) {
    mapVenues.value = []
    mapError.value = describeError(e, 'errors.subject.venues')
  } finally {
    mapLoading.value = false
  }
}

watch(() => [showMap.value, params.value.q, params.value.district], loadMap, { immediate: true })

const { t, locale } = useI18n()

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

// The compact view is a global display preference — see `useCompactView`.
const { compact } = useCompactView()
const localePath = useLocalePath()
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('venues.title') }}</h1>
      <p class="text-muted-foreground">{{ t('venues.subtitle') }}</p>
    </header>

    <div :class="PANEL_CLASS">
      <form class="flex gap-2" @submit.prevent="applyFilters({ q: search })">
        <BaseInput
          v-model="search"
          :placeholder="t('venues.searchPlaceholder')"
          class="px-3"
          type="search"
        />
        <Button type="submit" variant="outline">{{ t('common.actions.search') }}</Button>
      </form>

      <BaseSelect
        :aria-label="t('venues.byDistrict')"
        :model-value="queryString('district')"
        @change="applyFilters({ district: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">{{ t('venues.allDistricts') }}</option>
        <option v-for="d in DISTRICTS" :key="d.slug" :value="d.slug">{{ d.label }}</option>
      </BaseSelect>

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
      <p v-if="mapLoading" class="text-sm text-muted-foreground">
        {{ t('common.states.loadingVenues') }}
      </p>
      <p v-else-if="mapError" class="text-sm text-destructive">{{ mapError }}</p>
      <div v-else-if="!pins.length" class="space-y-3">
        <p class="text-sm text-muted-foreground">{{ t('venues.empty') }}</p>
        <Button v-if="isFiltered" variant="outline" @click="clearSearch">
          {{ t('common.actions.clearSearch') }}
        </Button>
      </div>
      <p v-else class="text-sm text-muted-foreground">
        {{ t('venues.resultCount', { count: pins.length }) }}
        <template v-if="unpinnedCount">
          ·
          <button class="text-primary hover:underline" type="button" @click="setView('list')">
            {{ t('venues.unpinned', { count: unpinnedCount }) }}
          </button>
        </template>
      </p>

      <section :aria-label="t('map.near.label')" class="space-y-3">
        <div class="flex flex-wrap items-center gap-2 text-sm">
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
          class="text-sm text-muted-foreground"
        >
          {{ t('map.near.locating') }}
        </p>
        <p
          v-else-if="locateState === 'denied' || locateState === 'unavailable'"
          aria-live="polite"
          class="text-sm text-muted-foreground"
        >
          {{ t(`venues.near.${locateState}`) }}
        </p>
        <p v-if="coarse" class="text-sm text-muted-foreground">
          {{ t('map.near.coarse', { accuracy: coarse }) }}
        </p>
      </section>

      <div v-if="mapUnavailable" class="space-y-3">
        <p class="text-sm text-muted-foreground">{{ t('map.unavailable') }}</p>
        <Button variant="outline" @click="setView('list')">{{ t('map.showList') }}</Button>
      </div>
      <VenueMap
        v-else
        v-model:selected="selected"
        :origin="origin"
        :pins="pins"
        :radius-km="origin ? radiusKm : null"
        @unavailable="mapUnavailable = true"
      />

      <section v-if="selectedVenue" aria-live="polite" class="space-y-2">
        <div :class="CARD_LIST_CLASS">
          <VenueRow :venue="selectedVenue" as="h2" />
        </div>
        <RouterLink
          :to="{ path: localePath('/map'), query: { venue: selectedVenue.slug } }"
          class="inline-block text-body text-primary hover:underline"
        >
          {{ t('venues.whatsOn') }}
        </RouterLink>
      </section>
      <p v-else-if="pins.length && !mapUnavailable && !near" class="text-sm text-muted-foreground">
        {{ t('venues.pickPin') }}
      </p>

      <section v-if="near && !mapLoading && !mapError" aria-live="polite" class="space-y-2">
        <h2 class="text-section font-bold tracking-tight">
          {{ t('map.near.heading', { radius: distance(radiusKm) }) }}
        </h2>
        <p class="text-sm text-muted-foreground">
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

    <p v-else-if="loading" class="text-sm text-muted-foreground">
      {{ t('common.states.loadingVenues') }}
    </p>
    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>
    <!-- An empty result offers a control, not only a sentence (#1266). -->
    <div v-else-if="!page?.content?.length" class="space-y-3">
      <p class="text-sm text-muted-foreground">{{ t('venues.empty') }}</p>
      <Button v-if="isFiltered" variant="outline" @click="clearSearch">
        {{ t('common.actions.clearSearch') }}
      </Button>
    </div>
    <template v-else>
      <p class="text-sm text-muted-foreground">
        {{ t('venues.resultCount', { count: page.totalElements }) }}
      </p>
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
