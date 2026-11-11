<script setup lang="ts">
import { EVENT_FEATURES, type EventFeatures } from '@/api/events'

/**
 * The "kind of night" checkboxes and the cues the importer was unsure of. The editor loads and
 * saves the set, because its save must run before the event PUT.
 */
const props = defineProps<{ stored: EventFeatures | null; error: string | null }>()
const features = defineModel<string[]>({ required: true })

function cuesOf(slug: string): string[] {
  return (props.stored?.uncertain ?? [])
    .filter((cue) => cue.feature === slug)
    .map((cue) => cue.phrase)
}
</script>

<template>
  <fieldset class="flex flex-col gap-2" aria-describedby="features-hint">
    <legend class="mb-2 text-sm font-medium">Kind of night</legend>
    <p v-if="error" class="text-sm text-destructive" role="alert">
      {{ error }}
    </p>
    <ul v-else-if="stored" class="flex flex-col gap-1.5">
      <li
        v-for="feature in EVENT_FEATURES"
        :key="feature.slug"
        class="flex flex-col gap-0.5"
        data-testid="feature-row"
      >
        <label :for="`edit-feature-${feature.slug}`" class="flex items-center gap-2 text-sm">
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
      Saving a changed set pins <code>features</code>, so the importer keeps it, and clears the
      uncertain cues.
    </span>
  </fieldset>
</template>
