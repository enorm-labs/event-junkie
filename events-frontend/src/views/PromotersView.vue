<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { type LocationQueryRaw, useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import BaseInput from '@/components/BaseInput.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import PromoterCard from '@/components/PromoterCard.vue'
import { usePagedList } from '@/composables/usePagedList'
import { usePromoterSearch, type PromoterSearchParams } from '@/composables/usePromoters'
import { useI18n } from 'vue-i18n'
import { CARD_GRID_CLASS, PANEL_CLASS } from '@/lib/utils'

const PAGE_SIZE = 24

const route = useRoute()
const router = useRouter()

// Filters live in the URL query so the list is shareable and survives back/forward.
function queryString(key: string): string {
  const value = route.query[key]
  return typeof value === 'string' ? value : ''
}

/**
 * The two orders the page offers. The name order is the API's default and stays out of the URL;
 * the count order is `sort=upcomingEvents,desc`, which is the whole value the BFF reads (#1349).
 */
const SORT_UPCOMING = 'upcomingEvents,desc'
const sort = computed(() => (queryString('sort') === SORT_UPCOMING ? SORT_UPCOMING : ''))

const params = computed<PromoterSearchParams>(() => ({
  q: queryString('q') || undefined,
  sort: sort.value ? [sort.value] : undefined,
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = usePromoterSearch(() => params.value)

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
  Object.keys(route.query).some((key) => key !== 'page' && key !== 'sort'),
)

function clearSearch() {
  search.value = ''
  router.push({ query: {} })
}

const { t } = useI18n()
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('promoters.title') }}</h1>
      <p class="text-muted-foreground">{{ t('promoters.subtitle') }}</p>
    </header>

    <div :class="PANEL_CLASS">
      <form class="flex gap-2" @submit.prevent="applyFilters({ q: search })">
        <BaseInput
          v-model="search"
          :placeholder="t('promoters.searchPlaceholder')"
          class="px-3"
          type="search"
        />
        <Button type="submit" variant="outline">{{ t('common.actions.search') }}</Button>
      </form>

      <BaseSelect
        :aria-label="t('promoters.sortBy')"
        :model-value="sort"
        @change="applyFilters({ sort: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">{{ t('promoters.sortName') }}</option>
        <option :value="SORT_UPCOMING">{{ t('promoters.sortUpcoming') }}</option>
      </BaseSelect>
    </div>

    <p v-if="loading" class="text-sm text-muted-foreground">
      {{ t('common.states.loadingPromoters') }}
    </p>
    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>
    <!-- An empty result offers a control, not only a sentence (#1266). -->
    <div v-else-if="!page?.content?.length" class="space-y-3">
      <p class="text-sm text-muted-foreground">{{ t('promoters.empty') }}</p>
      <Button v-if="isFiltered" variant="outline" @click="clearSearch">
        {{ t('common.actions.clearSearch') }}
      </Button>
    </div>
    <template v-else>
      <p class="text-sm text-muted-foreground">
        {{ t('promoters.resultCount', { count: page.totalElements }) }}
      </p>
      <div :class="CARD_GRID_CLASS">
        <!-- Second level of the outline: nothing sits between the page `h1` and this grid. -->
        <PromoterCard
          v-for="promoter in page.content"
          :key="promoter.slug"
          :promoter="promoter"
          as="h2"
        />
      </div>

      <div v-if="totalPages > 1" class="flex items-center justify-between gap-3 pt-2">
        <Button :disabled="currentPage <= 0" variant="outline" @click="goToPage(currentPage - 1)">
          {{ t('common.actions.previous') }}
        </Button>
        <span class="text-sm text-muted-foreground">
          {{ t('common.pagination.pageOf', { current: currentPage + 1, total: totalPages }) }}
        </span>
        <Button
          :disabled="currentPage >= totalPages - 1"
          variant="outline"
          @click="goToPage(currentPage + 1)"
        >
          {{ t('common.actions.next') }}
        </Button>
      </div>
    </template>
  </main>
</template>
