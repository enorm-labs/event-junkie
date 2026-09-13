<script lang="ts" setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { VenueSummary } from '@/api/types'
import { districtLabel } from '@/lib/districts'
import { useLocalePath } from '@/composables/useLocalePath'

/** One venue as a line of text, for the compact view — the counterpart to `EventRow`. */
const props = withDefaults(
  defineProps<{
    venue: VenueSummary
    /** Heading level, which belongs to the page rather than to the row — see `EventCard.vue`. */
    as?: 'h2' | 'h3' | 'h4'
  }>(),
  { as: 'h3' },
)

const location = computed(() =>
  [props.venue.address, districtLabel(props.venue.district)].filter(Boolean).join(' · '),
)

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
    </div>
    <p v-if="location || venue.city" class="truncate text-meta text-muted-foreground">
      {{ location || venue.city }}
    </p>
  </RouterLink>
</template>
