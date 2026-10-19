<script lang="ts" setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { EventSummary } from '@/api/types'
import BaseBadge from '@/components/BaseBadge.vue'
import PickBadge from '@/components/PickBadge.vue'
import { eventLabel, formatPrice } from '@/lib/format'
import { useEventState } from '@/composables/useEventState'
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
    /**
     * Whether a featured event shows its "Our pick" pill (#3065). The page decides: `/events` marks
     * a pick, and the home page, the map and the calendar do not.
     */
    marksPick?: boolean
  }>(),
  { as: 'h3', marksPick: false },
)

const { formatShortDate, formatEventTime } = useFormat()
const { t } = useI18n()
const localePath = useLocalePath()

const { eventType, isLive, language, state, timeHint } = useEventState(() => props.event)

// Venue, kind of night and genres read as one list, as they do on the card (#1248).
const meta = computed(() =>
  [
    props.event.venue?.name,
    eventType.value,
    language.value,
    ...(props.event.genreTags ?? []),
  ].filter(Boolean),
)

const price = computed(() => formatPrice(props.event.pricePresale, props.event.priceCurrency))

const showsPick = computed(() => props.marksPick && props.event.featured === true)
</script>

<template>
  <RouterLink :to="localePath(`/events/${event.slug}`)" class="group block py-3">
    <!-- A pick's line may wrap, so on a phone the title moves under the pill instead of shrinking to a word. -->
    <div :class="['flex items-baseline gap-x-3', showsPick && 'flex-wrap gap-y-1']">
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
        <span class="sr-only">{{ t('events.card.onNow') }}</span>
      </span>
      <PickBadge v-if="showsPick" small class="shrink-0 self-center" />
      <component
        :is="as"
        :title="eventLabel(event.title, event.venue?.name)"
        :class="[
          'min-w-0 flex-1 truncate text-body font-medium group-hover:underline group-hover:underline-offset-4',
          showsPick && 'basis-40',
        ]"
      >
        {{ event.title }}
      </component>
      <span v-if="price" class="shrink-0 text-meta font-medium text-foreground">{{ price }}</span>
    </div>
    <p v-if="state || meta.length" class="truncate text-meta text-muted-foreground">
      <!-- The row has no poster to carry it, so the festival's pill opens the second line and the
           title keeps its width on a phone (#3273). -->
      <BaseBadge
        v-if="event.festival?.name"
        :title="t('events.card.partOf', { name: event.festival.name })"
        class="mr-2 py-0"
        data-testid="festival-badge"
      >
        {{ event.festival.name }}
      </BaseBadge>
      <span v-if="state" :class="['font-medium', state.class]">{{ state.label }}</span>
      <span v-if="state && meta.length" aria-hidden="true"> · </span>
      <span v-if="meta.length">{{ meta.join(' · ') }}</span>
    </p>
  </RouterLink>
</template>
