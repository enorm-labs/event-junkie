import { alternatesFor, canonicalUrl, OG_LOCALES } from '@/lib/seo'
import { type Locale, LOCALES } from '@/i18n/locales'

/**
 * Keeps `<link rel="canonical">`, the `hreflang` alternates and the locale-dependent Open Graph
 * tags in step with the current route.
 *
 * **These are deliberately absent from `index.html`.** A static canonical there would name the home
 * page as the canonical URL of *every* route for anything that does not run scripts — social
 * scrapers included — consolidating the whole site onto one URL. Absent beats confidently wrong,
 * and the sitemap carries the same annotations in a form needing no rendering (`lib/seo.ts`). The
 * static site-level `og:title`/`og:description` stay: they are true of every page.
 *
 * Every element written here is marked `data-seo` and the whole set is replaced on each navigation
 * rather than mutated, because `og:locale:alternate` repeats once per other locale and its elements
 * would otherwise accumulate as the visitor switches language.
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
 * Rewrites the managed head tags for `path` in `locale`.
 *
 * `path` is locale-relative and **carries no query string**: `/en/events?type=CONCERT` canonicalises
 * to `/en/events`. Filtering is a client-side refinement of the same list served by the same HTML,
 * so indexing each combination separately would be near-duplicate content, and the filter space is
 * large enough that it matters.
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
