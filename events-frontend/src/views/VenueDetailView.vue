<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import BaseBadge from '@/components/BaseBadge.vue'
import BaseDetailView from '@/components/BaseDetailView.vue'
import DetailLinks from '@/components/DetailLinks.vue'
import DirectionsLink from '@/components/DirectionsLink.vue'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { usePageMeta } from '@/composables/usePageMeta'
import { notFoundPageMeta, placeholderPageMeta, venuePageMeta } from '@/lib/pageMeta'
import { useEventSearch } from '@/composables/useEvents'
import { usePastEvents } from '@/composables/usePastEvents'
import { useVenue } from '@/composables/useVenue'
import { imageCredit } from '@/lib/imageCredit'
import { useI18n } from 'vue-i18n'
import { useStructuredData } from '@/composables/useStructuredData'
import { venuePageJsonLd } from '@/lib/structuredData'
import { descriptionFor } from '@/lib/description'
import { inCharacterOrder } from '@/lib/venueCharacters'
import { districtLabel } from '@/lib/districts'
import { venuePosition } from '@/lib/mapPins'
import { programmeLink } from '@/lib/programmeLink'
import { venueClosure } from '@/lib/venueClosure'
import SectionLabel from '@/components/SectionLabel.vue'
import { withReferral } from '@/lib/referral'
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
const { past, run: loadPastEvents } = usePastEvents(() => ({ venue: slug.value }), events)

/** "Holzmarktstr. 25 · Friedrichshain": a part the venue lacks drops out with its separator. */
const addressLine = computed(() =>
  [venue.value?.address, districtLabel(venue.value?.district)].filter(Boolean).join(' · '),
)

/** The map centred on this venue with its pin open; none without a coordinate to centre on. */
const mapLink = computed(() =>
  venue.value?.slug && venuePosition(venue.value)
    ? { path: localePath('/map'), query: { focus: venue.value.slug } }
    : null,
)

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

const links = computed(() =>
  venue.value?.websiteUrl
    ? [{ label: t('common.actions.website'), url: withReferral(venue.value.websiteUrl) }]
    : [],
)

/** The link to the programme of a venue we do not import, labelled by where it points (#2766). */
const programmeSite = computed(() => {
  const href = venue.value?.programmeUrl
  if (!href) return null
  const target = programmeLink(href)
  const label =
    target.kind === 'resident-advisor'
      ? t('detail.venue.programmeResidentAdvisor')
      : target.kind === 'platform'
        ? t('detail.venue.programmePlatform', { platform: target.platform })
        : t('detail.venue.programmeOwn')
  return { href, label }
})

const {
  formatDate,
  formatEventType,
  formatFamily,
  formatMonthYear,
  formatVenueCharacter,
  formatVenueFacts,
  formatVenueType,
} = useFormat()

/** "Closed for good in October 2026", or "Open until …" while the last day is still ahead (ADR-046). */
const closureLine = computed(() => {
  const closure = venueClosure(venue.value?.closedOn)
  if (!closure) return null
  return {
    closed: closure.state === 'closed',
    text:
      closure.state === 'closed'
        ? t('detail.venue.closed', { month: formatMonthYear(closure.lastDay) })
        : t('detail.venue.closing', { date: formatDate(closure.lastDay) }),
  }
})

// Only the capacity: the types are the table's first row, and the capacity its last.
const capacity = computed(() => formatVenueFacts({ capacity: venue.value?.capacity }))

const FACT_TERM_CLASS = 'text-muted-foreground sm:w-24 sm:shrink-0'

/** Each type lists every venue of that type, the same shape as the rows under it. */
const types = computed(() =>
  (venue.value?.venueTypes ?? []).map((type) => ({
    label: formatVenueType(type),
    to: { path: localePath('/venues'), query: { type } },
  })),
)

/**
 * What the venue says about itself (#2379). Each tag is a pill that lists every venue with the same
 * tag, which makes it a control rather than a fact (design.instructions.md §1).
 */
const characters = computed(() =>
  inCharacterOrder(venue.value?.characterTags ?? [], (c) => c.tag ?? '').map((c) => ({
    label: formatVenueCharacter(c.tag ?? ''),
    to: { path: localePath('/venues'), query: { character: c.tag ?? '' } },
  })),
)

/** What the venue plays and hosts, then what it says about itself: one block of filter pills. */
const facets = computed(() => [
  ...(types.value.length ? [{ term: t('detail.venue.type'), items: types.value }] : []),
  ...programme.value,
  ...(characters.value.length
    ? [{ term: t('detail.venue.character'), items: characters.value }]
    : []),
])

/** The venue's pages the tags rest on, once each: several tags usually share one page. */
const characterSources = computed(() => {
  const urls = [...new Set((venue.value?.characterTags ?? []).map((c) => c.sourceUrl ?? ''))]
  return urls.filter(Boolean).map((href) => ({ href, label: sourceLabel(href) }))
})

/** `berghain.berlin/de/awareness` for `https://www.berghain.berlin/de/awareness/`. */
function sourceLabel(href: string): string {
  try {
    const url = new URL(href)
    const path = decodeURIComponent(url.pathname).replace(/\/$/, '')
    return url.hostname.replace(/^www\./, '') + path
  } catch {
    return href
  }
}
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
    :past="past"
    :ready="Boolean(venue)"
    :report-path="`/venues/${slug}`"
    :report-text="t('detail.venue.report')"
  >
    <template v-if="closureLine?.closed" #upcoming>
      <section class="space-y-4" data-testid="venue-closed">
        <SectionLabel>{{ t('common.upcomingEvents') }}</SectionLabel>
        <p class="text-body text-muted-foreground">{{ t('detail.venue.closedNoEvents') }}</p>
      </section>
    </template>
    <template v-else-if="venue?.imported === false" #upcoming>
      <section class="space-y-4" data-testid="venue-not-imported">
        <SectionLabel>{{ t('common.upcomingEvents') }}</SectionLabel>
        <p class="text-body text-muted-foreground">{{ t('detail.venue.notImported') }}</p>
        <p v-if="programmeSite" class="text-body">
          <a
            :href="programmeSite.href"
            class="text-primary underline-offset-4 hover:underline"
            data-testid="venue-programme-link"
            rel="noopener noreferrer"
            target="_blank"
          >
            {{ programmeSite.label }}
          </a>
        </p>
      </section>
    </template>

    <template #meta>
      <p v-if="closureLine" class="text-body font-medium" data-testid="venue-closure">
        {{ closureLine.text }}
      </p>
      <p v-if="addressLine || mapLink" class="text-body text-muted-foreground">
        {{ addressLine }}
        <template v-if="addressLine && mapLink">{{ ' · ' }}</template>
        <RouterLink
          v-if="mapLink"
          :to="mapLink"
          class="text-primary underline-offset-4 hover:underline"
          data-testid="venue-map-link"
        >
          {{ t('detail.venue.onMap') }}
        </RouterLink>
        <template v-if="mapLink">{{ ' · ' }}<DirectionsLink :venue="venue" /></template>
      </p>
      <DetailLinks :links="links" />
    </template>

    <p
      v-if="description"
      :lang="description.lang ?? undefined"
      class="text-prose whitespace-pre-line wrap-anywhere text-foreground/90"
    >
      {{ description.text }}
    </p>

    <!-- Every pill here opens a filtered list, which makes it a control (design.instructions.md §1). -->
    <section
      v-if="facets.length || capacity"
      class="space-y-3 text-body"
      data-testid="venue-facets"
    >
      <dl class="space-y-2">
        <div
          v-for="row in facets"
          :key="row.term"
          class="space-y-1 sm:flex sm:items-baseline sm:gap-4 sm:space-y-0"
        >
          <!-- One label width for every row, so the values start on one line; "Mostly plays" is the widest. -->
          <dt :class="FACT_TERM_CLASS">{{ row.term }}</dt>
          <dd class="flex flex-wrap gap-2">
            <RouterLink
              v-for="item in row.items"
              :key="item.label"
              :to="item.to"
              class="rounded-full transition-colors hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            >
              <BaseBadge variant="outline">{{ item.label }}</BaseBadge>
            </RouterLink>
          </dd>
        </div>
        <div v-if="capacity" class="space-y-1 sm:flex sm:items-baseline sm:gap-4 sm:space-y-0">
          <dt :class="FACT_TERM_CLASS">{{ t('detail.venue.capacity') }}</dt>
          <dd>{{ capacity }}</dd>
        </div>
      </dl>
      <div class="space-y-0.5 text-meta text-muted-foreground">
        <p v-if="programme.length">{{ t('detail.venue.programmeNote') }}</p>
        <p v-if="characterSources.length" class="break-words">
          {{ t('detail.venue.characterNote') }}:
          <template v-for="(source, i) in characterSources" :key="source.href">
            <template v-if="i">{{ ' · ' }}</template>
            <a
              :href="source.href"
              class="underline underline-offset-4 hover:text-foreground"
              rel="noopener noreferrer"
              target="_blank"
            >
              {{ source.label }}</a
            >
          </template>
        </p>
      </div>
    </section>
  </BaseDetailView>
</template>
