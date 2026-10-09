<script lang="ts" setup>
import { ArrowLeft, ArrowRight } from '@lucide/vue'
import { computed, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import type { EventSummary } from '@/api/types'
import { useAsync } from '@/composables/useAsync'
import { useCompactView } from '@/composables/useCompactView'
import { fetchCalendarEvents } from '@/composables/useEvents'
import { useLocalePath } from '@/composables/useLocalePath'
import { usePageMeta } from '@/composables/usePageMeta'
import type { Locale } from '@/i18n/locales'
import { currentIsoWeek, daysOf, formatIsoWeek, parseIsoWeek, shiftWeek } from '@/lib/isoWeek'
import { CARD_GRID_CLASS, CARD_LIST_CLASS } from '@/lib/utils'
import {
  dayHeading,
  isPastWeek,
  weekDays,
  weekListRange,
  weekPageMeta,
  weekPath,
  weekRange,
  weekTitle,
} from '@/lib/weekPage'

/**
 * A week's events day by day (#2728), a page to share or bookmark. Each day shows the first
 * `DAY_CAP` events in the list's order and hands the rest to the events list for that day.
 */

const route = useRoute()
const { t, locale } = useI18n()
const localePath = useLocalePath()
const { compact } = useCompactView()

// The router's guard lets only a real week through, so the fallback never shows.
const week = computed(() => parseIsoWeek(route.params.isoWeek) ?? currentIsoWeek())
const days = computed(() => daysOf(week.value))

const { data, error, loading, run } = useAsync<EventSummary[]>(
  () => fetchCalendarEvents(days.value[0]!, days.value[6]!),
  'errors.subject.events',
  () => `/api/events/calendar?week=${formatIsoWeek(week.value)}`,
)
watch(week, run, { immediate: true })

const lang = computed(() => locale.value as Locale)
usePageMeta(() => weekPageMeta(week.value, lang.value))

const listed = computed(() => weekDays(data.value ?? [], week.value))
const past = computed(() => isPastWeek(week.value))
const isCurrent = computed(() => formatIsoWeek(week.value) === formatIsoWeek(currentIsoWeek()))
const previous = computed(() => shiftWeek(week.value, -1))
const next = computed(() => shiftWeek(week.value, 1))

// A visitor who wants filters gets the events list for the week; this page keeps none of its own.
const filterLink = computed(() => {
  const range = weekListRange(week.value)
  return range && { path: localePath('/events'), query: { from: range.from, to: range.to } }
})

function dayLink(date: string) {
  return { path: localePath('/events'), query: { from: date, to: date } }
}

const HEADING_CLASS = 'text-section font-bold tracking-tight'
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-8 p-4 sm:p-8">
    <header class="space-y-1">
      <p v-if="isCurrent" class="text-body font-medium text-primary">{{ t('week.thisWeek') }}</p>
      <h1 class="text-page font-bold tracking-tight">{{ weekTitle(week, lang) }}</h1>
      <p class="text-body text-muted-foreground">{{ weekRange(week, lang) }}</p>
      <p v-if="past" class="text-body text-muted-foreground" data-testid="past-week">
        {{ t('week.past') }}
      </p>
      <Button v-if="filterLink" as-child class="mt-3" variant="outline">
        <RouterLink :to="filterLink" data-testid="filter-week">
          {{ t('week.filter') }}
          <ArrowRight aria-hidden="true" data-icon="inline-end" />
        </RouterLink>
      </Button>
    </header>

    <nav :aria-label="t('week.navigation')" class="flex flex-wrap gap-3">
      <Button as-child variant="outline">
        <RouterLink :to="localePath(weekPath(previous))" rel="prev">
          <ArrowLeft aria-hidden="true" data-icon="inline-start" />
          {{ t('week.weekLink', { week: previous.week }) }}
        </RouterLink>
      </Button>
      <Button v-if="!isCurrent" as-child variant="ghost">
        <RouterLink :to="localePath(weekPath(currentIsoWeek()))">
          {{ t('week.thisWeek') }}
        </RouterLink>
      </Button>
      <Button as-child variant="outline">
        <RouterLink :to="localePath(weekPath(next))" rel="next">
          {{ t('week.weekLink', { week: next.week }) }}
          <ArrowRight aria-hidden="true" data-icon="inline-end" />
        </RouterLink>
      </Button>
    </nav>

    <p v-if="loading" class="text-body text-muted-foreground">{{ t('common.states.loading') }}</p>
    <p v-else-if="error" class="text-body text-destructive">{{ error }}</p>
    <template v-else>
      <section
        v-for="day in listed"
        :key="day.date"
        :aria-labelledby="`day-${day.date}`"
        class="space-y-4 border-t border-border pt-6"
      >
        <div class="flex items-baseline gap-3">
          <h2 :id="`day-${day.date}`" :class="HEADING_CLASS">{{ dayHeading(day.date, lang) }}</h2>
          <span v-if="day.total" class="text-body text-muted-foreground tabular-nums">
            {{ t('home.eventCount', { count: day.total }) }}
          </span>
        </div>
        <p v-if="!day.total" class="text-body text-muted-foreground">{{ t('week.empty') }}</p>
        <template v-else>
          <div v-if="compact" :class="CARD_LIST_CLASS">
            <EventRow v-for="event in day.shown" :key="event.slug" :event="event" />
          </div>
          <div v-else :class="CARD_GRID_CLASS">
            <EventCard v-for="event in day.shown" :key="event.slug" :event="event" />
          </div>
          <Button v-if="day.total > day.shown.length" as-child variant="outline">
            <RouterLink :to="dayLink(day.date)">
              {{ t('week.allEvents', { count: day.total }) }}
              <ArrowRight aria-hidden="true" data-icon="inline-end" />
            </RouterLink>
          </Button>
        </template>
      </section>
    </template>
  </main>
</template>
