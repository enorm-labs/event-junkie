import '@fontsource-variable/geist/index.css'
// Geist Mono, for the eyebrow labels, the hero-adjacent mono copy and the footer's version
// string. Without it those fell back to whatever monospace the OS had — SF Mono, Consolas,
// DejaVu — so a named brand device looked different on every platform (docs/BRANDING.md §5.3).
import '@fontsource-variable/geist-mono/index.css'
import './assets/main.css'

import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import { i18n } from './i18n'

const app = createApp(App)

app.use(i18n)
app.use(router)

app.mount('#app')
