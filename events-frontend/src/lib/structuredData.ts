import type { EventDetail, VenueDetail } from '@/api/types'
import { canonicalUrl, SITE_URL } from '@/lib/seo'
import { APP_NAME } from '@/lib/pageMeta'
import type { Locale } from '@/i18n/locales'

/**
 * schema.org documents, as JSON-LD.
 *
 * The point of this file is Google's **event rich results** — the date-and-venue cards in Search.
 * It is the only rich result this product is a candidate for, and eligibility requires structured
 * data. Two rules govern everything below, both Google policy rather than taste:
 *
 * 1. **Never describe anything the page does not show.** Every property emitted here is rendered by
 *    `EventDetailView.vue` or `VenueDetailView.vue` — check before adding one.
 * 2. **Omit rather than guess.** An absent property costs a recommendation; a wrong one is a
 *    misrepresentation we volunteered. Every `?? undefined` below is deliberate.
 *
 * Unlike `seoTags.ts` this needs no prerendering: Googlebot runs JavaScript and reads JSON-LD
 * injected after boot, which is why it shipped before the rendering decision.
 */

/** Anything JSON-serialisable that a schema.org document can hold. */
export type JsonLd = Record<string, unknown>

/** Berlin. Every venue in scope is here, so the timezone is a constant rather than venue data. */
const TIME_ZONE = 'Europe/Berlin'

/**
 * The UTC offset Berlin was on for a given date, as `+02:00`.
 *
 * **Computed per date, never hardcoded.** Berlin is `+01:00` in winter and `+02:00` in summer, so
 * a fixed offset silently misstates the start time of roughly half the year's events — by exactly
 * one hour, which is small enough to look like a typo and large enough to make someone miss a
 * support act.
 *
 * `Intl` rather than the `temporal-polyfill` dependency: this is one lookup, and the polyfill is
 * not currently imported anywhere in the app.
 */
function berlinOffset(isoDate: string): string {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: TIME_ZONE,
    timeZoneName: 'longOffset',
  }).formatToParts(new Date(`${isoDate}T12:00:00Z`))
  // "GMT+02:00" → "+02:00". Bare "GMT" means UTC, which Berlin never is, but handle it anyway.
  return parts.find((part) => part.type === 'timeZoneName')?.value.replace('GMT', '') || '+01:00'
}

/**
 * `startDate` in the form Google prefers: a local datetime with an explicit offset.
 *
 * Falls back to the bare date when no start time is known — valid ISO 8601, and accepted. Guessing
 * a time would be worse than omitting one: a wrong start time is the single most damaging thing
 * this file could publish.
 */
export function eventStartDate(event: EventDetail): string | undefined {
  if (!event.eventDate) return undefined
  const time = event.startTime ?? event.doorsTime
  if (!time) return event.eventDate
  return `${event.eventDate}T${time.slice(0, 5)}:00${berlinOffset(event.eventDate)}`
}

/**
 * The BFF's scheduling status, in schema.org's vocabulary.
 *
 * `RELOCATED` has no counterpart — schema.org offers `EventRescheduled` (a time change) and
 * `EventMovedOnline`, neither of which is a venue change. It is left undefined, which Google reads
 * as the default `EventScheduled`: true, since a relocated event is still going ahead.
 */
const EVENT_STATUS: Record<string, string> = {
  SCHEDULED: 'https://schema.org/EventScheduled',
  CANCELLED: 'https://schema.org/EventCancelled',
  POSTPONED: 'https://schema.org/EventPostponed',
}

/**
 * The most specific schema.org type each event kind maps to.
 *
 * Specificity helps only where it is accurate. `READING` and `SHOW` have no good subtype — there is
 * no `LiteraryEvent`, and `TheaterEvent` would assert a form we do not know — so they stay `Event`.
 */
const EVENT_TYPES: Record<string, string> = {
  CONCERT: 'MusicEvent',
  FESTIVAL: 'MusicEvent',
  CLUB_NIGHT: 'MusicEvent',
  PARTY: 'SocialEvent',
  QUIZ: 'SocialEvent',
  SCREENING: 'ScreeningEvent',
  EXHIBITION: 'ExhibitionEvent',
}

function offers(event: EventDetail, url: string): JsonLd | undefined {
  const price = event.free ? 0 : (event.pricePresale ?? event.priceBoxOffice)
  if (price == null) return undefined

  return {
    '@type': 'Offer',
    price,
    priceCurrency: event.priceCurrency ?? 'EUR',
    availability: event.soldOut ? 'https://schema.org/SoldOut' : 'https://schema.org/InStock',
    // The ticket seller where we have one: they hold the authoritative price, and ours is a
    // scraped snapshot that may already be stale. Our own page is the honest fallback.
    url: event.ticketUrl ?? url,
  }
}

/**
 * A performing artist.
 *
 * **`PerformingGroup`, not `Person`** — and the reason is not only that most of these are bands.
 * We cannot tell a solo act from a group, and of the two available guesses only one asserts that a
 * named individual is a natural person. LEGAL.md §7.3 treats artist names as personal
 * data precisely because some are; publishing a machine-readable claim about which is gratuitous.
 * Google accepts either type for `performer`.
 */
function performers(event: EventDetail): JsonLd[] | undefined {
  const names = (event.lineup ?? [])
    .map((entry) => entry.artist?.name)
    .filter((name): name is string => Boolean(name))
  return names.length ? names.map((name) => ({ '@type': 'PerformingGroup', name })) : undefined
}

/** The venue, as a `Place` with a postal address — Google requires both for an event. */
function eventLocation(event: EventDetail): JsonLd | undefined {
  const venue = event.venue
  if (!venue?.name) return undefined

  return {
    '@type': 'Place',
    name: venue.name,
    address: {
      '@type': 'PostalAddress',
      streetAddress: venue.address ?? undefined,
      addressLocality: venue.city ?? 'Berlin',
      addressCountry: 'DE',
    },
    url: venue.slug ? `${SITE_URL}/en/venues/${venue.slug}` : undefined,
  }
}

/**
 * An event, as schema.org.
 *
 * Google's **required** properties are `name`, `startDate` and `location` (with an address); this
 * returns `null` rather than an incomplete document when any is missing, because partial
 * structured data is not partially useful — it is rejected, and it costs a crawl to find out.
 */
export function eventJsonLd(event: EventDetail, locale: Locale): JsonLd | null {
  const url = event.slug ? canonicalUrl(locale, `/events/${event.slug}`) : undefined
  const startDate = eventStartDate(event)
  const location = eventLocation(event)

  if (!event.title || !startDate || !location) return null

  return {
    '@context': 'https://schema.org',
    '@type': (event.eventType && EVENT_TYPES[event.eventType]) ?? 'Event',
    name: event.title,
    startDate,
    location,
    url,
    description: event.description ?? event.subtitle ?? undefined,
    image: event.imageUrl ?? undefined,
    eventStatus: (event.status && EVENT_STATUS[event.status]) ?? undefined,
    // Every event in scope is a physical one; we list nothing online-only.
    eventAttendanceMode: 'https://schema.org/OfflineEventAttendanceMode',
    performer: performers(event),
    organizer: event.promoters?.length
      ? event.promoters
          .filter((promoter) => promoter.name)
          .map((promoter) => ({ '@type': 'Organization', name: promoter.name }))
      : undefined,
    offers: url ? offers(event, url) : undefined,
  }
}

/** A venue, as a `MusicVenue` — the subtype that matches what this site actually lists. */
export function venueJsonLd(venue: VenueDetail, locale: Locale): JsonLd | null {
  if (!venue.name) return null

  return {
    '@context': 'https://schema.org',
    '@type': 'MusicVenue',
    name: venue.name,
    url: venue.slug ? canonicalUrl(locale, `/venues/${venue.slug}`) : undefined,
    description: venue.description ?? undefined,
    image: venue.imageUrl ?? undefined,
    sameAs: venue.websiteUrl ?? undefined,
    address: {
      '@type': 'PostalAddress',
      streetAddress: venue.address ?? undefined,
      postalCode: venue.postalCode ?? undefined,
      addressLocality: venue.city ?? 'Berlin',
      addressCountry: 'DE',
    },
    geo:
      venue.latitude != null && venue.longitude != null
        ? { '@type': 'GeoCoordinates', latitude: venue.latitude, longitude: venue.longitude }
        : undefined,
  }
}

/**
 * The trail Search shows in place of a bare URL.
 *
 * `items` are `[label, locale-relative path]`; the last one is the current page and carries no
 * link, per Google's guidance.
 */
export function breadcrumbJsonLd(items: [string, string][], locale: Locale): JsonLd {
  return {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: items.map(([name, path], index) => ({
      '@type': 'ListItem',
      position: index + 1,
      name,
      item: index === items.length - 1 ? undefined : canonicalUrl(locale, path),
    })),
  }
}

/**
 * The site itself.
 *
 * `WebSite` only — deliberately **not** `Organization`. The imprint states that Event Junkie is run
 * by a private individual and not a company (§ 5 DDG); publishing an `Organization` claim would
 * contradict our own legal page in a format built for machines to believe.
 */
export function websiteJsonLd(locale: Locale): JsonLd {
  return {
    '@context': 'https://schema.org',
    '@type': 'WebSite',
    name: APP_NAME,
    url: canonicalUrl(locale, ''),
    inLanguage: locale,
  }
}
