<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, shallowRef, watch } from 'vue'

import {
  type AdminEvent,
  createEvent,
  emptyNewEventForm,
  fetchEventsOn,
  EVENT_TYPES,
  type NewEventForm,
  toCreateRequest,
} from '@/api/events'
import { type AdminVenue, fetchAllVenues } from '@/api/venues'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

const selectClass =
  'h-8 rounded-lg border border-input bg-transparent px-2.5 text-sm focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none'

const venues = shallowRef<AdminVenue[]>([])
const venuesError = ref<string | null>(null)
const form = reactive<NewEventForm>(emptyNewEventForm())
const busy = ref(false)
const error = ref<string | null>(null)
const created = ref<{ id: number; title: string; eventDate: string; sourceId: string } | null>(null)

const venue = computed(() => venues.value.find((v) => v.id === form.venueId) ?? null)

/**
 * The events the venue already holds on the date (#347). The sourceId only refuses an exact
 * repeat, so a night entered with another title, or one an importer also covers, is caught here.
 * [needsConfirm] holds the button that was pressed until "Save anyway" sends it.
 */
const sameDay = shallowRef<AdminEvent[]>([])
const sameDayError = ref<string | null>(null)
const needsConfirm = ref<{ addAnother: boolean } | null>(null)
let checkRun = 0

/** Loads [sameDay] for the current venue and date. A slower, older answer is dropped. */
async function checkSameDay(): Promise<void> {
  const run = ++checkRun
  const { venueId, eventDate } = form
  if (venueId == null || !eventDate) {
    sameDay.value = []
    sameDayError.value = null
    return
  }
  try {
    const events = await fetchEventsOn(venueId, eventDate)
    if (run !== checkRun) return
    sameDay.value = events
    sameDayError.value = null
  } catch (e) {
    if (run !== checkRun) return
    sameDay.value = []
    sameDayError.value = e instanceof Error ? e.message : String(e)
  }
}

watch(
  () => [form.venueId, form.eventDate],
  () => {
    needsConfirm.value = null
    void checkSameDay()
  },
)

function startTime(event: AdminEvent): string {
  return event.startTime?.slice(0, 5) ?? 'no start time'
}

onMounted(async () => {
  try {
    venues.value = await fetchAllVenues()
  } catch (e) {
    venuesError.value = e instanceof Error ? e.message : String(e)
  }
})

/**
 * Enter submits with the first button, "Save and add another". The check runs again first; when
 * it finds events, or fails, the save waits for "Save anyway".
 */
async function onSubmit(event: Event) {
  const submitter = (event as SubmitEvent).submitter
  const addAnother = submitter?.getAttribute('name') !== 'save'
  busy.value = true
  error.value = null
  await checkSameDay()
  busy.value = false
  if (sameDay.value.length > 0 || sameDayError.value) {
    needsConfirm.value = { addAnother }
    return
  }
  await save(addAnother)
}

function saveAnyway() {
  const pending = needsConfirm.value
  if (!pending) return
  needsConfirm.value = null
  return save(pending.addAnother)
}

/**
 * Posts the form. With [addAnother], every field but the venue and the date is cleared and the
 * title takes the focus, so a batch of one venue's programme is typed without the mouse.
 */
async function save(addAnother: boolean) {
  busy.value = true
  error.value = null
  try {
    const request = toCreateRequest(form, venue.value?.slug ?? '')
    const saved = await createEvent(request)
    created.value = {
      id: saved.id,
      title: saved.title,
      eventDate: saved.eventDate,
      sourceId: saved.sourceId,
    }
    if (addAnother) {
      const { venueId, eventDate } = form
      Object.assign(form, emptyNewEventForm(), { venueId, eventDate })
      await nextTick()
      document.getElementById('new-event-title')?.focus()
    }
    // The venue and the date did not change, so the watcher does not run: the saved event is new.
    await checkSameDay()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <p class="max-w-2xl text-sm text-muted-foreground">
    For a venue with no importer. The event is stored with a <code>manual:</code> source ID, so the
    data-quality report counts it under <code>manual</code> and no import overwrites it.
  </p>

  <p v-if="venuesError" class="text-destructive" role="alert">{{ venuesError }}</p>

  <form class="grid max-w-2xl gap-4 sm:grid-cols-2" @submit.prevent="onSubmit">
    <label class="flex flex-col gap-1.5 sm:col-span-2" for="new-event-venue">
      <span class="text-sm font-medium">Venue</span>
      <select
        id="new-event-venue"
        v-model="form.venueId"
        :class="selectClass"
        name="venueId"
        required
      >
        <option disabled :value="null">Pick a venue</option>
        <option v-for="v in venues" :key="v.id" :value="v.id">
          {{ v.name }}{{ v.closedOn ? ` (closed ${v.closedOn})` : '' }}
        </option>
      </select>
    </label>

    <label class="flex flex-col gap-1.5 sm:col-span-2" for="new-event-title">
      <span class="text-sm font-medium">Title</span>
      <Input id="new-event-title" v-model="form.title" maxlength="255" name="title" required />
    </label>

    <label class="flex flex-col gap-1.5" for="new-event-date">
      <span class="text-sm font-medium">Date</span>
      <Input id="new-event-date" v-model="form.eventDate" name="eventDate" required type="date" />
    </label>
    <label class="flex flex-col gap-1.5" for="new-event-type">
      <span class="text-sm font-medium">Event type</span>
      <select id="new-event-type" v-model="form.eventType" :class="selectClass" name="eventType">
        <option v-for="type in EVENT_TYPES" :key="type" :value="type">{{ type }}</option>
      </select>
    </label>

    <label class="flex flex-col gap-1.5" for="new-event-doors">
      <span class="text-sm font-medium">Doors</span>
      <Input id="new-event-doors" v-model="form.doorsTime" name="doorsTime" type="time" />
    </label>
    <label class="flex flex-col gap-1.5" for="new-event-start">
      <span class="text-sm font-medium">Start</span>
      <Input id="new-event-start" v-model="form.startTime" name="startTime" type="time" />
    </label>

    <label class="flex flex-col gap-1.5 sm:col-span-2" for="new-event-genre">
      <span class="text-sm font-medium">Genre</span>
      <Input id="new-event-genre" v-model="form.genre" name="genre" placeholder="No genre" />
    </label>

    <label class="flex flex-col gap-1.5" for="new-event-presale">
      <span class="text-sm font-medium">Presale price (EUR)</span>
      <Input
        id="new-event-presale"
        v-model="form.pricePresale"
        inputmode="decimal"
        name="pricePresale"
      />
    </label>
    <label class="flex flex-col gap-1.5" for="new-event-box-office">
      <span class="text-sm font-medium">Box office price (EUR)</span>
      <Input
        id="new-event-box-office"
        v-model="form.priceBoxOffice"
        inputmode="decimal"
        name="priceBoxOffice"
      />
    </label>

    <label class="flex items-center gap-2 sm:col-span-2" for="new-event-free">
      <input id="new-event-free" v-model="form.free" name="free" type="checkbox" />
      <span class="text-sm font-medium">Free entry</span>
    </label>

    <label class="flex flex-col gap-1.5 sm:col-span-2" for="new-event-ticket-url">
      <span class="text-sm font-medium">Ticket URL</span>
      <Input id="new-event-ticket-url" v-model="form.ticketUrl" name="ticketUrl" type="url" />
    </label>
    <label class="flex flex-col gap-1.5 sm:col-span-2" for="new-event-source-url">
      <span class="text-sm font-medium">Source URL</span>
      <Input
        id="new-event-source-url"
        v-model="form.sourceUrl"
        name="sourceUrl"
        placeholder="The Instagram post or flyer the event comes from"
        type="url"
      />
    </label>

    <div class="flex flex-col gap-2 sm:col-span-2">
      <div v-if="sameDay.length > 0" class="text-sm" data-testid="same-day">
        <p class="font-medium">Already on this date at {{ venue?.name ?? 'this venue' }}:</p>
        <ul class="list-disc pl-5">
          <li v-for="event in sameDay" :key="event.id">
            <span class="font-mono text-muted-foreground">{{ startTime(event) }}</span>
            {{ event.title }}
          </li>
        </ul>
      </div>
      <p v-if="sameDayError" class="text-destructive" role="alert">
        Could not check the events on this date. {{ sameDayError }}
      </p>
      <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
      <div
        v-if="needsConfirm"
        class="flex flex-wrap items-center gap-2 rounded-lg border border-input p-2"
        role="group"
        aria-labelledby="new-event-confirm"
      >
        <p id="new-event-confirm" class="text-sm">
          {{
            sameDayError
              ? 'Save without the check?'
              : 'Is this a different event? Save it next to the ones above.'
          }}
        </p>
        <Button :disabled="busy" name="saveAnyway" type="button" @click="saveAnyway">
          Save anyway
        </Button>
      </div>
      <p v-if="created" class="text-sm" role="status">
        Created event {{ created.id }}: {{ created.title }} on {{ created.eventDate }}
        <span class="font-mono text-muted-foreground">({{ created.sourceId }})</span>
      </p>
      <div class="flex flex-wrap gap-2">
        <!-- First in the form, so Enter in any field saves and starts the next event. -->
        <Button :disabled="busy" name="saveAndAddAnother" type="submit">
          Save and add another
        </Button>
        <Button :disabled="busy" name="save" type="submit" variant="outline">Save</Button>
      </div>
    </div>
  </form>
</template>
