// Date ranges the app asks the BFF for: the filter bar's shortcuts — the questions people
// actually ask ("what's on today?", "anything this weekend?") rather than two dates they have
// to pick — and the window the calendar endpoints accept.
// Preset arithmetic is on the Berlin calendar date, matching the rest of the app.

import { addDays, isoWeekday, todayIso } from './format'

/** An inclusive date range as ISO `YYYY-MM-DD` strings, the shape the BFF's from/to expect. */
export interface DateRange {
  from: string
  to: string
}

const FRIDAY = 5
const SUNDAY = 7

/** Today only. */
export function today(): DateRange {
  const date = todayIso()
  return { from: date, to: date }
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

/**
 * The presets the filter bar offers, in display order. Each range is computed on click rather
 * than up front, so a page left open overnight still resolves "today" against the current day.
 */
// `key` rather than a literal label: these are rendered as buttons, so the text is UI chrome and
// belongs in the message catalogue (`dateRange.*`). The key doubles as the list key.
export const DATE_PRESETS: readonly { key: string; range: () => DateRange }[] = [
  { key: 'dateRange.today', range: today },
  { key: 'dateRange.thisWeekend', range: thisWeekend },
  { key: 'dateRange.next7Days', range: nextSevenDays },
]

/**
 * The key of the preset a range reads as, or `undefined`. Two presets can resolve to the same
 * range — on a Sunday Today and This weekend are both today alone — so the first in display
 * order wins and at most one button is ever pressed (#2559).
 */
export function activePresetKey(
  shown: DateRange,
  presets: readonly { key: string; range: () => DateRange }[] = DATE_PRESETS,
): string | undefined {
  return presets.find((preset) => {
    const { from, to } = preset.range()
    return from === shown.from && to === shown.to
  })?.key
}

/** The widest range the BFF's calendar endpoints accept, in days, counting both ends — `EventService.MAX_CALENDAR_DAYS`. */
const MAX_CALENDAR_DAYS = 92

/**
 * Narrows [to] so the range stays inside that window. No built-in calendar view exceeds it, but
 * a request that does is a 400, and both calendar pages drive their range from the widget.
 */
export function clampToCalendarWindow(from: string, to: string): string {
  const lastDay = addDays(from, MAX_CALENDAR_DAYS - 1)
  return to > lastDay ? lastDay : to
}
