<script lang="ts" setup>
import { Rss } from '@lucide/vue'
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import { useCompactView } from '@/composables/useCompactView'
import { CARD_GRID_CLASS, CARD_LIST_CLASS, RESULTS_BAR_CLASS } from '@/lib/utils'
import { todayIso } from '@/lib/format'
import EventFilterBar from '@/components/EventFilterBar.vue'
import PaginationControls from '@/components/PaginationControls.vue'
import SortControl, { type SortOption } from '@/components/SortControl.vue'
import { type EventSearchParams, useEventSearch } from '@/composables/useEvents'
import { useEventFilters } from '@/composables/useEventFilters'
import { usePagedList } from '@/composables/usePagedList'
import { useI18n } from 'vue-i18n'
import type { Locale } from '@/i18n/locales'
import { FEED_TYPE, feedPath } from '@/lib/seo'
import { useLocalePath } from '@/composables/useLocalePath'

const PAGE_SIZE = 20

const route = useRoute()
const router = useRouter()

// Filters live in the URL query so list views are shareable and survive back/forward; the
// filter bar writes them and `useEventFilters` reads them back (see EventFilterBar.vue).
const { queryString, filters, dateRange, applyFilters } = useEventFilters()

const EARLIEST_FIRST = 'eventDate,asc'
const LATEST_FIRST = 'eventDate,desc'

/**
 * Date is the only order. A range that ends before today is an archive and reads latest first; a
 * range that reaches into the past lets the visitor flip it. Without a lower bound the BFF reads
 * back through every year, so `to` alone reaches the past too (#360).
 */
const reachesPast = computed(() => {
  const { from, to } = dateRange.value
  return from ? from < todayIso() : !!to
})
const defaultSort = computed(() => {
  const { to } = dateRange.value
  return to && to < todayIso() ? LATEST_FIRST : EARLIEST_FIRST
})
const sort = computed(() => {
  const requested = queryString('sort')
  return reachesPast.value && [EARLIEST_FIRST, LATEST_FIRST].includes(requested)
    ? requested
    : defaultSort.value
})

// The list owns its dates, so it merges the range in; the BFF defaults to today onwards when
// both bounds are absent, which is the right empty state here.
const params = computed<EventSearchParams>(() => ({
  ...filters.value,
  ...dateRange.value,
  sort: sort.value === EARLIEST_FIRST ? undefined : [sort.value],
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = useEventSearch(() => params.value)

// Paging, the clamp on an out-of-range `?page=`, and the reload on any query change.
const { currentPage, totalPages, goToPage } = usePagedList(page, run)

/** Whether anything narrows the list, which is what a "clear" control has to have to offer. */
const isFiltered = computed(() =>
  Object.keys(route.query).some((key) => key !== 'page' && key !== 'sort'),
)

function clearFilters() {
  router.push({ query: {} })
}

const localePath = useLocalePath()

const { t, locale } = useI18n()

const sortOptions = computed<SortOption[]>(() => [
  { value: EARLIEST_FIRST, label: t('common.sort.earliest') },
  { value: LATEST_FIRST, label: t('common.sort.latest') },
])

/** The default order stays out of the URL, so a shared link follows its range. */
function applySort(value: string) {
  applyFilters({ sort: value === defaultSort.value ? '' : value })
}
// The compact view is a global display preference — see `useCompactView`.
const { compact } = useCompactView()

/**
 * The feed of new events for these filters (#368), a saved search a reader polls. The feed has
 * no dates, so the date range stays out of it.
 */
const feedHref = computed(() => feedPath(locale.value as Locale, filters.value))
const feedLabel = computed(() =>
  Object.values(filters.value).some((value) => value !== undefined)
    ? t('events.feedFiltered')
    : t('events.feedAll'),
)

// "Near me" lives on the map, which measures distance on the device (#358); the filters go with it.
const mapLink = computed(() => ({
  path: localePath('/map'),
  query: { ...route.query, page: undefined, sort: undefined },
}))
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('events.title') }}</h1>
      <p class="text-muted-foreground">{{ t('events.subtitle') }}</p>
    </header>

    <EventFilterBar />

    <p v-if="loading" class="text-body text-muted-foreground">{{ t('common.states.loading') }}</p>
    <p v-else-if="error" class="text-body text-destructive">{{ error }}</p>
    <!--
      An empty result has to offer something to do. It used to be one sentence and no control at
      all, under a filter bar six rows tall on a phone — the visitor had to work out which of eight
      inputs to undo (#1266).
    -->
    <div v-else-if="!page?.content?.length" class="space-y-3">
      <p class="text-body text-muted-foreground">{{ t('events.empty') }}</p>
      <div class="flex flex-wrap gap-3">
        <Button v-if="isFiltered" variant="outline" @click="clearFilters">
          {{ t('common.actions.clearFilters') }}
        </Button>
        <Button as-child variant="outline">
          <RouterLink :to="localePath('/')">{{ t('common.actions.browseTonight') }}</RouterLink>
        </Button>
      </div>
    </div>
    <template v-else>
      <div :class="RESULTS_BAR_CLASS">
        <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-body">
          <p class="text-muted-foreground">
            {{ t('events.resultCount', { count: page.totalElements }) }}
          </p>
          <RouterLink :to="mapLink" class="text-primary hover:underline">
            {{ t('events.nearMe') }}
          </RouterLink>
          <a
            :aria-label="feedLabel"
            :href="feedHref"
            :title="feedLabel"
            :type="FEED_TYPE"
            class="inline-flex items-center gap-1 self-center text-primary hover:underline"
          >
            <Rss aria-hidden="true" class="size-4" />
            {{ t('events.feed') }}
          </a>
        </div>
        <SortControl
          v-if="reachesPast"
          :model-value="sort"
          :options="sortOptions"
          @update:model-value="applySort"
        />
      </div>
      <!-- No section heading sits between the page `h1` and the grid here (unlike the home and
           detail pages), so the cards are the second level of the outline. The compact view swaps
           the whole grid rather than hiding the posters: a hidden image is still downloaded. -->
      <div v-if="compact" :class="CARD_LIST_CLASS">
        <EventRow v-for="event in page.content" :key="event.slug" :event="event" as="h2" />
      </div>
      <div v-else :class="CARD_GRID_CLASS">
        <EventCard
          v-for="(event, index) in page.content"
          :key="event.slug"
          :event="event"
          :priority="index === 0"
          as="h2"
        />
      </div>

      <PaginationControls :current-page="currentPage" :total-pages="totalPages" @goto="goToPage" />
    </template>
  </main>
</template>
