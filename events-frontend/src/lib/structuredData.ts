import type { EventDetail, VenueDetail } from '@/api/types'
import { descriptionFor } from '@/lib/description'
import { absoluteImageUrl, canonicalUrl, SITE_URL } from '@/lib/seo'
import { isPastEvent } from '@/lib/format'
import { APP_NAME } from '@/lib/pageMeta'
import type { Locale } from '@/i18n/locales'

/**
 * schema.org documents, as JSON-LD, for Google's event rich results, the only rich result this
 * product is a candidate for. Two rules, both Google policy:
 *
 * 1. Never describe anything the page does not show: every property here is rendered by
 * `EventDetailView.vue` or `VenueDetailView.vue`.
 * 2. Omit rather than guess: an absent property costs a recommendation, a wrong one is a
 * misrepresentation we volunteered. Every `?? undefined` is deliberate.
 *
 * Needs no prerendering: Googlebot runs JavaScript and reads JSON-LD injected after boot.
 */

/** Anything JSON-serialisable that a schema.org document can hold. */
export type JsonLd = Record<string, unknown>

/** Berlin. Every venue in scope is here, so the timezone is a constant rather than venue data. */
const TIME_ZONE = 'Europe/Berlin'

/**
 * The UTC offset Berlin was on for a given date, as `+02:00`. Computed per date: Berlin is
 * `+01:00` in winter and `+02:00` in summer, and a fixed offset misstates half the year's start
 * times by one hour. `Intl` rather than `temporal-polyfill`, which nothing else imports.
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
 * `startDate` as Google prefers: a local datetime with an explicit offset. Falls back to the bare
 * date when no start time is known; a wrong start time is the most damaging thing this file could
 * publish.
 */
export function eventStartDate(event: EventDetail): string | undefined {
  if (!event.eventDate) return undefined
  const time = event.startTime ?? event.doorsTime
  if (!time) return event.eventDate
  return `${event.eventDate}T${time.slice(0, 5)}:00${berlinOffset(event.eventDate)}`
}

/**
 * `endDate` only when the venue stated an end (ADR-029). Never derived.
 */
export function eventEndDate(event: EventDetail): string | undefined {
  if (!event.endDate) return undefined
  if (!event.endTime) return event.endDate
  return `${event.endDate}T${event.endTime.slice(0, 5)}:00${berlinOffset(event.endDate)}`
}

/**
 * The BFF's scheduling status in schema.org's vocabulary. `RELOCATED` has no counterpart
 * (`EventRescheduled` is a time change, `EventMovedOnline` is not a venue change), so it stays
 * undefined, which Google reads as `EventScheduled`: true, the event is still going ahead.
 */
const EVENT_STATUS: Record<string, string> = {
  SCHEDULED: 'https://schema.org/EventScheduled',
  CANCELLED: 'https://schema.org/EventCancelled',
  POSTPONED: 'https://schema.org/EventPostponed',
}

/**
 * The most specific schema.org type per event kind. `READING` and `SHOW` stay `Event`: there is no
 * `LiteraryEvent`, and `TheaterEvent` would assert a form we do not know.
 */
const EVENT_TYPES: Record<string, string> = {
  CONCERT: 'MusicEvent',
  FESTIVAL: 'MusicEvent',
  PARTY: 'SocialEvent',
  QUIZ: 'SocialEvent',
  SCREENING: 'ScreeningEvent',
  EXHIBITION: 'ExhibitionEvent',
}

function offers(event: EventDetail, url: string): JsonLd | undefined {
  // Rule 1: a past event's page shows no ticket link, so this publishes none.
  if (isPastEvent(event)) return undefined

  const price = event.free ? 0 : (event.pricePresale ?? event.priceBoxOffice)
  if (price == null) return undefined

  return {
    '@type': 'Offer',
    price,
    priceCurrency: event.priceCurrency ?? 'EUR',
    availability: event.soldOut ? 'https://schema.org/SoldOut' : 'https://schema.org/InStock',
    // The ticket seller holds the authoritative price; ours is a scraped snapshot. Our page is the
    // honest fallback.
    url: event.ticketUrl ?? url,
  }
}

/**
 * `PerformingGroup`, not `Person`: we cannot tell a solo act from a group, and only one guess
 * asserts that a named individual is a natural person, which LEGAL.md §7.3 treats as personal
 * data. Google accepts either for `performer`.
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
 * An event. Google requires `name`, `startDate` and `location` with an address; this returns
 * `null` rather than an incomplete document, because partial structured data is rejected and
 * costs a crawl to find out.
 */
export function eventJsonLd(event: EventDetail, locale: Locale): JsonLd | null {
  const url = event.slug ? canonicalUrl(locale, `/events/${event.slug}`) : undefined
  const startDate = eventStartDate(event)
  const location = eventLocation(event)

  if (!event.title || !startDate || !location) return null

  // The language of the text shown, not of the page: a German description on /en/ says `de`, an
  // unclassified one says nothing (ADR-026).
  const description = descriptionFor(event, locale)

  return {
    '@context': 'https://schema.org',
    '@type': (event.eventType && EVENT_TYPES[event.eventType]) ?? 'Event',
    name: event.title,
    startDate,
    endDate: eventEndDate(event),
    location,
    url,
    description: description?.text ?? event.subtitle ?? undefined,
    inLanguage: description?.lang ?? undefined,
    image: absoluteImageUrl(event.imageUrl),
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

  // The language of the text this page shows, for the same reason the event builder declares it.
  const description = descriptionFor(venue, locale)

  return {
    '@context': 'https://schema.org',
    '@type': 'MusicVenue',
    name: venue.name,
    url: venue.slug ? canonicalUrl(locale, `/venues/${venue.slug}`) : undefined,
    description: description?.text ?? undefined,
    inLanguage: description?.lang ?? undefined,
    image: absoluteImageUrl(venue.imageUrl),
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
 * The trail Search shows in place of a bare URL. `items` are `[label, locale-relative path]`;
 * the last is the current page and carries no link.
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
 * The site itself, as `WebSite` and not `Organization`: the imprint states Event Junkie is run
 * by a private individual (§ 5 DDG), and an `Organization` claim would contradict it in a format
 * built for machines to believe.
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
