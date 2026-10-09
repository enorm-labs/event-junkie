import text from 'virtual:page-meta-text'

import { INTL_LOCALES, type Locale } from '@/i18n/locales'
import { daysOf, formatIsoWeek, type IsoWeek, isoWeekOf, shiftWeek } from '@/lib/isoWeek'
import type { DateRange } from '@/lib/dateRanges'
import { todayIso } from '@/lib/format'
import { type PageMeta, staticPageMeta } from '@/lib/pageMeta'

/**
 * The week page (#2728): a week's events day by day, at `/<locale>/week/2026-41`. Its head is
 * composed here for both writers, the view and the injector, from the catalogue's `pageTitle.week`
 * and `pageDescription.week` as plain strings, so the two cannot disagree (ADR-014 §Decision 3).
 */

/** How many events a day shows before "All N events" hands over to the events list. */
export const DAY_CAP = 12

/** How many weeks the weeks sitemap lists: this one and the next four. */
export const SITEMAP_WEEKS = 5

/** The locale-relative path of a week, `/week/2026-41`. */
export function weekPath(week: IsoWeek): string {
  return `/week/${formatIsoWeek(week)}`
}

/** The paths of this week and the next ones, for the weeks sitemap. */
export function upcomingWeekPaths(today = todayIso(), count = SITEMAP_WEEKS): string[] {
  const current = isoWeekOf(today)
  return Array.from({ length: count }, (_, index) => weekPath(shiftWeek(current, index)))
}

/** Fills `{name}` placeholders, as vue-i18n does for the same message in the app. */
function fill(message: string, values: Record<string, string | number>): string {
  return message.replace(/\{(\w+)\}/g, (placeholder, name: string) =>
    name in values ? String(values[name]) : placeholder,
  )
}

/**
 * Monday to Sunday in words: "5–11 October 2026" / "5.–11. Oktober 2026". ICU's thin spaces become
 * plain ones, so the text is the same whichever ICU the browser or the injector ships.
 */
export function weekRange(week: IsoWeek, locale: Locale): string {
  const days = daysOf(week)
  const date = (iso: string) => new Date(`${iso}T00:00:00Z`)
  // ES2021, newer than the `lib` the app compiles against; every supported browser and Node have it.
  const format = new Intl.DateTimeFormat(INTL_LOCALES[locale], {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  }) as Intl.DateTimeFormat & { formatRange(start: Date, end: Date): string }
  return format.formatRange(date(days[0]!), date(days[6]!)).replace(/[\u2009\u202f]/g, ' ')
}

/** A day's heading: "Monday 5 October" / "Montag, 5. Oktober". */
export function dayHeading(isoDate: string, locale: Locale): string {
  return new Intl.DateTimeFormat(INTL_LOCALES[locale], {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    timeZone: 'UTC',
  }).format(new Date(`${isoDate}T00:00:00Z`))
}

/** The page's `h1`, and its title before the brand. */
export function weekTitle(week: IsoWeek, locale: Locale): string {
  return fill(text[locale].pageTitle.week, { week: week.week })
}

/** The week page's head. Its description carries the dates, which tell one year's week 41 from the next. */
export function weekPageMeta(week: IsoWeek, locale: Locale): PageMeta {
  return staticPageMeta(
    weekTitle(week, locale),
    fill(text[locale].pageDescription.week, { range: weekRange(week, locale) }),
  )
}

/** Whether the week's Sunday is before today in Berlin. */
export function isPastWeek(week: IsoWeek, today = todayIso()): boolean {
  return daysOf(week)[6]! < today
}

/**
 * The events list's range for the week, for the "Filter this week" link: Monday to Sunday, from
 * today on in the current week. `null` for a past week, as the list shows no past events.
 */
export function weekListRange(week: IsoWeek, today = todayIso()): DateRange | null {
  if (isPastWeek(week, today)) return null
  const days = daysOf(week)
  return { from: days[0]! < today ? today : days[0]!, to: days[6]! }
}

type Dated = { eventDate?: string | null; endDate?: string | null }

export interface WeekDay<T> {
  /** ISO date. */
  date: string
  /** The first {@link DAY_CAP} of the day's events, in the order the BFF sent them. */
  shown: T[]
  /** How many events the day has, shown or not. */
  total: number
}

/**
 * Splits the calendar endpoint's answer for a week into its days, keeping the events list's order.
 * An event counts on the day it starts only: a club night that runs into Sunday morning is
 * Saturday's, and what began the week before is last week's, so a week never opens with last
 * Sunday's nights.
 */
export function weekDays<T extends Dated>(events: readonly T[], week: IsoWeek): WeekDay<T>[] {
  return daysOf(week).map((date) => {
    const all = events.filter((event) => event.eventDate === date)
    return { date, shown: all.slice(0, DAY_CAP), total: all.length }
  })
}
