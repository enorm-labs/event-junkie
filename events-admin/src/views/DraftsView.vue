<script setup lang="ts">
import {
  CalendarPlus,
  Check,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  Pencil,
  X,
} from '@lucide/vue'
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { type AdminEvent, fetchEvent, updateEvent } from '@/api/events'
import {
  applyChanges,
  approveDraft,
  type DraftChange,
  draftChanges,
  type DraftNight,
  type DraftPage,
  fetchDrafts,
  fetchInstagramVenues,
  groupByNight,
  type InstagramDraft,
  type InstagramVenue,
  rejectDraft,
  switchVenueOff,
} from '@/api/instagramDrafts'
import EventEditor from '@/components/EventEditor.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

const route = useRoute()
const router = useRouter()

const page = computed(() => Math.max(0, Number(route.query.page) || 0))
const result = shallowRef<DraftPage | null>(null)
const nights = ref<DraftNight[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref<string | null>(null)
const failures = ref<Record<string, string>>({})
const notice = ref<string | null>(null)

const venues = shallowRef<InstagramVenue[]>([])
const venuesError = ref<string | null>(null)
/** The approved event of each night a later post updates, or the error that kept it from loading. */
const targets = ref<Record<string, AdminEvent | string>>({})
/** The event open in the editor, to add the acts of an update. */
const editing = ref<number | null>(null)

/** The venue whose "Switch off" was pressed once. The second press deletes its drafts. */
const confirmingOff = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  failures.value = {}
  try {
    result.value = await fetchDrafts(page.value)
    nights.value = groupByNight(result.value.content)
    void loadTargets()
  } catch (e) {
    result.value = null
    nights.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/** Reads the approved event of every update night on the page. */
async function loadTargets() {
  targets.value = {}
  await Promise.all(
    nights.value.map(async (night) => {
      const eventId = night.drafts[0]!.eventId
      if (eventId == null) return
      try {
        targets.value[night.key] = await fetchEvent(eventId)
      } catch (e) {
        targets.value[night.key] = e instanceof Error ? e.message : String(e)
      }
    }),
  )
}

function target(night: DraftNight): AdminEvent | null {
  const event = targets.value[night.key]
  return typeof event === 'object' ? event : null
}

function changes(night: DraftNight): DraftChange[] {
  const event = target(night)
  return event ? draftChanges(event, night.drafts) : []
}

/** Writes the night's changes to its event, then approves the night against it. */
async function applyUpdate(night: DraftNight) {
  const event = target(night)
  if (!event) return
  busy.value = night.key
  notice.value = null
  delete failures.value[night.key]
  const changed = changes(night)
  try {
    if (changed.length) await updateEvent(event.id, applyChanges(event, changed))
    await approveDraft(night.drafts[0]!.id, event.id)
  } catch (e) {
    failures.value[night.key] = e instanceof Error ? e.message : String(e)
    return
  } finally {
    busy.value = null
  }
  nights.value = nights.value.filter((n) => n.key !== night.key)
  notice.value = changed.length
    ? `Updated ${event.title}: ${changed.map((c) => `${c.label} ${c.to}`).join(', ')}.`
    : `Approved ${event.title}: nothing new.`
}

function onEdited(saved: AdminEvent) {
  for (const night of nights.value) {
    if (night.drafts[0]!.eventId === saved.id) targets.value[night.key] = saved
  }
}

async function loadVenues() {
  try {
    venues.value = await fetchInstagramVenues()
    venuesError.value = null
  } catch (e) {
    venuesError.value = e instanceof Error ? e.message : String(e)
  }
}

watch(page, load, { immediate: true })
onMounted(loadVenues)

const hasNext = computed(() => page.value + 1 < (result.value?.totalPages ?? 0))

function navigate(next: number) {
  return router.replace({ query: next ? { page: String(next) } : {} })
}

function create(night: DraftNight) {
  return router.push({ name: 'new-event', query: { draft: String(night.drafts[0]!.id) } })
}

async function reject(night: DraftNight) {
  busy.value = night.key
  notice.value = null
  delete failures.value[night.key]
  try {
    await rejectDraft(night.drafts[0]!.id)
  } catch (e) {
    failures.value[night.key] = e instanceof Error ? e.message : String(e)
    return
  } finally {
    busy.value = null
  }
  nights.value = nights.value.filter((n) => n.key !== night.key)
  notice.value = `Rejected: ${night.venueName} on ${night.eventDate}.`
}

async function switchOff(venue: InstagramVenue) {
  if (confirmingOff.value !== venue.slug) {
    confirmingOff.value = venue.slug
    return
  }
  confirmingOff.value = null
  busy.value = venue.slug
  try {
    const deleted = await switchVenueOff(venue.slug)
    notice.value = `${venue.name} is switched off. ${deleted} draft(s) deleted.`
    await Promise.all([loadVenues(), load()])
  } catch (e) {
    venuesError.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = null
  }
}

function percent(confidence: number | null) {
  return confidence == null ? '–' : `${Math.round(confidence * 100)} %`
}

function time(draft: InstagramDraft) {
  const start = draft.startTime?.slice(0, 5)
  const doors = draft.doorsTime?.slice(0, 5)
  if (start && doors) return `doors ${doors}, start ${start}`
  if (doors) return `doors ${doors}`
  return start ?? 'no start time'
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <p class="max-w-prose text-sm text-muted-foreground">
      Events the model drafted from Instagram posts, the soonest night first. Create event opens the
      New event form filled from the draft; saving it approves the night. A later post of an
      approved night proposes an update to its event instead. Reject drops it. Nothing appears on
      the site until you save an event.
    </p>
    <div class="ml-auto flex items-center gap-2">
      <p class="text-sm text-muted-foreground tabular-nums" data-testid="pending-count">
        {{ result?.totalElements ?? 0 }} pending
      </p>
      <Button
        :disabled="page === 0 || loading"
        aria-label="Previous page"
        size="icon-sm"
        variant="outline"
        @click="navigate(page - 1)"
      >
        <ChevronLeft />
      </Button>
      <Button
        :disabled="!hasNext || loading"
        aria-label="Next page"
        size="icon-sm"
        variant="outline"
        @click="navigate(page + 1)"
      >
        <ChevronRight />
      </Button>
    </div>
  </div>

  <p v-if="notice" class="text-sm" role="status">{{ notice }}</p>
  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <p v-else-if="loading" class="text-muted-foreground">Loading the drafts…</p>
  <p
    v-else-if="!nights.length"
    class="rounded-lg border p-6 text-center text-sm"
    data-testid="no-drafts"
  >
    No draft is pending.
  </p>

  <ul v-if="!loading && !error" class="flex flex-col gap-3">
    <li
      v-for="night in nights"
      :key="night.key"
      class="flex flex-col gap-3 rounded-lg border bg-card p-4 text-card-foreground"
      data-testid="night"
    >
      <div class="flex flex-wrap items-start gap-x-3 gap-y-1">
        <div class="min-w-0 flex-1">
          <h2 class="text-base font-medium">{{ night.drafts[0]!.title }}</h2>
          <p class="text-sm text-muted-foreground">
            <span class="tabular-nums">{{ night.eventDate }}</span> · {{ night.venueName }} ·
            <span class="tabular-nums">{{ time(night.drafts[0]!) }}</span>
          </p>
        </div>
        <Badge v-if="night.drafts[0]!.eventId != null" data-testid="update-badge" variant="outline">
          Update to event #{{ night.drafts[0]!.eventId }}
        </Badge>
        <Badge v-if="night.drafts.length > 1" variant="secondary">
          {{ night.drafts.length }} posts
        </Badge>
      </div>

      <div
        v-if="night.drafts[0]!.eventId != null"
        class="flex flex-col gap-1 rounded-md bg-muted p-3 text-sm"
        data-testid="update"
      >
        <p class="font-medium">A later post of a night you approved.</p>
        <p v-if="typeof targets[night.key] === 'string'" class="text-destructive" role="alert">
          {{ targets[night.key] }}
        </p>
        <p v-else-if="!target(night)" class="text-muted-foreground">Loading the event…</p>
        <ul v-else-if="changes(night).length" class="flex flex-col gap-0.5">
          <li v-for="change in changes(night)" :key="change.field" class="tabular-nums">
            {{ change.label }}: {{ change.from ?? 'none' }} → {{ change.to }}
          </li>
        </ul>
        <p v-else class="text-muted-foreground">Doors, start and prices match the event.</p>
        <p class="text-muted-foreground">Compare the acts with the lineup in the event editor.</p>
      </div>

      <div v-for="draft in night.drafts" :key="draft.id" class="flex flex-col gap-2 border-t pt-2">
        <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
          <dt class="text-muted-foreground">Acts</dt>
          <dd class="break-words">
            <template v-if="draft.acts.length">{{ draft.acts.join(', ') }}</template>
            <span v-else class="text-muted-foreground italic">none named</span>
          </dd>
          <dt class="text-muted-foreground">Evidence</dt>
          <dd>
            <blockquote class="border-l-2 pl-2 break-words">{{ draft.evidence }}</blockquote>
          </dd>
          <dt class="text-muted-foreground">Confidence</dt>
          <dd class="tabular-nums">{{ percent(draft.confidence) }}</dd>
          <dt class="text-muted-foreground">Post</dt>
          <dd>
            <a
              :href="draft.permalink"
              class="inline-flex items-center gap-1 underline-offset-4 hover:underline"
              rel="noopener noreferrer"
              target="_blank"
            >
              {{ draft.postedAt.slice(0, 10) }}
              <ExternalLink aria-hidden="true" class="size-3" />
            </a>
          </dd>
        </dl>
        <details v-if="draft.caption" class="text-sm">
          <summary class="cursor-pointer text-muted-foreground">Caption</summary>
          <p class="mt-1 break-words whitespace-pre-line">{{ draft.caption }}</p>
        </details>
        <details v-if="draft.flyerText" class="text-sm" data-testid="flyer-text">
          <summary class="cursor-pointer text-muted-foreground">Read from the image</summary>
          <p class="mt-1 break-words whitespace-pre-line">{{ draft.flyerText }}</p>
        </details>
      </div>

      <p v-if="failures[night.key]" class="text-sm text-destructive" role="alert">
        {{ failures[night.key] }}
      </p>
      <div class="flex flex-wrap gap-2">
        <template v-if="night.drafts[0]!.eventId != null">
          <Button
            :aria-label="`Apply the update to ${night.venueName} on ${night.eventDate}`"
            :disabled="busy !== null || !target(night)"
            size="sm"
            @click="applyUpdate(night)"
          >
            <Check aria-hidden="true" />
            {{ changes(night).length ? 'Apply to event' : 'Approve, nothing new' }}
          </Button>
          <Button
            :aria-label="`Edit the event of ${night.venueName} on ${night.eventDate}`"
            :disabled="busy !== null"
            size="sm"
            variant="outline"
            @click="editing = night.drafts[0]!.eventId"
          >
            <Pencil aria-hidden="true" />
            Edit event
          </Button>
        </template>
        <Button
          v-else
          :aria-label="`Create the event for ${night.venueName} on ${night.eventDate}`"
          :disabled="busy !== null"
          size="sm"
          @click="create(night)"
        >
          <CalendarPlus aria-hidden="true" />
          Create event
        </Button>
        <Button
          :aria-label="`Reject ${night.venueName} on ${night.eventDate}`"
          :disabled="busy !== null"
          size="sm"
          variant="outline"
          @click="reject(night)"
        >
          <X aria-hidden="true" />
          Reject
        </Button>
      </div>
    </li>
  </ul>

  <EventEditor :event-id="editing" @close="editing = null" @saved="onEdited" />

  <section aria-labelledby="drafts-venues" class="flex flex-col gap-2">
    <h2 id="drafts-venues" class="text-base font-medium">Venues read</h2>
    <p v-if="venuesError" class="text-destructive" role="alert">{{ venuesError }}</p>
    <ul class="flex flex-col gap-1 text-sm">
      <li
        v-for="venue in venues"
        :key="venue.slug"
        class="flex items-center gap-2"
        data-testid="instagram-venue"
      >
        <span class="min-w-0 flex-1">
          {{ venue.name }}
          <span class="font-mono text-muted-foreground">@{{ venue.handle ?? 'no profile' }}</span>
        </span>
        <Button
          :aria-label="
            confirmingOff === venue.slug
              ? `Confirm: switch ${venue.name} off and delete its drafts`
              : `Switch ${venue.name} off`
          "
          :disabled="busy !== null"
          size="sm"
          :variant="confirmingOff === venue.slug ? 'destructive' : 'outline'"
          @click="switchOff(venue)"
        >
          {{ confirmingOff === venue.slug ? 'Delete its drafts' : 'Switch off' }}
        </Button>
      </li>
    </ul>
  </section>
</template>
