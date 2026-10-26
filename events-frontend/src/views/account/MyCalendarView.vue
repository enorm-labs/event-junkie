<script lang="ts" setup>
/**
 * "My calendar" (#3892): the events the visitor is interested in or going to, as a list (coming up,
 * then the history) or in the calendar the public calendar page uses. Private, so never indexed.
 * Signed out, it sends the visitor to sign-in and back.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import type { EventInput } from '@fullcalendar/vue3'
import EventCalendar from '@/components/EventCalendar.vue'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import ListPage from '@/components/ListPage.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { Button } from '@/components/ui/button'
import { fetchRsvpCalendar, fetchRsvpPast, fetchRsvpUpcoming } from '@/api/account'
import { ApiError, describeError } from '@/api/client'
import type { EventSummary } from '@/api/types'
import { useCompactView } from '@/composables/useCompactView'
import { useLocalePath } from '@/composables/useLocalePath'
import { useSession } from '@/composables/useSession'
import { toCalendarInput } from '@/lib/calendarEvent'
import { CARD_GRID_CLASS, CARD_LIST_CLASS } from '@/lib/utils'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const localePath = useLocalePath()
const { account, ready } = useSession()
const { compact } = useCompactView()

/** The view lives in the URL, as the venues page's does, so a reload keeps it. */
const showCalendar = ref(route.query.view === 'calendar')

const upcoming = ref<EventSummary[] | null>(null)
const past = ref<EventSummary[]>([])
const upcomingPage = ref(0)
const upcomingMore = ref(false)
const pastPage = ref(0)
const pastMore = ref(false)
const calendarEvents = ref<EventInput[]>([])
const problem = ref('')

async function loadList() {
  problem.value = ''
  try {
    const [ahead, behind] = await Promise.all([fetchRsvpUpcoming(0), fetchRsvpPast(0)])
    upcoming.value = ahead.content ?? []
    upcomingMore.value = (ahead.totalPages ?? 0) > 1
    past.value = behind.content ?? []
    pastMore.value = (behind.totalPages ?? 0) > 1
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) await toSignIn()
    else problem.value = describeError(e, 'errors.subject.myCalendar')
  }
}

async function more(which: 'upcoming' | 'past') {
  try {
    if (which === 'upcoming') {
      const next = await fetchRsvpUpcoming(upcomingPage.value + 1)
      upcoming.value = [...(upcoming.value ?? []), ...(next.content ?? [])]
      upcomingPage.value += 1
      upcomingMore.value = upcomingPage.value + 1 < (next.totalPages ?? 0)
    } else {
      const next = await fetchRsvpPast(pastPage.value + 1)
      past.value = [...past.value, ...(next.content ?? [])]
      pastPage.value += 1
      pastMore.value = pastPage.value + 1 < (next.totalPages ?? 0)
    }
  } catch (e) {
    problem.value = describeError(e, 'errors.subject.myCalendar')
  }
}

/** Clamps a range to the BFF's 92-day maximum, as the public calendar does. */
function clampTo(from: string, to: string): string {
  const max = new Date(from)
  max.setDate(max.getDate() + 92)
  const maxIso = max.toISOString().slice(0, 10)
  return to > maxIso ? maxIso : to
}

// Driven by EventCalendar's `datesSet`, which also fires on its first render.
async function loadRange({ from, to }: { from: string; to: string }) {
  try {
    const data = await fetchRsvpCalendar(from, clampTo(from, to))
    calendarEvents.value = data.map((event) => toCalendarInput(event, localePath))
  } catch (e) {
    problem.value = describeError(e, 'errors.subject.myCalendar')
  }
}

function setView(calendar: boolean) {
  showCalendar.value = calendar
  void router.replace({ query: { ...route.query, view: calendar ? 'calendar' : undefined } })
}

function openEvent(slug: string) {
  void router.push(localePath(`/events/${slug}`))
}

function toSignIn() {
  return router.replace({
    path: localePath('/sign-in'),
    query: { next: localePath('/my-calendar') },
  })
}

onMounted(async () => {
  await ready()
  if (!account.value) await toSignIn()
  else await loadList()
})
</script>

<template>
  <ListPage :title="t('pageTitle.myCalendar')">
    <template #header>
      <p class="text-body text-muted-foreground">{{ t('account.rsvp.page.intro') }}</p>
      <div :aria-label="t('pageTitle.myCalendar')" class="flex gap-2" role="group">
        <Button
          :aria-pressed="!showCalendar"
          :variant="showCalendar ? 'outline' : 'default'"
          size="sm"
          type="button"
          @click="setView(false)"
        >
          {{ t('account.rsvp.page.list') }}
        </Button>
        <Button
          :aria-pressed="showCalendar"
          :variant="showCalendar ? 'default' : 'outline'"
          size="sm"
          type="button"
          @click="setView(true)"
        >
          {{ t('account.rsvp.page.calendar') }}
        </Button>
      </div>
    </template>

    <p v-if="problem" class="text-body text-destructive" role="alert">{{ problem }}</p>

    <EventCalendar
      v-if="showCalendar"
      :events="calendarEvents"
      @dates-set="loadRange"
      @event-click="openEvent"
    />

    <p v-else-if="upcoming === null" class="text-body text-muted-foreground">
      {{ t('common.states.loading') }}
    </p>

    <template v-else>
      <section class="space-y-4">
        <SectionLabel as="h2">{{ t('account.rsvp.page.upcoming') }}</SectionLabel>
        <p v-if="!upcoming.length" class="text-body text-muted-foreground">
          {{ t('account.rsvp.page.none') }}
        </p>
        <div v-else-if="compact" :class="CARD_LIST_CLASS">
          <EventRow v-for="event in upcoming" :key="event.slug" :event="event" />
        </div>
        <div v-else :class="CARD_GRID_CLASS">
          <EventCard v-for="event in upcoming" :key="event.slug" :event="event" />
        </div>
        <Button v-if="upcomingMore" variant="outline" @click="more('upcoming')">
          {{ t('common.actions.showMore') }}
        </Button>
      </section>

      <section v-if="past.length" class="space-y-4">
        <SectionLabel as="h2">{{ t('account.rsvp.page.past') }}</SectionLabel>
        <div :class="CARD_LIST_CLASS">
          <EventRow v-for="event in past" :key="event.slug" :event="event" />
        </div>
        <Button v-if="pastMore" variant="outline" @click="more('past')">
          {{ t('common.actions.showMore') }}
        </Button>
      </section>
    </template>
  </ListPage>
</template>
