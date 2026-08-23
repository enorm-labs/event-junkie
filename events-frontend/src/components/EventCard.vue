<script lang="ts" setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { EventSummary } from '@/api/types'
import BaseBadge from '@/components/BaseBadge.vue'
import { eventLabel, formatPrice, formatTime, todayIso } from '@/lib/format'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'
import { CARD_CLASS } from '@/lib/utils'

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

const { formatDate, formatEventType } = useFormat()

// `OTHER` is the importers' catch-all — it tells a reader nothing the card doesn't already say,
// so it's dropped rather than spending a pill on it. Every other type earns its place.
const eventType = computed(() =>
  props.event.eventType && props.event.eventType !== 'OTHER'
    ? formatEventType(props.event.eventType)
    : null,
)

// An event happening today gets a pulsing "live" dot — it stands out in the Upcoming feed and on
// venue/artist pages, and reinforces liveness in the Tonight feed. Self-contained, so any caller
// gets it for free.
const isLive = computed(
  () => Boolean(props.event.eventDate) && props.event.eventDate === todayIso(),
)

const localePath = useLocalePath()

const { t } = useI18n()
</script>

<template>
  <RouterLink
    :to="localePath(`/events/${event.slug}`)"
    :class="CARD_CLASS"
  >
    <img
      v-if="event.imageUrl"
      :alt="event.title ?? ''"
      :src="event.imageUrl"
      class="size-20 shrink-0 rounded-lg object-cover grayscale transition duration-300 group-hover:grayscale-0"
      loading="lazy"
    />
    <div class="min-w-0 flex-1 space-y-1">
      <div class="flex items-start justify-between gap-2">
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
            class="truncate leading-tight font-semibold"
          >
            {{ event.title }}
          </component>
        </div>
        <BaseBadge v-if="event.soldOut" class="shrink-0" variant="destructive">{{
          t('events.card.soldOut')
        }}</BaseBadge>
        <BaseBadge v-else-if="event.free" class="shrink-0" variant="success">{{
          t('events.card.free')
        }}</BaseBadge>
      </div>
      <p
        v-if="event.subtitle"
        :title="event.subtitle"
        class="truncate text-sm text-muted-foreground"
      >
        {{ event.subtitle }}
      </p>
      <p class="text-sm text-muted-foreground">
        {{ formatDate(event.eventDate) }}
        <template v-if="event.startTime"> · {{ formatTime(event.startTime) }}</template>
        <template v-if="event.venue?.name"> · {{ event.venue.name }}</template>
      </p>
      <div class="flex flex-wrap items-center gap-1.5 pt-0.5">
        <!--
          Type and genre are different taxonomies — one kind of night, many kinds of music — so
          the type pill is outlined rather than filled. Same row, same size, but the eye can tell
          "Club night" from "Techno" without reading them.
        -->
        <BaseBadge v-if="eventType" variant="outline">{{ eventType }}</BaseBadge>
        <BaseBadge v-for="tag in event.genreTags" :key="tag">{{ tag }}</BaseBadge>
        <span
          v-if="formatPrice(event.pricePresale, event.priceCurrency)"
          class="ml-auto text-sm font-medium"
        >
          {{ formatPrice(event.pricePresale, event.priceCurrency) }}
        </span>
      </div>
    </div>
  </RouterLink>
</template>
