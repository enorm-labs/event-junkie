/**
 * Hours an event lasts when the venue stated no end, per event type. An estimate: the calendar
 * export says so on the entry, and `isOnNow` ends by it. Mirrored in `EventCalendarIcs.kt`.
 */
export const DEFAULT_DURATION_HOURS: Readonly<Record<string, number>> = {
  CONCERT: 3,
  FESTIVAL: 8,
  PARTY: 6,
  QUIZ: 2,
  SHOW: 2,
  COMEDY: 2,
  SCREENING: 2,
  EXHIBITION: 3,
  READING: 2,
  OTHER: 3,
}
const FALLBACK_DURATION_HOURS = 3

/** The hours an event of `eventType` is assumed to last; an unknown type gets the fallback. */
export function assumedDurationHours(eventType?: string | null): number {
  return DEFAULT_DURATION_HOURS[eventType ?? ''] ?? FALLBACK_DURATION_HOURS
}
