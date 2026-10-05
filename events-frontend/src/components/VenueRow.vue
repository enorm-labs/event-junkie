<script lang="ts" setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { VenueListItem, VenueSummary } from '@/api/types'
import { districtLabel } from '@/lib/districts'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'

/** One venue as a line of text, for the compact view — the counterpart to `EventRow`. */
const props = withDefaults(
  defineProps<{
    /** The count line shows when the venue list sent one; a distance takes its place. */
    venue: VenueSummary &
      Partial<
        Pick<
          VenueListItem,
          | 'upcomingEventCount'
          | 'upcomingNext30DaysCount'
          | 'venueTypes'
          | 'characterTags'
          | 'capacity'
        >
      >
    /** Heading level, which belongs to the page rather than to the row — see `EventCard.vue`. */
    as?: 'h2' | 'h3' | 'h4'
    /** How far the venue is from the visitor's chosen origin, already formatted. */
    distance?: string
  }>(),
  { as: 'h3' },
)

const location = computed(() =>
  [props.venue.address, districtLabel(props.venue.district)].filter(Boolean).join(' · '),
)

const { formatVenueFacts, formatUpcoming } = useFormat()
// One meta line, as on the card: where it is, then what it is.
const line = computed(() =>
  [location.value || props.venue.city, formatVenueFacts(props.venue)].filter(Boolean).join(' · '),
)
// The card's order: the count goes under the facts, where its two numbers have the width.
const upcoming = computed(() => (props.distance ? '' : formatUpcoming(props.venue)))

const localePath = useLocalePath()
</script>

<template>
  <RouterLink :to="localePath(`/venues/${venue.slug}`)" class="group block py-3">
    <div class="flex items-baseline gap-3">
      <component
        :is="as"
        :title="venue.name ?? undefined"
        class="min-w-0 flex-1 truncate text-body font-medium group-hover:underline group-hover:underline-offset-4"
      >
        {{ venue.name }}
      </component>
      <span v-if="distance" class="shrink-0 text-meta text-muted-foreground tabular-nums">
        {{ distance }}
      </span>
    </div>
    <p v-if="line" class="truncate text-meta text-muted-foreground">{{ line }}</p>
    <p v-if="upcoming" class="truncate text-meta text-muted-foreground tabular-nums">
      {{ upcoming }}
    </p>
  </RouterLink>
</template>
