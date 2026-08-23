import '@fontsource-variable/geist/index.css'
// Geist Mono, for the eyebrow labels, the hero-adjacent mono copy and the footer's version
// string. Without it those fell back to whatever monospace the OS had — SF Mono, Consolas,
// DejaVu — so a named brand device looked different on every platform (docs/BRANDING.md §5.3).
import '@fontsource-variable/geist-mono/index.css'
// Rubik, for the wordmark only. Rubik Distressed — the display face on the stamp — is derived from
// it, so the wordmark rhymes with the artwork without being set in a face that reads as dirt at
// 18 px (docs/BRANDING.md §5.3).
import '@fontsource-variable/rubik/index.css'
import './assets/main.css'

import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import { i18n } from './i18n'

const app = createApp(App)

app.use(i18n)
app.use(router)

app.mount('#app')
