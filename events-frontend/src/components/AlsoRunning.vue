<script lang="ts" setup>
import { useI18n } from 'vue-i18n'
import type { EventSummary } from '@/api/types'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import { useCompactView } from '@/composables/useCompactView'
import { CARD_GRID_CLASS, CARD_LIST_CLASS } from '@/lib/utils'

/**
 * The long runs a day list folds away on the days between their opening and their last days
 * (`lib/longRuns.ts`). Collapsed at the end of the list, as the same cards or rows the list above
 * draws, so a run looks the same whether it is folded or not.
 */
withDefaults(
  defineProps<{
    events: EventSummary[]
    /** The heading level of the cards above it, which belongs to the page — see `EventCard.vue`. */
    as?: 'h2' | 'h3' | 'h4'
  }>(),
  { as: 'h3' },
)

const { t } = useI18n()
const { compact } = useCompactView()
</script>

<template>
  <details v-if="events.length" class="border-t border-border pt-4">
    <summary class="cursor-pointer text-body font-medium">
      {{ t('events.alsoRunning.title') }}
      <span class="text-muted-foreground tabular-nums">({{ events.length }})</span>
    </summary>
    <div v-if="compact" :class="['mt-4', CARD_LIST_CLASS]">
      <EventRow v-for="event in events" :key="event.slug" :event="event" :as="as" />
    </div>
    <div v-else :class="['mt-4', CARD_GRID_CLASS]">
      <EventCard v-for="event in events" :key="event.slug" :event="event" :as="as" />
    </div>
  </details>
</template>
