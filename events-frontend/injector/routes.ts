// Explicit `.ts` on every import here: the injector is type-checked under `tsconfig.node.json`
// and bundled by `vite.injector.config.ts`, both following Node's ESM resolver (vite.config.ts).
import { type Locale, LOCALES } from '../src/i18n/locales.ts'
import { INDEXABLE_PATHS } from '../src/lib/seo.ts'
import type { StaticPath } from '../src/lib/staticPages.ts'

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
