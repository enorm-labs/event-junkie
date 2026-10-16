<script lang="ts" setup>
/**
 * The first row of a filter bar (#3410): the `?q=` search, the toggle for the filters behind it,
 * and "Clear all". The events bar and the list bar each own their panel and the toggle's state; a
 * bar without a panel passes no `label` and shows no toggle. The `actions` slot ends the row.
 */
import { ChevronDown } from '@lucide/vue'
import { useTemplateRef } from 'vue'
import { Button } from '@/components/ui/button'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import SearchInput from '@/components/SearchInput.vue'
import { useSearchDraft } from '@/composables/useSearchDraft'

withDefaults(
  defineProps<{
    placeholder: string
    label?: string
    expanded?: boolean
    /** The id of the panel the toggle opens; none when it opens a dialog instead. */
    controls?: string
    haspopup?: 'dialog'
    compact?: boolean
  }>(),
  { label: undefined, expanded: false, controls: undefined, haspopup: undefined, compact: false },
)

const emit = defineEmits<{ toggle: [] }>()
defineSlots<{ actions?: () => unknown }>()

const { search, applySearch } = useSearchDraft()

const toggleButton = useTemplateRef<{ $el: HTMLElement }>('toggle')
defineExpose({ focusToggle: () => toggleButton.value?.$el.focus() })
</script>

<template>
  <div class="flex w-full flex-wrap items-center gap-2">
    <form class="min-w-0 flex-1 basis-48" role="search" @submit.prevent="applySearch">
      <SearchInput
        v-model="search"
        :placeholder="placeholder"
        @change="applySearch"
        @clear="applySearch"
      />
    </form>
    <Button
      v-if="label"
      ref="toggle"
      :aria-controls="controls"
      :aria-expanded="expanded"
      :aria-haspopup="haspopup"
      type="button"
      variant="outline"
      @click="emit('toggle')"
    >
      {{ label }}
      <ChevronDown
        :class="['transition-transform', { 'rotate-180': expanded }]"
        aria-hidden="true"
      />
    </Button>
    <ClearAllFilters :compact="compact" />
    <slot name="actions" />
  </div>
</template>
