<script lang="ts" setup>
/**
 * Every upcoming children's show in one list (#5079). The BFF hides a `for-children` event from the
 * default lists (EVENT_SCOPE.md §3.5), so this page and the "Für Kinder" filter on `/events` are where a
 * parent finds them. No filter bar: the page is for browsing, and the filter on `/events` is one click away.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute } from 'vue-router'
import AlsoRunning from '@/components/AlsoRunning.vue'
import ErrorLine from '@/components/ErrorLine.vue'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import ListPage from '@/components/ListPage.vue'
import ShowMoreEvents from '@/components/ShowMoreEvents.vue'
import { Button } from '@/components/ui/button'
import { useAsync } from '@/composables/useAsync'
import { useCompactView } from '@/composables/useCompactView'
import {
  type EventSearchParams,
  type GrownEventPage,
  growEventSearch,
} from '@/composables/useEvents'
import { useLocalePath } from '@/composables/useLocalePath'
import { usePagedList } from '@/composables/usePagedList'
import { FOR_CHILDREN } from '@/lib/eventFeatures'
import { todayIso } from '@/lib/format'
import { splitDayList } from '@/lib/longRuns'
import { CARD_GRID_CLASS, CARD_LIST_CLASS, RESULTS_BAR_CLASS, TEXT_LINK_CLASS } from '@/lib/utils'

const PAGE_SIZE = 20

const route = useRoute()
const { t } = useI18n()
const localePath = useLocalePath()
const { compact } = useCompactView()

const params = computed<EventSearchParams>(() => ({
  feature: [FOR_CHILDREN],
  page: Number(route.query.page) || 0,
  size: PAGE_SIZE,
}))

const {
  data: page,
  error,
  loading,
  retrying,
  run,
} = useAsync<GrownEventPage>(
  (): Promise<GrownEventPage> => growEventSearch(params.value, page.value),
  'errors.subject.forChildren',
  () => `/api/events?${JSON.stringify(params.value)}`,
)

const { currentPage, totalPages, pageLink } = usePagedList(page, run)

// A run of weeks, such as a Christmas show, folds into "Also running" after its first days (#2594).
const listed = computed(() => splitDayList(page.value?.content ?? [], todayIso()))

/** The list keeps its cards while the next page loads under them. */
const growing = computed(() => page.value?.page === (params.value.page ?? 0) - 1)
const hasMore = computed(() => currentPage.value < totalPages.value - 1)

const allEvents = computed(() => ({
  path: localePath('/events'),
  query: { feature: FOR_CHILDREN },
}))
</script>

<template>
  <ListPage :subtitle="t('forChildren.subtitle')" :title="t('forChildren.title')">
    <p v-if="loading && !growing" class="text-body text-muted-foreground">
      {{ retrying ? t('errors.retrying') : t('common.states.loading') }}
    </p>
    <ErrorLine v-else-if="error">{{ error }}</ErrorLine>
    <div v-else-if="!page?.content?.length" class="space-y-3">
      <p class="text-body text-muted-foreground">{{ t('forChildren.empty') }}</p>
      <Button as-child variant="outline">
        <RouterLink :to="localePath('/events')">{{ t('forChildren.browseAll') }}</RouterLink>
      </Button>
    </div>
    <template v-else>
      <div :class="RESULTS_BAR_CLASS">
        <p class="text-body text-muted-foreground">
          {{ t('forChildren.resultCount', { count: page.totalElements }) }}
        </p>
        <!-- The events list carries the filters (day, district, price) this page leaves out. -->
        <RouterLink :class="['text-body', TEXT_LINK_CLASS]" :to="allEvents">
          {{ t('forChildren.filterOnEvents') }}
        </RouterLink>
      </div>
      <p v-if="page.firstPage > 0" class="text-body">
        <RouterLink :class="TEXT_LINK_CLASS" :to="pageLink(0)">
          {{ t('common.pagination.fromStart') }}
        </RouterLink>
      </p>
      <div v-if="compact" :class="CARD_LIST_CLASS">
        <EventRow v-for="event in listed.cards" :key="event.slug" :event="event" as="h2" />
      </div>
      <div v-else :class="CARD_GRID_CLASS">
        <EventCard
          v-for="(event, index) in listed.cards"
          :key="event.slug"
          :event="event"
          :priority="index === 0"
          as="h2"
        />
      </div>

      <AlsoRunning :events="listed.alsoRunning" as="h2" />

      <ShowMoreEvents
        v-if="hasMore"
        :loading="growing && loading"
        :next="pageLink(currentPage + 1)"
        :shown="page.content.length"
        :total="page.totalElements ?? 0"
      />
    </template>
  </ListPage>
</template>
