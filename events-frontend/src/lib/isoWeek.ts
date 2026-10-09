import { addDays, todayIso } from '@/lib/format'

/**
 * ISO 8601 weeks for the week page (#2728): Monday to Sunday, week 1 holding the year's first
 * Thursday. The URL form is `2026-41`, the year being the ISO week-year, which differs from the
 * calendar year for a few days around New Year. Free of the DOM and of Vue, for the injector. All
 * arithmetic is on ISO dates in UTC, like `addDays`, so a DST shift cannot move a day.
 */

export interface IsoWeek {
  year: number
  week: number
}

const SHAPE = /^(\d{4})-(\d{2})$/

/** 1 for Monday to 7 for Sunday, of an ISO date. */
function isoWeekday(isoDate: string): number {
  return new Date(`${isoDate}T00:00:00Z`).getUTCDay() || 7
}

/** The Monday of week 1: the Monday on or before 4 January, which is always in week 1. */
function firstMonday(year: number): string {
  const january4 = `${String(year).padStart(4, '0')}-01-04`
  return addDays(january4, 1 - isoWeekday(january4))
}

/** The ISO week an ISO date falls in. The Thursday of its week decides the week-year. */
export function isoWeekOf(isoDate: string): IsoWeek {
  const thursday = addDays(isoDate, 4 - isoWeekday(isoDate))
  const year = Number(thursday.slice(0, 4))
  const days = Math.round(
    (Date.parse(`${thursday}T00:00:00Z`) - Date.parse(`${firstMonday(year)}T00:00:00Z`)) /
      86_400_000,
  )
  return { year, week: Math.floor(days / 7) + 1 }
}

/** 52 or 53: 28 December is always in the year's last week. */
export function weeksInYear(year: number): number {
  return isoWeekOf(`${String(year).padStart(4, '0')}-12-28`).week
}

/** Parses `2026-41`, or returns `null` for any other shape and for a week the year does not have. */
export function parseIsoWeek(value: unknown): IsoWeek | null {
  if (typeof value !== 'string') return null
  const match = SHAPE.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const week = Number(match[2])
  if (year < 1 || week < 1 || week > weeksInYear(year)) return null
  return { year, week }
}

/** The URL form, `2026-41`. */
export function formatIsoWeek({ year, week }: IsoWeek): string {
  return `${String(year).padStart(4, '0')}-${String(week).padStart(2, '0')}`
}

/** The week's Monday, as an ISO date. */
export function mondayOf({ year, week }: IsoWeek): string {
  return addDays(firstMonday(year), (week - 1) * 7)
}

/** The week's seven days, Monday first, as ISO dates. */
export function daysOf(week: IsoWeek): string[] {
  const monday = mondayOf(week)
  return Array.from({ length: 7 }, (_, index) => addDays(monday, index))
}

/** The week `count` weeks after `week`; negative for earlier. */
export function shiftWeek(week: IsoWeek, count: number): IsoWeek {
  return isoWeekOf(addDays(mondayOf(week), count * 7))
}

/** The week it is in Berlin now. */
export function currentIsoWeek(): IsoWeek {
  return isoWeekOf(todayIso())
}
