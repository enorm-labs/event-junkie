<script lang="ts" setup>
import { computed } from 'vue'
import type { VenueListItem, VenueSummary } from '@/api/types'
import EventPoster from '@/components/EventPoster.vue'
import PosterCardShell from '@/components/PosterCardShell.vue'
import { districtLabel } from '@/lib/districts'
import { imageCredit } from '@/lib/imageCredit'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'

const props = withDefaults(
  defineProps<{
    /** The count and types show when the venue list sent them; an event's embedded venue has neither. */
    venue: VenueSummary &
      Partial<
        Pick<
          VenueListItem,
          | 'upcomingEventCount'
          | 'upcomingNext30DaysCount'
          | 'venueTypes'
          | 'characterTags'
          | 'programmeFamilies'
          | 'imported'
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
</script>

<template>
  <PosterCardShell
    :to="localePath(`/venues/${venue.slug}`)"
    :image-url="venue.imageUrl"
    :image-sources="venue.imageSources"
    :alt="venue.name ?? ''"
    :title="creditTitle"
  >
    <template #fallback>
      <EventPoster
        :title="venue.name"
        :eyebrow="districtLabel(venue.district)"
        :families="venue.programmeFamilies"
        :venue-types="venue.venueTypes"
      />
    </template>
    <div class="min-w-0 space-y-1">
      <component :is="as" class="truncate text-lede font-semibold">
        {{ venue.name }}
      </component>
      <p v-if="location" class="truncate text-body text-muted-foreground">{{ location }}</p>
      <p v-else-if="venue.city" class="truncate text-body text-muted-foreground">
        {{ venue.city }}
      </p>
      <p v-if="facts" class="truncate text-body text-muted-foreground">{{ facts }}</p>
      <!-- A venue we do not import has no count; its state is one word in the meta line (#2766). -->
      <p
        v-if="venue.imported === false"
        class="text-body text-muted-foreground"
        data-testid="venue-not-imported"
      >
        {{ t('venues.notImported') }}
      </p>
      <p v-else-if="upcoming.length" class="text-body text-muted-foreground">
        <template v-for="(part, i) in upcoming" :key="part">
          <template v-if="i">{{ ' ' }}</template>
          <span class="whitespace-nowrap">{{ i < upcoming.length - 1 ? `${part} ·` : part }}</span>
        </template>
      </p>
    </div>
  </PosterCardShell>
</template>
