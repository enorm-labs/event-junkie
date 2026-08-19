import type { ArtistDetail, EventDetail, PromoterDetail, VenueDetail } from '@/api/types'
import { formatDate } from '@/lib/format'
import { INTL_LOCALES, type Locale } from '@/i18n/locales'

/**
 * What each page calls itself: the document title, a description, and a representative image.
 *
 * **This module exists to be used twice.** The client writes these tags after boot, reaching
 * Googlebot and nothing else; the planned meta injector
 * ([ADR-014](../../docs/adr/ADR-014_RENDERING_STRATEGY.md) §Decision 3) will write the same tags
 * server-side, for the scrapers that do not run JavaScript. If the two disagree, a shared link
 * previews as one thing and opens as another — so both read from here.
 *
 * Hence **descriptions are composed from data and punctuation, never from prose**: "Concert at Lido
 * on Friday" would need the message catalogue, and the injector may run where there is none — an
 * edge worker, or a language it was not built with. And **canonical URLs are deliberately absent**:
 * `canonicalUrl()` in `lib/seo.ts` derives them and `lib/seoTags.ts` writes them, so a second
 * source here would be the divergence this module exists to prevent.
 */

/** Brand name shown in the browser tab, appended to every interior view's title. */
export const APP_NAME = 'Event Junkie'

/** Homepage tagline — the descriptor best practice recommends over a bare brand name. */
export const TAGLINE = "Can't get enough of Berlin"

/** The root/home title: brand plus tagline. Interior views use `<page> · Event Junkie`. */
export const HOME_TITLE = `${APP_NAME} — ${TAGLINE}`

/**
 * Formats an interior page title as `<page> · Event Junkie`; falls back to the home title.
 *
 * Lives here rather than with the composable that writes it, so that this module stays free of
 * Vue and the DOM — the injector will import it from a runtime that has neither.
 */
export function formatTitle(title?: string | null): string {
  return title ? `${title} · ${APP_NAME}` : HOME_TITLE
}

export interface PageMeta {
  /** The full document title, already suffixed with the brand — see `formatTitle`. */
  title: string
  /**
   * One or two sentences for `<meta name="description">` and `og:description`.
   *
   * Optional, and left undefined rather than invented. An artist we hold nothing but a name for
   * has nothing true to say; the site-level description is a better answer than a padded one.
   */
  description?: string
  /** Absolute URL of a representative image, when the entity has one. */
  image?: string
}

/**
 * Roughly where Google truncates a snippet and where the major scrapers stop reading. Not a hard
 * limit anywhere — a budget, so long venue blurbs do not push the useful part out of the preview.
 */
const MAX_DESCRIPTION = 200

/** Collapses whitespace and trims to {@link MAX_DESCRIPTION}, breaking on a word where it can. */
function truncate(text: string): string {
  const collapsed = text.replace(/\s+/g, ' ').trim()
  if (collapsed.length <= MAX_DESCRIPTION) return collapsed

  const cut = collapsed.slice(0, MAX_DESCRIPTION - 1)
  const lastSpace = cut.lastIndexOf(' ')
  // Only break on a word if that does not throw away most of the budget — a 190-character word
  // is not a word, it is a URL or a hashtag wall, and chopping it mid-way is the better answer.
  const kept = lastSpace > MAX_DESCRIPTION * 0.6 ? cut.slice(0, lastSpace) : cut
  return `${kept.trimEnd()}…`
}

/** Joins the parts that exist, dropping blanks — `null`, `undefined` and `''` alike. */
const join = (separator: string, ...parts: (string | null | undefined)[]) =>
  parts.filter((part) => Boolean(part?.trim())).join(separator)

/**
 * An event: when and where first, then the venue's own blurb if there is one.
 *
 * The facts lead deliberately. Someone deciding whether to open a link in a group chat wants the
 * date and the room before they want promotional copy, and the copy is often long enough to push
 * both out of the preview.
 */
export function eventPageMeta(event: EventDetail, locale: Locale): PageMeta {
  const facts = join(
    ' · ',
    formatDate(event.eventDate, INTL_LOCALES[locale]),
    join(', ', event.venue?.name, event.venue?.city),
  )
  const description = join(' — ', facts, event.description)

  return {
    title: formatTitle(event.title),
    description: description ? truncate(description) : undefined,
    image: event.imageUrl ?? undefined,
  }
}

/**
 * A venue: its own description if we have one, otherwise the address, which is always useful.
 *
 * Takes no locale — nothing here is locale-dependent. Only the event builder formats a date, so
 * only it needs one; a uniform signature would be a parameter every caller has to pass and no
 * implementation reads.
 */
export function venuePageMeta(venue: VenueDetail): PageMeta {
  const address = join(', ', venue.address, join(' ', venue.postalCode, venue.city))

  return {
    title: formatTitle(venue.name),
    description: truncateOrUndefined(join(' — ', venue.description, address)),
    image: venue.imageUrl ?? undefined,
  }
}

/** An artist. Often nothing but a name, in which case there is no description to give. */
export function artistPageMeta(artist: ArtistDetail): PageMeta {
  return {
    title: formatTitle(artist.name),
    description: truncateOrUndefined(artist.description),
    image: artist.imageUrl ?? undefined,
  }
}

/** A promoter. The BFF holds little beyond the name, so this is mostly a title. */
export function promoterPageMeta(promoter: PromoterDetail): PageMeta {
  return { title: formatTitle(promoter.name) }
}

/**
 * A static page — the one case whose description *is* prose, so it comes from the message
 * catalogue rather than from data. These routes are not data-driven and are therefore out of the
 * injector's scope (ADR-014 §Decision 2), which is what makes the catalogue safe to use here.
 */
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
