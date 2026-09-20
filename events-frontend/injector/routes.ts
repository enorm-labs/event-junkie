// Explicit `.ts` on every import here: the injector is type-checked under `tsconfig.node.json`
// and bundled by `vite.injector.config.ts`, both following Node's ESM resolver (vite.config.ts).
import { type Locale, LOCALES } from '../src/i18n/locales.ts'

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
