<script lang="ts" setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { EventSummary } from '@/api/types'
import { eventLabel, formatPrice, isPastEvent, isRunningEvent, todayIso } from '@/lib/format'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'

/**
 * One event as a line of text, for the compact view (#1371).
 *
 * It carries what `EventCard` carries below its poster, and nothing else: no surface, no border of
 * its own, no image. The container draws the hairline between two rows, so a row that ends a list
 * has no trailing rule.
 */
const props = withDefaults(
  defineProps<{
    event: EventSummary
    /** Heading level, which belongs to the page rather than to the row — see `EventCard.vue`. */
    as?: 'h2' | 'h3' | 'h4'
  }>(),
  { as: 'h3' },
)

const {
  formatShortDate,
  formatEventTime,
  eventTimeHint,
  formatEventType,
  formatEventStatus,
  formatWeekday,
} = useFormat()
const timeHint = computed(() => eventTimeHint(props.event))
const { t } = useI18n()
const localePath = useLocalePath()

const isPast = computed(() => isPastEvent(props.event))
const isRunning = computed(() => isRunningEvent(props.event))
const status = computed(() => formatEventStatus(props.event.status))
const isLive = computed(
  () =>
    !status.value &&
    ((Boolean(props.event.eventDate) && props.event.eventDate === todayIso()) || isRunning.value),
)

// The same one word, chosen the same way as on the card: past beats status beats running beats sold out beats free.
const state = computed(() => {
  if (isPast.value) return { label: t('events.card.past'), class: 'text-muted-foreground' }
  if (status.value) return { label: status.value, class: 'text-destructive' }
  if (isRunning.value)
    return {
      label: t('events.card.runningSince', { day: formatWeekday(props.event.eventDate) }),
      class: 'text-primary',
    }
  if (props.event.soldOut) return { label: t('events.card.soldOut'), class: 'text-destructive' }
  if (props.event.free) return { label: t('events.card.free'), class: 'text-success' }
  return null
})

const eventType = computed(() =>
  props.event.eventType && props.event.eventType !== 'OTHER'
    ? formatEventType(props.event.eventType)
    : null,
)

// Venue, kind of night and genres read as one list, as they do on the card (#1248).
const meta = computed(() =>
  [props.event.venue?.name, eventType.value, ...(props.event.genreTags ?? [])].filter(Boolean),
)

const price = computed(() => formatPrice(props.event.pricePresale, props.event.priceCurrency))
</script>

<template>
  <RouterLink :to="localePath(`/events/${event.slug}`)" class="group block py-3">
    <div class="flex items-baseline gap-3">
      <span class="shrink-0 text-meta text-muted-foreground tabular-nums">
        {{ formatShortDate(event.eventDate) }}
        ·
        <span :title="timeHint ?? undefined">
          {{ formatEventTime(event) }}
          <span v-if="timeHint" class="sr-only">({{ timeHint }})</span>
        </span>
      </span>
      <span v-if="isLive" class="relative flex size-2 shrink-0">
        <span
          class="absolute inline-flex size-full rounded-full bg-primary opacity-75 motion-safe:animate-ping"
        />
        <span class="relative inline-flex size-2 rounded-full bg-primary" />
        <span class="sr-only">{{ t('events.card.liveTonight') }}</span>
      </span>
      <component
        :is="as"
        :title="eventLabel(event.title, event.venue?.name)"
        class="min-w-0 flex-1 truncate text-body font-medium group-hover:underline group-hover:underline-offset-4"
      >
        {{ event.title }}
      </component>
      <span v-if="price" class="shrink-0 text-meta font-medium text-foreground">{{ price }}</span>
    </div>
    <p v-if="state || meta.length" class="truncate text-meta text-muted-foreground">
      <span v-if="state" :class="['font-medium', state.class]">{{ state.label }}</span>
      <span v-if="state && meta.length" aria-hidden="true"> · </span>
      <span v-if="meta.length">{{ meta.join(' · ') }}</span>
    </p>
  </RouterLink>
</template>
