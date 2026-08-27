import { createI18n } from 'vue-i18n'

import de from './messages/de'
import en from './messages/en'
import { DEFAULT_LOCALE, type Locale } from './locales'

/**
 * The i18n instance.
 *
 * `legacy: false` selects the Composition API (`useI18n`). The Legacy API is deprecated in
 * vue-i18n v11 and removed in v12, so this is not a style preference — writing against it now
 * makes v12 a version bump rather than a migration (ADR-013 §Decision 1).
 *
 * Both published locales are registered eagerly. The catalogues are small (~4 kB each, precompiled
 * by the Vite plugin), so lazy-loading them would add a request and a loading state to save less
 * than one icon's worth of bytes.
 */
type MessageSchema = typeof en

/** A key into `errors.subject.*`. vue-i18n echoes a miss, so prose only breaks German (#768). */
export type ErrorSubjectKey = `errors.subject.${keyof (typeof en)['errors']['subject']}`

// The generics matter: without them vue-i18n infers the locale type from the keys of `messages`,
// which is `'en'` alone until German lands — so `setI18nLocale('de')` would not compile even
// though switching is the whole point. Declaring `Locale` up front keeps the type honest about
// what the site publishes rather than about what is currently loaded.
export const i18n = createI18n<[MessageSchema], Locale, false>({
  legacy: false,
  locale: DEFAULT_LOCALE,
  fallbackLocale: DEFAULT_LOCALE,
  messages: { en, de },
  // Left ON deliberately now that a second locale exists: a missing translation should be noisy in
  // development. The key-parity test in src/i18n/__tests__/messages.spec.ts is what stops one
  // reaching a build in the first place.
  missingWarn: true,
  fallbackWarn: true,
})

/**
 * Switches the active locale and reflects it on `<html lang>`.
 *
 * The `lang` attribute is not decoration: it tells assistive technology which language to
 * pronounce the page in, and an empty or wrong value is a WCAG 3.1.1 failure. It was `lang=""`
 * until the footer work fixed it — do not let it drift back.
 */
export function setI18nLocale(locale: Locale): void {
  i18n.global.locale.value = locale
  document.documentElement.setAttribute('lang', locale)
}
