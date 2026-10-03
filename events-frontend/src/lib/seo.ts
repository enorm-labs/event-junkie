// Relative and extension-bearing: `vite.config.ts` imports this through scripts/seoFiles.ts, before
// Vite's `resolve.alias` exists and, under `configLoader: 'native'`, by Node's ESM resolver.
import { DEFAULT_LOCALE, type Locale, LOCALES } from '../i18n/locales.ts'

/**
 * The facts every SEO surface needs: the canonical origin, which pages are worth indexing, how a
 * locale is named to a crawler. Imported by the app (`lib/seoTags.ts`) and the build
 * (`scripts/seoFiles.ts`, in Node), so no browser globals at module scope.
 */

/**
 * The canonical origin, a constant rather than an environment variable: canonical URLs exist to
 * name ONE address for a page, and deriving them from the request host would make a preview, a
 * `www.` alias and the apex each declare themselves canonical. BRANDING.md §1 fixes the host.
 */
export const SITE_URL = 'https://event-junkie.de'

/**
 * The static pages worth putting in front of a crawler, as locale-relative paths (`''` is the
 * locale home); each gets a `<url>` per locale in the sitemap.
 *
 * Detail routes are deliberately absent: this build is independent of the BFF and the database
 * (ADR-014 §Decision 1). Their sitemaps come from the BFF, which can leave out past events, and
 * the index names them ({@link DETAIL_SITEMAPS}). A unit test holds this list against the router,
 * so a new static route has to decide whether it is indexable.
 */
export const INDEXABLE_PATHS = [
  '',
  '/events',
  '/venues',
  '/promoters',
  '/calendar',
  '/map',
  '/about',
  '/legal/imprint',
  '/legal/privacy',
  '/legal/notices',
  '/legal/for-venues',
] as const

/**
 * Static routes intentionally out of the sitemap; the drift guard records a deliberate exclusion
 * here instead of being weakened. `/search` is one page per query, and its route meta says noindex.
 */
export const NON_INDEXABLE_PATHS: readonly string[] = ['/search']

/**
 * Open Graph wants `language_TERRITORY` with an underscore, neither the UI locale (`en`) nor the
 * BCP-47 tag (`en-GB`), so this is written out rather than derived by string surgery.
 */
export const OG_LOCALES: Record<Locale, string> = {
  en: 'en_GB',
  de: 'de_DE',
}

/** The absolute, canonical URL of `path` in `locale`. `path` is `''` for the locale home. */
export function canonicalUrl(locale: Locale, path: string): string {
  return `${SITE_URL}/${locale}${path}`
}

/**
 * An image URL a crawler can fetch. The API returns a path on our origin for a cached image and
 * an absolute URL for a venue's own (ADR-019); `<img src>` resolves both against the page, but
 * `og:image` and JSON-LD `image` are read with no page to resolve against.
 */
export function absoluteImageUrl(url: string | null | undefined): string | undefined {
  if (!url) return undefined
  return url.startsWith('/') ? `${SITE_URL}${url}` : url
}

/**
 * The `hreflang` set for one page: every published locale, plus `x-default`, which points at the
 * default locale and not the unprefixed path: that path negotiates `Accept-Language` in
 * JavaScript, and Google asks that hreflang name indexable URLs rather than redirects.
 */
export function alternatesFor(path: string): { hreflang: string; href: string }[] {
  return [
    ...LOCALES.map((locale) => ({ hreflang: locale as string, href: canonicalUrl(locale, path) })),
    { hreflang: 'x-default', href: canonicalUrl(DEFAULT_LOCALE, path) },
  ]
}

/**
 * The RSS feed of new events (#368), one per locale, advertised in every page's head and linked
 * from the footer and the filter bar. The BFF renders it at `/api/events/feed`; nginx and the
 * injector serve it at {@link FEED_PATH}, the public URL, as `Site.FEED_PATH` in the BFF names it.
 * The titles are the feed's own channel titles in `EventFeedXml.kt`; change both or neither.
 */
export const FEED_PATH = '/feed.xml'

export const FEED_TYPE = 'application/rss+xml'

export const FEED_TITLES: Record<Locale, string> = {
  en: 'Event Junkie — new events',
  de: 'Event Junkie — neue Veranstaltungen',
}

/** A filter value as the filter bar holds it; a list is a repeated parameter. */
type FeedFilterValue = string | number | boolean | readonly string[] | undefined

/**
 * The site-relative feed path in `locale`, narrowed by the filter bar's `filters`, so it works on
 * every environment. The feed takes the list's filter names and no dates, so a filtered URL is a
 * saved search. An empty or false value is left out, as the list leaves it out of its URL.
 */
export function feedPath(
  locale: Locale,
  filters: Readonly<Record<string, FeedFilterValue>> = {},
): string {
  const query = new URLSearchParams({ locale })
  for (const [name, value] of Object.entries(filters)) {
    for (const item of Array.isArray(value) ? value : [value]) {
      if (item !== undefined && item !== '' && item !== false) query.append(name, String(item))
    }
  }
  return `${FEED_PATH}?${query}`
}

/** The absolute URL of the feed in `locale`, from {@link SITE_URL} like every other URL here. */
export function feedUrl(locale: Locale): string {
  return `${SITE_URL}${feedPath(locale)}`
}

const escapeXml = (value: string) =>
  value.replace(
    /[&<>"']/g,
    (char) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[char] as string,
  )

/**
 * The detail sitemaps (#367), one per route family. The BFF renders them, and nginx serves them at
 * the root through the injector, because a sitemap only vouches for URLs under its own directory.
 */
export const DETAIL_SITEMAPS = [
  '/sitemap-events.xml',
  '/sitemap-venues.xml',
  '/sitemap-artists.xml',
  '/sitemap-promoters.xml',
] as const

/** The static pages' sitemap, the one this build writes itself. */
export const PAGES_SITEMAP = '/sitemap-pages.xml'

/**
 * `/sitemap.xml`, a sitemap index: the static pages' sitemap and the four detail ones. An index
 * rather than one `Sitemap:` line each in `robots.txt`, so Search Console reports on every child
 * under the one URL it was given.
 */
export function sitemapIndexXml(): string {
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...[PAGES_SITEMAP, ...DETAIL_SITEMAPS].map(
      (path) => `  <sitemap>\n    <loc>${SITE_URL}${path}</loc>\n  </sitemap>`,
    ),
    '</sitemapindex>',
    '',
  ].join('\n')
}

/**
 * The static pages' sitemap, as XML. This is where `hreflang` works today: the `<link>` tags `lib/seoTags.ts`
 * injects after boot are script-injected, which Google treats as unreliable, while the sitemap is
 * a static file. Each language version needs its own `<url>` carrying the full alternate set,
 * including a self-reference; a one-way annotation is ignored.
 *
 * No `lastmod`, `changefreq` or `priority`: Google ignores the latter two, and a `lastmod` stamped
 * with the build date on every page is the untrustworthy signal it discounts.
 */
export function sitemapXml(): string {
  const entries = LOCALES.flatMap((locale) =>
    INDEXABLE_PATHS.map((path) => {
      const alternates = alternatesFor(path)
        .map(
          (alt) =>
            `    <xhtml:link href="${escapeXml(alt.href)}" hreflang="${alt.hreflang}" rel="alternate"/>`,
        )
        .join('\n')
      return `  <url>\n    <loc>${escapeXml(canonicalUrl(locale, path))}</loc>\n${alternates}\n  </url>`
    }),
  )

  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">',
    ...entries,
    '</urlset>',
    '',
  ].join('\n')
}

/**
 * `robots.txt`, open to everything. A hazard for any environment that is not the public site; the
 * chart overrides both files for non-production (`ingress.noindex`, #286).
 */
export function robotsTxt(): string {
  return ['User-agent: *', 'Allow: /', '', `Sitemap: ${SITE_URL}/sitemap.xml`, ''].join('\n')
}
