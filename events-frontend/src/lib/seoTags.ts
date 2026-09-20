import { alternatesFor, canonicalUrl, OG_LOCALES } from '@/lib/seo'
import { type Locale, LOCALES } from '@/i18n/locales'

/**
 * Keeps `<link rel="canonical">`, the `hreflang` alternates and the locale-dependent Open Graph
 * tags in step with the route. Deliberately absent from `index.html`: a static canonical there
 * would name the home page as the canonical URL of every route for anything that runs no
 * scripts, and the sitemap carries the same annotations without rendering (`lib/seo.ts`). Every
 * element written here is marked `data-seo` and the set is replaced on each navigation, because
 * `og:locale:alternate` would otherwise accumulate as the visitor switches language.
 */

const MANAGED_ATTRIBUTE = 'data-seo'

function managed<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attributes: Record<string, string>,
): HTMLElementTagNameMap[K] {
  const element = document.createElement(tag)
  element.setAttribute(MANAGED_ATTRIBUTE, '')
  for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value)
  return element
}

/**
 * Rewrites the managed head tags for `path` in `locale`. `path` carries no query string:
 * `/en/events?type=CONCERT` canonicalises to `/en/events`, since filtering is a client-side
 * refinement of the same HTML and indexing each combination would be near-duplicate content.
 */
export function updateSeoTags(locale: Locale, path: string): void {
  for (const stale of document.head.querySelectorAll(`[${MANAGED_ATTRIBUTE}]`)) stale.remove()

  const canonical = canonicalUrl(locale, path)
  const fragment = document.createDocumentFragment()

  fragment.append(managed('link', { rel: 'canonical', href: canonical }))
  for (const alternate of alternatesFor(path)) {
    fragment.append(
      managed('link', { rel: 'alternate', hreflang: alternate.hreflang, href: alternate.href }),
    )
  }

  fragment.append(managed('meta', { property: 'og:url', content: canonical }))
  fragment.append(managed('meta', { property: 'og:locale', content: OG_LOCALES[locale] }))
  for (const other of LOCALES.filter((candidate) => candidate !== locale)) {
    fragment.append(
      managed('meta', { property: 'og:locale:alternate', content: OG_LOCALES[other] }),
    )
  }

  document.head.append(fragment)
}
