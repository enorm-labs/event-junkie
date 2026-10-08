<script lang="ts" setup>
/**
 * The filter bar's two date bounds. Two native date inputs rather than a range picker: the value is
 * already the ISO date the BFF wants, and `min`/`max` express "to cannot precede from". Neither
 * bound is floored at today: past dates are the archive. The browser's calendar follows dark mode
 * via the `color-scheme` on `:root`/`.dark` in main.css.
 */
import { useI18n } from 'vue-i18n'
import BaseInput from '@/components/BaseInput.vue'
import { useEventFilters } from '@/composables/useEventFilters'

const { t } = useI18n()
const { queryString, applyFilters } = useEventFilters()

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
    <BaseInput
      :aria-label="t('events.filters.earliestDate')"
      :max="queryString('to') || undefined"
      :model-value="queryString('from')"
      type="date"
      @change="applyDate('from', ($event.target as HTMLInputElement).value)"
      @click="openDatePicker"
    />
    <span class="text-body text-muted-foreground">–</span>
    <BaseInput
      :aria-label="t('events.filters.latestDate')"
      :min="queryString('from') || undefined"
      :model-value="queryString('to')"
      type="date"
      @change="applyDate('to', ($event.target as HTMLInputElement).value)"
      @click="openDatePicker"
    />
  </div>
</template>
