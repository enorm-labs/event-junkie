<script lang="ts" setup>
import { CalendarSync, Rss } from '@lucide/vue'
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import AlsoRunning from '@/components/AlsoRunning.vue'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import { useCompactView } from '@/composables/useCompactView'
import { CARD_GRID_CLASS, CARD_LIST_CLASS, RESULTS_BAR_CLASS } from '@/lib/utils'
import { todayIso } from '@/lib/format'
import { splitDayList } from '@/lib/longRuns'
import EventFilterBar from '@/components/EventFilterBar.vue'
import PaginationControls from '@/components/PaginationControls.vue'
import SortControl, { type SortOption } from '@/components/SortControl.vue'
import type { EventPage } from '@/api/types'
import { useAsync } from '@/composables/useAsync'
import { type EventSearchParams, fetchOnNowPage, searchEvents } from '@/composables/useEvents'
import { useEventFilters } from '@/composables/useEventFilters'
import { useFilterLabels } from '@/composables/useFilterLabels'
import { provideFilterLists } from '@/composables/useFilterLists'
import { usePagedList } from '@/composables/usePagedList'
import { useI18n } from 'vue-i18n'
import type { Locale } from '@/i18n/locales'
import { CALENDAR_TYPE, calendarPath, FEED_TYPE, feedPath } from '@/lib/seo'
import { useLocalePath } from '@/composables/useLocalePath'

const PAGE_SIZE = 20

const route = useRoute()

// Filters live in the URL query so list views are shareable and survive back/forward; the
// filter bar writes them and `useEventFilters` reads them back (see EventFilterBar.vue).
const { queryString, filters, dateRange, applyFilters } = useEventFilters()

const EARLIEST_FIRST = 'eventDate,asc'
const LATEST_FIRST = 'eventDate,desc'
/** When the importer first stored the event, as the feed orders (#2721). */
const NEWEST_ADDED = 'createdAt,desc'

const onNow = computed(() => queryString('now') === '1')

/**
 * A range that ends before today is an archive and reads latest first; a range that reaches into
 * the past lets the visitor flip it. Without a lower bound the BFF reads back through every year,
 * so `to` alone reaches the past too (#360). Newest added is for a list of what has not ended, so
 * a range into the past does not offer it.
 */
const reachesPast = computed(() => {
  if (onNow.value) return false
  const { from, to } = dateRange.value
  return from ? from < todayIso() : !!to
})
const defaultSort = computed(() => {
  const { to } = dateRange.value
  return to && to < todayIso() ? LATEST_FIRST : EARLIEST_FIRST
})
const sortValues = computed(() =>
  reachesPast.value ? [EARLIEST_FIRST, LATEST_FIRST] : [EARLIEST_FIRST, NEWEST_ADDED],
)
const sort = computed(() => {
  const requested = queryString('sort')
  return sortValues.value.includes(requested) ? requested : defaultSort.value
})

// The list owns its dates, so it merges the range in; the BFF defaults to today onwards when
// both bounds are absent, which is the right empty state here. `running` keeps a weekend that
// opened the night before `from` in the range (#2674).
const params = computed<EventSearchParams>(() => ({
  ...filters.value,
  ...dateRange.value,
  running: dateRange.value.from ? true : undefined,
  sort: sort.value === EARLIEST_FIRST ? undefined : [sort.value],
  page: queryString('page') ? Number(queryString('page')) : 0,
  size: PAGE_SIZE,
}))

/** On now is today's running events in one list, so it replaces the range, the sort and the paging. */
const {
  data: page,
  error,
  loading,
  run,
} = useAsync<EventPage>(
  () => (onNow.value ? fetchOnNowPage(filters.value) : searchEvents(params.value)),
  'errors.subject.events',
  () =>
    onNow.value
      ? `on-now:${todayIso()}?${JSON.stringify(filters.value)}`
      : `/api/events?${JSON.stringify(params.value)}`,
)

/**
 * A run of weeks folds into "Also running" between its opening and its last days (#2594). On now
 * folds on today, and a range folds on its first day, which a run that opened earlier reaches.
 */
const foldDay = computed(() => (onNow.value ? todayIso() : dateRange.value.from))
const listed = computed(() =>
  foldDay.value
    ? splitDayList(page.value?.content ?? [], foldDay.value)
    : { cards: page.value?.content ?? [], alsoRunning: [] },
)

// Paging, the clamp on an out-of-range `?page=`, and the reload on any query change.
const { currentPage, totalPages, goToPage } = usePagedList(page, run)

const localePath = useLocalePath()

const { t, locale } = useI18n()

const SORT_LABELS: Record<string, string> = {
  [EARLIEST_FIRST]: 'common.sort.earliest',
  [LATEST_FIRST]: 'common.sort.latest',
  [NEWEST_ADDED]: 'common.sort.newest',
}
const sortOptions = computed<SortOption[]>(() =>
  sortValues.value.map((value) => ({ value, label: t(SORT_LABELS[value]!) })),
)

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

/**
 * The calendar subscription for these filters (#2719), named after them (#2772). `webcal:` hands it to
 * the calendar app; an app that asks for an address, Google Calendar's among them, takes the `https:` one.
 */
const { filterLabels } = useFilterLabels(provideFilterLists())
const calendarHref = computed(() =>
  calendarPath(locale.value as Locale, filters.value, filterLabels(filters.value)),
)
const webcalHref = computed(() => `webcal://${window.location.host}${calendarHref.value}`)
const subscribeLabel = computed(() =>
  Object.values(filters.value).some((value) => value !== undefined)
    ? t('events.subscribeFiltered')
    : t('events.subscribeAll'),
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
      <p class="text-body text-muted-foreground">{{ t('events.subtitle') }}</p>
    </header>

    <EventFilterBar show-on-now />

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
        <ClearAllFilters empty-state />
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
          <span class="inline-flex items-baseline gap-1">
            <a
              :aria-label="subscribeLabel"
              :href="webcalHref"
              :title="subscribeLabel"
              class="inline-flex items-center gap-1 self-center text-primary hover:underline"
              data-testid="calendar-subscribe"
            >
              <CalendarSync aria-hidden="true" class="size-4" />
              {{ t('events.subscribe') }}
            </a>
            <a
              :aria-label="t('events.subscribeUrl')"
              :href="calendarHref"
              :title="t('events.subscribeUrl')"
              :type="CALENDAR_TYPE"
              class="text-muted-foreground hover:underline"
              data-testid="calendar-url"
            >
              (URL)
            </a>
          </span>
        </div>
        <SortControl
          v-if="!onNow"
          :model-value="sort"
          :options="sortOptions"
          @update:model-value="applySort"
        />
      </div>
      <!-- No section heading sits between the page `h1` and the grid here (unlike the home and
           detail pages), so the cards are the second level of the outline. The compact view swaps
           the whole grid rather than hiding the posters: a hidden image is still downloaded. -->
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

      <PaginationControls :current-page="currentPage" :total-pages="totalPages" @goto="goToPage" />
    </template>
  </main>
</template>
