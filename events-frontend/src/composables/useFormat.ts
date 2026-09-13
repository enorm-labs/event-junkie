import { useI18n } from 'vue-i18n'

import { formatDate, formatShortDate, humaniseEventType } from '@/lib/format'
import { INTL_LOCALES, isLocale } from '@/i18n/locales'

/**
 * Locale-aware wrappers around the pure helpers in `lib/format.ts`.
 *
 * The helpers stay pure and take the locale as an argument so they remain unit-testable without an
 * app; this composable is the thin layer that supplies it from the active i18n instance.
 */
export function useFormat() {
  const { locale, t, te } = useI18n()

  return {
    /**
     * `formatDate` bound to the active locale — "Fri, 12 Jun 2026" / "Fr., 12. Juni 2026".
     *
     * Maps the UI locale to a formatting tag first: bare `en` means US conventions to `Intl`, and
     * would render the month first (see {@link INTL_LOCALES}).
     */
    formatDate: (isoDate?: string | null) =>
      formatDate(isoDate, isLocale(locale.value) ? INTL_LOCALES[locale.value] : 'en-GB'),

    /** `formatShortDate` bound to the active locale — the compact view's date. */
    formatShortDate: (isoDate?: string | null) =>
      formatShortDate(isoDate, isLocale(locale.value) ? INTL_LOCALES[locale.value] : 'en-GB'),

    /**
     * The display label for an event type.
     *
     * Looks the enum value up in the `eventType.*` catalogue, and falls back to sentence-casing
     * the constant when the BFF sends a value the frontend has not been taught yet. That fallback
     * matters: `EventType` lives in `events-core` and can gain a value in a backend release that
     * ships before the frontend does — "Silent disco" reads acceptably in the meantime, whereas
     * `SILENT_DISCO` or an empty label does not.
     */
    formatEventType: (eventType?: string | null) => {
      if (!eventType) return ''
      const key = `eventType.${eventType}`
      return te(key) ? t(key) : humaniseEventType(eventType)
    },
  }
}
