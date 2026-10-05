<script lang="ts" setup>
/**
 * A filter that takes several values: a field-styled trigger naming the selection, opening a list
 * of checkboxes and a clear action. It holds no URL logic. It emits the whole new selection in
 * option order, so the URL, and the BFF's cache key, do not depend on the order of the ticks.
 * With `counts`, each option shows how many results ticking it leaves, and one that leaves none is
 * disabled rather than hidden, so the list does not jump (#2671).
 */
import { computed, useId } from 'vue'
import { ChevronDown } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn, FIELD_CLASS } from '@/lib/utils'

export interface FilterOption {
  value: string
  label: string
}

const props = defineProps<{
  options: readonly FilterOption[]
  selected: readonly string[]
  /** The accessible name of the trigger and the list, e.g. "Filter by event type". */
  label: string
  /** The trigger text when nothing is selected. */
  allLabel: string
  /** The trigger text for two or more selected values. */
  countLabel: (count: number) => string
  clearLabel: string
  /** A line above the options for what they cannot say: how they combine, or what no option matches. */
  hint?: string
  /** Results per option if it is ticked; an option absent from it leaves none. Omitted: no counts. */
  counts?: Readonly<Record<string, number>>
  class?: string
}>()

const hintId = useId()

const emit = defineEmits<{ change: [values: string[]] }>()

const summary = computed(() => {
  const chosen = props.options.filter((option) => props.selected.includes(option.value))
  const [first, ...rest] = chosen
  if (!first) return props.allLabel
  return rest.length ? props.countLabel(chosen.length) : first.label
})

function count(value: string): number | undefined {
  return props.counts ? (props.counts[value] ?? 0) : undefined
}

/** A selected option stays enabled, so it can always be unticked. */
function unavailable(value: string): boolean {
  return count(value) === 0 && !props.selected.includes(value)
}

function toggle(value: string, checked: boolean) {
  const next = new Set(props.selected)
  if (checked) next.add(value)
  else next.delete(value)
  emit(
    'change',
    props.options.filter((option) => next.has(option.value)).map((option) => option.value),
  )
}
</script>

<template>
  <Popover>
    <!-- eslint-disable shadcn/require-static-classes -- FIELD_CLASS lives in lib/utils, shared with BaseInput and BaseSelect -->
    <PopoverTrigger
      :aria-label="`${label}: ${summary}`"
      :class="cn(FIELD_CLASS, 'flex items-center justify-between gap-2 text-left', props.class)"
    >
      <!-- eslint-enable shadcn/require-static-classes -->
      <span class="truncate">{{ summary }}</span>
      <ChevronDown aria-hidden="true" class="size-4 shrink-0 text-muted-foreground" />
    </PopoverTrigger>
    <PopoverContent :collision-padding="8" align="start" class="w-56">
      <p v-if="hint" :id="hintId" class="mb-2 text-meta text-muted-foreground">{{ hint }}</p>
      <fieldset :aria-describedby="hint ? hintId : undefined" class="flex flex-col gap-1">
        <legend class="sr-only">{{ label }}</legend>
        <label
          v-for="option in options"
          :key="option.value"
          class="flex h-8 items-center gap-2 has-disabled:text-muted-foreground"
        >
          <input
            :checked="selected.includes(option.value)"
            :disabled="unavailable(option.value)"
            class="size-4 rounded border-border accent-primary outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            type="checkbox"
            @change="toggle(option.value, ($event.target as HTMLInputElement).checked)"
          />
          {{ option.label }}
          <span
            v-if="count(option.value) !== undefined"
            class="ml-auto text-meta text-muted-foreground tabular-nums"
            >{{ count(option.value) }}</span
          >
        </label>
      </fieldset>
      <Button
        :disabled="!selected.length"
        size="sm"
        type="button"
        variant="ghost"
        @click="emit('change', [])"
      >
        {{ clearLabel }}
      </Button>
    </PopoverContent>
  </Popover>
</template>
