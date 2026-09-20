import type { Locale } from '@/i18n/locales'

/**
 * Which description to show, and what language to declare for it. A German text under English
 * chrome is what the venue published; claiming it is English is the defect. So the page shows the
 * visitor's locale where a text exists in it and marks the language either way (ADR-026). One
 * function shared by the view, the page meta, the structured data and, through those, the
 * injector; `injector/__tests__/parity.spec.ts` asserts the two heads agree.
 */
export interface ChosenDescription {
  /** The text to render. */
  text: string
  /** BCP 47 language for `lang` and `inLanguage`, or null when the language is unknown. */
  lang: Locale | null
  /** True when a machine produced this text, which the page must disclose. */
  machine: boolean
}

/**
 * Anything that carries a description in up to two languages: an event, a venue and a promoter,
 * one rule, so this is structural. `descriptionAltOrigin` is optional because only event text can
 * be machine-made; a venue or promoter description is our own prose in both languages (#1210,
 * #328).
 */
export interface Described {
  description?: string | null
  descriptionLanguage?: string | null
  descriptionAlt?: string | null
  descriptionAltLanguage?: string | null
  descriptionAltOrigin?: string | null
}

const isLocale = (value: string | null | undefined): value is Locale =>
  value === 'de' || value === 'en'

/**
 * The description for [locale], or null. The locale's own text wins; failing that the original in
 * its own language, which still says who is playing.
 */
export function descriptionFor(subject: Described, locale: Locale): ChosenDescription | null {
  const original = subject.description
  const originalLang = isLocale(subject.descriptionLanguage) ? subject.descriptionLanguage : null

  const alt = subject.descriptionAlt
  const altLang = isLocale(subject.descriptionAltLanguage) ? subject.descriptionAltLanguage : null
  const altIsMachine = subject.descriptionAltOrigin === 'MACHINE'

  if (original && originalLang === locale) return { text: original, lang: locale, machine: false }
  if (alt && altLang === locale) return { text: alt, lang: locale, machine: altIsMachine }
  if (original) return { text: original, lang: originalLang, machine: false }
  return null
}
