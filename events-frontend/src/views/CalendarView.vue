<script lang="ts" setup>
import type { EventInput } from '@fullcalendar/vue3'
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EventCalendar from '@/components/EventCalendar.vue'
import EventFilterBar from '@/components/EventFilterBar.vue'
import ListPage from '@/components/ListPage.vue'
import { describeError } from '@/api/client'
import { retryTransient } from '@/api/retry'
import { fetchCalendarEvents } from '@/composables/useEvents'
import { toCalendarInput } from '@/lib/calendarEvent'
import { useEventFilters } from '@/composables/useEventFilters'
import { useLocalePath } from '@/composables/useLocalePath'
import { beginContentLoad } from '@/composables/useViewLoaded'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const router = useRouter()
const { filters } = useEventFilters()
const localePath = useLocalePath()

const events = ref<EventInput[]>([])
const error = ref<string | null>(null)
const retrying = ref(false)
let generation = 0
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
  const call = ++generation
  const { from, to } = range.value
  const done = beginContentLoad()
  retrying.value = false
  try {
    const data = await retryTransient(
      () => fetchCalendarEvents(from, clampTo(from, to), filters.value),
      {
        isStale: () => call !== generation,
        onRetry: () => {
          retrying.value = true
          done()
        },
      },
    )
    if (call !== generation) return
    events.value = data.map((event) => toCalendarInput(event, localePath))
    error.value = null
  } catch (e) {
    if (call !== generation) return
    error.value = describeError(e, 'errors.subject.calendar')
  } finally {
    if (call === generation) retrying.value = false
    done()
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
  router.push(localePath(`/events/${slug}`))
}

const { t } = useI18n()
</script>

<template>
  <ListPage :subtitle="t('calendar.subtitle')" :title="t('calendar.title')">
    <!-- No date range here: FullCalendar's visible window already is the range. -->
    <EventFilterBar :show-date-range="false" />
    <p v-if="retrying" class="text-body text-muted-foreground">{{ t('errors.retrying') }}</p>
    <p v-else-if="error" class="text-body text-destructive">{{ error }}</p>
    <EventCalendar :events="events" @dates-set="loadRange" @event-click="openEvent" />
  </ListPage>
</template>
