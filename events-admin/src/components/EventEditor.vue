<script setup lang="ts">
import { ref, watch } from 'vue'

import {
  type AdminEvent,
  EVENT_TYPES,
  type EventType,
  fetchEvent,
  toEventRequest,
  unpinField,
  updateEvent,
} from '@/api/events'
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

const props = defineProps<{ eventId: number | null }>()
const emit = defineEmits<{ close: []; saved: [event: AdminEvent] }>()

const event = ref<AdminEvent | null>(null)
const eventType = ref<EventType>('OTHER')
const genre = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref<string | null>(null)

function show(loaded: AdminEvent) {
  event.value = loaded
  eventType.value = loaded.eventType
  genre.value = loaded.genre ?? ''
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
    error.value = null
    if (id === null) return
    loading.value = true
    try {
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
    const saved = await updateEvent(
      current.id,
      toEventRequest(current, { eventType: eventType.value, genre: genre.value }),
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
