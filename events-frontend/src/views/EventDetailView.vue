<script lang="ts" setup>
import { computed, onMounted, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import BaseBadge from '@/components/BaseBadge.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { useEvent } from '@/composables/useEvent'
import { usePageMeta } from '@/composables/usePageMeta'
import { APP_NAME, eventPageMeta, placeholderPageMeta } from '@/lib/pageMeta'
import { useStructuredData } from '@/composables/useStructuredData'
import { breadcrumbJsonLd, eventJsonLd, type JsonLd } from '@/lib/structuredData'
import type { Locale } from '@/i18n/locales'
import { formatPrice, formatTime } from '@/lib/format'
import { useFormat } from '@/composables/useFormat'
import { useLocalePath } from '@/composables/useLocalePath'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const slug = computed(() => String(route.params.slug))

const { data: event, error, notFound, loading, run } = useEvent(() => slug.value)

// Title, description and image for this page. The description leads with the date and venue
// Lineup arrives in billing order already, but sort defensively so headliners stay first.
const lineup = computed(() =>
  [...(event.value?.lineup ?? [])].sort((a, b) => (a.billingOrder ?? 0) - (b.billingOrder ?? 0)),
)

onMounted(run)
watch(slug, run)

const localePath = useLocalePath()
const { formatDate } = useFormat()

const { t, te, locale } = useI18n()

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
      <h1 class="text-2xl font-bold tracking-tight">{{ t('events.detail.notFound') }}</h1>
      <p class="text-muted-foreground">
        {{ t('events.detail.notFoundBody') }}
      </p>
      <Button as-child variant="outline">
        <RouterLink :to="localePath('/')">{{ t('common.actions.backToHome') }}</RouterLink>
      </Button>
    </div>

    <p v-else-if="error" class="text-sm text-destructive">{{ error }}</p>

    <article v-else-if="event" class="space-y-8">
      <header class="space-y-3">
        <h1 class="text-3xl font-bold tracking-tight">{{ event.title }}</h1>
        <p v-if="event.subtitle" class="text-lg text-muted-foreground">{{ event.subtitle }}</p>
        <div class="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span>{{ formatDate(event.eventDate) }}</span>
          <span v-if="event.startTime">· {{ formatTime(event.startTime) }}</span>
          <span v-if="event.venue?.name">· {{ event.venue.name }}</span>
          <BaseBadge v-if="event.status && event.status !== 'SCHEDULED'" variant="destructive">
            {{ enumLabel('events.status', event.status) }}
          </BaseBadge>
          <BaseBadge v-if="event.soldOut" variant="destructive">{{
            t('events.card.soldOut')
          }}</BaseBadge>
          <BaseBadge v-else-if="event.free" variant="success">{{
            t('events.card.free')
          }}</BaseBadge>
        </div>
      </header>

      <img
        v-if="event.imageUrl"
        :alt="event.title ?? ''"
        :src="event.imageUrl"
        class="w-full rounded-lg border border-border object-cover"
        loading="lazy"
      />

      <p v-if="event.description" class="whitespace-pre-line text-foreground/90">
        {{ event.description }}
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
            <div class="flex items-center gap-2 text-xs text-muted-foreground">
              <BaseBadge v-if="entry.stage" variant="outline">{{ entry.stage }}</BaseBadge>
              <span v-if="entry.role">
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

      <section
        v-if="event.ticketUrl || event.sourceUrl || event.facebookEventUrl"
        class="flex flex-wrap gap-3"
      >
        <Button v-if="event.ticketUrl" as-child>
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
