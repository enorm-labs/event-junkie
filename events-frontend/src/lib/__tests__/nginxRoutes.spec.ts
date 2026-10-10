import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { LOCALES } from '@/i18n/locales'
import router from '@/router'

/**
 * nginx answers a path no location names with 404, and the app still renders it, so a route the
 * router gained but nginx did not looks fine in a browser and is broken for everything else (#2665).
 */

const conf = readFileSync(resolve(process.cwd(), 'docker/nginx.conf'), 'utf8')
const appLocations = [...conf.matchAll(/location ~ "?(\^\/\(en\|de\)[^\s"]*)"? \{/g)].map(
  ([, source]) => new RegExp(source ?? '(?!)'),
)

const localeSegment = `/:locale(${LOCALES.join('|')})`
/** A value of the right shape for a parameter that is not a slug. */
const SAMPLE_PARAMS: Record<string, string> = { isoWeek: '2026-41' }
const routerPaths = router
  .getRoutes()
  .filter((route) => route.path.startsWith(localeSegment))
  .flatMap((route) =>
    LOCALES.map((locale) =>
      `/${locale}${route.path.slice(localeSegment.length)}`.replace(
        /:([a-zA-Z]+)/g,
        (_, name: string) => SAMPLE_PARAMS[name] ?? 'some-slug-1',
      ),
    ),
  )

describe('the nginx locations for the app routes', () => {
  it('are found', () => {
    expect(appLocations.length).toBeGreaterThan(1)
  })

  it('name every route the router has, so none answers 404', () => {
    expect(
      routerPaths.filter((path) => !appLocations.some((location) => location.test(path))),
    ).toEqual([])
  })

  // nginx reads an unquoted `{` as the start of the block and refuses the whole config, so the
  // container exits and the release rolls back (#3085).
  it('quote every regex that holds a brace', () => {
    expect(
      [...conf.matchAll(/location ~\*? ([^\s"]*\{[^\s"]*) \{/g)].map(([, source]) => source),
    ).toEqual([])
  })

  it('include search, a noindex route the injector does not answer', () => {
    expect(routerPaths).toContain('/en/search')
  })
})
