<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import BaseBadge from '@/components/BaseBadge.vue'
import CachedImage from '@/components/CachedImage.vue'
import EventCard from '@/components/EventCard.vue'
import EventRow from '@/components/EventRow.vue'
import EventShareActions from '@/components/EventShareActions.vue'
import EventWhen from '@/components/EventWhen.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { useCompactView } from '@/composables/useCompactView'
import { useEvent } from '@/composables/useEvent'
import { useRelatedEvents } from '@/composables/useRelatedEvents'
import { descriptionFor } from '@/lib/description'
import { feedbackMailto } from '@/lib/feedback'
import { CONTROLLER } from '@/lib/legal'
import { canonicalUrl } from '@/lib/seo'
import { usePageMeta } from '@/composables/usePageMeta'
import { eventPageMeta, notFoundPageMeta, placeholderPageMeta } from '@/lib/pageMeta'
import { useStructuredData } from '@/composables/useStructuredData'
import { eventPageJsonLd } from '@/lib/structuredData'
import type { Locale } from '@/i18n/locales'
import { formatPrice, isPastEvent, isRunningEvent } from '@/lib/format'
import { runningOrder, type RunningOrderSet } from '@/lib/runningOrder'
import { CARD_GRID_CLASS, CARD_LIST_CLASS, cn } from '@/lib/utils'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const slug = computed(() => String(route.params.slug))

const { data: event, error, notFound, loading, run: loadEvent } = useEvent(() => slug.value)

// "More like this" (#359): an extra, so a failure or an empty answer leaves no trace on the page.
const { data: relatedData, run: loadRelated } = useRelatedEvents(() => slug.value)
const related = computed(() => (Array.isArray(relatedData.value) ? relatedData.value : []))
const { compact } = useCompactView()

// Lineup arrives in billing order already, but sort defensively so headliners stay first.
const lineup = computed(() =>
  [...(event.value?.lineup ?? [])].sort((a, b) => (a.billingOrder ?? 0) - (b.billingOrder ?? 0)),
)

/**
 * Whether the lineup's role labels carry information: a co-bill like `Alibi + Onyon + Tense`
 * bills every act `HEADLINER`, and three "Headliner" tags say nothing the list does not. An
 * all-DJ night keeps its tags: `DJ` says the acts play records, not live.
 */
const showRoles = computed(() => lineup.value.some((entry) => entry.role !== 'HEADLINER'))

// Floors and set times where the venue published a running order (#2002); null for the rest.
const floors = computed(() => runningOrder(lineup.value))

/** The host of the page the lineup came from when it is not the venue's, credited under it (ADR-036). */
const lineupCredit = computed(() => {
  const url = event.value?.lineupSourceUrl
  if (!url || !lineup.value.length) return null
  try {
    return { url, host: new URL(url).host }
  } catch {
    return null
  }
})

/** `Sun 08:30–12:30`: the weekday only where a new night begins, the end only where it is known. */
function setTimeLabel(set: RunningOrderSet): string {
  const day = set.opensNight ? `${formatWeekday(set.opensNight)} ` : ''
  const span = set.start ? `${set.start}${set.end ? `–${set.end}` : ''}` : ''
  return `${day}${span}`
}

/**
 * Whether the headliners print larger than the rest, as on a festival poster. Only a lineup that
 * mixes headliners with other roles has a billing to show; a co-bill or an all-DJ night stays level.
 */
const billed = computed(
  () => showRoles.value && lineup.value.some((entry) => entry.role === 'HEADLINER'),
)

function lineupNameClass(role: string | undefined): string {
  if (!billed.value) return 'text-card-title'
  return role === 'HEADLINER' ? 'text-lede' : 'text-body'
}

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
  formatWeekday,
} = useFormat()
// The header has room for the words behind a `~` time, where the card only has a title (#1384).
const timeHint = computed(() => (event.value ? eventTimeHint(event.value) : null))
// A fact block beside the venue and the tickets, not a word under the title (#2523).
const spokenLanguage = computed(() => formatSpokenLanguage(event.value?.spokenLanguages, null))
const subtitles = computed(() => formatSpokenLanguage(null, event.value?.subtitleLanguage))
const hasTickets = computed(
  () =>
    !!event.value &&
    !!(
      formatPrice(event.value.pricePresale, event.value.priceCurrency) ||
      formatPrice(event.value.priceBoxOffice, event.value.priceCurrency) ||
      event.value.priceNote
    ),
)
// When, venue, tickets, language: three share a row, and four wrap to 2×2 rather than squeeze.
const factColumns = computed(() => {
  const optional = [event.value?.venue, hasTickets.value, spokenLanguage.value || subtitles.value]
  return optional.filter(Boolean).length === 2 ? 'sm:grid-cols-3' : 'sm:grid-cols-2'
})

const { t, te, locale } = useI18n()

// The page's URL in the body, so a report names its event without the visitor copying anything.
const reportMailto = computed(() =>
  event.value?.slug
    ? feedbackMailto(
        t('events.detail.reportSubject', { title: event.value.title ?? event.value.slug }),
        t('events.detail.reportBody', {
          url: canonicalUrl(locale.value as Locale, `/events/${event.value.slug}`),
        }),
      )
    : null,
)

// The text for this locale, its language, and whether a machine wrote it, shared with the page
// meta, the structured data and the injector (lib/description.ts). Below `useI18n()` as
// `usePageMeta` is.
const description = computed(() =>
  event.value ? descriptionFor(event.value, locale.value as Locale) : null,
)

/**
 * A backend enum's label, falling back to the raw value: `ArtistRole` lives in `events-core` and
 * can gain a value in a release that ships first. `HEADLINER` is poor; `events.role.HEADLINER` is
 * a bug report.
 */
function enumLabel(namespace: string, value: string): string {
  const key = `${namespace}.${value}`
  return te(key) ? t(key) : value
}

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
    <p v-if="loading" class="text-body text-muted-foreground">{{ t('common.states.loading') }}</p>

    <div v-else-if="notFound" class="space-y-3">
      <h1 class="text-page font-bold tracking-tight">{{ t('events.detail.notFound') }}</h1>
      <p class="text-muted-foreground">
        {{ t('events.detail.notFoundBody') }}
      </p>
      <Button as-child variant="outline">
        <RouterLink :to="localePath('/')">{{ t('common.actions.backToHome') }}</RouterLink>
      </Button>
    </div>

    <p v-else-if="error" class="text-body text-destructive">{{ error }}</p>

    <article v-else-if="event" class="space-y-8">
      <!-- The poster belongs to the title, so it sits closer than the article's stride. `mb-5` wins
           over `space-y-8` because the latter is a zero-specificity `:where()` rule. -->
      <header class="mb-5 space-y-3">
        <h1 class="text-page font-bold tracking-tight">{{ event.title }}</h1>
        <p v-if="event.subtitle" class="text-lede text-muted-foreground">{{ event.subtitle }}</p>
        <div class="flex flex-wrap items-center gap-2 text-body text-muted-foreground">
          <span>{{ formatEventDates(event) }}</span>
          <span>· {{ formatEventTime(event) }}</span>
          <span v-if="timeHint">· {{ timeHint }}</span>
          <span v-if="event.venue?.name">· {{ event.venue.name }}</span>
          <!-- The room as the venue names it (#316): a proper name, so never translated. -->
          <span v-if="event.room">· {{ event.room }}</span>
          <!-- The one exception #1248 left: a cancelled event must not read as a word in a grey row. -->
          <BaseBadge
            v-if="formatEventStatus(event.status, event.relocatedTo)"
            variant="destructive"
          >
            {{ formatEventStatus(event.status, event.relocatedTo) }}
          </BaseBadge>
          <span v-if="isPast">· {{ t('events.card.past') }}</span>
          <span v-else-if="isRunning" class="text-primary">
            · {{ t('events.card.runningSince', { day: formatWeekday(event.eventDate) }) }}
          </span>
          <span v-else-if="event.soldOut" class="font-medium text-destructive">
            · {{ t('events.card.soldOut') }}
          </span>
          <span v-else-if="event.free" class="font-medium text-success">
            · {{ t('events.card.free') }}
          </span>
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
      <div v-if="event.imageUrl">
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
        class="whitespace-pre-line wrap-anywhere text-foreground/90"
      >
        {{ description.text }}
      </p>
      <!-- Machine output is never presented as the venue's own words. -->
      <p v-if="description?.machine" class="text-body text-muted-foreground">
        {{ t('events.detail.machineTranslated') }}
        <a
          v-if="event.sourceUrl"
          :href="event.sourceUrl"
          class="underline underline-offset-2"
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

      <section v-if="floors" class="space-y-3">
        <SectionLabel>{{ t('events.detail.runningOrder') }}</SectionLabel>
        <div v-for="floor in floors" :key="floor.stage ?? ''" class="space-y-1">
          <h3 v-if="floor.stage" class="pt-2 text-body font-medium text-muted-foreground">
            {{ floor.stage }}
          </h3>
          <ul :class="CARD_LIST_CLASS">
            <li
              v-for="set in floor.sets"
              :key="set.entry.artist?.slug ?? set.entry.artist?.name"
              class="flex items-baseline gap-3 py-3"
            >
              <span class="w-28 shrink-0 text-meta text-muted-foreground tabular-nums">
                {{ setTimeLabel(set) }}
              </span>
              <RouterLink
                v-if="set.entry.artist?.slug"
                :to="localePath(`/artists/${set.entry.artist.slug}`)"
                class="min-w-0 flex-1 text-card-title font-medium hover:text-primary"
              >
                {{ set.entry.artist.name }}
              </RouterLink>
              <span v-else class="min-w-0 flex-1 text-card-title font-medium">
                {{ set.entry.artist?.name }}
              </span>
              <span
                v-if="showRoles && set.entry.role"
                class="shrink-0 text-meta text-muted-foreground"
              >
                {{ enumLabel('events.role', set.entry.role) }}
              </span>
            </li>
          </ul>
        </div>
      </section>

      <section v-else-if="lineup.length" class="space-y-3">
        <SectionLabel>{{ t('events.detail.lineup') }}</SectionLabel>
        <ul :class="CARD_LIST_CLASS">
          <li
            v-for="entry in lineup"
            :key="entry.artist?.slug ?? entry.artist?.name"
            class="flex items-baseline justify-between gap-3 py-3"
          >
            <RouterLink
              v-if="entry.artist?.slug"
              :to="localePath(`/artists/${entry.artist.slug}`)"
              :class="cn(lineupNameClass(entry.role), 'font-medium hover:text-primary')"
            >
              {{ entry.artist.name }}
            </RouterLink>
            <span v-else :class="cn(lineupNameClass(entry.role), 'font-medium')">
              {{ entry.artist?.name }}
            </span>
            <div class="flex shrink-0 items-center gap-2 text-meta text-muted-foreground">
              <span v-if="entry.stage">{{ entry.stage }}</span>
              <span v-if="entry.stage && showRoles && entry.role" aria-hidden="true">·</span>
              <span v-if="showRoles && entry.role">
                {{ enumLabel('events.role', entry.role) }}
              </span>
            </div>
          </li>
        </ul>
      </section>

      <p v-if="lineupCredit" class="text-meta text-muted-foreground">
        {{ t('events.detail.lineupFrom') }}
        <a
          class="underline underline-offset-2"
          :href="lineupCredit.url"
          rel="noopener"
          target="_blank"
          >{{ lineupCredit.host }}</a
        >
      </p>

      <section :class="cn('grid grid-cols-1 gap-6', factColumns)">
        <EventWhen :event="event" />

        <div v-if="event.venue" class="space-y-1">
          <SectionLabel>{{ t('events.detail.venue') }}</SectionLabel>
          <RouterLink
            v-if="event.venue.slug"
            :to="localePath(`/venues/${event.venue.slug}`)"
            class="font-medium text-primary underline-offset-4 hover:underline"
          >
            {{ event.venue.name }}
          </RouterLink>
          <p v-else class="font-medium">{{ event.venue.name }}</p>
          <p v-if="event.venue.address" class="text-body text-muted-foreground">
            {{ event.venue.address
            }}<template v-if="event.venue.city">, {{ event.venue.city }}</template>
          </p>
        </div>

        <div v-if="hasTickets" class="space-y-1">
          <SectionLabel>{{ t('events.detail.tickets') }}</SectionLabel>
          <p v-if="formatPrice(event.pricePresale, event.priceCurrency)" class="text-body">
            {{ t('events.detail.presale') }}:
            {{ formatPrice(event.pricePresale, event.priceCurrency) }}
          </p>
          <p v-if="formatPrice(event.priceBoxOffice, event.priceCurrency)" class="text-body">
            {{ t('events.detail.boxOffice') }}:
            {{ formatPrice(event.priceBoxOffice, event.priceCurrency) }}
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
              class="text-primary underline-offset-4 hover:underline"
            >
              {{ promoter.name }}</RouterLink
            >
            <span v-else>{{ promoter.name }}</span>
            <span v-if="index < event.promoters.length - 1">, </span>
          </template>
        </p>
      </section>

      <!--
        No ticket CTA once the night has happened, nor on the row a show left — the house it
        moved to sells the tickets (#1551); source and Facebook stay honest. Share closes the row.
      -->
      <EventShareActions :event="event">
        <Button v-if="event.ticketUrl && !isPast && !event.relocatedTo" as-child>
          <a :href="event.ticketUrl" rel="noopener noreferrer" target="_blank">{{
            t('events.detail.buyTickets')
          }}</a>
        </Button>
        <Button v-if="event.sourceUrl" as-child variant="outline">
          <a :href="event.sourceUrl" rel="noopener noreferrer" target="_blank">{{
            t('events.detail.eventPage')
          }}</a>
        </Button>
        <Button v-if="event.facebookEventUrl" as-child variant="outline">
          <a :href="event.facebookEventUrl" rel="noopener noreferrer" target="_blank">{{
            t('events.detail.facebook')
          }}</a>
        </Button>
      </EventShareActions>

      <!-- The address is the link text, so a visitor without a mail client can still copy it. -->
      <p v-if="reportMailto" class="text-body text-muted-foreground">
        {{ t('events.detail.reportWrong') }}
        <a :href="reportMailto" class="text-foreground underline underline-offset-4">{{
          CONTROLLER.email
        }}</a>
      </p>
    </article>

    <!-- Outside the article: these are other events, not facts about this one. -->
    <section v-if="event && related.length" class="space-y-4" data-testid="related-events">
      <SectionLabel>{{ t('events.detail.related') }}</SectionLabel>
      <div v-if="compact" :class="CARD_LIST_CLASS">
        <EventRow v-for="other in related" :key="other.slug" :event="other" />
      </div>
      <div v-else :class="CARD_GRID_CLASS">
        <EventCard v-for="other in related" :key="other.slug" :event="other" />
      </div>
    </section>
  </main>
</template>
