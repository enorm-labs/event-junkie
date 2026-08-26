<script lang="ts" setup>
import { RouterLink } from 'vue-router'
import type { EventPage } from '@/api/types'
import { Button } from '@/components/ui/button'
import EventCard from '@/components/EventCard.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'

/**
 * Presentational shell shared by the artist, venue, and promoter detail pages. Owns the
 * loading / not-found / error scaffold, the hero header (image + kind label + name), and both
 * event feeds, so each view only wires its data and fills the entity-specific slots.
 *
 * Slots:
 * - `meta` — entity-specific header metadata rendered under the name (links, address, …).
 * - default — optional content between the header and the events feed (e.g. a description).
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
  /** Upcoming-events feed state (from `useEventSearch`). */
  events: EventPage | null
  eventsLoading: boolean
  eventsError: string | null
  /** Copy shown when the events feed is empty. */
  emptyText: string
  /**
   * The past-events feed. No loading or error state: the section appears only once it has events,
   * so a slow or failed archive is absent rather than noisy.
   */
  pastEvents?: EventPage | null
}>()

// The document title is *not* set here. Each detail view owns its own page meta, because the
// description and image come from its entity and this component only ever sees a name — see
// lib/pageMeta.ts and the `usePageMeta` call in each view.

const localePath = useLocalePath()

const { t } = useI18n()
</script>

<template>
  <main class="mx-auto max-w-3xl space-y-8 p-4 sm:p-8">
    <p v-if="loading" class="text-sm text-muted-foreground">{{ t('common.states.loading') }}</p>

    <div v-else-if="notFound" class="space-y-3">
      <!-- Interpolated rather than concatenated: German puts the negation last ("Location nicht
           gefunden"), so the two halves cannot be separate strings. -->
      <h1 class="text-2xl font-bold tracking-tight">{{ t('detail.notFoundHeading', { kind }) }}</h1>
      <p class="text-muted-foreground">{{ notFoundText }}</p>
      <Button as-child variant="outline">
        <RouterLink :to="localePath('/events')">{{ t('common.actions.browseEvents') }}</RouterLink>
      </Button>
    </div>

    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>

    <template v-else-if="ready">
      <header class="flex gap-4">
        <img
          v-if="imageUrl"
          :alt="name ?? ''"
          :src="imageUrl"
          class="size-24 shrink-0 rounded-lg border border-border object-cover"
          loading="lazy"
        />
        <div class="space-y-2">
          <SectionLabel as="p">{{ kind }}</SectionLabel>
          <h1 class="text-3xl font-bold tracking-tight">{{ name }}</h1>
          <slot name="meta" />
        </div>
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
        <div v-else class="grid grid-cols-1 gap-3 sm:grid-cols-2">
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
        <div class="grid grid-cols-1 gap-3 pt-3 sm:grid-cols-2">
          <EventCard v-for="event in pastEvents.content" :key="event.slug" :event="event" />
        </div>
      </details>
    </template>
  </main>
</template>
