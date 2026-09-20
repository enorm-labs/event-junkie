import { createI18n } from 'vue-i18n'

import de from './messages/de'
import en from './messages/en'
import { DEFAULT_LOCALE, type Locale } from './locales'

/**
 * The i18n instance. `legacy: false` selects the Composition API: the Legacy API is deprecated in
 * vue-i18n v11 and removed in v12 (ADR-013 §Decision 1). Both locales are registered eagerly;
 * the catalogues are ~4 kB each, precompiled, so lazy-loading would add a request and a loading
 * state to save less than one icon.
 */
type MessageSchema = typeof en

/** A key into `errors.subject.*`. vue-i18n echoes a miss, so prose only breaks German (#768). */
export type ErrorSubjectKey = `errors.subject.${keyof (typeof en)['errors']['subject']}`

// The generics matter: without them vue-i18n infers the locale type from the keys of `messages`,
// so `setI18nLocale('de')` would not compile until German landed. `Locale` is what the site
// publishes, not what is loaded.
export const i18n = createI18n<[MessageSchema], Locale, false>({
  legacy: false,
  locale: DEFAULT_LOCALE,
  fallbackLocale: DEFAULT_LOCALE,
  messages: { en, de },
  // Left ON: a missing translation should be noisy in development. The key-parity test in
  // src/i18n/__tests__/messages.spec.ts stops one reaching a build.
  missingWarn: true,
  fallbackWarn: true,
})

/**
 * Switches the active locale and reflects it on `<html lang>`, which tells assistive technology
 * which language to pronounce; an empty or wrong value is a WCAG 3.1.1 failure.
 */
export function setI18nLocale(locale: Locale): void {
  i18n.global.locale.value = locale
  document.documentElement.setAttribute('lang', locale)
}
