<script lang="ts" setup>
/**
 * The filter bar's two date bounds. Two native date inputs rather than a range picker: the value is
 * already the ISO date the BFF wants, and `min`/`max` express "to cannot precede from". Neither
 * bound is floored at today: past dates are the archive. The browser's calendar follows dark mode
 * via the `color-scheme` on `:root`/`.dark` in main.css.
 */
import { computed, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import BaseInput from '@/components/BaseInput.vue'
import { useEventFilters } from '@/composables/useEventFilters'
import { cn, FIELD_CLASS } from '@/lib/utils'

const { t } = useI18n()
const { queryString, applyFilters } = useEventFilters()
const ids = { from: useId(), to: useId() }

/**
 * Each bound names itself inside its box, as the dropdowns do with "All types". A date input
 * ignores `placeholder`, and iOS draws an empty one blank.
 */
const bounds = computed(() => [
  {
    bound: 'from' as const,
    id: ids.from,
    label: t('events.filters.from'),
    max: queryString('to') || undefined,
  },
  {
    bound: 'to' as const,
    id: ids.to,
    label: t('events.filters.to'),
    min: queryString('from') || undefined,
  },
])

/**
 * Opens the browser's calendar on a click anywhere in the field; Chrome otherwise opens it from
 * the icon only. `showPicker` is absent on older browsers, hence the optional call.
 */
function openDatePicker(event: MouseEvent) {
  const input = event.currentTarget as HTMLInputElement & { showPicker?: () => void }
  input.showPicker?.()
}

/** Any date choice ends On now, so one time button is pressed at most. */
function applyDate(bound: 'from' | 'to', value: string) {
  applyFilters({ [bound]: value, now: '' })
}
</script>

<template>
  <div class="flex items-center gap-2">
    <label
      v-for="field in bounds"
      :key="field.bound"
      :class="
        cn(
          FIELD_CLASS,
          'flex min-w-0 items-center gap-1.5 focus-within:ring-3 focus-within:ring-ring/50',
        )
      "
      :for="field.id"
    >
      <span class="text-muted-foreground">{{ field.label }}</span>
      <BaseInput
        :id="field.id"
        :max="field.max"
        :min="field.min"
        :model-value="queryString(field.bound)"
        class="h-full min-w-0 rounded-none border-0 bg-transparent px-0 focus-visible:ring-0"
        type="date"
        @change="applyDate(field.bound, ($event.target as HTMLInputElement).value)"
        @click="openDatePicker"
      />
    </label>
  </div>
</template>
