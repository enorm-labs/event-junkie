// The Christmas markets' own page (#4972): an event feature set by hand, which the default lists leave out.
import { daysBetween } from '@/lib/format'

import type { EventSummary } from '@/api/types'
import { addDays, isoWeekday } from './format'
import { distanceKm, type Position } from './geo'
import { venuePosition } from './mapPins'

/** The event feature that marks a Christmas market, as `EventFeature.CHRISTMAS_MARKET` in the importer. */
export const CHRISTMAS_MARKET = 'christmas-market'

/**
 * Whether the home page points to the markets on [day], an ISO date: from 10 November to 6 January. The first
 * markets open in early November, and the last close after New Year.
 */
export function inChristmasMarketSeason(day: string): boolean {
  const monthDay = day.slice(5)
  return monthDay >= '11-10' || monthDay <= '01-06'
}

/** The day filter's two words in the URL; any other value is a date (#5005). */
export const MARKET_TODAY = 'today'
export const MARKET_WEEKEND = 'weekend'

/** The `sort` value for nearest first (#5010). */
export const MARKET_NEAREST = 'distance'

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/
const SATURDAY = 6
const SUNDAY = 7

/**
 * The Berlin days a choice of the day filter stands for, `[]` for none. The weekend is Saturday and Sunday, and on a
 * Sunday that Sunday alone. A date before [today] is no choice, because the page loads only the markets still on.
 */
export function marketDays(choice: string, today: string): string[] {
  if (choice === MARKET_TODAY) return [today]
  if (choice === MARKET_WEEKEND) {
    const weekday = isoWeekday(today)
    if (weekday === SUNDAY) return [today]
    const saturday = addDays(today, SATURDAY - weekday)
    return [saturday, addDays(saturday, 1)]
  }
  return ISO_DATE.test(choice) && choice >= today ? [choice] : []
}

/**
 * Whether a market is open on [day]. A market open on some days only is one event per block of open days (#4988),
 * so the days between its first and last are open days.
 */
export function isOpenOn(
  market: { eventDate?: string | null; endDate?: string | null },
  day: string,
): boolean {
  const first = market.eventDate
  return !!first && first <= day && day <= (market.endDate ?? first)
}

/** One market on the page: every event entered for it, and the one its row and its pin link to. */
export interface ChristmasMarket<T> {
  key: string
  /** The block the row shows: the first one that passes the filters and has not ended. */
  shown: T
  /** Every block of the market, first one first. */
  blocks: T[]
}

type MarketEvent = {
  slug?: string
  title?: string
  eventDate?: string | null
  endDate?: string | null
  venue?: { slug?: string } | null
}

/**
 * Folds the events of one market into one entry (#5005). A market open on some days only is one event per block of
 * open days (#4988), and its blocks share the venue and the title. Two markets at one venue keep their own titles.
 * A market passes when one of its blocks passes [keep], and it shows the first such block that has not ended by
 * [today]. The order is the order of each market's first event.
 */
export function foldMarkets<T extends MarketEvent>(
  events: readonly T[],
  today: string,
  keep: (block: T) => boolean = () => true,
): ChristmasMarket<T>[] {
  const groups = new Map<string, T[]>()
  for (const event of events) {
    const key = event.venue?.slug
      ? `${event.venue.slug}|${event.title ?? ''}`
      : `event|${event.slug}`
    groups.set(key, [...(groups.get(key) ?? []), event])
  }
  return [...groups].flatMap(([key, group]) => {
    const blocks = [...group].sort((a, b) => (a.eventDate ?? '').localeCompare(b.eventDate ?? ''))
    const kept = blocks.filter(keep)
    const shown = kept.find((block) => (block.endDate ?? block.eventDate ?? '') >= today) ?? kept[0]
    return shown ? [{ key, shown, blocks }] : []
  })
}

/**
 * The markets nearest first from [origin], each with its distance, measured by the venue of the block it shows. A
 * market with no coordinate goes last; the sort is stable, so a tie and those last keep the order they came in.
 */
export function nearestFirst<T extends EventSummary>(
  markets: readonly ChristmasMarket<T>[],
  origin: Position,
): { market: ChristmasMarket<T>; distanceKm: number | null }[] {
  return markets
    .map((market) => {
      const position = venuePosition(market.shown.venue)
      return { market, distanceKm: position ? distanceKm(origin, position) : null }
    })
    .sort((a, b) => {
      if (a.distanceKm === null || b.distanceKm === null) {
        return (a.distanceKm === null ? 1 : 0) - (b.distanceKm === null ? 1 : 0)
      }
      return a.distanceKm - b.distanceKm
    })
}

/** How many days ahead a market's opening or ending earns a marker in its row (#5006). */
const MARKER_DAYS = 6

/**
 * The one state word a market's row carries about its dates: opening soon, last day, or ending soon. A one-day market
 * has its own two: on its day, or on a day in the next {@link MARKER_DAYS}.
 */
export type MarketMarker = {
  kind: 'opens' | 'lastDay' | 'endsSoon' | 'todayOnly' | 'onlyOn'
  day: string
}

/**
 * The marker for a market's run on [today], a Berlin ISO date, or `null`. A run with no end date is a run of one day.
 * When the opening and the ending both fall in the next {@link MARKER_DAYS} days, the ending wins.
 */
export function marketMarker(
  run: { eventDate?: string | null; endDate?: string | null },
  today: string,
): MarketMarker | null {
  if (!run.eventDate) return null
  const ends = run.endDate ?? run.eventDate
  const oneDay = ends === run.eventDate
  const untilEnd = daysBetween(today, ends)
  if (untilEnd === 0) return { kind: oneDay ? 'todayOnly' : 'lastDay', day: ends }
  if (untilEnd > 0 && untilEnd <= MARKER_DAYS)
    return { kind: oneDay ? 'onlyOn' : 'endsSoon', day: ends }
  const untilStart = daysBetween(today, run.eventDate)
  if (untilStart > 0 && untilStart <= MARKER_DAYS) return { kind: 'opens', day: run.eventDate }
  return null
}
