import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { LOCALES } from '@/i18n/locales'
import { DETAIL_SITEMAPS, INDEXABLE_PATHS, WEEKS_SITEMAP } from '@/lib/seo'
import {
  ENTITY_KINDS,
  matchDetailRoute,
  matchCalendar,
  matchFeed,
  matchSitemap,
  matchStaticRoute,
  matchThisWeek,
  matchWeekRoute,
  matchWeeksSitemap,
  thisWeekLocation,
} from '../routes.ts'

/**
 * The route matcher is the injector's whole surface: what it accepts reaches the BFF as a URL, so
 * the refusals matter more than the matches.
 */
describe('matchDetailRoute', () => {
  it('recognises each of the four families in either locale', () => {
    expect(matchDetailRoute('/en/events/2026-06-12-lido-test-act')).toEqual({
      kind: 'events',
      locale: 'en',
      slug: '2026-06-12-lido-test-act',
      path: '/events/2026-06-12-lido-test-act',
    })
    expect(matchDetailRoute('/de/venues/lido')?.kind).toBe('venues')
    expect(matchDetailRoute('/de/artists/test-act')?.locale).toBe('de')
    expect(matchDetailRoute('/en/promoters/somebody')?.path).toBe('/promoters/somebody')
  })

  it('ignores the query string and the fragment, as the client canonical does', () => {
    expect(matchDetailRoute('/en/venues/lido?from=share#tickets')?.slug).toBe('lido')
  })

  it('tolerates a trailing slash', () => {
    expect(matchDetailRoute('/en/venues/lido/')?.slug).toBe('lido')
  })

  it('refuses everything that is not a detail page', () => {
    const paths = [
      '/',
      '/en',
      '/en/events',
      '/en/calendar',
      '/assets/index-abc.js',
      '/en/legal/imprint',
    ]
    // Mapped rather than looped, so a failure names the path that matched.
    expect(paths.map((path) => [path, matchDetailRoute(path)])).toEqual(
      paths.map((path) => [path, null]),
    )
  })

  it('refuses a slug the BFF could never have minted, before it becomes a URL', () => {
    const paths = [
      '/en/events/../admin',
      '/en/events/%2e%2e',
      '/en/events/Test-Act',
      '/en/events/a b',
      '/en/events/-leading',
      '/fr/events/test-act',
      '/en/things/test-act',
      '/en/events/test-act/extra',
    ]
    expect(paths.map((path) => [path, matchDetailRoute(path)])).toEqual(
      paths.map((path) => [path, null]),
    )
  })
})

describe('matchStaticRoute', () => {
  it('recognises every indexable page in either locale, the home page as the empty path', () => {
    const urls = LOCALES.flatMap((locale) =>
      INDEXABLE_PATHS.map((path) => ({ url: `/${locale}${path}`, locale, path })),
    )
    expect(urls.map(({ url }) => [url, matchStaticRoute(url)])).toEqual(
      urls.map(({ url, locale, path }) => [url, { locale, path }]),
    )
  })

  it('tolerates a trailing slash, and ignores the query string and the fragment', () => {
    expect(matchStaticRoute('/de/')).toEqual({ locale: 'de', path: '' })
    expect(matchStaticRoute('/en/events/?genre=techno#top')).toEqual({
      locale: 'en',
      path: '/events',
    })
  })

  it('refuses detail pages, prefixes and anything outside the two locales', () => {
    const paths = [
      '/',
      '/en/events/test-act',
      '/en/eventsx',
      '/en/legal',
      '/fr/events',
      '/en/legal/imprint/x',
    ]
    expect(paths.map((path) => [path, matchStaticRoute(path)])).toEqual(
      paths.map((path) => [path, null]),
    )
  })
})

describe('the week routes (#2728)', () => {
  it('recognise a week page in either locale, with its canonical path', () => {
    expect(matchWeekRoute('/de/week/2026-41/?ref=x#mo')).toEqual({
      locale: 'de',
      week: { year: 2026, week: 41 },
      path: '/week/2026-41',
    })
    expect(matchWeekRoute('/en/week/2026-53')?.week).toEqual({ year: 2026, week: 53 })
  })

  it('refuse a week the year does not have and any other shape', () => {
    const paths = ['/en/week/2025-53', '/en/week/2026-00', '/en/week/2026-W41', '/fr/week/2026-41']
    expect(paths.map((path) => [path, matchWeekRoute(path)])).toEqual(
      paths.map((path) => [path, null]),
    )
  })

  it('recognise /week, which the injector redirects, apart from a week page', () => {
    expect(matchThisWeek('/de/week')).toBe('de')
    expect(matchThisWeek('/en/week/?x=1')).toBe('en')
    expect(matchThisWeek('/en/week/2026-41')).toBeNull()
    expect(matchThisWeek('/en/weeks')).toBeNull()
  })

  it("redirect /week to this week's page under a constant locale prefix", () => {
    expect(thisWeekLocation('de', '2026-10-09')).toBe('/de/week/2026-41')
    expect(thisWeekLocation('en', '2026-10-12')).toBe('/en/week/2026-42')
    // The ISO week-year differs from the calendar year around New Year.
    expect(thisWeekLocation('en', '2027-01-01')).toBe('/en/week/2026-53')
  })

  it('recognise the weeks sitemap the index names, and nothing else', () => {
    expect(matchWeeksSitemap(WEEKS_SITEMAP)).toBe(true)
    expect(
      ['/sitemap-weeks.xml/x', '/en/sitemap-weeks.xml', '/sitemap-week.xml'].map(matchWeeksSitemap),
    ).toEqual([false, false, false])
    expect(matchSitemap(WEEKS_SITEMAP)).toBeNull()
  })
})

/**
 * nginx decides what reaches the injector, and the injector decides again. The two lists are kept
 * in step by reading the regex out of `docker/nginx.conf`: a page the injector knows but nginx does
 * not send would keep the English shell without anything failing.
 */
describe('the nginx location that proxies to the injector', () => {
  const conf = readFileSync(resolve(process.cwd(), 'docker/nginx.conf'), 'utf8')
  const source =
    /location ~ "?(\^\/\(en\|de\)[^\s"]*)"? \{\s*proxy_pass http:\/\/127\.0\.0\.1:3000;/.exec(
      conf,
    )?.[1]
  const nginx = new RegExp(source ?? '(?!)')

  it('is found', () => {
    expect(source).toBeTruthy()
  })

  it('sends every static page and every detail family to the injector', () => {
    const paths = LOCALES.flatMap((locale) => [
      ...INDEXABLE_PATHS.map((path) => `/${locale}${path}`),
      ...['events', 'venues', 'artists', 'promoters'].map((kind) => `/${locale}/${kind}/some-slug`),
      `/${locale}/week`,
      `/${locale}/week/2026-41`,
    ])
    expect(paths.filter((path) => !nginx.test(path))).toEqual([])
  })

  it('sends nothing the injector would refuse', () => {
    const paths = [
      '/en/eventsx',
      '/en/legal',
      '/fr/events',
      '/en/events/Bad-Slug',
      '/assets/index.js',
      '/en/week/next',
      '/en/week/2026-W41',
    ]
    expect(paths.filter((path) => nginx.test(path))).toEqual([])
    for (const path of paths) expect(matchStaticRoute(path) ?? matchDetailRoute(path)).toBeNull()
  })
})

describe('matchSitemap', () => {
  it('recognises the sitemap the index names for each family', () => {
    // The index, nginx and the BFF all list these four; a fifth family needs every one of them.
    expect(DETAIL_SITEMAPS.map((path) => matchSitemap(path))).toEqual([...ENTITY_KINDS])
  })

  it('refuses anything else, the static pages sitemap included', () => {
    const paths = [
      '/sitemap.xml',
      '/sitemap-pages.xml',
      '/sitemap-genres.xml',
      '/sitemap-events.xml/x',
      '/en/sitemap-events.xml',
    ]
    expect(paths.map((path) => [path, matchSitemap(path)])).toEqual(
      paths.map((path) => [path, null]),
    )
  })

  it('agrees with the nginx location that sends the sitemaps here', () => {
    const conf = readFileSync(resolve(process.cwd(), 'docker/nginx.conf'), 'utf8')
    const source = /location ~ (\^\/sitemap-\S*) \{\s*proxy_pass http:\/\/127\.0\.0\.1:3000;/.exec(
      conf,
    )?.[1]
    expect(source).toBeTruthy()
    const nginx = new RegExp(source ?? '(?!)')
    expect([...DETAIL_SITEMAPS, WEEKS_SITEMAP].filter((path) => !nginx.test(path))).toEqual([])
    expect(['/sitemap.xml', '/sitemap-pages.xml'].filter((path) => nginx.test(path))).toEqual([])
  })
})

describe('matchFeed', () => {
  it('returns the query of a feed request, or an empty one', () => {
    expect(matchFeed('/feed.xml')).toBe('')
    expect(matchFeed('/feed.xml?locale=de&genre=techno')).toBe('?locale=de&genre=techno')
    expect(matchFeed('/feed.xml?locale=de#top')).toBe('?locale=de')
  })

  it('refuses any other path', () => {
    const paths = ['/feed', '/feed.xml/x', '/en/feed.xml', '/feed.xmlx', '/api/events/feed']
    expect(paths.map((path) => [path, matchFeed(path)])).toEqual(paths.map((path) => [path, null]))
  })

  it('agrees with the nginx location that sends the feed here', () => {
    const conf = readFileSync(resolve(process.cwd(), 'docker/nginx.conf'), 'utf8')
    expect(conf).toMatch(/location = \/feed\.xml \{\s*proxy_pass http:\/\/127\.0\.0\.1:3000;/)
  })
})

describe('matchCalendar', () => {
  it('returns the query of a calendar request, or an empty one', () => {
    expect(matchCalendar('/calendar.ics')).toBe('')
    expect(matchCalendar('/calendar.ics?locale=de&genre=jazz')).toBe('?locale=de&genre=jazz')
  })

  it('refuses any other path, the feed among them', () => {
    const paths = [
      '/calendar',
      '/calendar.ics/x',
      '/en/calendar.ics',
      '/feed.xml',
      '/api/events/calendar.ics',
    ]
    expect(paths.map((path) => [path, matchCalendar(path)])).toEqual(
      paths.map((path) => [path, null]),
    )
  })

  it('agrees with the nginx location that sends the calendar here', () => {
    const conf = readFileSync(resolve(process.cwd(), 'docker/nginx.conf'), 'utf8')
    expect(conf).toMatch(/location = \/calendar\.ics \{\s*proxy_pass http:\/\/127\.0\.0\.1:3000;/)
  })
})
