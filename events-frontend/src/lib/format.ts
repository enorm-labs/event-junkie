// Formatting helpers for BFF values: ISO dates and times, plain-number prices, rendered for a
// Berlin/EU audience.

const DATE_FORMAT_OPTIONS: Intl.DateTimeFormatOptions = {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
}

// The compact view sets the date beside the title, where the year costs five characters (#1371).
const SHORT_DATE_FORMAT_OPTIONS: Intl.DateTimeFormatOptions = {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
}

// Intl.DateTimeFormat construction is not free and these render per event card; one per locale.
const dateFormatters = new Map<string, Intl.DateTimeFormat>()

function dateFormatter(
  locale: string,
  options: Intl.DateTimeFormatOptions = DATE_FORMAT_OPTIONS,
): Intl.DateTimeFormat {
  const key = `${locale}|${options === DATE_FORMAT_OPTIONS ? 'full' : 'short'}`
  let formatter = dateFormatters.get(key)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(locale, options)
    dateFormatters.set(key, formatter)
  }
  return formatter
}

/**
 * Formats an ISO date for `locale`: "Fri, 12 Jun 2026" / "Fr., 12. Juni 2026". Parses the parts
 * by hand to avoid the UTC shift `new Date('2026-06-12')` causes. `locale` is passed in so this
 * stays a pure function the unit tests can call without an app.
 */
export function formatDate(isoDate?: string | null, locale: string = 'en'): string {
  if (!isoDate) return ''
  const [year, month, day] = isoDate.split('-').map(Number)
  if (!year || !month || !day) return isoDate
  return dateFormatter(locale).format(new Date(year, month - 1, day))
}

/** The weekday alone — "Fri" / "Fr." — for the far end of a span that crosses more than one night. */
export function formatWeekday(isoDate?: string | null, locale: string = 'en'): string {
  if (!isoDate) return ''
  const [year, month, day] = isoDate.split('-').map(Number)
  if (!year || !month || !day) return isoDate
  return new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(
    new Date(year, month - 1, day),
  )
}

/** Days from `from` to `to`, both ISO dates; negative when `to` is earlier. */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000)
}

/**
 * The same date without the year, for the compact view. The year comes back for any other year,
 * so a gig from 2024 cannot read as one from this June; `year` is a parameter because a helper
 * that reads the clock cannot be tested without freezing it.
 */
export function formatShortDate(
  isoDate?: string | null,
  locale: string = 'en',
  currentYear: number = new Date().getFullYear(),
): string {
  if (!isoDate) return ''
  const [year, month, day] = isoDate.split('-').map(Number)
  if (!year || !month || !day) return isoDate
  if (year !== currentYear) return formatDate(isoDate, locale)
  return dateFormatter(locale, SHORT_DATE_FORMAT_OPTIONS).format(new Date(year, month - 1, day))
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
 * The full one-line label, `"<title> @ <venue>"`, the hover tooltip wherever the visible title is
 * clipped. Falls back to the bare title rather than a dangling "@".
 */
export function eventLabel(title?: string | null, venueName?: string | null): string {
  if (!title) return venueName ?? ''
  return venueName ? `${title} @ ${venueName}` : title
}

/**
 * Sentence-cases an unknown event-type constant, `CLUB_NIGHT` to "Club night". Labels come from
 * the message catalogue (`eventType.*`, ADR-013 §Decision 4); this is the fallback for a BFF enum
 * value the catalogue has not seen, so it reads as English words rather than a raw constant.
 */
export function humaniseEventType(eventType?: string | null): string {
  if (!eventType) return ''
  const words = eventType.replace(/_/g, ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/**
 * Today's date in Berlin as `YYYY-MM-DD`, for default date filters. `en-CA` is a format, not a
 * language: do NOT make it locale-aware. The active locale would still produce a plausible date
 * (`12.6.2026` for `de-DE`) that the BFF rejects or misreads, silently breaking every filter.
 */
export function todayIso(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date())
}

/** The time of day in Berlin as `HH:mm`, for the late-night grace in `isPastEvent`. */
export function berlinTimeIso(): string {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Europe/Berlin',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date())
}

/**
 * Tomorrow in Berlin as `YYYY-MM-DD`. The Home "Upcoming" feed starts here; today's events are
 * the "Tonight" section.
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

/** The fields that decide whether an event is over. */
export type EventSpan = {
  eventDate?: string | null
  endDate?: string | null
  endTime?: string | null
  startTime?: string | null
  doorsTime?: string | null
  assumedStartTime?: string | null
}

/** A start at or after this is a night, and gets the grace below (#299). */
const LATE_START = '22:00'

/** When last night is over (#299). The BFF and the importer share the hour. */
const GRACE_ENDS = '06:00'

/**
 * Whether an event is over: on `endDate` when the venue stated one, else on its date, and today
 * counts as not over, matching the importer's `dropPastEvents` and the BFF's window on
 * `COALESCE(end_date, event_date)` (ADR-029). A stated `endTime` on today's date is over once the
 * Berlin clock passes it.
 *
 * Last night gets a grace (#299): before 06:00 Berlin, an event dated yesterday with no stated
 * end and an effective start of 22:00 or later (start, else doors, else the BFF's assumed slot)
 * is not over. A club night ends at six, not at midnight.
 */
export function isPastEvent(event: EventSpan): boolean {
  const ends = event.endDate ?? event.eventDate
  if (!ends) return false
  const today = todayIso()
  if (ends > today) return false
  if (ends === today) return !!event.endTime && event.endTime.slice(0, 5) <= berlinTimeIso()
  if (event.endDate || ends !== yesterdayIso() || berlinTimeIso() >= GRACE_ENDS) return true
  // No time at all is a night too, as the importer counts it; the BFF sends a slot anyway.
  const start = event.startTime ?? event.doorsTime ?? event.assumedStartTime
  return !!start && start.slice(0, 5) < LATE_START
}

/** Whether an event started before today and is not over: a weekender in its second night. */
export function isRunningEvent(event: EventSpan): boolean {
  return !!event.eventDate && event.eventDate < todayIso() && !isPastEvent(event)
}
