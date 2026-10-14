<script setup lang="ts">
import { Check, ChevronDown, ChevronUp, ExternalLink, X } from '@lucide/vue'
import { ref, shallowRef } from 'vue'

import {
  berlinToday,
  fetchCandidates,
  fetchUpcomingEvents,
  lifeSpan,
  type MusicBrainzCandidate,
  type ReviewArtist,
  setMusicBrainzId,
} from '@/api/artists'
import type { AdminEvent } from '@/api/events'
import { fetchNames } from '@/api/names'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  artist: ReviewArtist
  /** Venue names by id; an id it lacks shows as the id. */
  venueNames: ReadonlyMap<number, string>
  origin: string
}>()

const emit = defineEmits<{ matched: [candidate: MusicBrainzCandidate] }>()

interface BilledEvent {
  event: AdminEvent
  otherActs: string[]
}

const open = ref(false)
const events = shallowRef<BilledEvent[] | null>(null)
const eventsError = ref<string | null>(null)
const candidates = shallowRef<MusicBrainzCandidate[] | null>(null)
const candidatesError = ref<string | null>(null)
/** The candidate the operator chose and has not confirmed yet. Nothing is chosen at first. */
const chosen = ref<MusicBrainzCandidate | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)

async function loadEvents() {
  eventsError.value = null
  try {
    const billed = await fetchUpcomingEvents(props.artist.id, berlinToday())
    const otherIds = billed.flatMap((e) =>
      e.artists.map((a) => a.artistId).filter((id) => id !== props.artist.id),
    )
    const names = otherIds.length ? await fetchNames('artists', otherIds) : new Map()
    events.value = billed.map((event) => ({
      event,
      otherActs: [...event.artists]
        .sort((a, b) => a.billingOrder - b.billingOrder)
        .filter((a) => a.artistId !== props.artist.id)
        .map((a) => names.get(a.artistId)?.name ?? `#${a.artistId}`),
    }))
  } catch (e) {
    events.value = null
    eventsError.value = e instanceof Error ? e.message : String(e)
  }
}

async function loadCandidates() {
  candidatesError.value = null
  try {
    candidates.value = await fetchCandidates(props.artist.id)
  } catch (e) {
    candidates.value = null
    candidatesError.value = e instanceof Error ? e.message : String(e)
  }
}

function toggle() {
  open.value = !open.value
  // Read once: each candidate request is a MusicBrainz search on the importer's shared queue.
  if (open.value && events.value === null && candidates.value === null) {
    void loadEvents()
    void loadCandidates()
  }
}

async function confirm() {
  const candidate = chosen.value
  if (!candidate) return
  saving.value = true
  saveError.value = null
  try {
    await setMusicBrainzId(props.artist.id, candidate.musicbrainzId)
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : String(e)
    return
  } finally {
    saving.value = false
  }
  emit('matched', candidate)
}

function venueName(id: number) {
  return props.venueNames.get(id) ?? `Venue #${id}`
}

function time(value: string | null) {
  return value ? value.slice(0, 5) : null
}
</script>

<template>
  <li
    class="flex flex-col gap-3 rounded-lg border bg-card p-4 text-card-foreground"
    data-testid="review-artist"
  >
    <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
      <h2 class="min-w-0 flex-1 text-base font-medium break-words">
        <a
          :href="`${origin}/en/artists/${encodeURIComponent(artist.slug)}`"
          class="inline-flex items-center gap-1 underline-offset-4 hover:underline"
          rel="noopener noreferrer"
          target="_blank"
        >
          {{ artist.name }}
          <ExternalLink aria-hidden="true" class="size-3 shrink-0" />
        </a>
      </h2>
      <Button
        :aria-expanded="open"
        :aria-label="`${open ? 'Hide' : 'Review'} ${artist.name}`"
        size="sm"
        variant="outline"
        @click="toggle"
      >
        <component :is="open ? ChevronUp : ChevronDown" aria-hidden="true" />
        {{ open ? 'Hide' : 'Review' }}
      </Button>
    </div>

    <div v-if="open" class="grid gap-4 lg:grid-cols-2">
      <section class="flex min-w-0 flex-col gap-2">
        <h3 class="text-sm font-medium">Billed from today on</h3>
        <p v-if="eventsError" class="text-sm text-destructive" role="alert">{{ eventsError }}</p>
        <p v-else-if="events === null" class="text-sm text-muted-foreground">Loading the events…</p>
        <p v-else-if="!events.length" class="text-sm text-muted-foreground">
          No event from today on bills this artist.
        </p>
        <ul v-else class="flex flex-col gap-2 text-sm">
          <li
            v-for="{ event, otherActs } in events"
            :key="event.id"
            class="min-w-0 break-words"
            data-testid="billed-event"
          >
            <span class="tabular-nums">{{ event.eventDate }}</span>
            <span
              v-if="time(event.startTime)"
              class="ml-1.5 text-muted-foreground tabular-nums"
              data-testid="start-time"
              >{{ time(event.startTime) }}</span
            >
            · {{ venueName(event.venueId) }}
            <a
              :href="`${origin}/en/events/${encodeURIComponent(event.slug)}`"
              class="block font-medium underline-offset-4 hover:underline"
              rel="noopener noreferrer"
              target="_blank"
            >
              {{ event.title }}
            </a>
            <span class="block text-muted-foreground" data-testid="other-acts">
              {{ otherActs.length ? `With ${otherActs.join(', ')}` : 'No other act' }}
            </span>
          </li>
        </ul>
      </section>

      <section class="flex min-w-0 flex-col gap-2">
        <h3 class="text-sm font-medium">MusicBrainz candidates</h3>
        <p v-if="candidatesError" class="text-sm text-destructive" role="alert">
          {{ candidatesError }}
        </p>
        <p v-else-if="candidates === null" class="text-sm text-muted-foreground">
          Asking MusicBrainz… This can take a few seconds.
        </p>
        <p v-else-if="!candidates.length" class="text-sm" data-testid="no-candidates">
          MusicBrainz finds no artist by this name. Leave the row: it stays AMBIGUOUS.
        </p>
        <template v-else>
          <ul class="flex flex-col gap-2 text-sm">
            <li
              v-for="candidate in candidates"
              :key="candidate.musicbrainzId"
              class="flex flex-wrap items-start gap-2 rounded-md border p-2"
              data-testid="candidate"
            >
              <div class="min-w-0 flex-1 break-words">
                <a
                  :href="candidate.url"
                  class="inline-flex items-center gap-1 font-medium underline-offset-4 hover:underline"
                  rel="noopener noreferrer"
                  target="_blank"
                >
                  {{ candidate.name }}
                  <ExternalLink aria-hidden="true" class="size-3 shrink-0" />
                </a>
                <Badge v-if="candidate.nameMatch !== 'NAME'" class="ml-2" variant="outline">
                  {{ candidate.nameMatch === 'OTHER_NAME' ? 'alias' : 'similar name' }}
                </Badge>
                <p v-if="candidate.disambiguation" class="text-muted-foreground">
                  {{ candidate.disambiguation }}
                </p>
                <p class="text-xs text-muted-foreground" data-testid="candidate-facts">
                  {{
                    [
                      candidate.type ?? 'No type',
                      candidate.country,
                      candidate.area,
                      lifeSpan(candidate),
                    ]
                      .filter(Boolean)
                      .join(' · ')
                  }}
                </p>
              </div>
              <Button
                :aria-label="`Choose ${candidate.name} for ${artist.name}`"
                :disabled="saving"
                :variant="chosen?.musicbrainzId === candidate.musicbrainzId ? 'default' : 'outline'"
                size="xs"
                @click="chosen = candidate"
              >
                Choose
              </Button>
            </li>
          </ul>
          <p class="text-xs text-muted-foreground">
            When none fits, leave the row: it stays AMBIGUOUS.
          </p>
        </template>
      </section>
    </div>

    <div
      v-if="chosen"
      class="flex flex-col gap-2 rounded-md border border-primary/40 bg-muted/50 p-3 text-sm"
      data-testid="confirm"
      role="group"
      aria-label="Confirm the match"
    >
      <p class="break-words">
        Store <strong>{{ chosen.name }}</strong>
        <template v-if="chosen.disambiguation"> ({{ chosen.disambiguation }})</template> as the
        MusicBrainz match of <strong>{{ artist.name }}</strong
        >? The artist becomes EXACT and leaves this list.
      </p>
      <p v-if="saveError" class="text-destructive" role="alert">{{ saveError }}</p>
      <div class="flex flex-wrap gap-2">
        <Button :disabled="saving" size="sm" @click="confirm">
          <Check aria-hidden="true" />
          Confirm
        </Button>
        <Button :disabled="saving" size="sm" variant="outline" @click="chosen = null">
          <X aria-hidden="true" />
          Cancel
        </Button>
      </div>
    </div>
  </li>
</template>
