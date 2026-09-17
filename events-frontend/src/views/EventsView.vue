<script lang="ts" setup>
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import { useCompactView } from '@/composables/useCompactView'
import { CARD_GRID_CLASS, CARD_LIST_CLASS } from '@/lib/utils'
import EventFilterBar from '@/components/EventFilterBar.vue'
import { type EventSearchParams, useEventSearch } from '@/composables/useEvents'
import { useEventFilters } from '@/composables/useEventFilters'
import { usePagedList } from '@/composables/usePagedList'
import { useI18n } from 'vue-i18n'
import { useLocalePath } from '@/composables/useLocalePath'

const PAGE_SIZE = 20

const route = useRoute()
const router = useRouter()

// Filters live in the URL query so list views are shareable and survive back/forward; the
// filter bar writes them and `useEventFilters` reads them back (see EventFilterBar.vue).
const { queryString, filters, dateRange } = useEventFilters()

// The list owns its dates, so it merges the range in; the BFF defaults to today onwards when
// both bounds are absent, which is the right empty state here.
const params = computed<EventSearchParams>(() => ({
  ...filters.value,
  ...dateRange.value,
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

const { data: page, error, loading, run } = useEventSearch(() => params.value)

// Paging, the clamp on an out-of-range `?page=`, and the reload on any query change.
const { currentPage, totalPages, goToPage } = usePagedList(page, run)

/** Whether anything narrows the list, which is what a "clear" control has to have to offer. */
const isFiltered = computed(() => Object.keys(route.query).some((key) => key !== 'page'))

function clearFilters() {
  router.push({ query: {} })
}

const localePath = useLocalePath()

const { t } = useI18n()
// The compact view is a global display preference — see `useCompactView`.
const { compact } = useCompactView()
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <h1 class="text-page font-bold tracking-tight">{{ t('events.title') }}</h1>
      <p class="text-muted-foreground">{{ t('events.subtitle') }}</p>
    </header>

    <EventFilterBar />

    <p v-if="loading" class="text-sm text-muted-foreground">{{ t('common.states.loading') }}</p>
    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>
    <!--
      An empty result has to offer something to do. It used to be one sentence and no control at
      all, under a filter bar six rows tall on a phone — the visitor had to work out which of eight
      inputs to undo (#1266).
    -->
    <div v-else-if="!page?.content?.length" class="space-y-3">
      <p class="text-sm text-muted-foreground">{{ t('events.empty') }}</p>
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
      <p class="text-sm text-muted-foreground">
        {{ t('events.resultCount', { count: page.totalElements }) }}
      </p>
      <!-- No section heading sits between the page `h1` and the grid here (unlike the home and
           detail pages), so the cards are the second level of the outline. The compact view swaps
           the whole grid rather than hiding the posters: a hidden image is still downloaded. -->
      <div v-if="compact" :class="CARD_LIST_CLASS">
        <EventRow v-for="event in page.content" :key="event.slug" :event="event" as="h2" />
      </div>
      <div v-else :class="CARD_GRID_CLASS">
        <EventCard v-for="event in page.content" :key="event.slug" :event="event" as="h2" />
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
