<script lang="ts" setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { EventSummary } from '@/api/types'
import CachedImage from '@/components/CachedImage.vue'
import EventPoster from '@/components/EventPoster.vue'
import { eventLabel, formatPrice, isPastEvent, isRunningEvent, todayIso } from '@/lib/format'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'
import { CARD_CLASS, CARD_POSTER_CLASS } from '@/lib/utils'
import { useViewportFocus } from '@/composables/useViewportFocus'

const props = withDefaults(
  defineProps<{
    event: EventSummary
    /**
     * Heading level for the card's title: which level is a property of the page, not the card. On
     * the home and detail pages a `SectionLabel` `h2` sits above the grid, so `h3`; on `/events` the
     * cards hang off the page `h1`, so `h2`, or axe's `heading-order` trips. Sets the heading
     * element, not the card's root.
     */
    as?: 'h2' | 'h3' | 'h4'
    /** The first card of a list, whose poster is a likely LCP element: see `CachedImage`. */
    priority?: boolean
  }>(),
  { as: 'h3', priority: false },
)

const {
  formatEventDates,
  formatEventTime,
  eventTimeHint,
  formatEventType,
  formatEventStatus,
  formatWeekday,
} = useFormat()

// Set only when the time shown is the BFF's guess (#1384); the card carries it as a title.
const timeHint = computed(() => eventTimeHint(props.event))

// `OTHER` is the importers' catch-all and tells a reader nothing, so it earns no pill.
const eventType = computed(() =>
  props.event.eventType && props.event.eventType !== 'OTHER'
    ? formatEventType(props.event.eventType)
    : null,
)

const isPast = computed(() => isPastEvent(props.event))
// A weekender in its second night: started, not over (ADR-029).
const isRunning = computed(() => isRunningEvent(props.event))

// An event on today gets a pulsing "live" dot, self-contained so any caller gets it. A running
// weekender is on today too; a cancelled or moved one is not live.
const status = computed(() => formatEventStatus(props.event.status, props.event.relocatedTo))
const isLive = computed(
  () =>
    !status.value &&
    ((Boolean(props.event.eventDate) && props.event.eventDate === todayIso()) || isRunning.value),
)

/**
 * The one word on a card that changes what the reader does next, and the only coloured thing in
 * the meta line. Past wins the slot ("Sold out" on last month's gig is stale); a cancelled,
 * postponed or relocated night next (#1550); then running, because "since Friday" is what a
 * Sunday reader needs first. Colour is emphasis on top of the word, never instead (WCAG 1.4.1).
 */
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

// Type and genre are different taxonomies, but both are filter values in the query string, so
// they read as one list (#1248).
const taxonomy = computed(() => [eventType.value, ...(props.event.genreTags ?? [])].filter(Boolean))

const localePath = useLocalePath()

const { t } = useI18n()

// Reveals the poster while the card passes the middle of a touch screen, where `:hover` is dead.
const { el: posterEl, focused: posterFocused } = useViewportFocus()
</script>

<template>
  <RouterLink :to="localePath(`/events/${event.slug}`)" :class="CARD_CLASS">
    <div ref="posterEl" :data-focus="posterFocused || undefined" :class="CARD_POSTER_CLASS">
      <!-- `sizes` describes the real slot: the whole viewport below `sm`, about 474 px in the
           two-column grid above. A wrong value silently downloads the wrong file. -->
      <CachedImage
        v-if="event.imageUrl"
        :src="event.imageUrl"
        :sources="event.imageSources"
        :alt="event.title ?? ''"
        :priority="priority"
        aspect="aspect-[3/2]"
        sizes="(min-width: 640px) 474px, 100vw"
        img-class="grayscale transition duration-300 group-hover:grayscale-0 group-data-focus/poster:grayscale-0"
      />
      <EventPoster v-else :title="event.title" />
    </div>
    <div class="min-w-0 space-y-1">
      <div class="flex min-w-0 items-center gap-2">
        <span v-if="isLive" class="relative flex size-2 shrink-0">
          <span
            class="absolute inline-flex size-full rounded-full bg-primary opacity-75 motion-safe:animate-ping"
          />
          <span class="relative inline-flex size-2 rounded-full bg-primary" />
          <span class="sr-only">{{ t('events.card.liveTonight') }}</span>
        </span>
        <!--
            Both lines are `truncate`d, so the native `title` spells each out on hover, sharing
            `eventLabel` with the calendar cells. Scoped to the clipped elements, not the card.
          -->
        <component
          :is="as"
          :title="eventLabel(event.title, event.venue?.name)"
          class="truncate text-lede font-semibold"
        >
          {{ event.title }}
        </component>
      </div>
      <p
        v-if="event.subtitle"
        :title="event.subtitle"
        class="truncate text-body text-muted-foreground"
      >
        {{ event.subtitle }}
      </p>
      <p class="text-body text-muted-foreground">
        {{ formatEventDates(event) }}
        ·
        <span :title="timeHint ?? undefined">
          {{ formatEventTime(event) }}
          <span v-if="timeHint" class="sr-only">({{ timeHint }})</span>
        </span>
        <template v-if="event.venue?.name"> · {{ event.venue.name }}</template>
      </p>
      <p
        v-if="state || taxonomy.length || formatPrice(event.pricePresale, event.priceCurrency)"
        class="flex flex-wrap items-baseline gap-x-2 text-body text-muted-foreground"
      >
        <span v-if="state" :class="['font-medium', state.class]">{{ state.label }}</span>
        <span v-if="state && taxonomy.length" aria-hidden="true">·</span>
        <span v-if="taxonomy.length">{{ taxonomy.join(' · ') }}</span>
        <span
          v-if="formatPrice(event.pricePresale, event.priceCurrency)"
          class="ml-auto text-body font-medium text-foreground"
        >
          {{ formatPrice(event.pricePresale, event.priceCurrency) }}
        </span>
      </p>
    </div>
  </RouterLink>
</template>
