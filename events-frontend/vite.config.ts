import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import tailwindcss from '@tailwindcss/vite'
import vueI18n from '@intlify/unplugin-vue-i18n/vite'

// Explicit `.ts`, unlike the rest of the repo: Vite's config loader is moving to
// `configLoader: 'native'`, Node's own resolver, which follows ESM rules. Transitive, so
// `scripts/seoFiles.ts` and `src/lib/seo.ts` carry one too (events-frontend/AGENTS.md
// §Config-loader imports).
import { seoFiles } from './scripts/seoFiles.ts'
import { pageMetaText } from './scripts/pageMetaText.ts'
import { contentSecurityPolicy } from './scripts/csp.ts'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
    tailwindcss(),
    // Precompiles the message catalogues so the vue-i18n message compiler is not shipped to the
    // browser (ADR-013).
    vueI18n({ include: fileURLToPath(new URL('./src/i18n/messages/**/*.json', import.meta.url)) }),
    // Generates /sitemap.xml and /robots.txt from LOCALES and INDEXABLE_PATHS, in dev and build.
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
    },
  },
})
