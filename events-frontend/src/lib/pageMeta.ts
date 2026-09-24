import type { ArtistDetail, EventDetail, PromoterDetail, VenueDetail } from '@/api/types'
import { descriptionFor } from '@/lib/description'
import { formatDate } from '@/lib/format'
import { INTL_LOCALES, type Locale } from '@/i18n/locales'
import { absoluteImageUrl } from '@/lib/seo'

/**
 * What each page calls itself: title, description, representative image.
 *
 * Used twice: the client writes these tags after boot, and the meta injector (ADR-014 §Decision 3)
 * writes the same tags server-side for scrapers that do not run JavaScript; both read from here
 * so a shared link previews as what it opens as. Hence a detail page's description is composed from
 * data and punctuation, never prose; a static page's prose comes from the catalogue, through
 * `lib/staticPages.ts`. Canonical URLs are absent, `canonicalUrl()` in `lib/seo.ts` being the one
 * source.
 */

/** Brand name shown in the browser tab, appended to every interior view's title. */
export const APP_NAME = 'Event Junkie'

/** Homepage tagline — the descriptor best practice recommends over a bare brand name. */
export const TAGLINE = "Can't get enough of Berlin"

/** The root/home title: brand plus tagline. Interior views use `<page> · Event Junkie`. */
export const HOME_TITLE = `${APP_NAME} — ${TAGLINE}`

/**
 * Formats an interior page title as `<page> · Event Junkie`; falls back to the home title. Here
 * rather than with the composable so this module stays free of Vue and the DOM for the injector.
 */
export function formatTitle(title?: string | null): string {
  return title ? `${title} · ${APP_NAME}` : HOME_TITLE
}

export interface PageMeta {
  /** The full document title, already suffixed with the brand — see `formatTitle`. */
  title: string
  /**
   * One or two sentences for `<meta name="description">` and `og:description`. Left undefined
   * rather than invented: the site-level description beats a padded one.
   */
  description?: string
  /** Absolute URL of a representative image, when the entity has one. */
  image?: string
}

/**
 * Roughly where Google truncates a snippet and the major scrapers stop reading. A budget, not a
 * hard limit.
 */
const MAX_DESCRIPTION = 200

/** Collapses whitespace and trims to {@link MAX_DESCRIPTION}, breaking on a word where it can. */
function truncate(text: string): string {
  const collapsed = text.replace(/\s+/g, ' ').trim()
  if (collapsed.length <= MAX_DESCRIPTION) return collapsed

  const cut = collapsed.slice(0, MAX_DESCRIPTION - 1)
  const lastSpace = cut.lastIndexOf(' ')
  // Only break on a word if that keeps most of the budget: a 190-character word is a URL or a
  // hashtag wall, and chopping it is the better answer.
  const kept = lastSpace > MAX_DESCRIPTION * 0.6 ? cut.slice(0, lastSpace) : cut
  return `${kept.trimEnd()}…`
}

/** Joins the parts that exist, dropping blanks — `null`, `undefined` and `''` alike. */
const join = (separator: string, ...parts: (string | null | undefined)[]) =>
  parts.filter((part) => Boolean(part?.trim())).join(separator)

/**
 * An event: when and where first, then the venue's blurb. Someone deciding whether to open a link
 * wants the date and the room before promotional copy, which is often long enough to push both
 * out of the preview.
 */
export function eventPageMeta(event: EventDetail, locale: Locale): PageMeta {
  const facts = join(
    ' · ',
    formatDate(event.eventDate, INTL_LOCALES[locale]),
    join(', ', event.venue?.name, event.venue?.city),
  )
  const description = join(' — ', facts, descriptionFor(event, locale)?.text)

  return {
    title: formatTitle(event.title),
    description: description ? truncate(description) : undefined,
    image: absoluteImageUrl(event.imageUrl),
  }
}

/**
 * A venue: its own description if we have one, otherwise the address. Takes the locale (#1210)
 * so the preview matches the page.
 */
export function venuePageMeta(venue: VenueDetail, locale: Locale): PageMeta {
  const address = join(', ', venue.address, join(' ', venue.postalCode, venue.city))
  const description = descriptionFor(venue, locale)

  return {
    title: formatTitle(venue.name),
    description: truncateOrUndefined(join(' — ', description?.text, address)),
    image: absoluteImageUrl(venue.imageUrl),
  }
}

/** An artist. Often nothing but a name, in which case there is no description to give. */
export function artistPageMeta(artist: ArtistDetail): PageMeta {
  return {
    title: formatTitle(artist.name),
    description: truncateOrUndefined(artist.description),
    image: absoluteImageUrl(artist.imageUrl),
  }
}

/**
 * A promoter: its description in the visitor's locale where one exists (#328), as a venue's.
 */
export function promoterPageMeta(promoter: PromoterDetail, locale: Locale): PageMeta {
  return {
    title: formatTitle(promoter.name),
    description: truncateOrUndefined(descriptionFor(promoter, locale)?.text),
    image: absoluteImageUrl(promoter.imageUrl),
  }
}

/** A static page, the one case whose description is prose, from the message catalogue. */
export function staticPageMeta(title: string | null, description?: string | null): PageMeta {
  return { title: formatTitle(title), description: truncateOrUndefined(description) }
}

/** The title a detail view shows before its entity arrives, or when there is none. */
export function placeholderPageMeta(title: string): PageMeta {
  return { title: formatTitle(title) }
}

function truncateOrUndefined(text?: string | null): string | undefined {
  return text?.trim() ? truncate(text) : undefined
}
