<script setup lang="ts">
import { computed, ref } from 'vue'

import { type EventSource, type SourceUpdate, updateSource } from '@/api/eventSources'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

const props = defineProps<{ source: EventSource }>()
const emit = defineEmits<{ saved: [source: EventSource]; cancel: [] }>()

const enabled = ref(props.source.enabled)
// An emptied number input sets its model to '', so both refs can hold a string.
const interval = ref<number | string>(props.source.importIntervalMinutes)
const maxRetries = ref<number | string>(props.source.maxRetries)
// The slug keeps the label ids unique on a page that has one row per source.
const id = `edit-${props.source.slug}`
const saving = ref(false)
const error = ref<string | null>(null)

/** The importer's own limits (`EventSourceUpdateRequest.kt`), checked before the request. */
function invalid(): string | null {
  const minutes = Number(interval.value)
  if (interval.value === '' || !Number.isInteger(minutes) || minutes < 1) {
    return 'The interval must be a whole number of minutes, at least 1.'
  }
  const retries = Number(maxRetries.value)
  if (maxRetries.value === '' || !Number.isInteger(retries) || retries < 0) {
    return 'Max retries must be a whole number, at least 0.'
  }
  return null
}

// Only the changed fields go out: a field left out stays as the importer has it.
const changes = computed<SourceUpdate>(() => {
  const update: SourceUpdate = {}
  if (enabled.value !== props.source.enabled) update.enabled = enabled.value
  if (Number(interval.value) !== props.source.importIntervalMinutes) {
    update.importIntervalMinutes = Number(interval.value)
  }
  if (Number(maxRetries.value) !== props.source.maxRetries) {
    update.maxRetries = Number(maxRetries.value)
  }
  return update
})

async function save() {
  error.value = invalid()
  if (error.value) return
  saving.value = true
  try {
    emit('saved', await updateSource(props.source.slug, changes.value))
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <form class="flex flex-col gap-4 px-4" novalidate @submit.prevent="save">
    <!-- The lint rule wants both: the control inside its label, and a for/id pair. -->
    <label :for="`${id}-enabled`" class="flex items-center gap-2 text-sm">
      <input
        :id="`${id}-enabled`"
        v-model="enabled"
        class="size-4"
        name="enabled"
        type="checkbox"
      />
      Enabled
    </label>
    <label :for="`${id}-interval`" class="flex flex-col gap-1 text-sm">
      Interval in minutes
      <Input
        :id="`${id}-interval`"
        v-model.number="interval"
        min="1"
        name="importIntervalMinutes"
        step="1"
        type="number"
      />
      <span class="text-xs text-muted-foreground">1440 is once a day.</span>
    </label>
    <label :for="`${id}-retries`" class="flex flex-col gap-1 text-sm">
      Max retries
      <Input
        :id="`${id}-retries`"
        v-model.number="maxRetries"
        min="0"
        name="maxRetries"
        step="1"
        type="number"
      />
    </label>
    <p v-if="error" class="text-xs whitespace-normal text-destructive" role="alert">
      {{ error }}
    </p>
    <div class="flex gap-2">
      <Button :disabled="saving || !Object.keys(changes).length" size="sm" type="submit">
        Save
      </Button>
      <Button :disabled="saving" size="sm" type="button" variant="outline" @click="emit('cancel')">
        Cancel
      </Button>
    </div>
  </form>
</template>
