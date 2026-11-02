<script setup lang="ts">
/**
 * The problem reports allowed accounts file on event pages (#4399). One row per event. Resolve
 * deletes its reports, as the privacy notice promises, so resolve once the fix is live or the
 * verdict is final. `/fix-events` works the same queue.
 */
import { onMounted, ref, shallowRef } from 'vue'

import { fetchReports, PROBLEM_LABELS, type ReportedEvent, resolveReports } from '@/api/reports'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { publicOrigin } from '@/lib/environments'

const origin = publicOrigin(import.meta.env.MODE)

const events = shallowRef<ReportedEvent[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    events.value = await fetchReports()
  } catch (e) {
    events.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function resolve(event: ReportedEvent) {
  busy.value = true
  error.value = null
  try {
    await resolveReports(event.eventId)
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

const problems = (event: ReportedEvent) => [
  ...new Set(event.reports.flatMap((report) => report.problems)),
]
const notes = (event: ReportedEvent) =>
  event.reports.map((report) => report.note).filter((note): note is string => Boolean(note))

onMounted(load)
</script>

<template>
  <p class="mb-4 text-muted-foreground">
    Problems reported on event pages. Resolve deletes an event's reports: do it once the fix is live
    or the verdict is final.
  </p>

  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <div class="overflow-hidden rounded-lg border">
    <Table>
      <TableHeader class="bg-muted">
        <TableRow>
          <TableHead>Date</TableHead>
          <TableHead>Event</TableHead>
          <TableHead>Source</TableHead>
          <TableHead>Problems</TableHead>
          <TableHead>Notes</TableHead>
          <TableHead><span class="sr-only">Actions</span></TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow v-if="loading">
          <TableCell class="h-24 text-center text-muted-foreground" colspan="6">
            Loading the reports…
          </TableCell>
        </TableRow>
        <template v-else>
          <TableRow v-for="event in events" :key="event.eventId">
            <TableCell class="tabular-nums">{{ event.eventDate }}</TableCell>
            <TableCell class="whitespace-normal">
              <a
                :href="`${origin}/de/events/${event.slug}`"
                class="underline-offset-4 hover:underline"
                rel="noopener noreferrer"
                target="_blank"
              >
                {{ event.title }}
              </a>
              <span v-if="event.venue" class="block text-muted-foreground">{{ event.venue }}</span>
            </TableCell>
            <TableCell class="font-mono">{{ event.sourceSlug ?? 'manual' }}</TableCell>
            <TableCell class="whitespace-normal">
              <span class="flex flex-wrap gap-1">
                <Badge v-for="problem in problems(event)" :key="problem" variant="outline">
                  {{ PROBLEM_LABELS[problem] ?? problem }}
                </Badge>
              </span>
            </TableCell>
            <TableCell class="whitespace-normal">
              <p v-for="(note, index) in notes(event)" :key="index">{{ note }}</p>
            </TableCell>
            <TableCell class="text-right">
              <Button
                :aria-label="`Resolve: ${event.title}`"
                :disabled="busy"
                size="sm"
                variant="outline"
                @click="resolve(event)"
              >
                Resolve
              </Button>
            </TableCell>
          </TableRow>
          <TableRow v-if="!events.length">
            <TableCell class="h-24 text-center" colspan="6">No open reports.</TableCell>
          </TableRow>
        </template>
      </TableBody>
    </Table>
  </div>
</template>
