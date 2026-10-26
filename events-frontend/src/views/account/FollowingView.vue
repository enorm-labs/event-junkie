<script lang="ts" setup>
/**
 * "Following" (#3889): today's events of everything the visitor follows, as on the home page (#3922),
 * then the upcoming ones by date, and the list of what they follow. No ranking, so not "For you". Signed out, it sends the visitor to sign-in and
 * back. Empty, it suggests the venues with the most events in the next 30 days.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRouter } from 'vue-router'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import FollowButton from '@/components/FollowButton.vue'
import ListPage from '@/components/ListPage.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import TodayEvents from '@/components/TodayEvents.vue'
import { Button } from '@/components/ui/button'
import { fetchFollowing, fetchFollowingToday, fetchFollows, type FollowKind } from '@/api/account'
import { api, ApiError, describeError, unwrap } from '@/api/client'
import type { EventSummary } from '@/api/types'
import { useCompactView } from '@/composables/useCompactView'
import { useLocalePath } from '@/composables/useLocalePath'
import { useSession } from '@/composables/useSession'
import { tomorrowIso } from '@/lib/format'
import { CARD_GRID_CLASS, CARD_LIST_CLASS } from '@/lib/utils'

const { t } = useI18n()
const router = useRouter()
const localePath = useLocalePath()
const { account, ready } = useSession()
const { compact } = useCompactView()

const today = ref<EventSummary[]>([])
const todayExpanded = ref(false)
const events = ref<EventSummary[]>([])
const page = ref(0)
const hasMore = ref(false)
/** One followed entity, with the fields the generated type leaves optional checked. */
interface Followed {
  kind: FollowKind
  slug: string
  name: string
}

const follows = ref<Followed[] | null>(null)
const suggestions = ref<{ slug: string; name: string }[]>([])
const loadingMore = ref(false)
const problem = ref('')

const ROUTE: Record<FollowKind, string> = {
  artist: 'artists',
  venue: 'venues',
  promoter: 'promoters',
}

const empty = computed(() => follows.value !== null && follows.value.length === 0)

async function load() {
  problem.value = ''
  try {
    // Upcoming starts tomorrow: today's events have their own section above it.
    const [day, first, list] = await Promise.all([
      fetchFollowingToday(),
      fetchFollowing(0, tomorrowIso()),
      fetchFollows(),
    ])
    today.value = day
    events.value = first.content ?? []
    page.value = 0
    hasMore.value = (first.totalPages ?? 0) > 1
    follows.value = list.flatMap((item) =>
      item.kind && item.kind in ROUTE && item.slug && item.name
        ? [{ kind: item.kind as FollowKind, slug: item.slug, name: item.name }]
        : [],
    )
    if (follows.value.length === 0 && suggestions.value.length === 0) await loadSuggestions()
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) await toSignIn()
    else problem.value = describeError(e, 'errors.subject.following')
  }
}

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
  try {
    const next = await fetchFollowing(page.value + 1, tomorrowIso())
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
  else await load()
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
        <p v-if="!events.length" class="text-body text-muted-foreground">
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

      <section v-if="follows.length" class="space-y-4">
        <SectionLabel as="h2">{{
          t('account.follow.page.list', { count: follows.length })
        }}</SectionLabel>
        <ul class="divide-y divide-border border-y border-border">
          <li
            v-for="item in follows"
            :key="`${item.kind}/${item.slug}`"
            class="flex items-center justify-between gap-4 py-3"
          >
            <div class="min-w-0">
              <RouterLink
                class="text-body font-medium hover:underline"
                :to="localePath(`/${ROUTE[item.kind]}/${item.slug}`)"
              >
                {{ item.name }}
              </RouterLink>
              <p class="text-meta text-muted-foreground">{{ t(`detail.${item.kind}.kind`) }}</p>
            </div>
            <FollowButton
              no-hint
              :initially-following="true"
              :kind="item.kind"
              :slug="item.slug"
              @change="load"
            />
          </li>
        </ul>
      </section>
    </template>
  </ListPage>
</template>
