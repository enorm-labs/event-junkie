import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'

import { pageMetaText } from './scripts/pageMetaText.ts'

/**
 * The second build `npm run build` runs: the meta-injection sidecar (ADR-014 §Decision 3), bundled
 * into one file for the `Dockerfile.injector` image.
 *
 * `ssr: true` with `noExternal: true` inlines every import — `lib/pageMeta.ts`, `lib/seo.ts`, the
 * locales — so the container carries `injector.mjs` and nothing else: no `node_modules`, no
 * TypeScript, no second copy of the source to drift. Same alias as the app build, because
 * `lib/pageMeta.ts` imports through `@/`, and it is the module this whole build exists to share.
 */
export default defineConfig({
  // The app build copies `public/` into `dist/`; this one ships one file and no favicon.
  publicDir: false,
  // The static pages' head text, the one plugin the two builds share.
  plugins: [pageMetaText()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    ssr: 'injector/server.ts',
    outDir: 'dist-injector',
    target: 'node24',
    emptyOutDir: true,
    // Readable output on purpose: the file is inspected inside the container when something is
    // wrong, and there is no browser to shave bytes for.
    minify: false,
    rollupOptions: {
      output: { entryFileNames: 'injector.mjs', format: 'es' },
    },
  },
  ssr: {
    target: 'node',
    noExternal: true,
  },
})
