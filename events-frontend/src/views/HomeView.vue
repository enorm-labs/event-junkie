<script lang="ts">
import { ref } from 'vue'

// Module scope, not component state: going back from an event page remounts the view, and a list
// that collapsed again would put #1111's restored scroll position past its end.
const tonightExpanded = ref(false)
// The last surprise, so the next press picks another. Memory only (#2722): never stored or sent.
let lastSurprise: string | undefined
</script>

<script lang="ts" setup>
import { computed, nextTick, onMounted, useTemplateRef } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ArrowRight, CalendarDays, ChevronDown, Compass, Dices } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import AlsoRunning from '@/components/AlsoRunning.vue'
import EventCard from '@/components/EventCard.vue'
import { CARD_GRID_CLASS, CARD_LIST_CLASS } from '@/lib/utils'
import ClubStamp from '@/components/ClubStamp'
import ClubkulturNotice from '@/components/ClubkulturNotice.vue'
import EventRow from '@/components/EventRow.vue'
import { useCompactView } from '@/composables/useCompactView'
import { useNarrowViewport } from '@/composables/useNarrowViewport'
import { useTodayEvents, useUpcomingEvents } from '@/composables/useEvents'
import { todayIso, tomorrowIso } from '@/lib/format'
import { splitDayList } from '@/lib/longRuns'
import { pickSurprise, SMALL_ROOM_CAPACITY, surpriseCandidates } from '@/lib/surpriseMe'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'
import { useStructuredData } from '@/composables/useStructuredData'
import { websiteJsonLd } from '@/lib/structuredData'
import type { Locale } from '@/i18n/locales'

const today = useTodayEvents()
// Upcoming starts tomorrow — today's events live in the "Tonight" section above.
const upcoming = useUpcomingEvents(tomorrowIso())

onMounted(() => {
  today.run()
  upcoming.run()
})

const localePath = useLocalePath()

const { t, locale } = useI18n()

// Site-level identity, so Search can show the site name rather than the bare domain. Deliberately
// `WebSite` and not `Organization` — see lib/structuredData.ts.
useStructuredData(() => websiteJsonLd(locale.value as Locale))
// The compact view is a global display preference — see `useCompactView`.
const { compact } = useCompactView()

// A teaser, not the night's whole list: on a busy day a phone scrolled for minutes (#2321). Even, so
// the two-column grid ends on a full row. A compact row is about a sixth of a card's height.
const TONIGHT_CAP = { poster: 6, compact: 12 }
const tonightCap = computed(() => (compact.value ? TONIGHT_CAP.compact : TONIGHT_CAP.poster))
const tonightTotal = computed(() => today.data.value?.length ?? 0)
// A run of weeks is a card only on its opening and its last days; between them it folds (#2594).
const tonight = computed(() => splitDayList(today.data.value ?? [], todayIso()))
const tonightCards = computed(() => tonight.value.cards)
const tonightCapped = computed(
  () => !tonightExpanded.value && tonightCards.value.length > tonightCap.value,
)
const tonightVisible = computed(() =>
  tonightCapped.value ? tonightCards.value.slice(0, tonightCap.value) : tonightCards.value,
)

/** The section heading style, at the size of the map's and the venues' `h2`. */
const HEADING_CLASS = 'text-section font-bold tracking-tight'

// One random event tonight in a small room (#2722), from the night's cards: a long run folded into
// "Also running" has been on for weeks and is not a night out. The pick happens here, on the device.
const surprises = computed(() => surpriseCandidates(tonightCards.value))
const router = useRouter()
// The header's icon size on a phone, so the button fits beside the heading.
const narrow = useNarrowViewport()

function surpriseMe() {
  const pick = pickSurprise(surprises.value, lastSurprise)
  if (!pick?.slug) return
  lastSurprise = pick.slug
  void router.push(localePath(`/events/${pick.slug}`))
}

const tonightList = useTemplateRef<HTMLElement>('tonightList')

// The button disappears with the click, so focus moves to the first event it revealed rather than
// falling back to the document.
async function expandTonight() {
  const firstHidden = tonightCap.value
  tonightExpanded.value = true
  await nextTick()
  // Each card and row is itself the link.
  const revealed = tonightList.value?.children.item(firstHidden)
  if (revealed instanceof HTMLElement) revealed.focus()
}
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-12 p-4 sm:p-8">
    <section class="relative py-12 sm:py-16">
      <div class="relative flex flex-col items-center gap-5 text-center">
        <!-- The stamp carries both the name and the tagline as artwork, per locale. The ambient
             glow went with the pulse mark — its premise was that the mark is its light source, and
             a rubber stamp is ink, not light. -->
        <ClubStamp class="w-full max-w-lg text-foreground" />
        <!-- The accessible equivalent of the artwork above, so it says the same words. It replaces
             the separate tagline line the stamp now contains — which was hard-coded English and so
             showed the wrong language on /de; `footer.tagline` is the catalogue's tagline rather
             than a footer string, and the footer already reads it. -->
        <h1 class="sr-only">Event Junkie — {{ t('footer.tagline') }}</h1>
        <!-- The list is the primary way in: the sections below are already a list, and a visitor
             who wants more of it wants the filters, not a month grid. The calendar stays one click
             away for whoever has a date in mind (#366). -->
        <div class="flex flex-wrap justify-center gap-3">
          <Button as-child size="lg">
            <RouterLink :to="localePath('/events')">
              <Compass />
              {{ t('common.actions.browseEvents') }}
            </RouterLink>
          </Button>
          <Button as-child size="lg" variant="outline">
            <RouterLink :to="localePath('/calendar')">
              <CalendarDays />
              {{ t('common.actions.browseCalendar') }}
            </RouterLink>
          </Button>
        </div>
      </div>
    </section>

    <!-- Pulled up into the hero's bottom padding: with the section rhythm on top it sat about
         100px below the buttons, further from them than from the list it introduces. -->
    <ClubkulturNotice class="-mt-8 sm:-mt-10" />

    <!-- Headings by scale, not the mono eyebrow: at 14 px "Upcoming" sat in the grid's own rhythm
         and read as one more row of cards (#2347). The count stays outside the heading's name. -->
    <section class="space-y-4">
      <!-- One line at every width: below `sm` the button is icon-only, and a long count truncates
           before "Heute Abend" would wrap. -->
      <div class="flex items-baseline gap-x-3">
        <h2 :class="[HEADING_CLASS, 'shrink-0 whitespace-nowrap']">{{ t('home.tonight') }}</h2>
        <span
          v-if="tonightTotal"
          class="min-w-0 truncate text-body text-muted-foreground tabular-nums"
        >
          {{ t('home.eventCount', { count: tonightTotal }) }}
        </span>
        <!-- Hidden when no small room qualifies, rather than a button that leads nowhere. -->
        <Button
          v-if="surprises.length"
          class="ml-auto self-center"
          :size="narrow ? 'icon' : 'sm'"
          variant="outline"
          :title="t('home.surpriseMeHint', { capacity: SMALL_ROOM_CAPACITY })"
          @click="surpriseMe"
        >
          <Dices aria-hidden="true" />
          <!-- Still the button's name below `sm`, where only the dice show. -->
          <span class="sr-only sm:not-sr-only">{{ t('home.surpriseMe') }}</span>
        </Button>
      </div>
      <p v-if="today.loading.value" class="text-body text-muted-foreground">
        {{ t('common.states.loading') }}
      </p>
      <p v-else-if="today.error.value" class="text-body text-destructive">
        {{ today.error.value }}
      </p>
      <p v-else-if="!today.data.value?.length" class="text-body text-muted-foreground">
        {{ t('home.tonightEmpty') }}
      </p>
      <template v-else>
        <div v-if="compact" ref="tonightList" :class="CARD_LIST_CLASS">
          <EventRow v-for="event in tonightVisible" :key="event.slug" :event="event" />
        </div>
        <div v-else ref="tonightList" :class="CARD_GRID_CLASS">
          <EventCard v-for="event in tonightVisible" :key="event.slug" :event="event" />
        </div>
        <Button v-if="tonightCapped" variant="outline" @click="expandTonight">
          {{ t('home.showAllTonight', { count: tonightCards.length }) }}
          <ChevronDown aria-hidden="true" data-icon="inline-end" />
        </Button>
        <AlsoRunning :events="tonight.alsoRunning" />
      </template>
    </section>

    <section class="space-y-4 border-t border-border pt-8">
      <h2 :class="HEADING_CLASS">{{ t('home.upcoming') }}</h2>
      <p v-if="upcoming.loading.value" class="text-body text-muted-foreground">
        {{ t('common.states.loading') }}
      </p>
      <p v-else-if="upcoming.error.value" class="text-body text-destructive">
        {{ upcoming.error.value }}
      </p>
      <p v-else-if="!upcoming.data.value?.length" class="text-body text-muted-foreground">
        {{ t('home.upcomingEmpty') }}
      </p>
      <template v-else>
        <div v-if="compact" :class="CARD_LIST_CLASS">
          <EventRow v-for="event in upcoming.data.value" :key="event.slug" :event="event" />
        </div>
        <div v-else :class="CARD_GRID_CLASS">
          <EventCard v-for="event in upcoming.data.value" :key="event.slug" :event="event" />
        </div>
        <!-- Same family as Tonight's expand button; the arrow says these leave the page. -->
        <div class="flex flex-wrap gap-3">
          <Button as-child variant="outline">
            <RouterLink :to="localePath('/events')">
              {{ t('home.seeAllUpcoming') }}
              <ArrowRight aria-hidden="true" data-icon="inline-end" />
            </RouterLink>
          </Button>
          <Button as-child variant="outline">
            <RouterLink :to="localePath('/week')">
              {{ t('home.thisWeek') }}
              <ArrowRight aria-hidden="true" data-icon="inline-end" />
            </RouterLink>
          </Button>
        </div>
      </template>
    </section>
  </main>
</template>
