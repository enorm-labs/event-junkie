<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import BaseDetailView from '@/components/BaseDetailView.vue'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { usePageMeta } from '@/composables/usePageMeta'
import { notFoundPageMeta, placeholderPageMeta, venuePageMeta } from '@/lib/pageMeta'
import { useEventSearch } from '@/composables/useEvents'
import { yesterdayIso } from '@/lib/format'
import { useVenue } from '@/composables/useVenue'
import { imageCredit } from '@/lib/imageCredit'
import { useI18n } from 'vue-i18n'
import { useStructuredData } from '@/composables/useStructuredData'
import { venuePageJsonLd } from '@/lib/structuredData'
import { descriptionFor } from '@/lib/description'
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

// The visitor's language where the venue has a text in it, and the other one otherwise. The same
// rule the event page uses, from the same function (#1210).
const description = computed(() =>
  venue.value ? descriptionFor(venue.value, locale.value as Locale) : null,
)

/** Entity label. A `computed` because a locale switch rewrites the URL without remounting this. */
const kind = computed(() => t('detail.venue.kind'))

// A MusicVenue carries the address and coordinates the page already displays. No rich result rides
// on it the way it does for events, but it is accurate and it is what ties an event's `location`
// to a real place. See lib/structuredData.ts.
useStructuredData(() => (venue.value ? venuePageJsonLd(venue.value, locale.value as Locale) : []))

// The same values the meta injector will need server-side later (ADR-014 §Decision 3).
usePageMeta(() =>
  venue.value
    ? venuePageMeta(venue.value, locale.value as Locale)
    : notFound.value
      ? notFoundPageMeta(t('detail.notFoundHeading', { kind: kind.value }))
      : placeholderPageMeta(kind.value),
)

const credit = computed(() => imageCredit(venue.value))

const { formatEventType, formatFamily, formatVenueFacts } = useFormat()
const facts = computed(() => (venue.value ? formatVenueFacts(venue.value) : ''))
const localePath = useLocalePath()

/** What the venue mostly plays and hosts, each a link to its events of that kind. */
const programme = computed(() => {
  const v = venue.value
  if (!v?.slug) return []
  const link = (key: string, value: string) => ({
    path: localePath('/events'),
    query: { venue: v.slug, [key]: value },
  })
  return [
    {
      term: t('detail.venue.plays'),
      items: (v.programmeFamilies ?? []).map((f) => ({
        label: formatFamily(f),
        to: link('family', f),
      })),
    },
    {
      term: t('detail.venue.hosts'),
      items: (v.programmeEventTypes ?? []).map((e) => ({
        label: formatEventType(e),
        to: link('eventType', e),
      })),
    },
  ].filter((row) => row.items.length)
})
</script>

<template>
  <BaseDetailView
    :empty-text="t('detail.venue.empty')"
    :error="error"
    :events="events"
    :events-error="eventsError"
    :events-loading="eventsLoading"
    :image-url="venue?.imageUrl"
    :credit="credit"
    :image-sources="venue?.imageSources"
    :intrinsic-width="venue?.intrinsicWidth"
    :intrinsic-height="venue?.intrinsicHeight"
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
      <p v-if="facts" class="text-muted-foreground">{{ facts }}</p>
      <a
        v-if="venue?.websiteUrl"
        :href="venue.websiteUrl"
        class="text-body text-primary underline-offset-4 hover:underline"
        rel="noopener noreferrer"
        target="_blank"
      >
        {{ t('common.actions.website') }}
      </a>
    </template>

    <p
      v-if="description"
      :lang="description.lang ?? undefined"
      class="whitespace-pre-line text-foreground/90"
    >
      {{ description.text }}
    </p>

    <section v-if="programme.length" class="space-y-1 text-body">
      <dl class="space-y-1">
        <div v-for="row in programme" :key="row.term" class="flex flex-wrap gap-x-2">
          <dt class="text-muted-foreground">{{ row.term }}</dt>
          <dd>
            <template v-for="(item, i) in row.items" :key="item.label">
              <template v-if="i">{{ ' · ' }}</template>
              <RouterLink :to="item.to" class="text-primary underline-offset-4 hover:underline">
                {{ item.label }}
              </RouterLink>
            </template>
          </dd>
        </div>
      </dl>
      <p class="text-meta text-muted-foreground">{{ t('detail.venue.programmeNote') }}</p>
    </section>
  </BaseDetailView>
</template>
