<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import BaseDetailView from '@/components/BaseDetailView.vue'
import { usePageMeta } from '@/composables/usePageMeta'
import { APP_NAME, placeholderPageMeta, venuePageMeta } from '@/lib/pageMeta'
import { useEventSearch } from '@/composables/useEvents'
import { yesterdayIso } from '@/lib/format'
import { useVenue } from '@/composables/useVenue'
import { useI18n } from 'vue-i18n'
import { useStructuredData } from '@/composables/useStructuredData'
import { breadcrumbJsonLd, type JsonLd, venueJsonLd } from '@/lib/structuredData'
import type { Locale } from '@/i18n/locales'

const route = useRoute()
const slug = computed(() => String(route.params.slug))

const { data: venue, error, notFound, loading, run: loadVenue } = useVenue(() => slug.value)
const {
  data: events,
  error: eventsError,
  loading: eventsLoading,
  run: loadEvents,
} = useEventSearch(() => ({ venue: slug.value, size: 50 }), 'errors.subject.venueEvents')
// `to` with no `from` is every past event, newest first; the page size is what bounds it.
const { data: pastEvents, run: loadPastEvents } = useEventSearch(() => ({
  venue: slug.value,
  to: yesterdayIso(),
  size: 20,
  sort: ['eventDate,desc'],
}))

// Composed in script to avoid fragile template whitespace around the comma/space separators.
const addressLine = computed(() => {
  const v = venue.value
  if (!v?.address) return ''
  const cityLine = [v.postalCode, v.city].filter(Boolean).join(' ')
  return cityLine ? `${v.address}, ${cityLine}` : v.address
})

function reload() {
  loadVenue()
  loadEvents()
  loadPastEvents()
}

onMounted(reload)
watch(slug, reload)

const { t, locale } = useI18n()

/** Entity label. A `computed` because a locale switch rewrites the URL without remounting this. */
const kind = computed(() => t('detail.venue.kind'))

// A MusicVenue carries the address and coordinates the page already displays. No rich result rides
// on it the way it does for events, but it is accurate and it is what ties an event's `location`
// to a real place. See lib/structuredData.ts.
useStructuredData((): JsonLd[] => {
  const current = venue.value
  if (!current?.slug || !current.name) return []

  return [
    venueJsonLd(current, locale.value as Locale),
    breadcrumbJsonLd(
      [
        [APP_NAME, ''],
        [t('common.nav.venues'), '/venues'],
        [current.name, `/venues/${current.slug}`],
      ],
      locale.value as Locale,
    ),
  ].filter((document): document is JsonLd => document !== null)
})

// The same values the meta injector will need server-side later (ADR-014 §Decision 3).
usePageMeta(() =>
  venue.value
    ? venuePageMeta(venue.value)
    : placeholderPageMeta(
        notFound.value ? t('detail.notFoundHeading', { kind: kind.value }) : kind.value,
      ),
)
</script>

<template>
  <BaseDetailView
    :empty-text="t('detail.venue.empty')"
    :error="error"
    :events="events"
    :events-error="eventsError"
    :events-loading="eventsLoading"
    :image-url="venue?.imageUrl"
    :kind="kind"
    :loading="loading"
    :name="venue?.name"
    :not-found="notFound"
    :not-found-text="t('detail.venue.notFound')"
    :past-events="pastEvents"
    :ready="Boolean(venue)"
  >
    <template #meta>
      <p v-if="addressLine" class="text-muted-foreground">{{ addressLine }}</p>
      <a
        v-if="venue?.websiteUrl"
        :href="venue.websiteUrl"
        class="text-sm text-primary underline-offset-4 hover:underline"
        rel="noopener noreferrer"
        target="_blank"
      >
        {{ t('common.actions.website') }}
      </a>
    </template>

    <p v-if="venue?.description" class="whitespace-pre-line text-foreground/90">
      {{ venue.description }}
    </p>
  </BaseDetailView>
</template>
