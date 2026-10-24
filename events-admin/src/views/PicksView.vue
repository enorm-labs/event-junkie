<script setup lang="ts">
import { X } from '@lucide/vue'
import { computed, onMounted, ref, shallowRef } from 'vue'

import { type AdminEvent, clearPick, fetchPicks } from '@/api/events'
import { type AdminVenue, fetchAllVenues } from '@/api/venues'
import EventEditor from '@/components/EventEditor.vue'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { formatBerlin } from '@/lib/berlinTime'

const picks = ref<AdminEvent[]>([])
const total = ref(0)
const venues = shallowRef<AdminVenue[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const editing = ref<number | null>(null)
/** The event whose Clear is in flight, and the last Clear that failed. */
const clearing = ref<number | null>(null)
const clearError = ref<string | null>(null)

const venueNames = computed(() => new Map(venues.value.map((v) => [v.id, v.name])))

const isCurrent = (event: AdminEvent) =>
  !!event.featuredUntil && Date.parse(event.featuredUntil) > Date.now()

onMounted(async () => {
  try {
    const page = await fetchPicks()
    picks.value = [...page.content]
    total.value = page.totalElements
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
})

onMounted(async () => {
  // The venue list fills the venue column. Without it the picks still list.
  try {
    venues.value = await fetchAllVenues()
  } catch {
    venues.value = []
  }
})

function drop(id: number) {
  picks.value = picks.value.filter((event) => event.id !== id)
  total.value -= 1
}

/** A save that ends or moves the pick past now takes the row off the list. */
function onSaved(saved: AdminEvent) {
  if (isCurrent(saved)) {
    picks.value = picks.value.map((event) => (event.id === saved.id ? saved : event))
  } else {
    drop(saved.id)
  }
}

async function clear(event: AdminEvent) {
  clearing.value = event.id
  clearError.value = null
  try {
    await clearPick(event.id)
    drop(event.id)
  } catch (e) {
    clearError.value = e instanceof Error ? e.message : String(e)
  } finally {
    clearing.value = null
  }
}
</script>

<template>
  <p class="text-sm text-muted-foreground">
    The events the site's list leads with as "Our pick", until the time in the last column. The site
    shows the earliest one that matches the visitor's filters.
  </p>
  <p v-if="loading" class="text-muted-foreground">Loading the picks…</p>
  <p v-else-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <template v-else>
    <p class="text-sm text-muted-foreground tabular-nums" data-testid="pick-count">
      {{ total }} {{ total === 1 ? 'pick' : 'picks' }}
      <template v-if="total > picks.length"> · the first {{ picks.length }} shown</template>
    </p>
    <p v-if="clearError" class="text-destructive" role="alert">{{ clearError }}</p>
    <div class="overflow-hidden rounded-lg border">
      <Table>
        <TableHeader class="bg-muted">
          <TableRow>
            <TableHead>Title</TableHead>
            <TableHead>Venue</TableHead>
            <TableHead>Start</TableHead>
            <TableHead>Until</TableHead>
            <TableHead><span class="sr-only">Actions</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="event in picks" :key="event.id" data-testid="pick-row">
            <TableCell class="break-words whitespace-normal">
              <button
                class="text-left underline-offset-4 hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                data-testid="pick-title"
                type="button"
                @click="editing = event.id"
              >
                {{ event.title }}
              </button>
            </TableCell>
            <TableCell class="whitespace-normal">
              {{ venueNames.get(event.venueId) ?? `Venue #${event.venueId}` }}
            </TableCell>
            <TableCell class="whitespace-normal tabular-nums">
              {{ event.eventDate }} {{ event.startTime?.slice(0, 5) ?? '' }}
            </TableCell>
            <TableCell class="whitespace-normal tabular-nums" data-testid="pick-until">
              {{ event.featuredUntil ? formatBerlin(event.featuredUntil) : '' }}
            </TableCell>
            <TableCell class="text-right">
              <Button
                :aria-label="`Clear the pick on ${event.title}`"
                :disabled="clearing !== null"
                size="xs"
                variant="outline"
                @click="clear(event)"
              >
                <X aria-hidden="true" />
                <!-- A phone shows the icon alone, so the row fits without a sideways scroll. -->
                <span class="sr-only sm:not-sr-only">Clear</span>
              </Button>
            </TableCell>
          </TableRow>
          <TableRow v-if="!picks.length">
            <TableCell class="h-24 text-center" colspan="5" data-testid="no-picks">
              No event is picked. Set "Our pick until" in an event's editor.
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </template>

  <EventEditor :event-id="editing" @close="editing = null" @saved="onSaved" />
</template>
