<script lang="ts" setup>
import type { EventInput } from '@fullcalendar/vue3'
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EventCalendar from '@/components/EventCalendar.vue'
import EventFilterBar from '@/components/EventFilterBar.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { describeError } from '@/api/client'
import { fetchCalendarEvents } from '@/composables/useEvents'
import { isPastEvent } from '@/lib/format'
import { useEventFilters } from '@/composables/useEventFilters'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const router = useRouter()
const { filters } = useEventFilters()

const events = ref<EventInput[]>([])
const error = ref<string | null>(null)
/** The visible window, as last reported by the calendar — null until its first render. */
const range = ref<{ from: string; to: string } | null>(null)

/** Clamps a range to the BFF's 92-day maximum (no built-in view exceeds it, but be safe). */
function clampTo(from: string, to: string): string {
  const max = new Date(from)
  max.setDate(max.getDate() + 92)
  const maxIso = max.toISOString().slice(0, 10)
  return to > maxIso ? maxIso : to
}

async function load() {
  if (!range.value) return
  const { from, to } = range.value
  try {
    const data = await fetchCalendarEvents(from, clampTo(from, to), filters.value)
    events.value = data.map((event) => ({
      title: event.title ?? '',
      start: event.startTime ? `${event.eventDate}T${event.startTime}` : event.eventDate,
      url: `/events/${event.slug}`,
      // Paging back a month already returned past events; this is what tells them apart.
      classNames: isPastEvent(event.eventDate) ? ['fc-event-past'] : [],
      // `venue` backs the calendar's hover tooltip, where the clipped title is spelled out.
      extendedProps: { slug: event.slug, venue: event.venue?.name },
    }))
    error.value = null
  } catch (e) {
    error.value = describeError(e, 'the calendar')
  }
}

// Driven by EventCalendar's `datesSet`, which also fires on initial render.
function loadRange(next: { from: string; to: string }) {
  range.value = next
  return load()
}

// Filters live in the URL, so a filter change means re-fetching the same visible window.
watch(() => route.query, load, { deep: true })

function openEvent(slug: string) {
  router.push(`/events/${slug}`)
}

const { t } = useI18n()
</script>

<template>
  <main class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
    <header class="space-y-1">
      <SectionLabel as="p">{{ t('calendar.eyebrow') }}</SectionLabel>
      <h1 class="text-3xl font-bold tracking-tight">{{ t('calendar.title') }}</h1>
      <p class="text-muted-foreground">{{ t('calendar.subtitle') }}</p>
    </header>
    <!-- No date range here: FullCalendar's visible window already is the range. -->
    <EventFilterBar :show-date-range="false" />
    <p v-if="error" class="text-sm text-destructive">{{ error }}</p>
    <EventCalendar :events="events" @dates-set="loadRange" @event-click="openEvent" />
  </main>
</template>
