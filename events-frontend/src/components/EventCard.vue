<script lang="ts" setup>
import { Sparkles } from '@lucide/vue'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { EventSummary } from '@/api/types'
import CachedImage from '@/components/CachedImage.vue'
import EventPoster from '@/components/EventPoster.vue'
import BaseBadge from '@/components/BaseBadge.vue'
import { eventLabel, formatPrice } from '@/lib/format'
import { useEventState } from '@/composables/useEventState'
import { useFormat } from '@/composables/useFormat'
import { useGenreFamilies } from '@/composables/useGenreFamilies'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'
import {
  CARD_CLASS,
  CARD_LEAD_CLASS,
  CARD_LEAD_POSTER_ASPECT,
  CARD_POSTER_CLASS,
} from '@/lib/utils'
import { useViewportFocus } from '@/composables/useViewportFocus'

const props = withDefaults(
  defineProps<{
    event: EventSummary
    /**
     * Heading level for the card's title: which level is a property of the page, not the card. On
     * the home and detail pages a section `h2` sits above the grid, so `h3`; on `/events` the
     * cards hang off the page `h1`, so `h2`, or axe's `heading-order` trips. Sets the heading
     * element, not the card's root.
     */
    as?: 'h2' | 'h3' | 'h4'
    /** The first card of a list, whose poster is a likely LCP element: see `CachedImage`. */
    priority?: boolean
    /**
     * The curated pick that leads `/events` (#1262): both grid columns, a 2:1 poster, an "Our pick"
     * pill on the poster and a larger title. The heading level stays `as`, because a bigger tile is not a
     * higher rank in the outline.
     */
    lead?: boolean
  }>(),
  { as: 'h3', priority: false, lead: false },
)

const { formatEventDates, formatEventTime, formatShortDate } = useFormat()

const posterDate = computed(() => formatShortDate(props.event.eventDate))
const genreFamilies = useGenreFamilies()

const { eventType, isLive, language, state, timeHint } = useEventState(() => props.event)

// Type and genre are different taxonomies, but both are filter values in the query string, so
// they read as one list (#1248).
const taxonomy = computed(() =>
  [eventType.value, language.value, ...(props.event.genreTags ?? [])].filter(Boolean),
)

const localePath = useLocalePath()

const { t } = useI18n()

// Reveals the poster while the card passes the middle of a touch screen, where `:hover` is dead.
const { el: posterEl, focused: posterFocused } = useViewportFocus()
</script>

<template>
  <RouterLink
    :to="localePath(`/events/${event.slug}`)"
    :class="[CARD_CLASS, lead && CARD_LEAD_CLASS]"
  >
    <div
      ref="posterEl"
      :data-focus="posterFocused || undefined"
      :class="[CARD_POSTER_CLASS, 'relative']"
    >
      <!-- `sizes` describes the real slot: the whole viewport below `sm`, about 474 px in the
           two-column grid above, and both columns, 960 px, for the lead. A wrong value silently
           downloads the wrong file. -->
      <CachedImage
        v-if="event.imageUrl"
        :src="event.imageUrl"
        :sources="event.imageSources"
        :alt="event.title ?? ''"
        :priority="priority"
        :aspect="lead ? CARD_LEAD_POSTER_ASPECT : 'aspect-poster'"
        :sizes="lead ? '(min-width: 640px) 960px, 100vw' : '(min-width: 640px) 474px, 100vw'"
        img-class="grayscale transition duration-300 group-hover:grayscale-0 group-data-focus/poster:grayscale-0"
      />
      <EventPoster
        v-else
        :title="event.title"
        :eyebrow="posterDate"
        :event-type="event.eventType"
        :families="genreFamilies(event.genreTags)"
        :aspect="lead ? CARD_LEAD_POSTER_ASPECT : undefined"
      />
      <!-- The lead's pill says why this tile is larger: a person chose it (#1262). A sibling of the
           image, not inside it, so it keeps its colour while the poster is grey. -->
      <BaseBadge
        v-if="lead"
        variant="primary"
        class="absolute top-3 left-3 z-10 inline-flex items-center gap-1.5 font-mono text-body tracking-eyebrow uppercase"
      >
        <Sparkles aria-hidden="true" class="size-4" />
        {{ t('events.card.ourPick') }}
      </BaseBadge>
    </div>
    <div class="min-w-0 space-y-1">
      <div class="flex min-w-0 items-center gap-2">
        <span v-if="isLive" class="relative flex size-2 shrink-0">
          <span
            class="absolute inline-flex size-full rounded-full bg-primary opacity-75 motion-safe:animate-ping"
          />
          <span class="relative inline-flex size-2 rounded-full bg-primary" />
          <span class="sr-only">{{ t('events.card.onNow') }}</span>
        </span>
        <!--
            Both lines are `truncate`d, so the native `title` spells each out on hover, sharing
            `eventLabel` with the calendar cells. Scoped to the clipped elements, not the card.
          -->
        <component
          :is="as"
          :title="eventLabel(event.title, event.venue?.name)"
          :class="['truncate font-semibold', lead ? 'text-section' : 'text-lede']"
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
