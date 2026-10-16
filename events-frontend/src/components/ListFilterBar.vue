<script lang="ts" setup>
/**
 * The filter bar of the venue and promoter lists (#2694), on the events bar's first row. The `more`
 * slot holds the selects; a page without one shows no toggle. The `actions` slot ends the first row.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import FilterSearchRow from '@/components/FilterSearchRow.vue'
import { PANEL_CLASS } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    placeholder: string
    /** How many filters behind "More filters" are set, so a closed section still says it narrows the list. */
    moreCount?: number
  }>(),
  { moreCount: 0 },
)

const slots = defineSlots<{ more?: () => unknown; actions?: () => unknown }>()

const { t } = useI18n()

// Closed unless the URL already sets one of its filters, as on the events bar (#2347).
const moreOpen = ref(props.moreCount > 0)

// No label, no toggle: a page without selects has nothing to open.
const moreLabel = computed(() => {
  if (!slots.more) return undefined
  return props.moreCount
    ? t('events.filters.moreCount', { n: props.moreCount })
    : t('events.filters.more')
})
</script>

<template>
  <div :class="PANEL_CLASS">
    <FilterSearchRow
      controls="more-filters"
      :expanded="moreOpen"
      :label="moreLabel"
      :placeholder="placeholder"
      @toggle="moreOpen = !moreOpen"
    >
      <template #actions><slot name="actions" /></template>
    </FilterSearchRow>

    <div v-if="slots.more" v-show="moreOpen" id="more-filters" class="w-full">
      <slot name="more" />
    </div>
  </div>
</template>
