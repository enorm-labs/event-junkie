<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ExternalLink, Ticket } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import BaseBadge from '@/components/BaseBadge.vue'
import CachedImage from '@/components/CachedImage.vue'
import DirectionsLink from '@/components/DirectionsLink.vue'
import ErrorLine from '@/components/ErrorLine.vue'
import NearbyStops from '@/components/NearbyStops.vue'
import EventCard from '@/components/EventCard.vue'
import EventLineup from '@/components/EventLineup.vue'
import EventMetaLine, { type MetaItem } from '@/components/EventMetaLine.vue'
import EventRow from '@/components/EventRow.vue'
import EventShareActions from '@/components/EventShareActions.vue'
import RsvpToggle from '@/components/RsvpToggle.vue'
import ReportProblem from '@/components/ReportProblem.vue'
import EventWhen from '@/components/EventWhen.vue'
import PickBadge from '@/components/PickBadge.vue'
import ReportLine from '@/components/ReportLine.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { useCompactView } from '@/composables/useCompactView'
import { useEvent } from '@/composables/useEvent'
import { useEventChanges } from '@/composables/useEventChanges'
import { useRelatedEvents } from '@/composables/useRelatedEvents'
import { descriptionFor } from '@/lib/description'
import { inFeatureOrder } from '@/lib/eventFeatures'
import { withReferral } from '@/lib/referral'
import { usePageMeta } from '@/composables/usePageMeta'
import { eventPageMeta, notFoundPageMeta, placeholderPageMeta } from '@/lib/pageMeta'
import { useStructuredData } from '@/composables/useStructuredData'
import { eventPageJsonLd } from '@/lib/structuredData'
import type { Locale } from '@/i18n/locales'
import { formatPrice, isPastEvent, isRunningEvent } from '@/lib/format'
import { eventPlace, venuePosition } from '@/lib/mapPins'
import {
  CARD_GRID_CLASS,
  CARD_LIST_CLASS,
  cn,
  PAGE_TITLE_CLASS,
  TEXT_LINK_CLASS,
  UNDERLINED_LINK_CLASS,
} from '@/lib/utils'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const slug = computed(() => String(route.params.slug))

const {
  data: event,
  error,
  notFound,
  loading,
  retrying,
  run: loadEvent,
} = useEvent(() => slug.value)

// "More like this" (#359): an extra, so a failure or an empty answer leaves no trace on the page.
const { data: relatedData, run: loadRelated } = useRelatedEvents(() => slug.value)
const related = computed(() => (Array.isArray(relatedData.value) ? relatedData.value : []))
const { compact } = useCompactView()

/**
 * The hosts of the other sources that filled empty fields of this event, a promoter's page or a fan
 * site, each linked (ADR-043). Their data is shown only beside this credit (ADR-036).
 */
const enrichmentCredits = computed(() =>
  (event.value?.enrichmentSources ?? []).flatMap((source) => {
    try {
      return source.sourceUrl
        ? [{ url: source.sourceUrl, host: new URL(source.sourceUrl).host }]
        : []
    } catch {
      return []
    }
  }),
)

const isPast = computed(() => !!event.value && isPastEvent(event.value))
const isRunning = computed(() => !!event.value && isRunningEvent(event.value))

function load() {
  loadEvent()
  loadRelated()
}

onMounted(load)
watch(slug, load)

const localePath = useLocalePath()
const {
  formatEventDates,
  formatEventTime,
  eventTimeHint,
  formatEventStatus,
  formatSpokenLanguage,
  formatRunState,
  formatEventFeature,
  formatEventType,
} = useFormat()
// The header has room for the words behind a `~` time, where the card only has a title (#1384).
const timeHint = computed(() => (event.value ? eventTimeHint(event.value) : null))
// A fact block beside the venue and the tickets, not a word under the title (#2523).
const spokenLanguage = computed(() => formatSpokenLanguage(event.value?.spokenLanguages, null))
const subtitles = computed(() => formatSpokenLanguage(null, event.value?.subtitleLanguage))
// What kind of night it is, as its own text says (#2631); each pill opens the list filtered by it.
const features = computed(() =>
  inFeatureOrder(event.value?.features ?? []).map((slug) => ({
    slug,
    label: formatEventFeature(slug),
    to: { path: localePath('/events'), query: { feature: slug } },
  })),
)
// What kind of event and which sound, the card's taxonomy without the language, which has its own
// fact block here. OTHER says nothing, as on the card.
const taxonomyItems = computed<MetaItem[]>(() => {
  const e = event.value
  if (!e) return []
  const type = e.eventType && e.eventType !== 'OTHER' ? formatEventType(e.eventType) : null
  const genres = (e.genreTags ?? []).map((text) => ({
    text,
    note: derivedGenreNote(e.derivedGenreTags?.[text]),
  }))
  return [...(type ? [{ text: type }] : []), ...genres]
})
// A genre the source did not publish says where it comes from (ADR-047).
function derivedGenreNote(origin: string | undefined): string | undefined {
  if (origin === 'ARTIST') return t('events.detail.genreFromArtist')
  if (origin === 'DESCRIPTION') return t('events.detail.genreFromDescription')
  return undefined
}
// A venue without a coordinate gets no route (#2767).
// A relocated night routes to its new house, and to nowhere when we do not list that house.
const place = computed(() => (event.value ? eventPlace(event.value) : undefined))
const hasDirections = computed(() => venuePosition(place.value) !== null)

// A venue move reads under the venue's name, not in the When block (#2725).
const { changeLines } = useEventChanges()
const venueChanges = computed(() => changeLines(event.value?.changes, (field) => field === 'VENUE'))
// A relocated night links the same night at the new house, else the house's page.
const movedToLink = computed(() => {
  const movedTo = event.value?.status === 'RELOCATED' ? event.value.movedTo : null
  if (!movedTo) return null
  const path = movedTo.eventSlug ? `/events/${movedTo.eventSlug}` : `/venues/${movedTo.venueSlug}`
  const address = [movedTo.address, movedTo.city].filter(Boolean).join(', ')
  return { name: movedTo.venueName, to: localePath(path), address }
})
// A house we do not list, by the note's own words, or no house at all.
const movedToText = computed(() =>
  event.value?.status === 'RELOCATED' && !movedToLink.value
    ? event.value.relocatedTo?.trim() || null
    : null,
)
const movedUnannounced = computed(
  () => event.value?.status === 'RELOCATED' && !movedToLink.value && !movedToText.value,
)
// A cancelled night shows no price: nothing is on sale (#2916). Every other night says what is
// known about the price, also when that is nothing (#2963).
const hasTickets = computed(() => !!event.value && event.value.status !== 'CANCELLED')
const presale = computed(() => formatPrice(event.value?.pricePresale, event.value?.priceCurrency))
const boxOffice = computed(() =>
  formatPrice(event.value?.priceBoxOffice, event.value?.priceCurrency),
)
const priceUnknown = computed(
  () => !presale.value && !boxOffice.value && !event.value?.free && !event.value?.priceNote,
)
// When, venue, tickets, language: three share a row, and four wrap to 2×2 rather than squeeze.
const factColumns = computed(() => {
  const optional = [event.value?.venue, hasTickets.value, spokenLanguage.value || subtitles.value]
  return optional.filter(Boolean).length === 2 ? 'sm:grid-cols-3' : 'sm:grid-cols-2'
})

const { t, locale } = useI18n()

/** Whether every suggestion is at this venue and shares no tag and no act with this event (#3337). */
const relatedByVenueOnly = computed(() => {
  const venue = event.value?.venue?.slug
  if (!venue) return false
  const tags = new Set(event.value?.genreTags ?? [])
  const acts = new Set((event.value?.lineup ?? []).map((entry) => entry.artist?.name))
  return related.value.every(
    (other) =>
      other.venue?.slug === venue &&
      !(other.genreTags ?? []).some((tag) => tags.has(tag)) &&
      !(other.artistNames ?? []).some((name) => acts.has(name)),
  )
})
const relatedHeading = computed(() =>
  relatedByVenueOnly.value
    ? t('events.detail.relatedAtVenue', { venue: event.value?.venue?.name ?? '' })
    : t('events.detail.related'),
)

const metaItems = computed<MetaItem[]>(() => {
  const e = event.value
  if (!e) return []
  // The venue's own name for the house when we know it: the note may say "huxleys" or "Hole".
  const movedTo = e.movedTo?.venueName ?? e.relocatedTo
  const status = formatEventStatus(e.status, movedTo, e.postponedTo?.eventDate, e.relocatedOn)
  // A postponed or cancelled show does not happen on this date, so its date is struck and its time dropped.
  const offDate = e.status === 'POSTPONED' || e.status === 'CANCELLED'
  const items: (MetaItem | null)[] = [
    { text: formatEventDates(e), struck: offDate },
    offDate ? null : { text: formatEventTime(e) },
    timeHint.value && !offDate ? { text: timeHint.value } : null,
    e.venue?.name ? { text: e.venue.name } : null,
    // The room as the venue names it (#316): a proper name, so never translated.
    e.room ? { text: e.room } : null,
    status ? { text: status, badge: true } : null,
    stateItem(e),
  ]
  return items.filter((item): item is MetaItem => item !== null)
})

function stateItem(e: NonNullable<typeof event.value>): MetaItem | null {
  if (isPast.value) return { text: t('events.card.past') }
  if (isRunning.value) {
    return {
      text: formatRunState(e),
      class: 'text-primary',
    }
  }
  if (e.soldOut) return { text: t('events.card.soldOut'), class: 'font-medium text-destructive' }
  if (e.free) return { text: t('events.card.free'), class: 'font-medium text-success' }
  return null
}

// The text for this locale, its language, and whether a machine wrote it, shared with the page
// meta, the structured data and the injector (lib/description.ts). Below `useI18n()` as
// `usePageMeta` is.
const description = computed(() =>
  event.value ? descriptionFor(event.value, locale.value as Locale) : null,
)

// Title, description and image for this page; the description leads with the date and venue
// (lib/pageMeta.ts). Below `useI18n()` on purpose: `watchEffect` runs immediately, so a getter
// reading `locale` from above this line would hit the temporal dead zone.
usePageMeta(() =>
  event.value
    ? eventPageMeta(event.value, locale.value as Locale)
    : notFound.value
      ? notFoundPageMeta(t('detail.notFoundHeading', { kind: t('events.detail.kind') }))
      : placeholderPageMeta(t('events.detail.kind')),
)

// The rich-result payload, the same documents the injector serves (lib/structuredData.ts).
useStructuredData(() => (event.value ? eventPageJsonLd(event.value, locale.value as Locale) : []))
</script>

<template>
  <main class="mx-auto max-w-3xl space-y-8 p-4 sm:p-8">
    <p v-if="loading" class="text-body text-muted-foreground">
      {{ retrying ? t('errors.retrying') : t('common.states.loading') }}
    </p>

    <div v-else-if="notFound" class="space-y-3">
      <h1 :class="PAGE_TITLE_CLASS">{{ t('events.detail.notFound') }}</h1>
      <p class="text-body text-muted-foreground">
        {{ t('events.detail.notFoundBody') }}
      </p>
      <Button as-child variant="outline">
        <RouterLink :to="localePath('/')">{{ t('common.actions.backToHome') }}</RouterLink>
      </Button>
    </div>

    <ErrorLine v-else-if="error">{{ error }}</ErrorLine>

    <article v-else-if="event" class="space-y-8">
      <!-- The poster belongs to the title, so it sits closer than the article's stride. `mb-5` wins
           over `space-y-8` because the latter is a zero-specificity `:where()` rule. -->
      <header class="mb-5 space-y-3">
        <h1 :class="PAGE_TITLE_CLASS">{{ event.title }}</h1>
        <p v-if="event.subtitle" class="text-lede text-muted-foreground">{{ event.subtitle }}</p>
        <!-- With no poster to sit on, the pick's pill opens the header's pills instead (#3065). -->
        <p v-if="event.featured && !event.imageUrl"><PickBadge /></p>
        <!-- Two lines of one block, when and where then what kind: closer than the header's stride. -->
        <!-- A filter link on a detail page, so a pill (design.instructions.md §1, #3273). -->
        <p v-if="event.festival?.slug">
          <RouterLink
            :aria-label="t('events.detail.festivalEvents', { name: event.festival.name })"
            :to="{ path: localePath('/events'), query: { festival: event.festival.slug } }"
            class="inline-flex max-w-full rounded-full transition-colors hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            data-testid="festival-link"
          >
            <BaseBadge class="truncate" variant="outline">{{ event.festival.name }}</BaseBadge>
          </RouterLink>
        </p>
        <div class="space-y-1">
          <EventMetaLine :items="metaItems" />
          <EventMetaLine
            v-if="taxonomyItems.length"
            :items="taxonomyItems"
            data-testid="event-taxonomy"
          />
        </div>
        <!--
          A past event is where a search engine sends people; saying only that it is over left the
          venue's own site as the nearest onward link (#1268), so the sentence carries one that stays
          here: the venue if we know it, the list otherwise.
        -->
        <p v-if="isPast" class="text-body text-muted-foreground">
          {{ t('events.detail.hasTakenPlace') }}
          <RouterLink
            v-if="event.venue?.slug"
            :to="localePath(`/venues/${event.venue.slug}`)"
            class="text-foreground underline underline-offset-4"
            >{{ t('events.detail.upcomingAtVenue', { venue: event.venue.name }) }}</RouterLink
          >
          <RouterLink
            v-else
            :to="localePath('/events')"
            class="text-foreground underline underline-offset-4"
            >{{ t('events.detail.browseUpcoming') }}</RouterLink
          >
        </p>
      </header>

      <!--
        The widest image on the site: 704 px in a `max-w-3xl` column once `sm:p-8` is subtracted,
        the viewport minus padding below that; `sizes` tracks the `<main>` classes above. The wrapper
        is the spacing: `CachedImage` renders a `display: contents` <picture> with no box to carry a
        `space-y-8` margin. No poster, no placeholder: here the title is the content, and a 3:2 void
        would push it off the screen. `priority` because the poster is the LCP element (#1207).
      -->
      <div v-if="event.imageUrl" class="relative">
        <!-- On the poster's corner, as on the lead card (#3065). -->
        <PickBadge v-if="event.featured" class="absolute top-3 left-3 z-10" />
        <CachedImage
          :src="event.imageUrl"
          :sources="event.imageSources"
          :intrinsic-width="event.intrinsicWidth"
          :intrinsic-height="event.intrinsicHeight"
          :alt="event.title ?? ''"
          priority
          sizes="(min-width: 768px) 704px, (min-width: 640px) calc(100vw - 4rem), calc(100vw - 2rem)"
          img-class="w-full border border-border object-cover"
        />
      </div>

      <!--
        `lang` marks the text, not the page, so a screen reader pronounces a German description on
        /en/ and a browser offers to translate it. Absent when the importer could not tell (ADR-026).
      -->
      <p
        v-if="description"
        :lang="description.lang ?? undefined"
        class="text-prose wrap-anywhere whitespace-pre-line text-foreground/90"
      >
        {{ description.text }}
      </p>
      <!-- Machine output is never presented as the venue's own words. -->
      <p v-if="description?.machine" class="text-body text-muted-foreground">
        {{ t('events.detail.machineTranslated') }}
        <a
          v-if="event.sourceUrl"
          :href="withReferral(event.sourceUrl)"
          :class="UNDERLINED_LINK_CLASS"
          rel="noopener noreferrer"
          target="_blank"
          >{{ t('events.detail.originalText') }}</a
        >
      </p>
      <!--
        Only where a licence removed a description, never where the venue wrote none: 56 events
        against 1,072 on a seeded database, which is why the API reports the reason (#811). It says
        where the text is and nothing about what the venue wants: both prohibitions were read off an
        Impressum, not sent to us (#809).
      -->
      <p v-if="!description && event.descriptionWithheld" class="text-body text-muted-foreground">
        {{ t('events.detail.descriptionElsewhere') }}
      </p>

      <!-- Keyed by the slug, so the next event opens with its lineup folded again. -->
      <EventLineup :key="event.slug" :event="event" />

      <section :class="cn('grid grid-cols-1 gap-6', factColumns)">
        <EventWhen :event="event" />

        <div v-if="event.venue" class="space-y-1">
          <SectionLabel>{{ t('events.detail.venue') }}</SectionLabel>
          <template v-if="movedToLink">
            <p class="text-body font-medium" data-testid="moved-to">
              {{ t('events.detail.movedTo') }}
              <RouterLink :to="movedToLink.to" :class="TEXT_LINK_CLASS">{{
                movedToLink.name
              }}</RouterLink>
            </p>
            <p v-if="movedToLink.address" class="text-body text-muted-foreground">
              {{ movedToLink.address }}
            </p>
            <p class="text-body text-muted-foreground" data-testid="moved-from">
              {{ t('events.detail.movedFrom') }}
              <RouterLink
                v-if="event.venue.slug"
                :to="localePath(`/venues/${event.venue.slug}`)"
                class="underline-offset-4 hover:underline"
                >{{ event.venue.name }}</RouterLink
              ><template v-else>{{ event.venue.name }}</template>
            </p>
          </template>
          <template v-else>
            <RouterLink
              v-if="event.venue.slug"
              :to="localePath(`/venues/${event.venue.slug}`)"
              :class="['text-body font-medium', TEXT_LINK_CLASS]"
            >
              {{ event.venue.name }}
            </RouterLink>
            <p v-else class="text-body font-medium">{{ event.venue.name }}</p>
            <p v-if="movedToText" class="text-body font-medium" data-testid="moved-to">
              {{ t('events.detail.movedTo') }} {{ movedToText }}
            </p>
            <p
              v-else-if="movedUnannounced"
              class="text-body font-medium"
              data-testid="moved-unannounced"
            >
              {{ t('events.detail.movedUnannounced') }}
            </p>
            <p v-if="event.venue.address" class="text-body text-muted-foreground">
              {{ event.venue.address
              }}<template v-if="event.venue.city">, {{ event.venue.city }}</template>
            </p>
          </template>
          <p v-for="(change, index) in venueChanges" :key="index" class="text-body">
            <span class="font-medium">{{ change.text }}</span
            ><span v-if="change.ago" class="text-muted-foreground"> · {{ change.ago }}</span>
          </p>
          <p v-if="hasDirections" class="text-body"><DirectionsLink :venue="place" /></p>
          <!-- The stations are the listed venue's, so a relocated night shows none rather than the old address's. -->
          <NearbyStops v-if="event.status !== 'RELOCATED'" :stops="event.nearbyStops" />
        </div>

        <div v-if="hasTickets" class="space-y-1">
          <SectionLabel>{{ t('events.detail.tickets') }}</SectionLabel>
          <p v-if="presale" class="text-body">{{ t('events.detail.presale') }}: {{ presale }}</p>
          <p v-if="boxOffice" class="text-body">
            {{ t('events.detail.boxOffice') }}: {{ boxOffice }}
          </p>
          <p v-if="event.free && !presale && !boxOffice" class="text-body font-medium text-success">
            {{ t('events.detail.freeEntry') }}
          </p>
          <p v-if="priceUnknown" class="text-body text-muted-foreground">
            {{ t('events.detail.priceUnknown') }}
          </p>
          <p v-if="event.priceNote" class="text-body text-muted-foreground">
            {{ event.priceNote }}
          </p>
        </div>

        <div v-if="spokenLanguage || subtitles" class="space-y-1">
          <SectionLabel>{{ t('events.detail.language') }}</SectionLabel>
          <p v-if="spokenLanguage" class="text-body">{{ spokenLanguage }}</p>
          <p v-if="subtitles" class="text-body text-muted-foreground">{{ subtitles }}</p>
        </div>
      </section>

      <!-- Every pill opens a filtered list, which makes it a control (design.instructions.md §1). -->
      <section v-if="features.length" class="space-y-2" data-testid="event-features">
        <SectionLabel>{{ t('events.detail.features') }}</SectionLabel>
        <p class="flex flex-wrap gap-2">
          <RouterLink
            v-for="feature in features"
            :key="feature.slug"
            :to="feature.to"
            class="rounded-full transition-colors hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
          >
            <BaseBadge variant="outline">{{ feature.label }}</BaseBadge>
          </RouterLink>
        </p>
        <p class="text-meta text-muted-foreground">{{ t('events.detail.featuresNote') }}</p>
      </section>

      <!--
        No ticket CTA once the night has happened, on a cancelled one (#2916), nor on the row a
        show left — the house it moved to sells the tickets (#1551). The source page stays: it says
        where refunds go. Share closes the row.
      -->
      <!--
        Private plans (#3892) first, then the links that leave the site (#4117). One row where it
        fits; a phone keeps the two groups on two rows, so neither breaks in the middle.
      -->
      <div class="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-start">
        <RsvpToggle v-if="event.slug" :slug="event.slug" />

        <EventShareActions :event="event">
          <Button
            v-if="event.ticketUrl && !isPast && event.status !== 'CANCELLED' && !event.relocatedTo"
            as-child
          >
            <a :href="withReferral(event.ticketUrl)" rel="noopener noreferrer" target="_blank">
              <Ticket aria-hidden="true" />
              {{ t('events.detail.buyTickets') }}
            </a>
          </Button>
          <Button v-if="event.sourceUrl" as-child variant="outline">
            <a :href="withReferral(event.sourceUrl)" rel="noopener noreferrer" target="_blank">
              <ExternalLink aria-hidden="true" />
              {{ t('events.detail.eventPage') }}
            </a>
          </Button>
          <Button v-if="event.facebookEventUrl" as-child variant="outline">
            <a :href="event.facebookEventUrl" rel="noopener noreferrer" target="_blank">
              <ExternalLink aria-hidden="true" />
              {{ t('events.detail.facebook') }}
            </a>
          </Button>
        </EventShareActions>
      </div>

      <!-- For the accounts the operator allows (#4399); everyone else has the mail line at the end. -->
      <ReportProblem v-if="event.slug" :slug="event.slug" :title="event.title ?? ''" />

      <p v-if="enrichmentCredits.length" class="text-meta text-muted-foreground">
        {{ t('events.detail.detailsFrom') }}
        <template v-for="(credit, index) in enrichmentCredits" :key="credit.url"
          ><template v-if="index">, </template
          ><a :class="UNDERLINED_LINK_CLASS" :href="credit.url" rel="noopener" target="_blank">{{
            credit.host
          }}</a></template
        >
      </p>

      <section v-if="event.promoters?.length" class="space-y-1">
        <SectionLabel>{{ t('events.detail.promoters') }}</SectionLabel>
        <p class="flex flex-wrap gap-x-1 text-body">
          <template
            v-for="(promoter, index) in event.promoters"
            :key="promoter.slug ?? promoter.name"
          >
            <RouterLink
              v-if="promoter.slug"
              :to="localePath(`/promoters/${promoter.slug}`)"
              :class="TEXT_LINK_CLASS"
            >
              {{ promoter.name }}</RouterLink
            >
            <span v-else>{{ promoter.name }}</span>
            <span v-if="index < event.promoters.length - 1">, </span>
          </template>
        </p>
      </section>

      <ReportLine
        v-if="event.slug"
        :path="`/events/${event.slug}`"
        :text="t('events.detail.reportWrong')"
        :title="event.title ?? event.slug"
      />
    </article>

    <!-- Outside the article: these are other events, not facts about this one. -->
    <section v-if="event && related.length" class="space-y-4" data-testid="related-events">
      <SectionLabel>{{ relatedHeading }}</SectionLabel>
      <div v-if="compact" :class="CARD_LIST_CLASS">
        <EventRow v-for="other in related" :key="other.slug" :event="other" />
      </div>
      <div v-else :class="CARD_GRID_CLASS">
        <EventCard v-for="other in related" :key="other.slug" :event="other" />
      </div>
    </section>
  </main>
</template>
