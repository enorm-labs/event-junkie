<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import {
  type AdminEvent,
  ARTIST_ROLES,
  type EventArtist,
  type EventFeatures,
  EVENT_TYPES,
  type EventType,
  fetchEvent,
  fetchEventFeatures,
  PARTY_FEATURES,
  setEventFeatures,
  toEventRequest,
  unpinField,
  updateEvent,
} from '@/api/events'
import { fetchNames, type NameKind, type NamedRow } from '@/api/names'
import NamePicker from '@/components/NamePicker.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'

const props = defineProps<{ eventId: number | null }>()
const emit = defineEmits<{ close: []; saved: [event: AdminEvent] }>()

const event = ref<AdminEvent | null>(null)
const eventType = ref<EventType>('OTHER')
const genre = ref('')
const subtitle = ref('')
const description = ref('')
const lineup = ref<EventArtist[]>([])
const promoterIds = ref<number[]>([])
/** The features as the importer answered them, and the slugs the checkboxes hold. */
const storedFeatures = ref<EventFeatures | null>(null)
const features = ref<string[]>([])
/** A failed features read leaves the rest of the editor usable. */
const featuresError = ref<string | null>(null)
/** Names by id, per kind. The event answer carries ids only. */
const names = ref<Record<NameKind, Map<number, NamedRow | null>>>({
  artists: new Map(),
  promoters: new Map(),
})
const loading = ref(false)
const busy = ref(false)
const error = ref<string | null>(null)

const lineupIds = computed(() => lineup.value.map((artist) => artist.artistId))

const featuresChanged = computed(() => {
  const stored = storedFeatures.value?.features.map((entry) => entry.feature) ?? []
  return (
    storedFeatures.value !== null &&
    (stored.length !== features.value.length ||
      stored.some((slug) => !features.value.includes(slug)))
  )
})

function cuesOf(slug: string): string[] {
  return (storedFeatures.value?.uncertain ?? [])
    .filter((cue) => cue.feature === slug)
    .map((cue) => cue.phrase)
}

function showFeatures(loaded: EventFeatures) {
  storedFeatures.value = loaded
  features.value = loaded.features.map((entry) => entry.feature)
}

async function loadFeatures(id: number) {
  featuresError.value = null
  try {
    showFeatures(await fetchEventFeatures(id))
  } catch (e) {
    featuresError.value = e instanceof Error ? e.message : String(e)
  }
}

function show(loaded: AdminEvent) {
  event.value = loaded
  eventType.value = loaded.eventType
  genre.value = loaded.genre ?? ''
  subtitle.value = loaded.subtitle ?? ''
  description.value = loaded.description ?? ''
  lineup.value = [...loaded.artists]
    .sort((a, b) => a.billingOrder - b.billingOrder)
    .map(({ artistId, role, billingOrder, stage }) => ({ artistId, role, billingOrder, stage }))
  promoterIds.value = [...loaded.promoterIds]
  void loadNames('artists', lineupIds.value)
  void loadNames('promoters', promoterIds.value)
}

async function loadNames(kind: NameKind, ids: number[]) {
  const missing = ids.filter((id) => !names.value[kind].has(id))
  if (missing.length === 0) return
  for (const [id, row] of await fetchNames(kind, missing)) names.value[kind].set(id, row)
}

function nameOf(kind: NameKind, id: number): string {
  const known = names.value[kind]
  if (!known.has(id)) return `#${id}`
  return known.get(id)?.name ?? `#${id} (not found)`
}

/** A new act goes to the bottom of the bill: the headliner when the lineup is empty, else support. */
function addArtist(row: NamedRow) {
  names.value.artists.set(row.id, row)
  const last = Math.max(-1, ...lineup.value.map((artist) => artist.billingOrder))
  lineup.value.push({
    artistId: row.id,
    role: lineup.value.length === 0 ? 'HEADLINER' : 'SUPPORT',
    billingOrder: last + 1,
    stage: null,
  })
}

function removeArtist(artistId: number) {
  lineup.value = lineup.value.filter((artist) => artist.artistId !== artistId)
}

function addPromoter(row: NamedRow) {
  names.value.promoters.set(row.id, row)
  promoterIds.value.push(row.id)
}

function removePromoter(id: number) {
  promoterIds.value = promoterIds.value.filter((promoterId) => promoterId !== id)
}

async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await action()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

watch(
  () => props.eventId,
  async (id) => {
    event.value = null
    storedFeatures.value = null
    error.value = null
    if (id === null) return
    loading.value = true
    try {
      void loadFeatures(id)
      show(await fetchEvent(id))
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

function save() {
  const current = event.value
  if (!current) return
  return run(async () => {
    // Before the event PUT, so the pin on `features` is in the answer the editor shows.
    if (featuresChanged.value) {
      const order = PARTY_FEATURES.map((feature) => feature.slug as string)
      const slugs = [...features.value].sort((a, b) => order.indexOf(a) - order.indexOf(b))
      showFeatures(await setEventFeatures(current.id, slugs))
    }
    const saved = await updateEvent(
      current.id,
      toEventRequest(current, {
        eventType: eventType.value,
        genre: genre.value,
        subtitle: subtitle.value,
        description: description.value,
        artists: lineup.value,
        promoterIds: promoterIds.value,
      }),
    )
    show(saved)
    emit('saved', saved)
  })
}

function unpin(field: string) {
  const current = event.value
  if (!current) return
  return run(async () => {
    await unpinField(current.id, field)
    // The DELETE answers 204, so the pins are read back rather than guessed.
    show(await fetchEvent(current.id))
    if (field === 'features') await loadFeatures(current.id)
  })
}
</script>

<template>
  <Sheet :open="eventId !== null" @update:open="(open) => open || emit('close')">
    <SheetContent class="w-full overflow-y-auto sm:max-w-md">
      <SheetHeader>
        <SheetTitle>{{ event?.title ?? 'Event' }}</SheetTitle>
        <SheetDescription>
          <template v-if="event">{{ event.eventDate }} · {{ event.sourceId }}</template>
          <template v-else>Event {{ eventId }}</template>
        </SheetDescription>
      </SheetHeader>

      <div class="flex flex-col gap-4 px-4">
        <p v-if="loading" class="text-muted-foreground">Loading the event…</p>
        <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>

        <form v-if="event" class="flex flex-col gap-4" @submit.prevent="save">
          <label class="flex flex-col gap-1.5" for="edit-subtitle">
            <span class="text-sm font-medium">Subtitle</span>
            <Input
              id="edit-subtitle"
              v-model="subtitle"
              maxlength="500"
              name="subtitle"
              placeholder="No subtitle"
            />
          </label>
          <label class="flex flex-col gap-1.5" for="edit-description">
            <span class="text-sm font-medium">Description</span>
            <Textarea
              id="edit-description"
              v-model="description"
              aria-describedby="edit-description-hint"
              class="max-h-80"
              maxlength="10000"
              name="description"
              placeholder="No description"
              rows="4"
            />
            <span id="edit-description-hint" class="text-xs text-muted-foreground">
              Write it in your own words. Do not paste the venue's text: its licence can forbid that
              or ask for a credit.
            </span>
          </label>
          <label class="flex flex-col gap-1.5" for="edit-event-type">
            <span class="text-sm font-medium">Event type</span>
            <select
              v-model="eventType"
              class="h-8 rounded-lg border border-input bg-transparent px-2.5 text-sm focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
              id="edit-event-type"
              name="eventType"
            >
              <option v-for="type in EVENT_TYPES" :key="type" :value="type">{{ type }}</option>
            </select>
          </label>
          <label class="flex flex-col gap-1.5" for="edit-genre">
            <span class="text-sm font-medium">Genre</span>
            <Input id="edit-genre" v-model="genre" name="genre" placeholder="No genre" />
            <span class="text-xs text-muted-foreground">
              Tags now: {{ event.genreTags.length ? event.genreTags.join(', ') : 'none' }}
            </span>
          </label>

          <fieldset class="flex flex-col gap-2" aria-describedby="features-hint">
            <legend class="mb-2 text-sm font-medium">Kind of night</legend>
            <p v-if="featuresError" class="text-sm text-destructive" role="alert">
              {{ featuresError }}
            </p>
            <ul v-else-if="storedFeatures" class="flex flex-col gap-1.5">
              <li
                v-for="feature in PARTY_FEATURES"
                :key="feature.slug"
                class="flex flex-col gap-0.5"
                data-testid="feature-row"
              >
                <label
                  :for="`edit-feature-${feature.slug}`"
                  class="flex items-center gap-2 text-sm"
                >
                  <input
                    :id="`edit-feature-${feature.slug}`"
                    v-model="features"
                    :value="feature.slug"
                    class="size-4"
                    name="features"
                    type="checkbox"
                  />
                  {{ feature.label }}
                </label>
                <span
                  v-for="cue in cuesOf(feature.slug)"
                  :key="cue"
                  class="pl-6 text-xs text-muted-foreground"
                  data-testid="feature-cue"
                >
                  Unsure: “{{ cue }}”
                </span>
              </li>
            </ul>
            <span id="features-hint" class="text-xs text-muted-foreground">
              Saving a changed set pins <code>features</code>, so the importer keeps it, and clears
              the uncertain cues.
            </span>
          </fieldset>

          <section class="flex flex-col gap-2" aria-labelledby="lineup-heading">
            <h3 id="lineup-heading" class="text-sm font-medium">Lineup</h3>
            <ol v-if="lineup.length" class="flex flex-col gap-1.5">
              <li
                v-for="artist in lineup"
                :key="artist.artistId"
                class="flex flex-wrap items-center gap-x-2 gap-y-1"
                data-testid="lineup-row"
              >
                <!-- On a phone a long name takes its own line, above its role and Remove. -->
                <span
                  class="min-w-40 flex-1 truncate text-sm"
                  :title="nameOf('artists', artist.artistId)"
                >
                  {{ nameOf('artists', artist.artistId) }}
                  <span v-if="artist.stage" class="text-xs text-muted-foreground">
                    · {{ artist.stage }}
                  </span>
                </span>
                <select
                  v-model="artist.role"
                  :aria-label="`Role of ${nameOf('artists', artist.artistId)}`"
                  class="h-7 rounded-lg border border-input bg-transparent px-2 text-xs focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
                >
                  <option v-for="role in ARTIST_ROLES" :key="role" :value="role">{{ role }}</option>
                </select>
                <Button
                  :aria-label="`Remove ${nameOf('artists', artist.artistId)}`"
                  size="xs"
                  type="button"
                  variant="outline"
                  @click="removeArtist(artist.artistId)"
                >
                  Remove
                </Button>
              </li>
            </ol>
            <p v-else class="text-sm text-muted-foreground">No artists.</p>
            <NamePicker
              id="add-artist"
              :exclude="lineupIds"
              kind="artists"
              label="Add an artist"
              @pick="addArtist"
            />
          </section>

          <section class="flex flex-col gap-2" aria-labelledby="promoters-heading">
            <h3 id="promoters-heading" class="text-sm font-medium">Promoters</h3>
            <ul v-if="promoterIds.length" class="flex flex-col gap-1.5">
              <li
                v-for="id in promoterIds"
                :key="id"
                class="flex items-center gap-2"
                data-testid="promoter-row"
              >
                <span class="min-w-0 flex-1 truncate text-sm" :title="nameOf('promoters', id)">
                  {{ nameOf('promoters', id) }}
                </span>
                <Button
                  :aria-label="`Remove ${nameOf('promoters', id)}`"
                  size="xs"
                  type="button"
                  variant="outline"
                  @click="removePromoter(id)"
                >
                  Remove
                </Button>
              </li>
            </ul>
            <p v-else class="text-sm text-muted-foreground">No promoters.</p>
            <NamePicker
              id="add-promoter"
              :exclude="promoterIds"
              kind="promoters"
              label="Add a promoter"
              @pick="addPromoter"
            />
          </section>

          <section class="flex flex-col gap-2" aria-labelledby="pins-heading">
            <h3 id="pins-heading" class="text-sm font-medium">Pinned fields</h3>
            <p class="text-xs text-muted-foreground">
              A saved change pins its field, and the importer keeps the value until it is unpinned.
            </p>
            <ul v-if="event.pinnedFields.length" class="flex flex-col gap-1">
              <li
                v-for="field in event.pinnedFields"
                :key="field"
                class="flex items-center justify-between gap-2"
              >
                <Badge class="font-mono" variant="secondary">{{ field }}</Badge>
                <Button
                  :aria-label="`Unpin ${field}`"
                  :disabled="busy"
                  size="xs"
                  type="button"
                  variant="outline"
                  @click="unpin(field)"
                >
                  Unpin
                </Button>
              </li>
            </ul>
            <p v-else class="text-sm text-muted-foreground">None.</p>
          </section>

          <SheetFooter class="px-0">
            <Button :disabled="busy" type="submit">Save</Button>
          </SheetFooter>
        </form>
      </div>
    </SheetContent>
  </Sheet>
</template>
