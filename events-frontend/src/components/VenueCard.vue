<script lang="ts" setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { VenueListItem, VenueSummary } from '@/api/types'
import CachedImage from '@/components/CachedImage.vue'
import EventPoster from '@/components/EventPoster.vue'
import { districtLabel } from '@/lib/districts'
import { imageCredit } from '@/lib/imageCredit'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { CARD_CLASS, CARD_POSTER_CLASS } from '@/lib/utils'
import { useViewportFocus } from '@/composables/useViewportFocus'
import { useI18n } from 'vue-i18n'

const props = withDefaults(
  defineProps<{
    /** The count, types and capacity show when the venue list sent them; an event's embedded venue has none. */
    venue: VenueSummary &
      Partial<
        Pick<
          VenueListItem,
          | 'upcomingEventCount'
          | 'upcomingNext30DaysCount'
          | 'venueTypes'
          | 'characterTags'
          | 'capacity'
          | 'programmeFamilies'
        >
      >
    /** Heading level for the card's name — see the same prop on `EventCard.vue` for why. */
    as?: 'h2' | 'h3' | 'h4'
  }>(),
  { as: 'h3' },
)

// A single "where" line: street address then district, skipping whatever is missing.
const location = computed(() =>
  [props.venue.address, districtLabel(props.venue.district)].filter(Boolean).join(' · '),
)

const { t } = useI18n()
const { formatVenueFacts, formatUpcoming } = useFormat()
const facts = computed(() => formatVenueFacts(props.venue))
const upcoming = computed(() => formatUpcoming(props.venue))

// The credit belongs on the image itself, so it travels with it wherever the card is reused.
const creditTitle = computed(() => {
  const credit = imageCredit(props.venue)
  return credit
    ? t('common.imageCredit.title', {
        attribution: credit.attribution,
        licence: credit.licenceLabel,
      })
    : undefined
})

const localePath = useLocalePath()

// Reveals the poster while the card passes the middle of a touch screen, where `:hover` is dead.
const { el: posterEl, focused: posterFocused } = useViewportFocus()
</script>

<template>
  <RouterLink :to="localePath(`/venues/${venue.slug}`)" :class="CARD_CLASS">
    <div ref="posterEl" :data-focus="posterFocused || undefined" :class="CARD_POSTER_CLASS">
      <CachedImage
        v-if="venue.imageUrl"
        :src="venue.imageUrl"
        :sources="venue.imageSources"
        :alt="venue.name ?? ''"
        :title="creditTitle"
        aspect="aspect-poster"
        sizes="(min-width: 640px) 474px, 100vw"
        img-class="grayscale transition duration-300 group-hover:grayscale-0 group-data-focus/poster:grayscale-0"
      />
      <EventPoster
        v-else
        :title="venue.name"
        :eyebrow="districtLabel(venue.district)"
        :families="venue.programmeFamilies"
        :venue-types="venue.venueTypes"
      />
    </div>
    <div class="min-w-0 space-y-1">
      <component :is="as" class="truncate text-lede font-semibold">
        {{ venue.name }}
      </component>
      <p v-if="location" class="truncate text-body text-muted-foreground">{{ location }}</p>
      <p v-else-if="venue.city" class="truncate text-body text-muted-foreground">
        {{ venue.city }}
      </p>
      <p v-if="facts" class="truncate text-body text-muted-foreground">{{ facts }}</p>
      <p v-if="upcoming.length" class="text-body text-muted-foreground">
        <template v-for="(part, i) in upcoming" :key="part">
          <template v-if="i">{{ ' ' }}</template>
          <span class="whitespace-nowrap">{{ i < upcoming.length - 1 ? `${part} ·` : part }}</span>
        </template>
      </p>
    </div>
  </RouterLink>
</template>
