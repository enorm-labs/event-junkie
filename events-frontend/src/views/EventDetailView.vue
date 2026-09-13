<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import BaseBadge from '@/components/BaseBadge.vue'
import CachedImage from '@/components/CachedImage.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { useEvent } from '@/composables/useEvent'
import { descriptionFor } from '@/lib/description'
import { usePageMeta } from '@/composables/usePageMeta'
import { APP_NAME, eventPageMeta, placeholderPageMeta } from '@/lib/pageMeta'
import { useStructuredData } from '@/composables/useStructuredData'
import { breadcrumbJsonLd, eventJsonLd, type JsonLd } from '@/lib/structuredData'
import type { Locale } from '@/i18n/locales'
import { formatPrice, isPastEvent } from '@/lib/format'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const slug = computed(() => String(route.params.slug))

const { data: event, error, notFound, loading, run } = useEvent(() => slug.value)

// Lineup arrives in billing order already, but sort defensively so headliners stay first.
const lineup = computed(() =>
  [...(event.value?.lineup ?? [])].sort((a, b) => (a.billingOrder ?? 0) - (b.billingOrder ?? 0)),
)

/**
 * Whether the lineup's role labels carry information.
 *
 * An importer that reads a co-bill like `Alibi + Onyon + Tense` bills every act `HEADLINER`, because
 * the venue named no order. Three "Headliner" tags say nothing the list does not, so an all-headliner
 * lineup drops them. An all-DJ night keeps its tags: `DJ` says the acts play records, not live.
 */
const showRoles = computed(() => lineup.value.some((entry) => entry.role !== 'HEADLINER'))

const isPast = computed(() => isPastEvent(event.value?.eventDate))

onMounted(run)
watch(slug, run)

const localePath = useLocalePath()
const { formatDate, formatEventTime, eventTimeHint } = useFormat()
// The header has room for the words behind a `~` time, where the card only has a title (#1384).
const timeHint = computed(() => (event.value ? eventTimeHint(event.value) : null))

const { t, te, locale } = useI18n()

// The text for this locale, its language, and whether a machine wrote it. Shared with the page
// meta, the structured data and the injector — see lib/description.ts. Below `useI18n()` for the
// same reason `usePageMeta` is.
const description = computed(() =>
  event.value ? descriptionFor(event.value, locale.value as Locale) : null,
)

/**
 * A backend enum's label, falling back to the raw value.
 *
 * `ArtistRole` and `EventStatus` live in `events-core`, so the BFF can gain a value in a release
 * that ships before the frontend — the same reason `humaniseEventType` exists for event types.
 * Showing `CANCELLED` is poor; showing `events.status.CANCELLED` is a bug report, and that is what
 * an unguarded lookup renders.
 */
function enumLabel(namespace: string, value: string): string {
  const key = `${namespace}.${value}`
  return te(key) ? t(key) : value
}

// Title, description and image for this page. The description leads with the date and venue
// rather than the promotional blurb, because that is what someone deciding whether to open a link
// in a group chat actually wants — see lib/pageMeta.ts.
//
// Below `useI18n()` on purpose: `watchEffect` runs its effect immediately, so a getter reading
// `locale` from above this line would hit the temporal dead zone at setup rather than at render.
usePageMeta(() =>
  event.value
    ? eventPageMeta(event.value, locale.value as Locale)
    : placeholderPageMeta(
        notFound.value
          ? t('detail.notFoundHeading', { kind: t('events.detail.kind') })
          : t('events.detail.kind'),
      ),
)

// The rich-result payload: an Event document plus the breadcrumb trail Search renders instead of a
// bare URL. `eventJsonLd` returns null when Google's required fields are missing, which is why
// this filters rather than assuming. See lib/structuredData.ts.
useStructuredData((): JsonLd[] => {
  const current = event.value
  if (!current?.slug || !current.title) return []

  return [
    eventJsonLd(current, locale.value as Locale),
    breadcrumbJsonLd(
      [
        [APP_NAME, ''],
        [t('common.nav.events'), '/events'],
        [current.title, `/events/${current.slug}`],
      ],
      locale.value as Locale,
    ),
  ].filter((document): document is JsonLd => document !== null)
})
</script>

<template>
  <main class="mx-auto max-w-3xl space-y-8 p-4 sm:p-8">
    <p v-if="loading" class="text-sm text-muted-foreground">{{ t('common.states.loading') }}</p>

    <div v-else-if="notFound" class="space-y-3">
      <h1 class="text-section font-bold tracking-tight">{{ t('events.detail.notFound') }}</h1>
      <p class="text-muted-foreground">
        {{ t('events.detail.notFoundBody') }}
      </p>
      <Button as-child variant="outline">
        <RouterLink :to="localePath('/')">{{ t('common.actions.backToHome') }}</RouterLink>
      </Button>
    </div>

    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>

    <article v-else-if="event" class="space-y-8">
      <!-- The poster belongs to the title, so it sits closer than the article's stride. `mb-5` wins
           over `space-y-8` because the latter is a zero-specificity `:where()` rule. -->
      <header class="mb-5 space-y-3">
        <h1 class="text-page font-bold tracking-tight">{{ event.title }}</h1>
        <p v-if="event.subtitle" class="text-lede text-muted-foreground">{{ event.subtitle }}</p>
        <div class="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span>{{ formatDate(event.eventDate) }}</span>
          <span>· {{ formatEventTime(event) }}</span>
          <span v-if="timeHint">· {{ timeHint }}</span>
          <span v-if="event.venue?.name">· {{ event.venue.name }}</span>
          <!--
            The status pill is the exception the rest of #1248 flattened: a cancelled event is the
            one thing on this page that must not read as another word in a grey row.
          -->
          <BaseBadge v-if="event.status && event.status !== 'SCHEDULED'" variant="destructive">
            {{ enumLabel('events.status', event.status) }}
          </BaseBadge>
          <span v-if="isPast">· {{ t('events.card.past') }}</span>
          <span v-else-if="event.soldOut" class="font-medium text-destructive">
            · {{ t('events.card.soldOut') }}
          </span>
          <span v-else-if="event.free" class="font-medium text-success">
            · {{ t('events.card.free') }}
          </span>
        </div>
        <!--
          A past event is where a search engine sends people, and it kept ranking for the act's name
          long after the night. Saying only that it is over left the venue's own site as the nearest
          onward link (#1268), so the sentence carries one that stays here — the venue if we know
          it, since the visitor has already shown interest in it, and the list otherwise.
        -->
        <p v-if="isPast" class="text-sm text-muted-foreground">
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
        The widest image on the site, drawn across a `max-w-3xl` column: 704 px once `sm:p-8` is
        subtracted, and the viewport minus its padding below that. Those three lengths are what turn
        `srcset`'s pixel widths into a choice, so they track the `<main>` classes above.

        The wrapper is the spacing. `space-y-8` puts its margin on the child that precedes the gap,
        and `CachedImage` renders a `display: contents` <picture>, which has no box to carry one.

        No poster, no placeholder. #811 draws one on a card, where a hole in a grid reads as broken.
        Here the title is the content, and a full-width 3:2 void would push it off the screen.

        `eager` because the poster is the LCP element on this page, which is what #1207 reports.
      -->
      <div v-if="event.imageUrl">
        <CachedImage
          :src="event.imageUrl"
          :sources="event.imageSources"
          :intrinsic-width="event.intrinsicWidth"
          :intrinsic-height="event.intrinsicHeight"
          :alt="event.title ?? ''"
          loading="eager"
          sizes="(min-width: 768px) 704px, (min-width: 640px) calc(100vw - 4rem), calc(100vw - 2rem)"
          img-class="w-full border border-border object-cover"
        />
      </div>

      <!--
        `lang` marks the text, not the page. A German description on /en/ is what the venue wrote,
        and declaring it lets a screen reader pronounce it and a browser offer to translate it.
        Absent when the importer could not tell, which is the honest answer (ADR-026).
      -->
      <p
        v-if="description"
        :lang="description.lang ?? undefined"
        class="whitespace-pre-line text-foreground/90"
      >
        {{ description.text }}
      </p>
      <!-- Machine output is never presented as the venue's own words. -->
      <p v-if="description?.machine" class="text-sm text-muted-foreground">
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
        Only where a licence removed a description, never where the venue wrote none. On a seeded
        database that is 56 events against 1,072, so a note keyed on `description` being null would
        be wrong twenty times more often than right — which is why the API reports the reason (#811).

        It says where the text is and nothing about what the venue wants. Both prohibitions were
        read off an Impressum rather than sent to us (#809), so "at the venue's request" would be a
        position we invented for them.
      -->
      <p v-if="!description && event.descriptionWithheld" class="text-sm text-muted-foreground">
        {{ t('events.detail.descriptionElsewhere') }}
      </p>

      <section v-if="lineup.length" class="space-y-3">
        <SectionLabel>{{ t('events.detail.lineup') }}</SectionLabel>
        <ul class="space-y-2">
          <li
            v-for="entry in lineup"
            :key="entry.artist?.slug ?? entry.artist?.name"
            class="flex items-center justify-between gap-3 rounded-lg border border-border p-3"
          >
            <RouterLink
              v-if="entry.artist?.slug"
              :to="localePath(`/artists/${entry.artist.slug}`)"
              class="font-medium text-primary underline-offset-4 hover:underline"
            >
              {{ entry.artist.name }}
            </RouterLink>
            <span v-else class="font-medium">{{ entry.artist?.name }}</span>
            <div class="flex items-center gap-2 text-meta text-muted-foreground">
              <span v-if="entry.stage">{{ entry.stage }}</span>
              <span v-if="entry.stage && showRoles && entry.role" aria-hidden="true">·</span>
              <span v-if="showRoles && entry.role">
                {{ enumLabel('events.role', entry.role) }}
              </span>
            </div>
          </li>
        </ul>
      </section>

      <section class="grid grid-cols-1 gap-6 sm:grid-cols-2">
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
          <p v-if="event.venue.address" class="text-sm text-muted-foreground">
            {{ event.venue.address
            }}<template v-if="event.venue.city">, {{ event.venue.city }}</template>
          </p>
        </div>

        <div
          v-if="
            formatPrice(event.pricePresale, event.priceCurrency) ||
            formatPrice(event.priceBoxOffice, event.priceCurrency) ||
            event.priceNote
          "
          class="space-y-1"
        >
          <SectionLabel>{{ t('events.detail.tickets') }}</SectionLabel>
          <p v-if="formatPrice(event.pricePresale, event.priceCurrency)" class="text-sm">
            {{ t('events.detail.presale') }}:
            {{ formatPrice(event.pricePresale, event.priceCurrency) }}
          </p>
          <p v-if="formatPrice(event.priceBoxOffice, event.priceCurrency)" class="text-sm">
            {{ t('events.detail.boxOffice') }}:
            {{ formatPrice(event.priceBoxOffice, event.priceCurrency) }}
          </p>
          <p v-if="event.priceNote" class="text-sm text-muted-foreground">{{ event.priceNote }}</p>
        </div>
      </section>

      <section v-if="event.promoters?.length" class="space-y-1">
        <SectionLabel>{{ t('events.detail.promoters') }}</SectionLabel>
        <p class="flex flex-wrap gap-x-1 text-sm">
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

      <!-- No ticket CTA once the night has happened; source and Facebook stay honest. -->
      <section
        v-if="(event.ticketUrl && !isPast) || event.sourceUrl || event.facebookEventUrl"
        class="flex flex-wrap gap-3"
      >
        <Button v-if="event.ticketUrl && !isPast" as-child>
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
      </section>
    </article>
  </main>
</template>
