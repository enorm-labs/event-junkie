<script lang="ts" setup>
import { computed } from 'vue'
import { type LocationQueryRaw, useRoute, useRouter } from 'vue-router'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import ListFilterBar from '@/components/ListFilterBar.vue'
import PaginationControls from '@/components/PaginationControls.vue'
import PromoterCard from '@/components/PromoterCard.vue'
import SortControl, { type SortOption } from '@/components/SortControl.vue'
import { usePagedList } from '@/composables/usePagedList'
import { usePromoterSearch, type PromoterSearchParams } from '@/composables/usePromoters'
import { useI18n } from 'vue-i18n'
import { CARD_GRID_CLASS, RESULTS_BAR_CLASS } from '@/lib/utils'

const PAGE_SIZE = 24

const route = useRoute()
const router = useRouter()

// Filters live in the URL query so the list is shareable and survives back/forward.
function queryString(key: string): string {
  const value = route.query[key]
  return typeof value === 'string' ? value : ''
}

/**
 * The two orders the page offers. A–Z stays out of the URL but is sent: without a sort the BFF
 * lists a search by relevance (#2694). The count order is `sort=upcomingEvents,desc` (#1349).
 */
const SORT_NAME = 'name,asc'
const SORT_UPCOMING = 'upcomingEvents,desc'
const sort = computed(() => (queryString('sort') === SORT_UPCOMING ? SORT_UPCOMING : ''))

const params = computed<PromoterSearchParams>(() => ({
  q: queryString('q') || undefined,
  sort: [sort.value || SORT_NAME],
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = usePromoterSearch(() => params.value)

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

const { t } = useI18n()

const sortOptions = computed<SortOption[]>(() => [
  { value: '', label: t('common.sort.name') },
  { value: SORT_UPCOMING, label: t('common.sort.upcoming') },
])
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('promoters.title') }}</h1>
      <p class="text-body text-muted-foreground">{{ t('promoters.subtitle') }}</p>
    </header>

    <ListFilterBar :placeholder="t('promoters.searchPlaceholder')" />

    <p v-if="loading" class="text-body text-muted-foreground">
      {{ t('common.states.loadingPromoters') }}
    </p>
    <p v-else-if="error" class="text-body text-destructive">{{ error }}</p>
    <!-- An empty result offers a control, not only a sentence (#1266). -->
    <div v-else-if="!page?.content?.length" class="space-y-3">
      <p class="text-body text-muted-foreground">{{ t('promoters.empty') }}</p>
      <ClearAllFilters empty-state />
    </div>
    <template v-else>
      <div :class="RESULTS_BAR_CLASS">
        <p class="text-body text-muted-foreground">
          {{ t('promoters.resultCount', { count: page.totalElements }) }}
        </p>
        <SortControl
          :model-value="sort"
          :options="sortOptions"
          @update:model-value="applyFilters({ sort: $event })"
        />
      </div>
      <div :class="CARD_GRID_CLASS">
        <!-- Second level of the outline: nothing sits between the page `h1` and this grid. -->
        <PromoterCard
          v-for="promoter in page.content"
          :key="promoter.slug"
          :promoter="promoter"
          as="h2"
        />
      </div>

      <PaginationControls :current-page="currentPage" :total-pages="totalPages" @goto="goToPage" />
    </template>
  </main>
</template>
