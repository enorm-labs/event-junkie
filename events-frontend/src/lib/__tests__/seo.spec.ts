import { describe, expect, it } from 'vitest'

import {
  alternatesFor,
  canonicalUrl,
  INDEXABLE_PATHS,
  NON_INDEXABLE_PATHS,
  robotsTxt,
  SITE_URL,
  sitemapXml,
} from '@/lib/seo'
import { DEFAULT_LOCALE, LOCALES } from '@/i18n/locales'
import router from '@/router'

/**
 * The sitemap is generated at build time from `INDEXABLE_PATHS`, but `INDEXABLE_PATHS` can go
 * stale against the router, and nothing about adding a route makes anyone think about the
 * sitemap. That drift is what these tests exist for.
 */

const XHTML = 'http://www.w3.org/1999/xhtml'

function parsedSitemap(): Document {
  const document = new DOMParser().parseFromString(sitemapXml(), 'application/xml')
  expect(document.querySelector('parsererror'), 'sitemap is not well-formed XML').toBeNull()
  return document
}

describe('the indexable path list', () => {
  // The router flattens children onto the parent's path, so a static route appears as
  // `/:locale(en|de)/events`. Everything with a remaining `:param` is a detail route.
  const localeSegment = `/:locale(${LOCALES.join('|')})`
  const routerStaticPaths = new Set(
    router
      .getRoutes()
      .map((route) => route.path)
      .filter((path) => path.startsWith(localeSegment))
      .map((path) => path.slice(localeSegment.length))
      .filter((path) => !path.includes(':')),
  )

  it('accounts for every static route the router publishes', () => {
    // Adding a page without deciding whether it belongs in the sitemap fails here; deliberate
    // omissions go in NON_INDEXABLE_PATHS.
    const accounted = new Set<string>([...INDEXABLE_PATHS, ...NON_INDEXABLE_PATHS])
    expect([...routerStaticPaths].filter((path) => !accounted.has(path))).toEqual([])
  })

  it('lists nothing the router does not serve', () => {
    expect([...INDEXABLE_PATHS].filter((path) => !routerStaticPaths.has(path))).toEqual([])
  })

  it('resolves every indexable path to a real route in every locale', () => {
    for (const locale of LOCALES) {
      for (const path of INDEXABLE_PATHS) {
        const resolved = router.resolve(`/${locale}${path}`)
        expect(resolved.matched, `/${locale}${path} matches no route`).not.toEqual([])
      }
    }
  })

  it('excludes detail routes, which have no prerendered content to crawl', () => {
    const listed = sitemapXml()
    for (const detail of ['/events/', '/venues/', '/artists/', '/promoters/']) {
      expect(listed).not.toContain(`${SITE_URL}/en${detail}`)
    }
  })
})

describe('the sitemap', () => {
  it('has one entry per locale per indexable path', () => {
    const locations = [...parsedSitemap().getElementsByTagName('loc')].map(
      (node) => node.textContent,
    )

    expect(locations).toHaveLength(LOCALES.length * INDEXABLE_PATHS.length)
    expect(new Set(locations).size, 'duplicate <loc> entries').toBe(locations.length)
    expect(locations).toContain(`${SITE_URL}/de/legal/imprint`)
  })

  it('gives every entry the full alternate set, including a self-reference', () => {
    // A one-way hreflang annotation is ignored outright: each version has to point at every version
    // including itself.
    for (const url of parsedSitemap().getElementsByTagName('url')) {
      const location = url.getElementsByTagName('loc')[0]?.textContent
      const alternates = [...url.getElementsByTagNameNS(XHTML, 'link')].map((link) => ({
        hreflang: link.getAttribute('hreflang'),
        href: link.getAttribute('href'),
      }))

      expect(alternates.map((alternate) => alternate.hreflang)).toEqual([...LOCALES, 'x-default'])
      expect(
        alternates.map((alternate) => alternate.href),
        `${location} does not reference itself`,
      ).toContain(location)
    }
  })

  it('points x-default at the default locale rather than at a redirect', () => {
    // The unprefixed path negotiates Accept-Language in JavaScript, and Google asks that hreflang
    // name indexable URLs (alternatesFor()).
    for (const alternate of alternatesFor('/events')) {
      if (alternate.hreflang !== 'x-default') continue
      expect(alternate.href).toBe(canonicalUrl(DEFAULT_LOCALE, '/events'))
      expect(alternate.href).toMatch(/\/(en|de)\//)
    }
  })

  it('uses absolute URLs throughout', () => {
    // A relative <loc> is invalid, and a relative hreflang href is ignored.
    for (const value of sitemapXml().matchAll(/(?:<loc>|href=")([^<"]+)/g)) {
      expect(value[1]).toMatch(/^https:\/\//)
    }
  })

  it('claims no lastmod, changefreq or priority', () => {
    // Google ignores the latter two, and a build-stamped lastmod on every page is a confident claim
    // that happens to be false.
    expect(sitemapXml()).not.toMatch(/<(lastmod|changefreq|priority)>/)
  })
})

describe('robots.txt', () => {
  it('announces the sitemap at an absolute URL', () => {
    // A relative Sitemap: line is invalid, and this is the only place the sitemap gets discovered
    // without a hand submission.
    expect(robotsTxt()).toContain(`Sitemap: ${SITE_URL}/sitemap.xml`)
  })

  it('lets crawlers in', () => {
    expect(robotsTxt()).toMatch(/User-agent: \*/)
    expect(robotsTxt()).not.toMatch(/Disallow: \/\s*$/m)
  })
})
