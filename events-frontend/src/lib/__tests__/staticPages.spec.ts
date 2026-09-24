import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { LOCALES } from '@/i18n/locales'
import { INDEXABLE_PATHS } from '@/lib/seo'
import { siteDescription, STATIC_PAGE_KEYS, staticPathMeta } from '@/lib/staticPages'
import router from '@/router'

/**
 * Two writers name a static page's head: the router, through `titleKey` and `descriptionKey` in
 * each route's meta, and the injector, through `STATIC_PAGE_KEYS` (#1911). These keep the two
 * lists identical, and the shipped `index.html` identical to the catalogue's English home text.
 */

const localeSegment = `/:locale(${LOCALES.join('|')})`
const routerKeys = Object.fromEntries(
  router
    .getRoutes()
    // Only routes that name a description: the home page shares its path with the locale's parent route.
    .filter((route) => route.path.startsWith(localeSegment) && route.meta.descriptionKey)
    .map((route) => [
      route.path.slice(localeSegment.length),
      {
        title: (route.meta.titleKey as string | undefined)?.replace('pageTitle.', ''),
        description: (route.meta.descriptionKey as string | undefined)?.replace(
          'pageDescription.',
          '',
        ),
      },
    ]),
)

describe('the static pages the injector writes', () => {
  it('uses the keys the router uses, for every indexable page', () => {
    // Mapped rather than looped, so a failure names the path.
    const router = INDEXABLE_PATHS.map((path) => [
      path,
      Object.fromEntries(Object.entries(routerKeys[path] ?? {}).filter(([, key]) => key)),
    ])
    expect(INDEXABLE_PATHS.map((path) => [path, STATIC_PAGE_KEYS[path]])).toEqual(router)
  })

  it('gives every page a title and a description in every locale', () => {
    const missing = LOCALES.flatMap((locale) =>
      INDEXABLE_PATHS.map((path) => [`${locale}${path}`, staticPathMeta(locale, path)] as const),
    ).filter(([, meta]) => !meta.title.includes('Event Junkie') || !meta.description)
    expect(missing).toEqual([])
  })

  it('has a site description per locale, and the German one is not the English one', () => {
    expect(siteDescription('de')).not.toBe(siteDescription('en'))
  })
})

describe('index.html', () => {
  const shell = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')
  const head = new DOMParser().parseFromString(shell, 'text/html').head

  it("ships the catalogue's English home description in all three tags", () => {
    const tags = [
      'meta[name="description"]',
      'meta[property="og:description"]',
      'meta[name="twitter:description"]',
    ]
    expect(tags.map((selector) => head.querySelector<HTMLMetaElement>(selector)?.content)).toEqual(
      Array(3).fill(siteDescription('en')),
    )
  })
})
