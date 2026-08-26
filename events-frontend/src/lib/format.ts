// Formatting helpers for BFF values. Dates/times arrive as ISO strings (`2026-06-12`, `19:00`)
// and prices as plain numbers; these render them for a Berlin/EU audience.

const DATE_FORMAT_OPTIONS: Intl.DateTimeFormatOptions = {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
}

// Intl.DateTimeFormat construction is not free and these are rendered per event card, so cache
// one formatter per locale rather than building one per call.
const dateFormatters = new Map<string, Intl.DateTimeFormat>()

function dateFormatter(locale: string): Intl.DateTimeFormat {
  let formatter = dateFormatters.get(locale)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(locale, DATE_FORMAT_OPTIONS)
    dateFormatters.set(locale, formatter)
  }
  return formatter
}

/**
 * Formats an ISO date (`YYYY-MM-DD`) for `locale` — "Fri, 12 Jun 2026" in English, "Fr., 12. Juni
 * 2026" in German. Parses the parts by hand to avoid the UTC shift `new Date('2026-06-12')` causes.
 *
 * `locale` is passed in rather than read from the i18n instance so this stays a pure function:
 * callers get it from `useI18n()`, and the unit tests do not need an app.
 */
export function formatDate(isoDate?: string | null, locale: string = 'en'): string {
  if (!isoDate) return ''
  const [year, month, day] = isoDate.split('-').map(Number)
  if (!year || !month || !day) return isoDate
  return dateFormatter(locale).format(new Date(year, month - 1, day))
}

/** Trims an ISO time (`HH:mm[:ss]`) down to `HH:mm`. */
export function formatTime(isoTime?: string | null): string {
  if (!isoTime) return ''
  return isoTime.slice(0, 5)
}

/** Formats a numeric amount with its ISO currency code, e.g. "38,00 €". Returns null when unknown. */
export function formatPrice(amount?: number | null, currency?: string | null): string | null {
  if (amount == null) return null
  return new Intl.NumberFormat('de-DE', {
    style: 'currency',
    currency: currency ?? 'EUR',
  }).format(amount)
}

/**
 * The full one-line label for an event — `"<title> @ <venue>"`. Used as the hover tooltip
 * wherever the visible title is clipped (calendar cells, event cards), so those surfaces read
 * the same. Falls back to the bare title when the venue is unknown, rather than leaving a
 * dangling "@".
 */
export function eventLabel(title?: string | null, venueName?: string | null): string {
  if (!title) return venueName ?? ''
  return venueName ? `${title} @ ${venueName}` : title
}

/**
 * Sentence-cases an unknown event-type constant — `CLUB_NIGHT` → "Club night".
 *
 * This *was* how every event type was labelled, chosen so a type added to the BFF enum read
 * correctly without a change here. It cannot survive localisation: no amount of locale plumbing
 * turns `CLUB_NIGHT` into "Clubnacht". Labels now come from the message catalogue
 * (`eventType.*`), and this remains only as the fallback for a value the catalogue has not seen —
 * a new BFF enum value still reads as English words rather than as a raw constant.
 *
 * See docs/adr/ADR-013_LOCALISATION.md §Decision 4.
 */
export function humaniseEventType(eventType?: string | null): string {
  if (!eventType) return ''
  const words = eventType.replace(/_/g, ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/**
 * Today's date in Berlin as an ISO date string (`YYYY-MM-DD`), for default date filters.
 *
 * **`en-CA` here is a format, not a language — do NOT make it locale-aware.** It is the shortest
 * way to get `YYYY-MM-DD` out of `Intl`. Swapping it for the active locale breaks every date
 * filter in the app *silently*, because the output is still a plausible date
 * (`12.6.2026` for `de-DE`) that the BFF then rejects or misreads.
 */
export function todayIso(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date())
}

/**
 * Tomorrow's date in Berlin as an ISO date string (`YYYY-MM-DD`). Used by the Home "Upcoming"
 * feed so it starts the day after today — today's events live in the separate "Tonight" section.
 */
export function tomorrowIso(): string {
  return addDays(todayIso(), 1)
}

/** Yesterday in Berlin. The archive feeds' inclusive `to`, so today's events stay out of them. */
export function yesterdayIso(): string {
  return addDays(todayIso(), -1)
}

/** Adds whole calendar days to an ISO date. UTC arithmetic, so a DST shift can't move it. */
export function addDays(isoDate: string, days: number): string {
  const date = new Date(`${isoDate}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

/**
 * Whether an event has happened. Today counts as upcoming, matching the importer's
 * `dropPastEvents` and the BFF's `event_date >= today`; one function keeps the three agreeing.
 */
export function isPastEvent(isoDate?: string | null): boolean {
  return !!isoDate && isoDate < todayIso()
}
