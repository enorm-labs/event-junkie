<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, onMounted, ref, shallowRef } from 'vue'

import {
  fetchNeedsReview,
  type NeedsReviewVenue,
  reviewVenue,
  runSiteCheck,
  type VenueReview,
} from '@/api/venues'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { publicOrigin } from '@/lib/environments'

const venues = shallowRef<NeedsReviewVenue[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
/** The venue whose action runs. One at a time: each is a read and a full write. */
const busy = ref<number | null>(null)
const notice = ref<string | null>(null)
const checkStarted = ref(false)
const checking = ref(false)
const closing = ref<NeedsReviewVenue | null>(null)
const closedOn = ref('')

const origin = publicOrigin(import.meta.env.MODE)

// As on the sources page: the actions stick to the table's right edge, so on a phone the other
// columns scroll under them. A sticky cell needs a solid background. Whole class names, for Tailwind.
const STICKY_HEAD = 'sticky right-0 z-10 shadow-[inset_1px_0_0_var(--color-border)] bg-muted'
const STICKY_CELL =
  'sticky right-0 z-10 shadow-[inset_1px_0_0_var(--color-border)] bg-background ' +
  '[tr:hover>&]:bg-[color-mix(in_oklab,var(--color-muted)_50%,var(--color-background))]'

const day = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  timeZone: 'Europe/Berlin',
})

/** The Berlin calendar day of an instant, as `YYYY-MM-DD`. */
function berlinDay(instant: string): string {
  return day.format(new Date(instant))
}

// The importer sorts the same way. The page does not rely on it: longest-failing first is the point.
const rows = computed(() =>
  [...venues.value].sort(
    (a, b) =>
      (a.failingSince ? Date.parse(a.failingSince) : Infinity) -
      (b.failingSince ? Date.parse(b.failingSince) : Infinity),
  ),
)

async function load() {
  loading.value = true
  error.value = null
  try {
    venues.value = await fetchNeedsReview()
  } catch (e) {
    venues.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function review(venue: NeedsReviewVenue, change: VenueReview, done: string) {
  busy.value = venue.venueId
  error.value = null
  notice.value = null
  try {
    await reviewVenue(venue.venueId, change)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    return false
  } finally {
    busy.value = null
  }
  // The importer leaves a reviewed or closed venue off the list, so the row goes without a reload.
  venues.value = venues.value.filter((v) => v.venueId !== venue.venueId)
  notice.value = done
  return true
}

function stillOpen(venue: NeedsReviewVenue) {
  void review(
    venue,
    { reviewedAt: new Date().toISOString() },
    `${venue.name} is marked as still open. It returns to the list after a new run of failures.`,
  )
}

function askClosedOn(venue: NeedsReviewVenue) {
  closedOn.value = venue.failingSince ? berlinDay(venue.failingSince) : ''
  closing.value = venue
}

async function saveClosedOn() {
  const venue = closing.value
  if (!venue || !closedOn.value) return
  const saved = await review(
    venue,
    { closedOn: closedOn.value },
    `${venue.name} is closed on ${closedOn.value}. From the day after, the public lists leave it out.`,
  )
  if (saved) closing.value = null
}

async function checkNow() {
  checking.value = true
  error.value = null
  try {
    await runSiteCheck()
    checkStarted.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    checking.value = false
  }
}
</script>

<template>
  <div class="flex flex-wrap items-start gap-2">
    <p class="max-w-prose text-sm text-muted-foreground">
      Venues without an importer whose site failed three monthly checks in a row, longest-failing
      first. Check the site by hand, then mark the venue as still open or closed.
    </p>
    <Button :disabled="checking" class="ml-auto" size="sm" variant="outline" @click="checkNow">
      <RefreshCw aria-hidden="true" />
      Check now
    </Button>
  </div>

  <p v-if="checkStarted" class="text-sm text-muted-foreground" role="status">
    The site check runs in the background. The list fills when it ends: reload the page in a few
    minutes.
  </p>
  <p v-if="notice" class="text-sm" role="status">{{ notice }}</p>
  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>

  <div class="overflow-hidden rounded-lg border">
    <Table>
      <TableHeader class="bg-muted">
        <TableRow>
          <TableHead>Venue</TableHead>
          <TableHead>Failing URL</TableHead>
          <TableHead>Outcome</TableHead>
          <TableHead>HTTP</TableHead>
          <TableHead>Failing since</TableHead>
          <TableHead :class="STICKY_HEAD"><span class="sr-only">Actions</span></TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow v-if="loading">
          <TableCell class="h-24 text-center text-muted-foreground" colspan="6">
            Loading the venues…
          </TableCell>
        </TableRow>
        <template v-else>
          <TableRow v-for="venue in rows" :key="venue.venueId" data-testid="review-row">
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
                v-if="venue.url"
                :href="venue.url"
                :title="venue.url"
                class="font-mono text-xs underline-offset-4 hover:underline"
                rel="noopener noreferrer"
                target="_blank"
              >
                {{ venue.url }}
              </a>
              <template v-else>—</template>
            </TableCell>
            <TableCell>
              <Badge variant="outline">{{ venue.outcome }}</Badge>
            </TableCell>
            <TableCell class="tabular-nums">{{ venue.httpStatus ?? '—' }}</TableCell>
            <TableCell class="tabular-nums">
              {{ venue.failingSince ? berlinDay(venue.failingSince) : '—' }}
              <span class="block text-xs text-muted-foreground">
                {{ venue.consecutiveFailures }} checks in a row
              </span>
            </TableCell>
            <TableCell :class="STICKY_CELL">
              <!-- Stacked on a phone, so the sticky column covers less of the row. -->
              <div class="flex flex-col gap-1 sm:flex-row">
                <Button
                  :aria-label="`${venue.name} is still open`"
                  :disabled="busy !== null"
                  size="xs"
                  variant="outline"
                  @click="stillOpen(venue)"
                >
                  Still open
                </Button>
                <Button
                  :aria-label="`${venue.name} closed on…`"
                  :disabled="busy !== null"
                  size="xs"
                  variant="outline"
                  @click="askClosedOn(venue)"
                >
                  Closed on…
                </Button>
              </div>
            </TableCell>
          </TableRow>
          <TableRow v-if="!rows.length">
            <TableCell class="h-24 text-center" colspan="6">No venue needs a review.</TableCell>
          </TableRow>
        </template>
      </TableBody>
    </Table>
  </div>

  <Sheet :open="closing !== null" @update:open="(open) => open || (closing = null)">
    <SheetContent class="w-full sm:max-w-md">
      <SheetHeader>
        <SheetTitle>{{ closing?.name }} closed</SheetTitle>
        <SheetDescription>
          From the day after, the public lists and the map leave the venue out, and its page says it
          closed (ADR-046).
        </SheetDescription>
      </SheetHeader>
      <form class="flex flex-col gap-4 px-4" @submit.prevent="saveClosedOn">
        <label class="flex flex-col gap-1.5" for="closed-on">
          <span class="text-sm font-medium">Last open day</span>
          <Input id="closed-on" v-model="closedOn" name="closedOn" required type="date" />
          <span class="text-xs text-muted-foreground">
            The default is the day the site began to fail.
          </span>
        </label>
        <p v-if="error" class="text-sm text-destructive" role="alert">{{ error }}</p>
        <SheetFooter class="px-0">
          <Button :disabled="busy !== null || !closedOn" type="submit">Save</Button>
        </SheetFooter>
      </form>
    </SheetContent>
  </Sheet>
</template>
