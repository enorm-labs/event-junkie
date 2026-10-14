<script setup lang="ts">
import { ChevronLeft, ChevronRight, Search } from '@lucide/vue'
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { berlinToday } from '@/api/artists'
import { type AdminEvent, type EventSearchPage, searchEvents } from '@/api/events'
import { type AdminVenue, fetchAllVenues } from '@/api/venues'
import EventEditor from '@/components/EventEditor.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'

const route = useRoute()
const router = useRouter()

// The query string holds the search, so a result page can be linked and survives a reload.
const title = computed(() => String(route.query.q ?? ''))
const venueId = computed(() => {
  const id = Number(route.query.venue)
  return Number.isInteger(id) && id > 0 ? id : null
})
const past = computed(() => route.query.past === '1')
const page = computed(() => Math.max(0, Number(route.query.page) || 0))

/** What the form holds until it is sent. */
const typedTitle = ref(title.value)
watch(title, (value) => (typedTitle.value = value))

const result = shallowRef<EventSearchPage | null>(null)
const events = ref<AdminEvent[]>([])
const venues = shallowRef<AdminVenue[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const editing = ref<number | null>(null)

const venueNames = computed(() => new Map(venues.value.map((v) => [v.id, v.name])))
const hasNext = computed(() => page.value + 1 < (result.value?.totalPages ?? 0))

const selectClass =
  'h-8 rounded-lg border border-input bg-transparent px-2.5 text-sm focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none'

function navigate(next: { q?: string; venue?: number | null; past?: boolean; page?: number }) {
  const q = (next.q ?? title.value).trim()
  const venue = next.venue === undefined ? venueId.value : next.venue
  const showPast = next.past ?? past.value
  const nextPage = next.page ?? 0
  return router.replace({
    query: {
      ...(q ? { q } : {}),
      ...(venue !== null ? { venue: String(venue) } : {}),
      ...(showPast ? { past: '1' } : {}),
      ...(nextPage ? { page: String(nextPage) } : {}),
    },
  })
}

/** Every control sends the title as typed, so a venue picked after typing searches both. */
function search(next: { venue?: number | null; past?: boolean } = {}) {
  return navigate({ q: typedTitle.value, ...next })
}

async function load() {
  loading.value = true
  error.value = null
  try {
    result.value = await searchEvents({
      title: title.value,
      venueId: venueId.value,
      // An event over midnight counts on the day it starts, so tonight's late show stays listed.
      from: past.value ? null : berlinToday(),
      page: page.value,
    })
    events.value = [...result.value.content]
  } catch (e) {
    result.value = null
    events.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

watch([title, venueId, past, page], load, { immediate: true })

onMounted(async () => {
  // The venue list fills the picker and the venue column. Without it the search still works.
  try {
    venues.value = await fetchAllVenues()
  } catch {
    venues.value = []
  }
})

function onSaved(saved: AdminEvent) {
  events.value = events.value.map((event) => (event.id === saved.id ? saved : event))
}

function statusVariant(status: string) {
  if (status === 'CANCELLED') return 'destructive'
  if (status === 'SCHEDULED') return 'outline'
  return 'secondary'
}
</script>

<template>
  <form class="flex flex-wrap items-center gap-2" role="search" @submit.prevent="search()">
    <label for="events-title">
      <span class="sr-only">Title</span>
      <Input
        id="events-title"
        v-model="typedTitle"
        class="h-8 w-64"
        name="q"
        placeholder="Part of the title"
        type="search"
      />
    </label>
    <label class="flex items-center gap-2 text-sm" for="events-venue">
      Venue
      <select
        :class="selectClass"
        :value="venueId ?? ''"
        id="events-venue"
        name="venue"
        @change="search({ venue: Number(($event.target as HTMLSelectElement).value) || null })"
      >
        <option value="">All venues</option>
        <!-- A venue in the query that the list lacks still shows as selected. -->
        <option v-if="venueId !== null && !venueNames.has(venueId)" :value="venueId">
          Venue #{{ venueId }}
        </option>
        <option v-for="v in venues" :key="v.id" :value="v.id">
          {{ v.name }}{{ v.closedOn ? ` (closed ${v.closedOn})` : '' }}
        </option>
      </select>
    </label>
    <label class="flex items-center gap-2 text-sm" for="events-past">
      <input
        :checked="past"
        class="size-4 accent-primary"
        id="events-past"
        name="past"
        role="switch"
        type="checkbox"
        @change="search({ past: ($event.target as HTMLInputElement).checked })"
      />
      Show past events
    </label>
    <Button size="sm" type="submit"><Search />Search</Button>
    <div class="ml-auto flex items-center gap-2">
      <p class="text-sm text-muted-foreground tabular-nums" data-testid="result-count">
        {{ result?.totalElements ?? 0 }} events
      </p>
      <Button
        :disabled="page === 0 || loading"
        aria-label="Previous page"
        size="icon-sm"
        type="button"
        variant="outline"
        @click="navigate({ page: page - 1 })"
      >
        <ChevronLeft />
      </Button>
      <Button
        :disabled="!hasNext || loading"
        aria-label="Next page"
        size="icon-sm"
        type="button"
        variant="outline"
        @click="navigate({ page: page + 1 })"
      >
        <ChevronRight />
      </Button>
    </div>
  </form>

  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <div v-else class="overflow-hidden rounded-lg border">
    <Table>
      <TableHeader class="bg-muted">
        <TableRow>
          <TableHead>Date</TableHead>
          <TableHead>Start</TableHead>
          <TableHead>Venue</TableHead>
          <TableHead>Title</TableHead>
          <TableHead>Status</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow v-if="loading">
          <TableCell class="h-24 text-center text-muted-foreground" colspan="5">
            Loading the events…
          </TableCell>
        </TableRow>
        <template v-else>
          <TableRow v-for="event in events" :key="event.id" data-testid="event-row">
            <TableCell class="tabular-nums">{{ event.eventDate }}</TableCell>
            <TableCell class="tabular-nums">{{ event.startTime?.slice(0, 5) ?? '—' }}</TableCell>
            <TableCell class="whitespace-normal">
              {{ venueNames.get(event.venueId) ?? `Venue #${event.venueId}` }}
            </TableCell>
            <TableCell class="whitespace-normal break-words">
              <button
                class="text-left underline-offset-4 hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                type="button"
                @click="editing = event.id"
              >
                {{ event.title }}
              </button>
            </TableCell>
            <TableCell>
              <Badge :variant="statusVariant(event.status)">{{ event.status }}</Badge>
            </TableCell>
          </TableRow>
          <TableRow v-if="!events.length">
            <TableCell class="h-24 text-center" colspan="5" data-testid="no-events">
              No event matches this search.
            </TableCell>
          </TableRow>
        </template>
      </TableBody>
    </Table>
  </div>

  <EventEditor :event-id="editing" @close="editing = null" @saved="onSaved" />
</template>
