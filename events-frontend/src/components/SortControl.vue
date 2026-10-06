<script lang="ts" setup>
import { ArrowDown, ArrowUp, ArrowUpDown } from '@lucide/vue'
import { useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'

export interface SortDirection {
  /** The `sort` query value, or `''` for an order that is the API's default. */
  value: string
  ascending: boolean
  /** Ends the accessible name ("Date, latest first"), or is the button's text when the order has no label ("Z–A"). */
  label: string
}

/**
 * One order: a single `value`, or two `directions` where both answer a question a visitor asks.
 * A press on an inactive two-way order picks its first direction.
 */
export type SortOption =
  | { label: string; value: string; directions?: never }
  | { label?: string; directions: [SortDirection, SortDirection]; value?: never }

/**
 * The one sort control every list shares: an icon in place of a visible "Sort" label, and a button
 * per order. Two or three orders read faster as buttons than behind a select, and the pressed one
 * names the order on screen (#360). Pressing the active two-way order flips it (#2763).
 */
const props = defineProps<{
  options: SortOption[]
  modelValue: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const labelId = useId()
const { t } = useI18n()

function activeDirection(option: SortOption): SortDirection | undefined {
  return option.directions?.find(({ value }) => value === props.modelValue)
}

function isActive(option: SortOption): boolean {
  return option.directions ? !!activeDirection(option) : option.value === props.modelValue
}

function shownDirection(option: SortOption): SortDirection | undefined {
  return activeDirection(option) ?? option.directions?.[0]
}

function text(option: SortOption): string {
  return option.label ?? shownDirection(option)!.label
}

/** A labelled two-way order names its direction; otherwise the visible text already does. */
function accessibleName(option: SortOption): string | undefined {
  const direction = shownDirection(option)
  return option.label && direction ? `${option.label}, ${direction.label}` : undefined
}

function press(option: SortOption) {
  if (!option.directions) {
    if (option.value !== props.modelValue) emit('update:modelValue', option.value)
    return
  }
  const [first, second] = option.directions
  emit('update:modelValue', activeDirection(option) === first ? second.value : first.value)
}
</script>

<template>
  <div class="flex items-center gap-1.5">
    <ArrowUpDown aria-hidden="true" class="size-4 shrink-0 text-muted-foreground" />
    <span :id="labelId" class="sr-only">{{ t('common.sort.label') }}</span>
    <div :aria-labelledby="labelId" class="flex gap-1" role="group">
      <Button
        v-for="option in options"
        :key="option.value ?? option.directions[0].value"
        :aria-label="accessibleName(option)"
        :aria-pressed="isActive(option)"
        :variant="isActive(option) ? 'default' : 'outline'"
        size="sm"
        type="button"
        @click="press(option)"
      >
        {{ text(option) }}
        <template v-if="activeDirection(option)">
          <ArrowUp
            v-if="activeDirection(option)!.ascending"
            aria-hidden="true"
            data-icon="inline-end"
          />
          <ArrowDown v-else aria-hidden="true" data-icon="inline-end" />
        </template>
      </Button>
    </div>
  </div>
</template>
