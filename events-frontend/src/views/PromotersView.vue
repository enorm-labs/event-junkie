<script lang="ts" setup>
import { computed } from 'vue'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import ErrorLine from '@/components/ErrorLine.vue'
import ListFilterBar from '@/components/ListFilterBar.vue'
import ListPage from '@/components/ListPage.vue'
import MultiSelectFilter, { type FilterOption } from '@/components/MultiSelectFilter.vue'
import PaginationControls from '@/components/PaginationControls.vue'
import PromoterCard from '@/components/PromoterCard.vue'
import SortControl from '@/components/SortControl.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePagedList } from '@/composables/usePagedList'
import { usePageMeta } from '@/composables/usePageMeta'
import { usePromoterSearch, type PromoterSearchParams } from '@/composables/usePromoters'
import { useI18n } from 'vue-i18n'
import { staticPageMeta } from '@/lib/pageMeta'
import { isShownKind, SHOWN_KINDS } from '@/lib/promoterKinds'
import { CARD_GRID_CLASS, RESULTS_BAR_CLASS } from '@/lib/utils'

const PAGE_SIZE = 24

const { queryString, queryList, applyFilters, sort, sortParam, sortOptions } = useListQuery()

// Only the kinds the filter offers reach the BFF, so a stale or hand-typed `?kind=` lists all rather than a 400 page.
const kinds = computed(() => queryList('kind').filter(isShownKind))

const params = computed<PromoterSearchParams>(() => ({
  q: queryString('q') || undefined,
  kind: kinds.value.length ? kinds.value : undefined,
  sort: sortParam.value,
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, retrying, run } = usePromoterSearch(() => params.value)

// Paging, the clamp on an out-of-range `?page=`, and the reload on any query change.
const { currentPage, totalPages, pageLink } = usePagedList(page, run)

const { t } = useI18n()

const kindOptions = computed<FilterOption[]>(() =>
  SHOWN_KINDS.map((kind) => ({ value: kind, label: t(`promoters.kind.${kind}`) })),
)

// One kind names the page, "Collectives in Berlin" (#5091). None or several keep "Promoters".
const onlyKind = computed(() => (kinds.value.length === 1 ? kinds.value[0] : undefined))
const title = computed(() =>
  onlyKind.value ? t(`promoters.kindTitle.${onlyKind.value}`) : t('promoters.title'),
)
const subtitle = computed(() =>
  onlyKind.value ? t(`promoters.kindSubtitle.${onlyKind.value}`) : t('promoters.subtitle'),
)

// The route meta's title stands for the unfiltered list. This runs after the router's guard, on a filter change too.
usePageMeta(() =>
  onlyKind.value
    ? staticPageMeta(title.value, subtitle.value)
    : staticPageMeta(t('pageTitle.promoters'), t('pageDescription.promoters')),
)
</script>

<template>
  <ListPage :subtitle="subtitle" :title="title">
    <ListFilterBar :placeholder="t('promoters.searchPlaceholder')">
      <template #actions>
        <MultiSelectFilter
          :all-label="t('promoters.allKinds')"
          :clear-label="t('promoters.clearKinds')"
          :count-label="(n) => t('promoters.kindsSelected', { n })"
          :label="t('promoters.byKind')"
          :options="kindOptions"
          :selected="kinds"
          class="w-full min-w-0 truncate sm:w-auto"
          @change="applyFilters({ kind: $event })"
        />
      </template>
    </ListFilterBar>

    <p v-if="loading" class="text-body text-muted-foreground">
      {{ retrying ? t('errors.retrying') : t('common.states.loadingPromoters') }}
    </p>
    <ErrorLine v-else-if="error">{{ error }}</ErrorLine>
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

      <PaginationControls
        :current-page="currentPage"
        :page-link="pageLink"
        :total-pages="totalPages"
      />
    </template>
  </ListPage>
</template>
