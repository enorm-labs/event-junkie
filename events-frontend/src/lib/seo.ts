// Relative and extension-bearing, unlike the rest of `src/`: `vite.config.ts` imports this module
// through scripts/seoFiles.ts, so it is resolved before Vite's `resolve.alias` exists (hence no `@`
// alias) and, under `configLoader: 'native'`, by Node's ESM resolver (hence the explicit `.ts`).
// See the note in vite.config.ts. Same reason the router uses relative imports.
import { DEFAULT_LOCALE, type Locale, LOCALES } from '../i18n/locales.ts'

/**
 * The facts every SEO surface needs, in one place: the canonical origin, which pages are worth
 * indexing, and how a locale is named to a crawler.
 *
 * Imported by both the app (`lib/seoTags.ts`, at runtime) and the build (`scripts/seoFiles.ts`,
 * in Node). Keep it free of browser globals at module scope so the Vite config can import it.
 */

/**
 * The canonical origin. Every absolute URL this project emits is built from it.
 *
 * A single constant rather than an environment variable on purpose. Canonical URLs exist to name
 * **one** address for a page; deriving them from the request host instead means a preview
 * deployment, a `www.` alias and the apex each declare themselves canonical, which is the exact
 * duplicate-content problem the tag is there to prevent.
 *
 * `event-junkie.de` is registered (#259) and BRANDING.md §1 fixes it as the canonical host. Nothing
 * is deployed behind it yet, so no URL here resolves until #560 lands.
 */
export const SITE_URL = 'https://event-junkie.de'

/**
 * The static pages worth putting in front of a crawler, as locale-relative paths (`''` is the
 * locale home). Every one gets a `<url>` entry per locale in the sitemap.
 *
 * **Detail routes are deliberately absent** — this build is independent of the BFF and the
 * database (ADR-014 §Decision 1), so it cannot enumerate them, and that independence is worth
 * more than the listing would be. It is *not* because they are client-rendered: Googlebot runs
 * JavaScript and reaches them through internal links regardless. When they do get a sitemap it
 * belongs in the BFF, which holds the data and can leave out events that have already happened.
 *
 * A unit test holds this list against the router: adding a static route without deciding whether
 * it is indexable fails the build rather than silently going unlisted.
 */
export const INDEXABLE_PATHS = [
  '',
  '/events',
  '/venues',
  '/calendar',
  '/about',
  '/legal/imprint',
  '/legal/privacy',
  '/legal/notices',
] as const

/**
 * Static routes that exist but are intentionally kept out of the sitemap. Empty today — it exists
 * so the drift guard has somewhere to record a deliberate exclusion instead of being weakened.
 */
export const NON_INDEXABLE_PATHS: readonly string[] = []

/**
 * Open Graph locale tags. OG wants `language_TERRITORY` with an underscore, which is neither the
 * UI locale (`en`) nor the BCP-47 formatting tag (`en-GB`) — three spellings of the same idea, so
 * this mapping is written out rather than derived by string surgery.
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
 * The `hreflang` set for one page: every published locale, plus `x-default`.
 *
 * **`x-default` points at the default locale, not at the unprefixed path.** The unprefixed path is
 * the more literal answer — it is the URL that negotiates `Accept-Language` — but it negotiates in
 * *JavaScript*, and Google asks that hreflang name canonical, indexable URLs rather than
 * redirects. `/en/...` is a real page; `/...` is a redirect that only resolves if scripts run.
 */
export function alternatesFor(path: string): { hreflang: string; href: string }[] {
  return [
    ...LOCALES.map((locale) => ({ hreflang: locale as string, href: canonicalUrl(locale, path) })),
    { hreflang: 'x-default', href: canonicalUrl(DEFAULT_LOCALE, path) },
  ]
}

const escapeXml = (value: string) =>
  value.replace(
    /[&<>"']/g,
    (char) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[char] as string,
  )

/**
 * The sitemap, as XML.
 *
 * **This is where `hreflang` actually works today.** The equivalent `<link>` tags are injected by
 * `lib/seoTags.ts` after the app boots, and Google treats script-injected hreflang as unreliable —
 * but a sitemap is a static file the server hands over directly, no rendering involved. Until
 * prerendering lands, the sitemap is the primary annotation and the head links are the secondary
 * one. Each language version needs its own `<url>` carrying the **full** alternate set, including
 * a self-reference; a one-way annotation is ignored.
 *
 * No `lastmod`, `changefreq` or `priority`. Google ignores the latter two outright, and a
 * `lastmod` stamped with the build date on every page is precisely the untrustworthy signal it
 * discounts — worse than omitting it, because it invites the reader to believe it.
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
 * `robots.txt`.
 *
 * Open to everything, which is right for a public events guide — and a hazard for any environment
 * that is *not* the public site. A staging or preview deployment serving this build will be
 * indexed, and its sitemap will point at production. Overriding both per environment is a
 * deployment concern, flagged in the root AGENTS.md alongside the privacy checks rather than
 * solved here.
 */
export function robotsTxt(): string {
  return ['User-agent: *', 'Allow: /', '', `Sitemap: ${SITE_URL}/sitemap.xml`, ''].join('\n')
}
