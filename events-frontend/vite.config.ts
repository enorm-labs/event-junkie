import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import tailwindcss from '@tailwindcss/vite'
import vueI18n from '@intlify/unplugin-vue-i18n/vite'
import markdown from 'unplugin-vue-markdown/vite'

// Explicit `.ts`, unlike the rest of the repo: the npm scripts load this file with
// `--configLoader native`, Node's own resolver, which follows ESM rules. Transitive, so
// `scripts/seoFiles.ts` and `src/lib/seo.ts` carry one too (events-frontend/AGENTS.md
// §Config-loader imports).
import { seoFiles } from './scripts/seoFiles.ts'
import { pageMetaText } from './scripts/pageMetaText.ts'
import { contentSecurityPolicy } from './scripts/csp.ts'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    // `.md` too: the About and For-venues pages are Markdown, compiled to components here (#471).
    vue({ include: [/\.vue$/, /\.md$/] }),
    // Build time only, so no Markdown renderer and no `v-html` reach the browser. Each page writes
    // its own root element (`ProsePage`, `LegalPage`), hence no wrapper div. Typographer and linkify
    // off, so the text renders as typed: no curly quotes the `.vue` version did not have.
    markdown({
      wrapperDiv: false,
      markdownOptions: { typographer: false, linkify: false },
      markdownSetup: (md) => {
        // Off-site links open in a new tab, as every hand-written one on these pages did.
        const render = md.renderer.rules.link_open
        md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
          const token = tokens[idx]!
          if (/^https?:\/\//.test(token.attrGet('href') ?? '')) {
            token.attrSet('rel', 'noopener')
            token.attrSet('target', '_blank')
          }
          return render
            ? render(tokens, idx, options, env, self)
            : self.renderToken(tokens, idx, options)
        }
      },
    }),
    vueDevTools(),
    tailwindcss(),
    // Precompiles the message catalogues so the vue-i18n message compiler is not shipped to the
    // browser (ADR-013).
    vueI18n({ include: fileURLToPath(new URL('./src/i18n/messages/**/*.json', import.meta.url)) }),
    // Generates /sitemap.xml, /sitemap-pages.xml and /robots.txt from LOCALES and INDEXABLE_PATHS,
    // in dev and build.
    seoFiles(),
    // The static pages' titles and descriptions as plain strings, for the injector's parity (#1911).
    pageMetaText(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // `preview`, not `server`: `npm run preview` serves the real `dist/` and is what Playwright
  // starts on CI, so the e2e suite runs against the policy the cluster sends. The dev server is
  // left without it, since `@vitejs/plugin-vue` injects styles inline there. `'self'` rather than
  // the `'self' https:` a serving-off deployment sends: a test stricter than production is the
  // right way round (`scripts/csp.ts`, `values.yaml`).
  preview: {
    headers: {
      'Content-Security-Policy': contentSecurityPolicy("'self'"),
    },
  },
  server: {
    proxy: {
      // Forward /api requests to the Spring Boot backend (events-bff)
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // The detail sitemaps, which nginx sends through the injector on a cluster (#367).
      '^/sitemap-(events|venues|artists|promoters)\\.xml$': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/sitemap-(\w+)\.xml$/, '/api/sitemaps/$1.xml'),
      },
      // The RSS feed, which nginx sends through the injector on a cluster (#368). The query stays.
      '^/feed\\.xml': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/feed\.xml/, '/api/events/feed'),
      },
      // The calendar subscription, which nginx sends through the injector the same way (#2719).
      '^/calendar\\.ics': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/calendar\.ics/, '/api/events/calendar.ics'),
      },
    },
  },
})
