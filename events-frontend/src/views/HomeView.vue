<script lang="ts" setup>
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { CalendarDays } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import EventCard from '@/components/EventCard.vue'
import ClubStamp from '@/components/ClubStamp.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { useTodayEvents, useUpcomingEvents } from '@/composables/useEvents'
import { TAGLINE } from '@/lib/pageMeta'
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
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-12 p-8">
    <section class="relative py-20 sm:py-28">
      <div class="relative flex flex-col items-center gap-5 text-center">
        <!-- EXPERIMENT (open decision F): the club stamp as the hero. The ambient glow is gone with
             the pulse mark — its whole premise was that the mark is its light source, and a rubber
             stamp is ink, not light. -->
        <ClubStamp class="w-full max-w-lg text-foreground" />
        <!-- The stamp carries the name as artwork, so the live text stays for search and screen
             readers. -->
        <h1 class="sr-only">Event Junkie</h1>
        <p class="font-mono text-xs tracking-eyebrow text-muted-foreground uppercase sm:text-sm">
          {{ TAGLINE }}
        </p>
        <Button as-child size="lg">
          <RouterLink :to="localePath('/calendar')">
            <CalendarDays />
            {{ t('common.actions.browseCalendar') }}
          </RouterLink>
        </Button>
      </div>
    </section>

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
      <div v-else class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <EventCard v-for="event in today.data.value" :key="event.slug" :event="event" />
      </div>
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
      <div v-else class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <EventCard v-for="event in upcoming.data.value" :key="event.slug" :event="event" />
      </div>
    </section>
  </main>
</template>
