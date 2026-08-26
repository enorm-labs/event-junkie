// Shortcut date ranges for the events filter bar — the questions people actually ask
// ("what's on tonight?", "anything this weekend?") rather than two dates they have to pick.
// All arithmetic is on the Berlin calendar date, matching the rest of the app.

import { addDays, todayIso } from './format'

/** An inclusive date range as ISO `YYYY-MM-DD` strings, the shape the BFF's from/to expect. */
export interface DateRange {
  from: string
  to: string
}

const FRIDAY = 5
const SUNDAY = 7

/** ISO-8601 weekday for a date: 1 = Monday … 7 = Sunday (the EU convention this app uses). */
function isoWeekday(isoDate: string): number {
  return new Date(`${isoDate}T00:00:00Z`).getUTCDay() || 7
}

/** Today only. */
export function tonight(): DateRange {
  const today = todayIso()
  return { from: today, to: today }
}

/**
 * Friday through Sunday of the current week. From Friday onwards it starts today instead, because
 * a weekend already under way should show what is left of it — so on a Sunday this is Sunday alone.
 */
export function thisWeekend(): DateRange {
  const today = todayIso()
  const weekday = isoWeekday(today)
  return {
    from: weekday < FRIDAY ? addDays(today, FRIDAY - weekday) : today,
    to: addDays(today, SUNDAY - weekday),
  }
}

/** Today plus the following six days — a full week counting today. */
export function nextSevenDays(): DateRange {
  const today = todayIso()
  return { from: today, to: addDays(today, 6) }
}

/** The thirty days before today. Ends yesterday, so it holds only events that have happened. */
export function lastThirtyDays(): DateRange {
  const today = todayIso()
  return { from: addDays(today, -30), to: addDays(today, -1) }
}

/**
 * The presets the filter bar offers, in display order. Each range is computed on click rather
 * than up front, so a page left open overnight still resolves "tonight" against the current day.
 */
// `key` rather than a literal label: these are rendered as buttons, so the text is UI chrome and
// belongs in the message catalogue (`dateRange.*`). The key doubles as the list key.
export const DATE_PRESETS: readonly { key: string; range: () => DateRange }[] = [
  { key: 'dateRange.tonight', range: tonight },
  { key: 'dateRange.thisWeekend', range: thisWeekend },
  { key: 'dateRange.next7Days', range: nextSevenDays },
  { key: 'dateRange.last30Days', range: lastThirtyDays },
]
