<script lang="ts" setup>
import { RouterLink } from 'vue-router'
import type { EventPage } from '@/api/types'
import { Button } from '@/components/ui/button'
import type { ImageSource } from '@/api/types'

import CachedImage from '@/components/CachedImage.vue'
import ImageCreditLine from '@/components/ImageCreditLine.vue'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import { useCompactView } from '@/composables/useCompactView'
import SectionLabel from '@/components/SectionLabel.vue'
import type { ImageCredit } from '@/lib/imageCredit'
import { CARD_GRID_CLASS, CARD_LIST_CLASS } from '@/lib/utils'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'

/**
 * Presentational shell shared by the artist, venue and promoter detail pages: the loading /
 * not-found / error scaffold, the hero header and both event feeds. Slots: `meta` for
 * entity-specific header metadata under the name, default for content between the header and
 * the events feed.
 */
defineProps<{
  /** Entity kind label, e.g. "Artist" — shown above the name and in the not-found heading. */
  kind: string
  /** Entity fetch state (from `useAsync`). */
  loading: boolean
  error: string | null
  notFound: boolean
  /** Whether the entity has loaded — guards the content branch. */
  ready: boolean
  /** Copy shown under the "<kind> not found" heading. */
  notFoundText: string
  /** Resolved entity display fields. */
  name?: string | null
  imageUrl?: string | null
  imageSources?: ImageSource[] | null
  intrinsicWidth?: number | null
  intrinsicHeight?: number | null
  /** Who to credit for the image, or null where it carries no credit (#1275). */
  credit?: ImageCredit | null
  /** Upcoming-events feed state (from `useEventSearch`). */
  events: EventPage | null
  eventsLoading: boolean
  eventsError: string | null
  /** Copy shown when the events feed is empty. */
  emptyText: string
  /**
   * The past-events feed, with no loading or error state: the section appears only once it has
   * events.
   */
  pastEvents?: EventPage | null
}>()

// The document title is not set here: each detail view owns its page meta, because the
// description and image come from its entity (lib/pageMeta.ts, `usePageMeta` in each view).

const localePath = useLocalePath()

const { t } = useI18n()
// The compact view is a global display preference — see `useCompactView`.
const { compact } = useCompactView()
</script>

<template>
  <main class="mx-auto max-w-3xl space-y-8 p-4 sm:p-8">
    <p v-if="loading" class="text-sm text-muted-foreground">{{ t('common.states.loading') }}</p>

    <div v-else-if="notFound" class="space-y-3">
      <!-- Interpolated rather than concatenated: German puts the negation last ("Location nicht
           gefunden"), so the two halves cannot be separate strings. -->
      <h1 class="text-section font-bold tracking-tight">
        {{ t('detail.notFoundHeading', { kind }) }}
      </h1>
      <p class="text-muted-foreground">{{ notFoundText }}</p>
      <Button as-child variant="outline">
        <RouterLink :to="localePath('/events')">{{ t('common.actions.browseEvents') }}</RouterLink>
      </Button>
    </div>

    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>

    <template v-else-if="ready">
      <!--
        The same three lengths as the event poster, the same column: 704 px in `max-w-3xl` less
        `sm:p-8`, the viewport less its padding below that; `sizes` tracks the `<main>` classes. The
        wrapper carries the border and positions the credit, because `CachedImage` renders a
        `display: contents` <picture>; the image is `block` so no inline descender opens a gap under
        it. `priority` because this is the LCP element (#1207). `max-h-[35rem]` is what makes
        `object-cover` do anything: 560 px sits above the tallest landscape image of the 43, so 39
        render unchanged and the 4 taller ones crop to the same weight.
      -->
      <div v-if="imageUrl" class="relative border border-border">
        <CachedImage
          :src="imageUrl"
          :sources="imageSources"
          :intrinsic-width="intrinsicWidth"
          :intrinsic-height="intrinsicHeight"
          :alt="name ?? ''"
          priority
          sizes="(min-width: 768px) 704px, (min-width: 640px) calc(100vw - 4rem), calc(100vw - 2rem)"
          img-class="block max-h-[35rem] w-full object-cover"
        />
        <ImageCreditLine v-if="credit" :credit="credit" overlay />
      </div>

      <!-- No picture, no placeholder: here the name is the content, and a full-width 3:2 void
           would push it off the screen (#811 draws one on a card). -->
      <header class="space-y-2">
        <SectionLabel as="p">{{ kind }}</SectionLabel>
        <h1 class="text-page font-bold tracking-tight">{{ name }}</h1>
        <slot name="meta" />
      </header>

      <slot />

      <section class="space-y-4">
        <SectionLabel>{{ t('common.upcomingEvents') }}</SectionLabel>
        <p v-if="eventsLoading" class="text-sm text-muted-foreground">
          {{ t('common.states.loading') }}
        </p>
        <p v-else-if="eventsError" class="text-sm text-destructive">{{ eventsError }}</p>
        <p v-else-if="!events?.content?.length" class="text-sm text-muted-foreground">
          {{ emptyText }}
        </p>
        <div v-else-if="compact" :class="CARD_LIST_CLASS">
          <EventRow v-for="event in events.content" :key="event.slug" :event="event" />
        </div>
        <div v-else :class="CARD_GRID_CLASS">
          <EventCard v-for="event in events.content" :key="event.slug" :event="event" />
        </div>
      </section>

      <!-- Collapsed and content-gated: history must not bury the forecast, and the depth
           caveat on an empty list would read as an excuse. -->
      <details v-if="pastEvents?.content?.length">
        <summary class="cursor-pointer">
          <SectionLabel as="span">{{ t('common.pastEvents') }}</SectionLabel>
        </summary>
        <p class="pt-3 text-sm text-muted-foreground">{{ t('common.pastEventsNote') }}</p>
        <div v-if="compact" :class="[CARD_LIST_CLASS, 'mt-3']">
          <EventRow v-for="event in pastEvents.content" :key="event.slug" :event="event" />
        </div>
        <div v-else :class="[CARD_GRID_CLASS, 'pt-3']">
          <EventCard v-for="event in pastEvents.content" :key="event.slug" :event="event" />
        </div>
      </details>
    </template>
  </main>
</template>
