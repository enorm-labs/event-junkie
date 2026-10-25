<script setup lang="ts">
import { CalendarPlus, Check } from '@lucide/vue'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { RouterLink } from 'vue-router'

import {
  fetchManualVenues,
  isDue,
  type ManualVenue,
  markProgrammeChecked,
  PROGRAMME_CHECK_DAYS,
  sortManualVenues,
} from '@/api/venues'
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
import { berlinDay } from '@/lib/berlinTime'
import { publicOrigin } from '@/lib/environments'
import { STICKY_CELL, STICKY_HEAD } from '@/lib/utils'

const venues = shallowRef<ManualVenue[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref<number | null>(null)
const notice = ref<string | null>(null)

const origin = publicOrigin(import.meta.env.MODE)

const rows = computed(() => sortManualVenues(venues.value))
const overdue = computed(() => rows.value.filter((v) => isDue(v)).length)

async function load() {
  loading.value = true
  error.value = null
  try {
    venues.value = await fetchManualVenues()
  } catch (e) {
    venues.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** Records the check. The row stays on the list and moves to the end, as the next check is furthest away. */
async function checked(venue: ManualVenue) {
  busy.value = venue.id
  error.value = null
  notice.value = null
  try {
    const { programmeCheckedAt } = await markProgrammeChecked(venue.id)
    venues.value = venues.value.map((v) => (v.id === venue.id ? { ...v, programmeCheckedAt } : v))
    notice.value = `${venue.name} is checked. It is due again in ${PROGRAMME_CHECK_DAYS} days.`
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <p class="max-w-prose text-sm text-muted-foreground">
    Open venues without an importer. Read the programme, enter the new events with New event, then
    press Checked. A venue is due {{ PROGRAMME_CHECK_DAYS }} days after its last check; the one
    never checked or checked longest ago comes first.
  </p>
  <p v-if="!loading && !error" class="text-sm" data-testid="overdue-count">
    {{ overdue }} of {{ rows.length }} venues overdue.
  </p>
  <p v-if="notice" class="text-sm" role="status">{{ notice }}</p>
  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>

  <div class="overflow-hidden rounded-lg border">
    <Table>
      <TableHeader class="bg-muted">
        <TableRow>
          <TableHead>Venue</TableHead>
          <TableHead>Programme</TableHead>
          <TableHead>Last checked</TableHead>
          <TableHead>Newest event entered</TableHead>
          <TableHead :class="STICKY_HEAD"><span class="sr-only">Actions</span></TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow v-if="loading">
          <TableCell class="h-24 text-center text-muted-foreground" colspan="5">
            Loading the venues…
          </TableCell>
        </TableRow>
        <template v-else>
          <TableRow v-for="venue in rows" :key="venue.id" data-testid="manual-row">
            <TableCell class="font-medium">
              <a
                :href="`${origin}/en/venues/${encodeURIComponent(venue.slug)}`"
                class="underline-offset-4 hover:underline"
                rel="noopener noreferrer"
                target="_blank"
              >
                {{ venue.name }}
              </a>
            </TableCell>
            <TableCell class="max-w-64 truncate">
              <a
                v-if="venue.programmeUrl"
                :href="venue.programmeUrl"
                :title="venue.programmeUrl"
                class="font-mono text-xs underline-offset-4 hover:underline"
                data-testid="programme-link"
                rel="noopener noreferrer"
                target="_blank"
              >
                {{ venue.programmeUrl }}
              </a>
              <template v-else>—</template>
            </TableCell>
            <TableCell class="tabular-nums">
              <span class="flex items-center gap-2">
                {{ venue.programmeCheckedAt ? berlinDay(venue.programmeCheckedAt) : 'Never' }}
                <Badge v-if="isDue(venue)" data-testid="due" variant="destructive">Due</Badge>
              </span>
            </TableCell>
            <TableCell class="tabular-nums">
              {{ venue.newestEventCreatedAt ? berlinDay(venue.newestEventCreatedAt) : '—' }}
            </TableCell>
            <TableCell :class="STICKY_CELL">
              <!-- Stacked on a phone, so the sticky column covers less of the row. -->
              <div class="flex flex-col gap-1 sm:flex-row">
                <Button as-child size="xs" variant="outline">
                  <RouterLink
                    :aria-label="`New event at ${venue.name}`"
                    :to="{ name: 'new-event', query: { venue: String(venue.id) } }"
                    data-testid="new-event"
                  >
                    <CalendarPlus aria-hidden="true" />
                    New event
                  </RouterLink>
                </Button>
                <Button
                  :aria-label="`${venue.name}: programme checked`"
                  :disabled="busy !== null"
                  data-testid="checked"
                  size="xs"
                  variant="outline"
                  @click="checked(venue)"
                >
                  <Check aria-hidden="true" />
                  Checked
                </Button>
              </div>
            </TableCell>
          </TableRow>
          <TableRow v-if="!rows.length">
            <TableCell class="h-24 text-center" colspan="5"
              >No venue without an importer.</TableCell
            >
          </TableRow>
        </template>
      </TableBody>
    </Table>
  </div>
</template>
