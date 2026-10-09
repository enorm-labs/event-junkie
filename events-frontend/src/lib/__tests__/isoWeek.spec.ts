import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  currentIsoWeek,
  daysOf,
  formatIsoWeek,
  isoWeekOf,
  mondayOf,
  parseIsoWeek,
  shiftWeek,
  weeksInYear,
} from '@/lib/isoWeek'

describe('isoWeekOf', () => {
  it.each([
    ['2026-10-05', 2026, 41], // a Monday
    ['2026-10-11', 2026, 41], // its Sunday
    ['2026-01-01', 2026, 1], // a Thursday: week 1 of its own year
    ['2027-01-01', 2026, 53], // a Friday: the last week of the year before
    ['2024-12-30', 2025, 1], // a Monday in December that belongs to the next week-year
    ['2021-01-03', 2020, 53],
  ])('puts %s in week %i-%i', (date, year, week) => {
    expect(isoWeekOf(date)).toEqual({ year, week })
  })
})

describe('weeksInYear', () => {
  it('is 53 for a year that starts or, in a leap year, ends on a Thursday, else 52', () => {
    expect([2020, 2025, 2026, 2027].map(weeksInYear)).toEqual([53, 52, 53, 52])
  })
})

describe('parseIsoWeek', () => {
  it('reads the URL form', () => {
    expect(parseIsoWeek('2026-41')).toEqual({ year: 2026, week: 41 })
    expect(parseIsoWeek('2026-53')).toEqual({ year: 2026, week: 53 })
  })

  it('refuses another shape, a week the year does not have, and anything not a string', () => {
    const values = [
      '2026-W41',
      '2026-4',
      '26-41',
      '2026-00',
      '2025-53',
      '2026-54',
      '',
      undefined,
      ['2026-41'],
    ]
    expect(values.map(parseIsoWeek)).toEqual(values.map(() => null))
  })

  it('round-trips with formatIsoWeek', () => {
    expect(formatIsoWeek(parseIsoWeek('2027-01')!)).toBe('2027-01')
  })
})

describe('the days of a week', () => {
  it('run Monday to Sunday, across a month and a year', () => {
    expect(mondayOf({ year: 2026, week: 41 })).toBe('2026-10-05')
    expect(daysOf({ year: 2026, week: 53 })).toEqual([
      '2026-12-28',
      '2026-12-29',
      '2026-12-30',
      '2026-12-31',
      '2027-01-01',
      '2027-01-02',
      '2027-01-03',
    ])
  })
})

describe('shiftWeek', () => {
  it('crosses a 53-week year in both directions', () => {
    expect(shiftWeek({ year: 2026, week: 53 }, 1)).toEqual({ year: 2027, week: 1 })
    expect(shiftWeek({ year: 2027, week: 1 }, -1)).toEqual({ year: 2026, week: 53 })
    expect(shiftWeek({ year: 2026, week: 41 }, 4)).toEqual({ year: 2026, week: 45 })
  })
})

describe('currentIsoWeek', () => {
  afterEach(() => vi.useRealTimers())

  it("is Berlin's week, which starts at midnight in Berlin, not in UTC", () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    // 00:30 on Monday 12 October in Berlin, still Sunday in UTC.
    vi.setSystemTime(new Date('2026-10-11T22:30:00Z'))
    expect(currentIsoWeek()).toEqual({ year: 2026, week: 42 })
  })
})
