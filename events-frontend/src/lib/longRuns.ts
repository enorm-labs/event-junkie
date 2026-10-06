// A run of weeks, such as an exhibition, folds out of a day list on the days between its opening and
// its last days (#2594). It stays findable in the list's "Also running" block. The threshold sits by
// duration above a club weekend: a 5-day Sisyphos run is that night's main event and stays a card.

import { daysBetween, isOnNow, type EventSpan } from './format'

/** A run longer than this many calendar days, `eventDate` to `endDate` inclusive, is a long run. */
export const LONG_RUN_DAYS = 7

/** A long run is a card again on this many last days, with "Closes <weekday>". */
export const CLOSING_DAYS = 3

type Run = { eventDate?: string | null; endDate?: string | null }

/** Whether the event runs longer than {@link LONG_RUN_DAYS} calendar days. */
export function isLongRun(event: Run): boolean {
  if (!event.eventDate || !event.endDate) return false
  return daysBetween(event.eventDate, event.endDate) + 1 > LONG_RUN_DAYS
}

/** Whether `day` is one of a long run's last {@link CLOSING_DAYS} days. */
export function isClosing(event: Run, day: string): boolean {
  if (!isLongRun(event) || !event.endDate) return false
  const left = daysBetween(day, event.endDate)
  return left >= 0 && left < CLOSING_DAYS
}

/** Whether a long run moves to the "Also running" block on `day`: after its opening, before its last days. */
export function foldsOn(event: Run, day: string): boolean {
  if (!isLongRun(event) || !event.eventDate || !event.endDate) return false
  return day > event.eventDate && daysBetween(day, event.endDate) >= CLOSING_DAYS
}

/** One day's list, split into the cards and the long runs folded into "Also running". Order is kept. */
export function splitDayList<T extends Run>(
  events: readonly T[],
  day: string,
): { cards: T[]; alsoRunning: T[] } {
  const cards: T[] = []
  const alsoRunning: T[] = []
  for (const event of events) (foldsOn(event, day) ? alsoRunning : cards).push(event)
  return { cards, alsoRunning }
}

/**
 * Whether an event carries the pulsing "live" dot: it is on now (`isOnNow`), and it is not a long
 * run. An exhibition has been "on" for weeks and states no opening hours, so a dot on it says
 * nothing; tonight's gig gets one from its start, not from the morning.
 */
export function isLiveNow(event: EventSpan): boolean {
  return isOnNow(event) && !isLongRun(event)
}
