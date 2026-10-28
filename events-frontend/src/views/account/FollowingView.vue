<script lang="ts" setup>
/**
 * "Following" (#3889): today's events of everything the visitor follows, as on the home page (#3922),
 * then the upcoming ones by date; what they follow is a tab of its own, `?tab=follows` (#4080). No ranking, so not "For you". Signed out, it
 * sends the visitor to sign-in and back. Empty, it suggests the venues with the most events in the next 30 days. The upcoming list takes the
 * events page's filters and sort from the URL, behind one Filter button (#4079); Today stays as it is. Below `sm` the bar shows without that
 * button, since its own Filters button already hides it in a sheet. The count and the sort sit under the open bar, as on the events page.
 * Saved searches show as chips beside the filters, and as a list to rename and delete on the follows tab (#4099).
 */
import { SlidersHorizontal } from '@lucide/vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import EventCard from '@/components/EventCard.vue'
import EventFilterBar from '@/components/EventFilterBar.vue'
import EventRow from '@/components/EventRow.vue'
import FollowButton from '@/components/FollowButton.vue'
import FollowedList, { type Followed } from '@/components/FollowedList.vue'
import ListPage from '@/components/ListPage.vue'
import SavedSearchChips from '@/components/SavedSearchChips.vue'
import SavedSearchList from '@/components/SavedSearchList.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import SortControl, { type SortOption } from '@/components/SortControl.vue'
import TodayEvents from '@/components/TodayEvents.vue'
import { Button } from '@/components/ui/button'
import {
  fetchFollowing,
  fetchFollowingToday,
  fetchFollows,
  type FollowingQuery,
  type FollowKind,
  type Notify,
} from '@/api/account'
import { api, ApiError, describeError, unwrap } from '@/api/client'
import type { EventSummary, EventSummaryPage } from '@/api/types'
import { useCompactView } from '@/composables/useCompactView'
import { useEventFilters } from '@/composables/useEventFilters'
import { useLocalePath } from '@/composables/useLocalePath'
import { useNarrowViewport } from '@/composables/useNarrowViewport'
import { usePush } from '@/composables/usePush'
import { useSavedSearches } from '@/composables/useSavedSearches'
import { useSession } from '@/composables/useSession'
import { tomorrowIso } from '@/lib/format'
import { CARD_GRID_CLASS, CARD_LIST_CLASS, cn, RESULTS_BAR_CLASS } from '@/lib/utils'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const localePath = useLocalePath()
const { account, ready } = useSession()
const { compact } = useCompactView()
const push = usePush()
const saved = useSavedSearches()

const { filters, dateRange, queryString, applyFilters } = useEventFilters()

const EARLIEST_FIRST = 'eventDate,asc'
/** When the importer first stored the event, as on the events page (#2721). */
const NEWEST_ADDED = 'createdAt,desc'
const sort = computed(() => (queryString('sort') === NEWEST_ADDED ? NEWEST_ADDED : EARLIEST_FIRST))
// The list starts tomorrow, so it never reaches the past and the date does not flip.
const sortOptions = computed<SortOption[]>(() => [
  { label: t('common.sort.date'), value: EARLIEST_FIRST },
  { label: t('common.sort.newest'), value: NEWEST_ADDED },
])
function applySort(value: string) {
  applyFilters({ sort: value === EARLIEST_FIRST ? '' : value })
}

/** How many filters narrow the list, the range counting once, so the closed button still says so. */
const filterCount = computed(
  () =>
    Object.values(filters.value).filter((value) => value !== undefined).length +
    (dateRange.value.from || dateRange.value.to ? 1 : 0),
)
// Open by itself only when a link brings filters, so the bar stays out of the way until asked for.
const filtersOpen = ref(filterCount.value > 0)
// The bar's own breakpoint: below it the bar is one Filters button, and a second one in front of it says nothing more.
const narrow = useNarrowViewport()
// Below `sm` the bar shows by itself; from `sm` up, when the Filter button opens it.
const barShown = computed(() =>
  narrow.value ? events.value.length > 0 || filterCount.value > 0 : filtersOpen.value,
)

/**
 * Upcoming starts tomorrow: today's events have their own section above it. A range that starts
 * earlier starts tomorrow here, so Today keeps them and they never show twice.
 */
function upcomingQuery(): FollowingQuery {
  const tomorrow = tomorrowIso()
  const { from, to } = dateRange.value
  return {
    ...filters.value,
    from: from && from > tomorrow ? from : tomorrow,
    to,
    sort: sort.value === EARLIEST_FIRST ? undefined : [sort.value],
  }
}

const today = ref<EventSummary[]>([])
const todayExpanded = ref(false)
const events = ref<EventSummary[]>([])
const page = ref(0)
const hasMore = ref(false)
const total = ref<number | undefined>(undefined)
const follows = ref<Followed[] | null>(null)
const suggestions = ref<{ slug: string; name: string }[]>([])
const loadingMore = ref(false)
const problem = ref('')

const ROUTE: Record<FollowKind, string> = {
  artist: 'artists',
  venue: 'venues',
  promoter: 'promoters',
}

type Tab = 'events' | 'follows'
const TABS: Tab[] = ['events', 'follows']
// Events is the default, so its URL stays bare and its own query stays alone.
const tab = computed<Tab>(() => (route.query.tab === 'follows' ? 'follows' : 'events'))
const tabButtons = ref<HTMLButtonElement[]>([])
// Saved searches count as following something (#3890), and the tab lists them too.
const followedCount = computed(
  () => (follows.value?.filter((item) => item.following).length ?? 0) + saved.searches.value.length,
)

function selectTab(next: Tab, focus = false) {
  void router.replace({ query: { ...route.query, tab: next === 'events' ? undefined : next } })
  if (focus) tabButtons.value[TABS.indexOf(next)]?.focus()
}

/** Arrow keys, Home and End move between the tabs, as the WAI-ARIA tabs pattern has them. */
function onTabKey(event: KeyboardEvent) {
  const index = TABS.indexOf(tab.value)
  const moves: Record<string, number> = {
    ArrowRight: (index + 1) % TABS.length,
    ArrowLeft: (index - 1 + TABS.length) % TABS.length,
    Home: 0,
    End: TABS.length - 1,
  }
  const next = moves[event.key]
  if (next === undefined) return
  event.preventDefault()
  selectTab(TABS[next]!, true)
}

// A row unfollowed on the tab stays for an undo; leaving the tab reloads, which drops it and its events.
let followsChanged = false
function onFollowChange(item: Followed, following: boolean) {
  item.following = following
  if (!following) item.notify = 'off'
  followsChanged = true
}
watch(tab, (_, before) => {
  if (before !== 'follows' || !followsChanged) return
  followsChanged = false
  void load()
})

/** The bells show once the site is known to send push (#3894); unknown or off, they would promise nothing. */
const bells = computed(() => !['loading', 'unavailable'].includes(push.state.value))

const empty = computed(
  () => follows.value !== null && follows.value.length === 0 && saved.searches.value.length === 0,
)

/** Counts the upcoming loads, so a slower answer to an older filter never replaces a newer one. */
let upcomingTicket = 0

async function load() {
  problem.value = ''
  try {
    const ticket = ++upcomingTicket
    const [day, first, list] = await Promise.all([
      fetchFollowingToday(),
      fetchFollowing(0, upcomingQuery()),
      fetchFollows(),
    ])
    today.value = day
    if (ticket === upcomingTicket) showFirst(first)
    follows.value = list.flatMap((item) =>
      item.kind && item.kind in ROUTE && item.slug && item.name
        ? [
            {
              kind: item.kind as FollowKind,
              slug: item.slug,
              name: item.name,
              notify: (item.notify === 'instant' ? 'instant' : 'off') as Notify,
              following: true,
            },
          ]
        : [],
    )
    if (follows.value.length === 0 && suggestions.value.length === 0) await loadSuggestions()
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) await toSignIn()
    else problem.value = describeError(e, 'errors.subject.following')
  }
}

function showFirst(first: EventSummaryPage) {
  events.value = first.content ?? []
  total.value = first.totalElements
  page.value = 0
  hasMore.value = (first.totalPages ?? 0) > 1
}

/** A filter or the sort changed in the URL: only the upcoming list reloads. */
async function loadUpcoming() {
  const ticket = ++upcomingTicket
  try {
    const first = await fetchFollowing(0, upcomingQuery())
    if (ticket === upcomingTicket) showFirst(first)
  } catch (e) {
    if (ticket === upcomingTicket) problem.value = describeError(e, 'errors.subject.following')
  }
}

watch(
  () => JSON.stringify(upcomingQuery()),
  () => {
    if (follows.value !== null) void loadUpcoming()
  },
)

/** A help, not the page: if the venues do not load, the empty page shows its text without them. */
async function loadSuggestions() {
  try {
    const venues = await unwrap(
      api.GET('/api/venues', { params: { query: { sort: ['upcomingEvents,desc'], size: 6 } } }),
    )
    suggestions.value = (venues.content ?? []).flatMap((venue) =>
      venue.slug && venue.name ? [{ slug: venue.slug, name: venue.name }] : [],
    )
  } catch {
    suggestions.value = []
  }
}

async function loadMore() {
  if (loadingMore.value) return
  loadingMore.value = true
  const ticket = upcomingTicket
  try {
    const next = await fetchFollowing(page.value + 1, upcomingQuery())
    if (ticket !== upcomingTicket) return
    events.value = [...events.value, ...(next.content ?? [])]
    page.value += 1
    hasMore.value = page.value + 1 < (next.totalPages ?? 0)
  } catch (e) {
    problem.value = describeError(e, 'errors.subject.following')
  } finally {
    loadingMore.value = false
  }
}

function toSignIn() {
  return router.replace({ path: localePath('/sign-in'), query: { next: localePath('/following') } })
}

onMounted(async () => {
  await ready()
  if (!account.value) await toSignIn()
  else await Promise.all([load(), saved.load(), push.load().catch(() => undefined)])
})
</script>

<template>
  <ListPage :title="t('pageTitle.following')">
    <template #header>
      <p class="text-body text-muted-foreground">
        {{ t('account.follow.page.intro') }}
        <RouterLink class="underline underline-offset-2" :to="localePath('/account')">
          {{ t('account.follow.page.account') }}
        </RouterLink>
      </p>
    </template>

    <p v-if="problem" class="text-body text-destructive" role="alert">{{ problem }}</p>

    <p v-else-if="follows === null" class="text-body text-muted-foreground">
      {{ t('common.states.loading') }}
    </p>

    <template v-else>
      <div
        :aria-label="t('pageTitle.following')"
        class="flex gap-4 border-b border-border"
        role="tablist"
      >
        <button
          v-for="name in TABS"
          :id="`following-tab-${name}`"
          :key="name"
          ref="tabButtons"
          :aria-controls="`following-panel-${name}`"
          :aria-selected="tab === name"
          :class="
            cn(
              '-mb-px border-b-2 py-2 text-body font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              tab === name
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground',
            )
          "
          role="tab"
          :tabindex="tab === name ? 0 : -1"
          type="button"
          @click="selectTab(name)"
          @keydown="onTabKey"
        >
          {{
            name === 'events'
              ? t('common.nav.events')
              : t('account.follow.page.list', { count: followedCount })
          }}
        </button>
      </div>

      <div
        v-show="tab === 'events'"
        id="following-panel-events"
        aria-labelledby="following-tab-events"
        class="space-y-6"
        role="tabpanel"
      >
        <section v-if="empty" class="space-y-4">
          <p class="text-body">{{ t('account.follow.page.empty') }}</p>
          <SectionLabel v-if="suggestions.length" as="h2">{{
            t('account.follow.page.suggestions')
          }}</SectionLabel>
          <ul class="divide-y divide-border border-y border-border">
            <li
              v-for="venue in suggestions"
              :key="venue.slug"
              class="flex items-center justify-between gap-4 py-3"
            >
              <RouterLink
                class="text-body font-medium hover:underline"
                :to="localePath(`/venues/${venue.slug}`)"
              >
                {{ venue.name }}
              </RouterLink>
              <FollowButton
                no-hint
                :initially-following="false"
                kind="venue"
                :slug="venue.slug"
                @change="load"
              />
            </li>
          </ul>
        </section>

        <!-- Left out when empty: a heading over nothing says less than no heading. -->
        <section v-if="!empty && today.length" class="space-y-4">
          <SectionLabel as="h2">{{ t('home.today') }}</SectionLabel>
          <TodayEvents v-model:expanded="todayExpanded" :events="today" />
        </section>

        <section v-if="!empty" class="space-y-4">
          <SectionLabel as="h2">{{ t('common.upcomingEvents') }}</SectionLabel>
          <SavedSearchChips :counts="saved.counts.value" :searches="saved.searches.value" />
          <!-- Wide screens only: a phone shows the bar directly. Nothing to narrow or order until something is upcoming, or a filter emptied it. -->
          <div
            v-if="!narrow && (events.length || filterCount)"
            class="flex flex-wrap items-center gap-3"
          >
            <Button
              :aria-expanded="filtersOpen"
              aria-controls="following-filters"
              :variant="filterCount ? 'default' : 'outline'"
              size="sm"
              type="button"
              @click="filtersOpen = !filtersOpen"
            >
              <SlidersHorizontal aria-hidden="true" />
              {{
                filterCount
                  ? t('account.follow.page.filterCount', { n: filterCount })
                  : t('account.follow.page.filter')
              }}
            </Button>
            <!-- The open bar carries its own clear, and the sort moves under it. -->
            <template v-if="!filtersOpen">
              <ClearAllFilters />
              <SortControl
                v-if="events.length"
                class="ml-auto"
                :model-value="sort"
                :options="sortOptions"
                @update:model-value="applySort"
              />
            </template>
          </div>
          <template v-if="barShown">
            <!-- No Today preset: Upcoming starts tomorrow, so it would always empty the list. -->
            <div :id="narrow ? undefined : 'following-filters'">
              <EventFilterBar hide-today-preset :result-count="total" />
            </div>
            <!-- Under the bar's rule, as on the events page: the count, then the sort. -->
            <div v-if="events.length" :class="RESULTS_BAR_CLASS">
              <p v-if="total !== undefined" class="text-body text-muted-foreground">
                {{ t('events.resultCount', { count: total }) }}
              </p>
              <SortControl
                class="ml-auto"
                :model-value="sort"
                :options="sortOptions"
                @update:model-value="applySort"
              />
            </div>
          </template>
          <div v-if="!events.length && filterCount" class="space-y-3">
            <p class="text-body text-muted-foreground">
              {{ t('account.follow.page.noneFiltered') }}
            </p>
            <ClearAllFilters empty-state />
          </div>
          <p v-else-if="!events.length" class="text-body text-muted-foreground">
            {{ t('account.follow.page.none') }}
          </p>
          <div v-else-if="compact" :class="CARD_LIST_CLASS">
            <EventRow v-for="event in events" :key="event.slug" :event="event" />
          </div>
          <div v-else :class="CARD_GRID_CLASS">
            <EventCard v-for="event in events" :key="event.slug" :event="event" />
          </div>
          <Button v-if="hasMore" :disabled="loadingMore" variant="outline" @click="loadMore">
            {{ t('common.actions.showMore') }}
          </Button>
        </section>
      </div>

      <div
        v-if="tab === 'follows'"
        id="following-panel-follows"
        aria-labelledby="following-tab-follows"
        class="space-y-4"
        role="tabpanel"
      >
        <!-- The bells only where the site sends push. Off in this browser, they still reach the others. -->
        <p v-if="bells && push.state.value !== 'on'" class="text-meta text-muted-foreground">
          {{ t('account.push.bellHint') }}
          <RouterLink class="underline underline-offset-2" :to="localePath('/account')">
            {{ t('account.push.bellHintLink') }}
          </RouterLink>
        </p>
        <FollowedList :bells="bells" :follows="follows" @change="onFollowChange" />
        <SavedSearchList :saved="saved" />
      </div>
    </template>
  </ListPage>
</template>
