import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

import { importerTarget } from './src/lib/environments.ts'

// The dev server is the only way this app runs (ADR-045). It proxies nothing but the admin API,
// and only to a local port-forward, so the cluster is reached the way ADR-023 allows.
export default defineConfig(({ command, mode }) => ({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 5173 is events-frontend's.
    port: 5174,
    strictPort: true,
    proxy:
      command === 'serve' && !process.env.VITEST
        ? { '/api/admin': { target: importerTarget(mode), changeOrigin: true } }
        : undefined,
  },
}))
