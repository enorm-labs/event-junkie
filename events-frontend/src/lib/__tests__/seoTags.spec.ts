import { beforeEach, describe, expect, it } from 'vitest'

import { updateSeoTags } from '@/lib/seoTags'
import { SITE_URL } from '@/lib/seo'

/**
 * The head tags are rewritten on every navigation, and an SPA navigates a lot. Accumulation is the
 * failure mode: two canonicals, or a growing pile of `og:locale:alternate`, is worse than none,
 * because a crawler has no way to pick between them.
 */

const hrefs = (selector: string) =>
  [...document.head.querySelectorAll(selector)].map((element) => element.getAttribute('href'))

const contents = (property: string) =>
  [...document.head.querySelectorAll(`meta[property="${property}"]`)].map((element) =>
    element.getAttribute('content'),
  )

beforeEach(() => {
  document.head.innerHTML = ''
})

describe('updateSeoTags', () => {
  it('names one canonical URL, built from the locale and the path', () => {
    updateSeoTags('de', '/legal/privacy')

    expect(hrefs('link[rel="canonical"]')).toEqual([`${SITE_URL}/de/legal/privacy`])
  })

  it('canonicalises the locale home without a trailing slash', () => {
    // `/en/` and `/en` would be two URLs for one page — the bug Phase 1 shipped and fixed.
    updateSeoTags('en', '')

    expect(hrefs('link[rel="canonical"]')).toEqual([`${SITE_URL}/en`])
  })

  it('emits reciprocal alternates plus x-default', () => {
    updateSeoTags('de', '/events')

    expect(hrefs('link[rel="alternate"][hreflang="en"]')).toEqual([`${SITE_URL}/en/events`])
    expect(hrefs('link[rel="alternate"][hreflang="de"]')).toEqual([`${SITE_URL}/de/events`])
    expect(hrefs('link[rel="alternate"][hreflang="x-default"]')).toEqual([`${SITE_URL}/en/events`])
  })

  it('names the feed of new events in the page locale, by an absolute URL', () => {
    updateSeoTags('de', '/venues')

    const feeds = [
      ...document.head.querySelectorAll('link[rel="alternate"][type="application/rss+xml"]'),
    ]
    expect(feeds.map((link) => [link.getAttribute('title'), link.getAttribute('href')])).toEqual([
      ['Event Junkie — neue Veranstaltungen', `${SITE_URL}/feed.xml?locale=de`],
    ])
  })

  it('sets og:url to the canonical URL and og:locale to the OG spelling', () => {
    updateSeoTags('de', '/about')

    expect(contents('og:url')).toEqual([`${SITE_URL}/de/about`])
    // `de_DE`, not `de` and not `de-DE` — Open Graph wants language_TERRITORY.
    expect(contents('og:locale')).toEqual(['de_DE'])
    expect(contents('og:locale:alternate')).toEqual(['en_GB'])
  })

  it('replaces its tags rather than accumulating them across navigations', () => {
    updateSeoTags('en', '/events')
    updateSeoTags('de', '/venues')
    updateSeoTags('en', '/about')

    expect(hrefs('link[rel="canonical"]')).toEqual([`${SITE_URL}/en/about`])
    expect(hrefs('link[rel="alternate"][hreflang]')).toHaveLength(3)
    expect(hrefs('link[rel="alternate"][type="application/rss+xml"]')).toEqual([
      `${SITE_URL}/feed.xml?locale=en`,
    ])
    // The one that would accumulate silently: its content changes with the active locale, so it
    // cannot be found and updated by selector the way canonical can.
    expect(contents('og:locale:alternate')).toEqual(['de_DE'])
  })

  it('leaves head tags it does not own alone', () => {
    // index.html carries site-level og:title and og:description, which are true of every page.
    document.head.innerHTML = '<meta content="Event Junkie" property="og:title" />'

    updateSeoTags('en', '/events')

    expect(contents('og:title')).toEqual(['Event Junkie'])
  })
})
