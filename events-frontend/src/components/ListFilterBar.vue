<script lang="ts" setup>
/**
 * The filter bar of the venue and promoter lists (#2694), in the events bar's shape: the search,
 * then "More filters", then "Clear all". The `more` slot holds the selects; a page without one
 * shows no toggle. The `actions` slot ends the first row.
 */
import { ChevronDown } from '@lucide/vue'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import ClearAllFilters from '@/components/ClearAllFilters.vue'
import SearchInput from '@/components/SearchInput.vue'
import { useSearchDraft } from '@/composables/useSearchDraft'
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
const { search, applySearch } = useSearchDraft()

// Closed unless the URL already sets one of its filters, as on the events bar (#2347).
const moreOpen = ref(props.moreCount > 0)
</script>

<template>
  <div :class="PANEL_CLASS">
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
        v-if="slots.more"
        :aria-expanded="moreOpen"
        aria-controls="more-filters"
        type="button"
        variant="outline"
        @click="moreOpen = !moreOpen"
      >
        {{ moreCount ? t('events.filters.moreCount', { n: moreCount }) : t('events.filters.more') }}
        <ChevronDown
          :class="['transition-transform', { 'rotate-180': moreOpen }]"
          aria-hidden="true"
        />
      </Button>
      <ClearAllFilters />
      <slot name="actions" />
    </div>

    <div v-if="slots.more" v-show="moreOpen" id="more-filters" class="w-full">
      <slot name="more" />
    </div>
  </div>
</template>
