import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EventSummary } from '@/api/types'
import {
  foldMarkets,
  inChristmasMarketSeason,
  isOpenOn,
  marketDays,
  marketMarker,
  nearestFirst,
} from '@/lib/christmasMarkets'
import { todayIso } from '@/lib/format'

describe('inChristmasMarketSeason', () => {
  it.each([
    ['2026-11-09', false],
    ['2026-11-10', true],
    ['2026-12-31', true],
    ['2027-01-06', true],
    ['2027-01-07', false],
    ['2027-07-01', false],
  ])('on %s is %s', (day, expected) => {
    expect(inChristmasMarketSeason(day)).toBe(expected)
  })
})

describe('marketDays', () => {
  it.each([
    ['a Wednesday', '2026-12-09', ['2026-12-12', '2026-12-13']],
    ['a Friday', '2026-12-11', ['2026-12-12', '2026-12-13']],
    ['a Saturday', '2026-12-12', ['2026-12-12', '2026-12-13']],
    ['a Sunday', '2026-12-13', ['2026-12-13']],
  ])('reads the weekend on %s', (_, today, expected) => {
    expect(marketDays('weekend', today)).toEqual(expected)
  })

  it('reads today and a date from today on', () => {
    expect(marketDays('today', '2026-12-09')).toEqual(['2026-12-09'])
    expect(marketDays('2026-12-09', '2026-12-09')).toEqual(['2026-12-09'])
    expect(marketDays('2026-12-24', '2026-12-09')).toEqual(['2026-12-24'])
  })

  it.each(['', '2026-12-08', 'tomorrow', '12.12.2026'])('reads %j as no choice', (choice) => {
    expect(marketDays(choice, '2026-12-09')).toEqual([])
  })
})

describe('isOpenOn', () => {
  const daily = { eventDate: '2026-11-23', endDate: '2026-12-31' }
  const oneWeekend = { eventDate: '2026-12-12', endDate: '2026-12-13' }
  const oneDay = { eventDate: '2026-12-12', endDate: null }

  it.each([
    ['a daily run on its first day', daily, '2026-11-23', true],
    ['a daily run on a Monday', daily, '2026-12-14', true],
    ['a daily run on its last day', daily, '2026-12-31', true],
    ['a daily run after it', daily, '2027-01-01', false],
    ['a daily run before it', daily, '2026-11-22', false],
    ['a weekend market on its Sunday', oneWeekend, '2026-12-13', true],
    ['a weekend market on the Monday after', oneWeekend, '2026-12-14', false],
    ['a one-day market on its day', oneDay, '2026-12-12', true],
    ['a one-day market the day after', oneDay, '2026-12-13', false],
    ['a market with no date', { eventDate: null }, '2026-12-12', false],
  ])('%s is %s', (_, market, day, expected) => {
    expect(isOpenOn(market, day)).toBe(expected)
  })
})

describe('foldMarkets', () => {
  function block(slug: string, title: string, venue: string, eventDate: string, endDate: string) {
    return { slug, title, eventDate, endDate, venue: { slug: venue } }
  }

  const daily = block('rathaus', 'Rotes Rathaus', 'rathaus-platz', '2026-11-23', '2026-12-31')
  // Domäne Dahlem: four Advent weekends, entered in no particular order.
  const dahlem = [
    block('dahlem-3', 'Adventsmarkt', 'domaene', '2026-12-12', '2026-12-13'),
    block('dahlem-1', 'Adventsmarkt', 'domaene', '2026-11-28', '2026-11-29'),
    block('dahlem-4', 'Adventsmarkt', 'domaene', '2026-12-19', '2026-12-20'),
    block('dahlem-2', 'Adventsmarkt', 'domaene', '2026-12-05', '2026-12-06'),
  ]
  const slugs = (blocks: { slug: string }[]) => blocks.map(({ slug }) => slug)

  it('leaves a daily run as it is', () => {
    expect(foldMarkets([daily], '2026-11-28')).toEqual([
      { key: 'rathaus-platz|Rotes Rathaus', shown: daily, blocks: [daily] },
    ])
  })

  it('folds the weekends of one market into one, first weekend first', () => {
    const folded = foldMarkets([daily, ...dahlem], '2026-11-28')

    expect(folded.map(({ key }) => key)).toEqual([
      'rathaus-platz|Rotes Rathaus',
      'domaene|Adventsmarkt',
    ])
    expect(slugs(folded[1]?.blocks ?? [])).toEqual(['dahlem-1', 'dahlem-2', 'dahlem-3', 'dahlem-4'])
  })

  it('keeps two markets at one venue apart by their titles', () => {
    const lights = block('lights', 'Lichterfest', 'domaene', '2026-12-05', '2026-12-06')

    const folded = foldMarkets([dahlem[0]!, lights], '2026-11-28')

    expect(folded.map(({ shown }) => shown.slug)).toEqual(['dahlem-3', 'lights'])
  })

  it.each([
    ['on the first weekend', '2026-11-28', 'dahlem-1'],
    ['on the Monday after it', '2026-11-30', 'dahlem-2'],
    ['on the last Sunday', '2026-12-20', 'dahlem-4'],
  ])('shows the next block that has not ended %s', (_, today, expected) => {
    expect(foldMarkets(dahlem, today)[0]?.shown.slug).toBe(expected)
  })

  it('shows the block open on the chosen day, and drops a market with none', () => {
    const chosen = '2026-12-13'
    const folded = foldMarkets([daily, ...dahlem], '2026-11-28', (b) => isOpenOn(b, chosen))

    expect(folded.map(({ shown }) => shown.slug)).toEqual(['rathaus', 'dahlem-3'])
    expect(foldMarkets(dahlem, '2026-11-28', (b) => isOpenOn(b, '2026-12-09'))).toEqual([])
  })
})

describe('nearestFirst', () => {
  const origin = { latitude: 52.52, longitude: 13.4 }
  function at(slug: string, latitude: number | null): EventSummary {
    return {
      slug,
      title: slug,
      venue: { slug, latitude, longitude: latitude === null ? null : 13.4 },
    }
  }

  it('orders by the distance of each market, and the unplaced last in their order', () => {
    const markets = foldMarkets(
      [at('none-b', null), at('far', 52.6), at('none-a', null), at('near', 52.53)],
      '2026-11-14',
    )
    const result = nearestFirst(markets, origin)

    expect(result.map(({ market }) => market.shown.slug)).toEqual([
      'near',
      'far',
      'none-b',
      'none-a',
    ])
    expect(result[0]?.distanceKm).toBeCloseTo(1.11, 2)
    expect(result[3]?.distanceKm).toBeNull()
  })
})

describe('marketMarker', () => {
  // 23:30 UTC is already Sunday 15 November in Berlin, so the day comes from Berlin, not UTC.
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-14T23:30:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const marker = (eventDate: string, endDate?: string | null) =>
    marketMarker({ eventDate, endDate }, todayIso())

  it('reads today in Berlin', () => {
    expect(todayIso()).toBe('2026-11-15')
  })

  it.each([
    ['opens tomorrow', '2026-11-16', { kind: 'opens', day: '2026-11-16' }],
    ['opens in 6 days', '2026-11-21', { kind: 'opens', day: '2026-11-21' }],
    ['opens in 7 days', '2026-11-22', null],
    ['opened today', '2026-11-15', null],
  ])('%s', (_, eventDate, expected) => {
    expect(marker(eventDate, '2026-12-31')).toEqual(expected)
  })

  it.each([
    ['ends today', '2026-11-15', { kind: 'lastDay', day: '2026-11-15' }],
    ['ends tomorrow', '2026-11-16', { kind: 'endsSoon', day: '2026-11-16' }],
    ['ends in 6 days', '2026-11-21', { kind: 'endsSoon', day: '2026-11-21' }],
    ['ends in 7 days', '2026-11-22', null],
    ['ended yesterday', '2026-11-14', null],
  ])('%s', (_, endDate, expected) => {
    expect(marker('2026-11-01', endDate)).toEqual(expected)
  })

  it('lets the ending win when opening and ending fall in the same 6 days', () => {
    expect(marker('2026-11-17', '2026-11-20')).toEqual({ kind: 'endsSoon', day: '2026-11-20' })
  })

  it.each([
    ['today', '2026-11-15', { kind: 'todayOnly', day: '2026-11-15' }],
    ['in 1 day', '2026-11-16', { kind: 'onlyOn', day: '2026-11-16' }],
    ['in 6 days', '2026-11-21', { kind: 'onlyOn', day: '2026-11-21' }],
    ['in 7 days', '2026-11-22', null],
  ])('marks a one-day market %s, with or without an end date', (_, day, expected) => {
    expect(marker(day, null)).toEqual(expected)
    expect(marker(day, day)).toEqual(expected)
  })

  it('has no marker without a date', () => {
    expect(marketMarker({ eventDate: null }, '2026-11-15')).toBeNull()
  })
})
