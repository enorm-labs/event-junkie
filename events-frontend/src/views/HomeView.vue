<script lang="ts">
import { ref } from 'vue'

// Module scope, not component state: going back from an event page remounts the view, and a list
// that collapsed again would put #1111's restored scroll position past its end.
const tonightExpanded = ref(false)
</script>

<script lang="ts" setup>
import { computed, nextTick, onMounted, useTemplateRef } from 'vue'
import { RouterLink } from 'vue-router'
import { CalendarDays, Compass } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import EventCard from '@/components/EventCard.vue'
import { CARD_GRID_CLASS, CARD_LIST_CLASS } from '@/lib/utils'
import ClubStamp from '@/components/ClubStamp'
import ClubkulturNotice from '@/components/ClubkulturNotice.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import EventRow from '@/components/EventRow.vue'
import { useCompactView } from '@/composables/useCompactView'
import { useTodayEvents, useUpcomingEvents } from '@/composables/useEvents'
import { tomorrowIso } from '@/lib/format'
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
const tonightCapped = computed(
  () => !tonightExpanded.value && tonightTotal.value > tonightCap.value,
)
const tonightVisible = computed(() =>
  tonightCapped.value ? today.data.value?.slice(0, tonightCap.value) : today.data.value,
)

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

    <section class="space-y-4">
      <SectionLabel>{{ t('home.tonight') }}</SectionLabel>
      <p v-if="today.loading.value" class="text-sm text-muted-foreground">
        {{ t('common.states.loading') }}
      </p>
      <p v-else-if="today.error.value" class="text-sm text-destructive">
        {{ today.error.value }}
      </p>
      <p v-else-if="!today.data.value?.length" class="text-sm text-muted-foreground">
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
          {{ t('home.showAllTonight', { count: tonightTotal }) }}
        </Button>
      </template>
    </section>

    <section class="space-y-4">
      <SectionLabel>{{ t('home.upcoming') }}</SectionLabel>
      <p v-if="upcoming.loading.value" class="text-sm text-muted-foreground">
        {{ t('common.states.loading') }}
      </p>
      <p v-else-if="upcoming.error.value" class="text-sm text-destructive">
        {{ upcoming.error.value }}
      </p>
      <p v-else-if="!upcoming.data.value?.length" class="text-sm text-muted-foreground">
        {{ t('home.upcomingEmpty') }}
      </p>
      <template v-else>
        <div v-if="compact" :class="CARD_LIST_CLASS">
          <EventRow v-for="event in upcoming.data.value" :key="event.slug" :event="event" />
        </div>
        <div v-else :class="CARD_GRID_CLASS">
          <EventCard v-for="event in upcoming.data.value" :key="event.slug" :event="event" />
        </div>
        <!-- eslint-disable-next-line shadcn/no-restyle -- a text link sits flush with the column above it -->
        <Button as-child class="px-0" variant="link">
          <RouterLink :to="localePath('/events')">{{ t('home.seeAllUpcoming') }}</RouterLink>
        </Button>
      </template>
    </section>
  </main>
</template>
