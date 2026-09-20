/**
 * The locales this site is published in, and how one is chosen. Separate from `i18n/index.ts` so
 * the router can import it without vue-i18n and the catalogues (ADR-013).
 */

/**
 * The locales this site publishes; the first is the fallback. Listing one makes `/<locale>/*`
 * routable and resolvable from `Accept-Language`, drives the route matcher directly, and the
 * key-parity test fails if it has no catalogue. Every published locale needs its legal pages in
 * that language (LEGAL.md §6.1): `ImprintView.de.vue`, `PrivacyView.de.vue` for `de`.
 */
export const LOCALES = ['en', 'de'] as const

export type Locale = (typeof LOCALES)[number]

export const DEFAULT_LOCALE: Locale = 'en'

/**
 * `localStorage` key holding the visitor's last locale: a hint for resolving a bare `/` only, the
 * URL being the source of truth (ADR-013 §2). A preference the visitor set themselves is strictly
 * necessary under § 25 (2) 2 TDDDG, so no consent banner; do not store anything else here.
 */
export const LOCALE_STORAGE_KEY = 'locale'

/**
 * The BCP-47 tag each UI locale formats with. Bare `en` resolves to US conventions in `Intl`
 * ("Jun 12, 2026"); `en-GB` gives "12 Jun 2026", right for a Berlin audience.
 */
export const INTL_LOCALES: Record<Locale, string> = {
  en: 'en-GB',
  de: 'de-DE',
}

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value)
}

/**
 * Drops the locale segment: `/de/legal/privacy` to `/legal/privacy`, `/en` to ``. One
 * implementation for the switcher's links, the canonical URL and the `hreflang` alternates,
 * because three subtly different ones is how `/en/` and `/en` became two URLs for one page.
 */
export function stripLocale(path: string): string {
  return path.replace(/^\/[^/]+/, '')
}

/** The stored preference, if it is still a locale we publish. */
function storedLocale(): Locale | null {
  try {
    const stored = localStorage.getItem(LOCALE_STORAGE_KEY)
    return isLocale(stored) ? stored : null
  } catch {
    // Private mode and blocked storage: fall through to the browser's languages.
    return null
  }
}

/** The best match from `Accept-Language`, as the browser reports it. */
function browserLocale(): Locale | null {
  for (const tag of navigator.languages ?? []) {
    // `de-AT` and `de-CH` should both get German.
    const base = tag.split('-')[0]?.toLowerCase()
    if (isLocale(base)) return base
  }
  return null
}

/**
 * Which locale a bare `/` or an unprefixed path sends a visitor to: their previous choice, then
 * the browser's, then `en`.
 */
export function resolveLocale(): Locale {
  return storedLocale() ?? browserLocale() ?? DEFAULT_LOCALE
}

/** Remembers the choice for the next bare-`/` visit. Best-effort; storage can be unavailable. */
export function rememberLocale(locale: Locale): void {
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, locale)
  } catch {
    // The URL still carries the locale, so nothing is lost within this visit.
  }
}
