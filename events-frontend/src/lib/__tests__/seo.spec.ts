import { describe, expect, it } from 'vitest'

import {
  alternatesFor,
  canonicalUrl,
  DETAIL_SITEMAPS,
  calendarPath,
  feedPath,
  feedUrl,
  INDEXABLE_PATHS,
  NON_INDEXABLE_PATHS,
  AI_TRAINING_CRAWLERS,
  llmsTxt,
  robotsTxt,
  PAGES_SITEMAP,
  SITE_URL,
  sitemapIndexXml,
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

describe('the sitemap index', () => {
  it('names the static pages and one sitemap per detail family, at absolute URLs', () => {
    const document = new DOMParser().parseFromString(sitemapIndexXml(), 'application/xml')
    expect(document.querySelector('parsererror'), 'index is not well-formed XML').toBeNull()
    expect(document.documentElement.localName).toBe('sitemapindex')

    const locs = [...document.getElementsByTagName('loc')].map((loc) => loc.textContent)
    expect(locs).toEqual([PAGES_SITEMAP, ...DETAIL_SITEMAPS].map((path) => `${SITE_URL}${path}`))
  })
})

describe('robots.txt', () => {
  it('announces the sitemap at an absolute URL', () => {
    // A relative Sitemap: line is invalid, and this is the only place the sitemap gets discovered
    // without a hand submission.
    expect(robotsTxt()).toContain(`Sitemap: ${SITE_URL}/sitemap.xml`)
  })

  it('lets crawlers in', () => {
    const everyone = robotsTxt().split('\n\n')[0]
    expect(everyone).toBe('User-agent: *\nAllow: /')
  })

  it('keeps the AI training crawlers out, and only those', () => {
    // ADR-044: retrieval for answers is allowed, collection for training is not.
    for (const crawler of AI_TRAINING_CRAWLERS) {
      expect(robotsTxt()).toContain(`User-agent: ${crawler}\nDisallow: /\n`)
    }
    for (const retrieval of [
      'OAI-SearchBot',
      'ChatGPT-User',
      'Claude-SearchBot',
      'PerplexityBot',
    ]) {
      expect(robotsTxt()).not.toContain(retrieval)
    }
    expect(robotsTxt().match(/Disallow: \//g)).toHaveLength(AI_TRAINING_CRAWLERS.length)
  })
})

describe('llms.txt', () => {
  it('opens with the llmstxt.org header: a title and a one-line summary', () => {
    expect(llmsTxt()).toMatch(/^# Event Junkie\n\n> .+\n/)
  })

  it('links the lists, the feeds and every sitemap at absolute URLs, in both languages', () => {
    for (const path of [
      '/en/events',
      '/de/events',
      '/en/venues',
      '/de/venues',
      '/sitemap.xml',
      PAGES_SITEMAP,
      ...DETAIL_SITEMAPS,
    ]) {
      expect(llmsTxt()).toContain(`](${SITE_URL}${path})`)
    }
    expect(llmsTxt()).toContain(`](${feedUrl('en')})`)
    expect(llmsTxt()).toContain(`](${feedUrl('de')})`)
  })
})

describe('the feed URL', () => {
  it('is site-relative in the UI and absolute in the head', () => {
    expect(feedPath('de')).toBe('/feed.xml?locale=de')
    expect(feedUrl('en')).toBe(`${SITE_URL}/feed.xml?locale=en`)
  })

  it("carries the filter bar's values, a list as a repeated parameter", () => {
    expect(
      feedPath('en', {
        genre: 'techno',
        district: ['kreuzberg', 'friedrichshain'],
        maxPrice: 20,
        free: true,
      }),
    ).toBe(
      '/feed.xml?locale=en&genre=techno&district=kreuzberg&district=friedrichshain&maxPrice=20&free=true',
    )
  })

  it('leaves out what the list leaves out of its URL', () => {
    expect(feedPath('en', { q: '', venue: undefined, excludeSoldOut: false, family: [] })).toBe(
      '/feed.xml?locale=en',
    )
  })
})

describe('the calendar subscription path', () => {
  it("is site-relative and carries the filter bar's values as the feed does", () => {
    expect(calendarPath('de')).toBe('/calendar.ics?locale=de')
    expect(calendarPath('en', { genre: 'jazz', district: ['neukoelln'], free: false })).toBe(
      '/calendar.ics?locale=en&genre=jazz&district=neukoelln',
    )
    expect(calendarPath('en', { timeOfDay: ['evening', 'late'] })).toBe(
      '/calendar.ics?locale=en&timeOfDay=evening&timeOfDay=late',
    )
  })
})
