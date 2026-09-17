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
     * Heading level for the card's title. The card is a heading in its own right wherever it
     * appears, but *which* level is a property of the page, not of the card: on the home page and
     * the detail pages a `SectionLabel` `h2` sits above the grid, so `h3` is correct; on `/events`
     * the cards hang directly off the page `h1`, so anything below `h2` skips a level and trips
     * axe's `heading-order`. Named `as` to match `SectionLabel`, but narrowed to the levels a
     * card can legitimately take — note that it sets the *heading* element, not the card's root.
     */
    as?: 'h2' | 'h3' | 'h4'
  }>(),
  { as: 'h3' },
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

// `OTHER` is the importers' catch-all — it tells a reader nothing the card doesn't already say,
// so it's dropped rather than spending a pill on it. Every other type earns its place.
const eventType = computed(() =>
  props.event.eventType && props.event.eventType !== 'OTHER'
    ? formatEventType(props.event.eventType)
    : null,
)

const isPast = computed(() => isPastEvent(props.event))
// A weekender in its second night: started, not over (ADR-029).
const isRunning = computed(() => isRunningEvent(props.event))

// An event on today gets a pulsing "live" dot — it stands out in the Upcoming feed and on
// venue/artist pages, and reinforces liveness in the Tonight feed. Self-contained, so any caller
// gets it for free. A running weekender is on today too; a cancelled or moved one is not live here.
const status = computed(() => formatEventStatus(props.event.status))
const isLive = computed(
  () =>
    !status.value &&
    ((Boolean(props.event.eventDate) && props.event.eventDate === todayIso()) || isRunning.value),
)

/**
 * The one word on a card that changes what the reader does next, and the only coloured thing in the
 * meta line. Past wins the slot: "Sold out" on last month's gig is stale, not informative. A
 * cancelled, postponed or relocated night comes next — it is not running and it is not for sale,
 * whatever else the row says (#1550) — then running, because "since Friday" is what a reader of a
 * Sunday listing needs to know first.
 *
 * The colour is emphasis on top of the word, never instead of it (WCAG 1.4.1).
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

// Type and genre are different taxonomies — one kind of night, many kinds of music — but both are
// filter values in the query string, so they read as one list rather than two rows of pills (#1248).
const taxonomy = computed(() => [eventType.value, ...(props.event.genreTags ?? [])].filter(Boolean))

const localePath = useLocalePath()

const { t } = useI18n()

// Reveals the poster while the card passes the middle of a touch screen, where `:hover` is dead.
const { el: posterEl, focused: posterFocused } = useViewportFocus()
</script>

<template>
  <RouterLink :to="localePath(`/events/${event.slug}`)" :class="CARD_CLASS">
    <div ref="posterEl" :data-focus="posterFocused || undefined" :class="CARD_POSTER_CLASS">
      <!--
        `sizes` describes the real slot: a `max-w-5xl` grid is one column below `sm` and two above
        it, so a card is the whole viewport, then about 474 px. A wrong value silently downloads the
        wrong file.
      -->
      <CachedImage
        v-if="event.imageUrl"
        :src="event.imageUrl"
        :sources="event.imageSources"
        :alt="event.title ?? ''"
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
            Both lines are `truncate`d, so a long one is cut off with no way to read the rest.
            The native `title` tooltip spells each out on hover — the same affordance the
            calendar cells got, sharing `eventLabel` so the two can't drift. Scoped to the
            clipped elements rather than the whole card, so hovering the card doesn't pop a
            tooltip over information that is already fully visible.
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
