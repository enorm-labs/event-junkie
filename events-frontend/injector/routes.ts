// Explicit `.ts` on every import here: the injector is type-checked under `tsconfig.node.json`
// and bundled by `vite.injector.config.ts`, both following Node's ESM resolver (vite.config.ts).
import { type Locale, LOCALES } from '../src/i18n/locales.ts'
import { todayIso } from '../src/lib/format.ts'
import { type IsoWeek, isoWeekOf, parseIsoWeek } from '../src/lib/isoWeek.ts'
import { CALENDAR_PATH, FEED_PATH, INDEXABLE_PATHS, WEEKS_SITEMAP } from '../src/lib/seo.ts'
import type { StaticPath } from '../src/lib/staticPages.ts'
import { weekPath } from '../src/lib/weekPage.ts'

/**
 * Which of the four data-driven route families a request is for: the slug to ask the BFF about,
 * and the locale-relative path the canonical URL is built from. Everything else returns `null`:
 * nginx only proxies paths this regex also accepts, and the double check keeps an nginx edit from
 * widening the injector's surface.
 */

export const ENTITY_KINDS = ['events', 'venues', 'artists', 'promoters'] as const

export type EntityKind = (typeof ENTITY_KINDS)[number]

export interface DetailRoute {
  kind: EntityKind
  locale: Locale
  /** Validated against {@link SLUG}, so it is safe to place in a URL without further escaping. */
  slug: string
  /** Locale-relative, for `canonicalUrl()` and `alternatesFor()` — `/events/<slug>`. */
  path: string
}

/**
 * What a slug may look like: lower-cased and hyphenated, minted by the BFF. Anything outside this
 * set is refused rather than forwarded, which keeps `..`, `%2e` and friends out of the BFF request.
 */
const SLUG = '[a-z0-9][a-z0-9-]*'

const DETAIL = new RegExp(`^/(${LOCALES.join('|')})/(${ENTITY_KINDS.join('|')})/(${SLUG})/?$`)

/** Parses a request URL. The query string and fragment are ignored, as `seoTags.ts` ignores them. */
export function matchDetailRoute(url: string): DetailRoute | null {
  const pathname = url.split('#')[0]?.split('?')[0] ?? ''
  const match = DETAIL.exec(pathname)
  if (!match) return null

  const [, locale, kind, slug] = match as unknown as [string, Locale, EntityKind, string]
  return { kind, locale, slug, path: `/${kind}/${slug}` }
}

export interface StaticRoute {
  locale: Locale
  /** Locale-relative, `''` for the home page, one of `INDEXABLE_PATHS`. */
  path: StaticPath
}

/**
 * The static pages, from the sitemap's list, so a new page is covered the day it is indexable.
 * Their head comes from the catalogue and needs no BFF request (ADR-014 §Decision 2). The regex
 * only shapes the path; the list decides, so no path is ever spliced into a pattern.
 */
const STATIC_SHAPE = new RegExp(`^/(${LOCALES.join('|')})((?:/[a-z-]+)*)/?$`)
const STATIC_PATHS = new Set<string>(INDEXABLE_PATHS)

function isStaticPath(path: string): path is StaticPath {
  return STATIC_PATHS.has(path)
}

/** Parses a request URL for a static page, as {@link matchDetailRoute} does for a detail page. */
export function matchStaticRoute(url: string): StaticRoute | null {
  const pathname = url.split('#')[0]?.split('?')[0] ?? ''
  const match = STATIC_SHAPE.exec(pathname)
  if (!match) return null

  const [, locale, path = ''] = match as unknown as [string, Locale, string | undefined]
  return isStaticPath(path) ? { locale, path } : null
}

export interface WeekRoute {
  locale: Locale
  week: IsoWeek
  /** Locale-relative, `/week/2026-41`. */
  path: string
}

const WEEK = new RegExp(`^/(${LOCALES.join('|')})/week/(\\d{4}-\\d{2})/?$`)
const THIS_WEEK = new RegExp(`^/(${LOCALES.join('|')})/week/?$`)

/** Parses a week page's URL (#2728). A week the year does not have, `2026-60`, is `null`. */
export function matchWeekRoute(url: string): WeekRoute | null {
  const pathname = url.split('#')[0]?.split('?')[0] ?? ''
  const match = WEEK.exec(pathname)
  const week = parseIsoWeek(match?.[2])
  if (!match || !week) return null
  return { locale: match[1] as Locale, week, path: weekPath(week) }
}

/** The locale of a `/week` request, which is redirected to this week's page, or `null`. */
export function matchThisWeek(url: string): Locale | null {
  const pathname = url.split('#')[0]?.split('?')[0] ?? ''
  return (THIS_WEEK.exec(pathname)?.[1] as Locale | undefined) ?? null
}

/**
 * Where `/week` redirects: this week's page. The prefix is a constant picked by the locale, so
 * nothing from the request reaches the `location` header.
 */
export function thisWeekLocation(locale: Locale, today = todayIso()): string {
  const prefix = locale === 'de' ? '/de' : '/en'
  return `${prefix}${weekPath(isoWeekOf(today))}`
}

/** Whether the request is for the weeks sitemap, which the injector writes itself. */
export function matchWeeksSitemap(url: string): boolean {
  return (url.split('#')[0]?.split('?')[0] ?? '') === WEEKS_SITEMAP
}

const SITEMAP = new RegExp(`^/sitemap-(${ENTITY_KINDS.join('|')})\\.xml$`)

/**
 * The detail sitemap a request is for (#367), or `null`. The kind comes from {@link ENTITY_KINDS}
 * and never from the request, so nothing the client sent reaches the BFF path.
 */
export function matchSitemap(url: string): EntityKind | null {
  const pathname = url.split('#')[0]?.split('?')[0] ?? ''
  const kind = SITEMAP.exec(pathname)?.[1]
  return ENTITY_KINDS.find((candidate) => candidate === kind) ?? null
}

/**
 * The query string of a feed request (#368), `''` when it has none, or `null` for any other path.
 * The path to the BFF is fixed; only the query, the list's filters, is forwarded, and the BFF
 * answers 400 to a parameter it does not know.
 */
export function matchFeed(url: string): string | null {
  return queryAt(FEED_PATH, url)
}

/** The query string of a calendar subscription request (#2719), as {@link matchFeed} reads a feed's. */
export function matchCalendar(url: string): string | null {
  return queryAt(CALENDAR_PATH, url)
}

function queryAt(path: string, url: string): string | null {
  const [beforeFragment = ''] = url.split('#')
  const queryStart = beforeFragment.indexOf('?')
  const pathname = queryStart < 0 ? beforeFragment : beforeFragment.slice(0, queryStart)
  if (pathname !== path) return null
  return queryStart < 0 ? '' : beforeFragment.slice(queryStart)
}
