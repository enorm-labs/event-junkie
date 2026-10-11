<script lang="ts" setup>
import { computed } from 'vue'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import ListFilterBar from '@/components/ListFilterBar.vue'
import ListPage from '@/components/ListPage.vue'
import PaginationControls from '@/components/PaginationControls.vue'
import PromoterCard from '@/components/PromoterCard.vue'
import SortControl from '@/components/SortControl.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePagedList } from '@/composables/usePagedList'
import { usePromoterSearch, type PromoterSearchParams } from '@/composables/usePromoters'
import { useI18n } from 'vue-i18n'
import { CARD_GRID_CLASS, RESULTS_BAR_CLASS } from '@/lib/utils'

const PAGE_SIZE = 24

const { queryString, applyFilters, sort, sortParam, sortOptions } = useListQuery()

const params = computed<PromoterSearchParams>(() => ({
  q: queryString('q') || undefined,
  sort: sortParam.value,
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = usePromoterSearch(() => params.value)

// Paging, the clamp on an out-of-range `?page=`, and the reload on any query change.
const { currentPage, totalPages, goToPage } = usePagedList(page, run)

const { t } = useI18n()
</script>

<template>
  <ListPage :subtitle="t('promoters.subtitle')" :title="t('promoters.title')">
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
  </ListPage>
</template>
