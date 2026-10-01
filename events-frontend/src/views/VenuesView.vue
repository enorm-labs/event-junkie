<script lang="ts" setup>
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
import { usePagedList } from '@/composables/usePagedList'
import { fetchAllVenues, useVenueSearch, type VenueSearchParams } from '@/composables/useVenues'
import { DISTRICTS } from '@/lib/districts'
import { type MapPin, venuePin } from '@/lib/mapPins'
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
  Object.keys(route.query).some((key) => key !== 'page' && key !== 'view'),
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

const pins = computed<MapPin[]>(() =>
  mapVenues.value
    .map((venue) => venuePin(venue, venue.name ?? ''))
    .filter((pin): pin is MapPin => pin !== null),
)

/** Venues without a coordinate: named, so a venue missing from the map is not taken for one we lack. */
const unpinnedCount = computed(() => mapVenues.value.length - pins.value.length)

const selectedVenue = computed(
  () => mapVenues.value.find((venue) => venue.slug === selected.value) ?? null,
)

const { t } = useI18n()
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

      <div v-if="mapUnavailable" class="space-y-3">
        <p class="text-sm text-muted-foreground">{{ t('map.unavailable') }}</p>
        <Button variant="outline" @click="setView('list')">{{ t('map.showList') }}</Button>
      </div>
      <VenueMap
        v-else
        v-model:selected="selected"
        :pins="pins"
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
      <p v-else-if="pins.length && !mapUnavailable" class="text-sm text-muted-foreground">
        {{ t('venues.pickPin') }}
      </p>
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
