import type { Plugin } from 'vite'

// Explicit `.ts`: this module is reached from vite.config.ts, which Vite's `configLoader: 'native'`
// mode loads through Node's ESM resolver. See the note in vite.config.ts.
import { llmsTxt, PAGES_SITEMAP, robotsTxt, sitemapIndexXml, sitemapXml } from '../src/lib/seo.ts'

/**
 * Emits `sitemap.xml`, the index, the static pages' `sitemap-pages.xml`, `robots.txt` and
 * `llms.txt`. The detail sitemaps the index names come from the BFF: the dev server proxies them (vite.config.ts).
 *
 * A build step rather than two checked-in files under `public/`: both are derived entirely from
 * `LOCALES` and `INDEXABLE_PATHS`, so generating them makes drift **impossible** instead of
 * something a test has to notice. The repo's other generated artefact, `src/assets/notices.json`,
 * is committed only because the bundle imports it; these two are served as-is, so nothing is
 * gained by having a copy in the tree that can go stale.
 *
 * The dev-server middleware is not a convenience. Without it `/sitemap.xml` would 404 under
 * `npm run dev` and resolve under `npm run preview`, so an e2e test covering it would pass in CI
 * and fail locally — the kind of split that teaches people to distrust the suite.
 */
export function seoFiles(): Plugin {
  const files: Record<string, { body: () => string; type: string }> = {
    '/sitemap.xml': { body: sitemapIndexXml, type: 'application/xml' },
    [PAGES_SITEMAP]: { body: sitemapXml, type: 'application/xml' },
    '/robots.txt': { body: robotsTxt, type: 'text/plain' },
    '/llms.txt': { body: llmsTxt, type: 'text/plain' },
  }

  return {
    name: 'event-junkie:seo-files',

    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        // `request.url` carries the query string; these are served by path alone.
        const file = files[(request.url ?? '').split('?')[0]!]
        if (!file) return next()
        response.setHeader('Content-Type', `${file.type}; charset=utf-8`)
        response.end(file.body())
      })
    },

    generateBundle() {
      for (const [path, file] of Object.entries(files)) {
        this.emitFile({ type: 'asset', fileName: path.slice(1), source: file.body() })
      }
    },
  }
}
