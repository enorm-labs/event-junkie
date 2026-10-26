<script lang="ts" setup>
/**
 * A day's events as the home page shows them (#2321, #2594): capped with "Show all", a run of
 * weeks folded into "Also running". Home and Following render it (#3922); each keeps its own
 * heading, and the state of "Show all" in `v-model:expanded`.
 */
import { computed, nextTick, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { ChevronDown } from '@lucide/vue'
import AlsoRunning from '@/components/AlsoRunning.vue'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import { Button } from '@/components/ui/button'
import type { EventSummary } from '@/api/types'
import { useCompactView } from '@/composables/useCompactView'
import { todayIso } from '@/lib/format'
import { splitDayList } from '@/lib/longRuns'
import { CARD_GRID_CLASS, CARD_LIST_CLASS } from '@/lib/utils'

const props = defineProps<{ events: EventSummary[] }>()
const expanded = defineModel<boolean>('expanded', { default: false })

const { t } = useI18n()
const { compact } = useCompactView()

// A teaser, not the day's whole list: on a busy day a phone scrolled for minutes (#2321). Even, so
// the two-column grid ends on a full row. A compact row is about a sixth of a card's height.
const CAP = { poster: 6, compact: 12 }
const cap = computed(() => (compact.value ? CAP.compact : CAP.poster))
const split = computed(() => splitDayList(props.events, todayIso()))
const capped = computed(() => !expanded.value && split.value.cards.length > cap.value)
const visible = computed(() =>
  capped.value ? split.value.cards.slice(0, cap.value) : split.value.cards,
)

const list = useTemplateRef<HTMLElement>('list')

// The button disappears with the click, so focus moves to the first event it revealed rather than
// falling back to the document.
async function expand() {
  const firstHidden = cap.value
  expanded.value = true
  await nextTick()
  // Each card and row is itself the link.
  const revealed = list.value?.children.item(firstHidden)
  if (revealed instanceof HTMLElement) revealed.focus()
}
</script>

<template>
  <div v-if="compact" ref="list" :class="CARD_LIST_CLASS">
    <EventRow v-for="event in visible" :key="event.slug" :event="event" />
  </div>
  <div v-else ref="list" :class="CARD_GRID_CLASS">
    <EventCard v-for="event in visible" :key="event.slug" :event="event" />
  </div>
  <Button v-if="capped" variant="outline" @click="expand">
    {{ t('home.showAllToday', { count: split.cards.length }) }}
    <ChevronDown aria-hidden="true" data-icon="inline-end" />
  </Button>
  <AlsoRunning :events="split.alsoRunning" />
</template>
