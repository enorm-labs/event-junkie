/// <reference types="vite/client" />

// The About and For-venues pages, compiled by unplugin-vue-markdown (vite.config.ts).
declare module '*.md' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent
  export default component
}
