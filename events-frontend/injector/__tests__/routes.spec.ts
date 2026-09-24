import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { LOCALES } from '@/i18n/locales'
import { INDEXABLE_PATHS } from '@/lib/seo'
import { matchDetailRoute, matchStaticRoute } from '../routes.ts'

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

/**
 * nginx decides what reaches the injector, and the injector decides again. The two lists are kept
 * in step by reading the regex out of `docker/nginx.conf`: a page the injector knows but nginx does
 * not send would keep the English shell without anything failing.
 */
describe('the nginx location that proxies to the injector', () => {
  const conf = readFileSync(resolve(process.cwd(), 'docker/nginx.conf'), 'utf8')
  const source = /location ~ (\^\/\(en\|de\)\S*) \{\s*proxy_pass http:\/\/127\.0\.0\.1:3000;/.exec(
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
    ]
    expect(paths.filter((path) => nginx.test(path))).toEqual([])
    for (const path of paths) expect(matchStaticRoute(path) ?? matchDetailRoute(path)).toBeNull()
  })
})
