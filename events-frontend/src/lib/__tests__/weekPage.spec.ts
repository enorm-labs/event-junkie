import { describe, expect, it } from 'vitest'

import type { EventSummary } from '@/api/types'
import { urlsetXml } from '@/lib/seo'
import {
  DAY_CAP,
  dayHeading,
  isPastWeek,
  upcomingWeekPaths,
  weekDays,
  weekListRange,
  weekPageMeta,
  weekRange,
} from '@/lib/weekPage'

const WEEK = { year: 2026, week: 41 }

function events(date: string, count: number, extra: Partial<EventSummary> = {}): EventSummary[] {
  return Array.from({ length: count }, (_, index) => ({
    slug: `${date}-${index}`,
    eventDate: date,
    ...extra,
  }))
}

describe('weekDays', () => {
  it('gives each day of the week its own list, Monday first', () => {
    const days = weekDays([], WEEK)
    expect(days.map((day) => day.date)).toEqual([
      '2026-10-05',
      '2026-10-06',
      '2026-10-07',
      '2026-10-08',
      '2026-10-09',
      '2026-10-10',
      '2026-10-11',
    ])
    expect(days.every((day) => day.total === 0 && day.shown.length === 0)).toBe(true)
  })

  it(`shows the first ${DAY_CAP} of a busy day in the order given, and counts them all`, () => {
    const friday = events('2026-10-09', 113)
    const day = weekDays(friday, WEEK)[4]!
    expect(day.total).toBe(113)
    expect(day.shown.map((event) => event.slug)).toEqual(friday.slice(0, 12).map((e) => e.slug))
  })

  it('shows a day of exactly twelve whole', () => {
    const day = weekDays(events('2026-10-06', 12), WEEK)[1]!
    expect([day.shown.length, day.total]).toEqual([12, 12])
  })

  it('counts an event over midnight or over several days on the day it starts only', () => {
    const overnight = { slug: 'overnight', eventDate: '2026-10-10', endDate: '2026-10-11' }
    const festival = { slug: 'festival', eventDate: '2026-10-08', endDate: '2026-10-11' }
    const lists = weekDays([festival, overnight], WEEK).map((day) => day.shown.map((e) => e.slug))
    expect(lists).toEqual([[], [], [], ['festival'], [], ['overnight'], []])
  })

  it('leaves to last week what began before Monday, however long it still runs', () => {
    const weekender = { slug: 'weekender', eventDate: '2026-10-02', endDate: '2026-10-06' }
    const sundayNight = { slug: 'sunday-night', eventDate: '2026-10-04', endDate: '2026-10-05' }
    const exhibition = { slug: 'exhibition', eventDate: '2026-09-01', endDate: '2026-11-01' }
    const monday = weekDays([exhibition, weekender, sundayNight], WEEK)[0]!
    expect([monday.shown, monday.total]).toEqual([[], 0])
  })
})

describe('isPastWeek', () => {
  it('is past only once its Sunday is over', () => {
    expect(isPastWeek(WEEK, '2026-10-11')).toBe(false)
    expect(isPastWeek(WEEK, '2026-10-12')).toBe(true)
    expect(isPastWeek(WEEK, '2026-10-05')).toBe(false)
  })
})

describe('the head of a week page', () => {
  it('names the week and its dates in either locale', () => {
    const meta = weekPageMeta(WEEK, 'en')
    expect(meta.title).toBe('Week 41 in Berlin · Event Junkie')
    // ICU versions differ on the spaces around the dash.
    expect(meta.description).toMatch(
      /^5 ?– ?11 October 2026: concerts, club nights and more in Berlin, day by day\.$/,
    )
    expect(weekPageMeta(WEEK, 'de').title).toBe('Woche 41 in Berlin · Event Junkie')
  })

  it('spells out a week across two months and two years', () => {
    expect(weekRange({ year: 2026, week: 53 }, 'en')).toBe('28 December 2026 – 3 January 2027')
    expect(weekRange({ year: 2026, week: 40 }, 'de')).toBe('28. September – 4. Oktober 2026')
  })

  it('heads each day with its weekday and date', () => {
    expect(dayHeading('2026-10-05', 'en')).toBe('Monday 5 October')
    expect(dayHeading('2026-10-05', 'de')).toBe('Montag, 5. Oktober')
  })
})

describe('the weeks sitemap', () => {
  it('lists this week and the next four, in both locales with their alternates', () => {
    expect(upcomingWeekPaths('2026-10-09')).toEqual([
      '/week/2026-41',
      '/week/2026-42',
      '/week/2026-43',
      '/week/2026-44',
      '/week/2026-45',
    ])
    const xml = urlsetXml(upcomingWeekPaths('2026-12-31'))
    expect(xml.match(/<loc>/g)).toHaveLength(10)
    expect(xml).toContain('<loc>https://event-junkie.de/de/week/2027-04</loc>')
    expect(xml).toContain('href="https://event-junkie.de/en/week/2026-53" hreflang="en"')
  })
})

describe('weekListRange', () => {
  it('starts today in the current week, and ends on Sunday', () => {
    expect(weekListRange(WEEK, '2026-10-09')).toEqual({ from: '2026-10-09', to: '2026-10-11' })
    expect(weekListRange(WEEK, '2026-10-05')).toEqual({ from: '2026-10-05', to: '2026-10-11' })
    expect(weekListRange(WEEK, '2026-10-11')).toEqual({ from: '2026-10-11', to: '2026-10-11' })
  })

  it('covers Monday to Sunday of a later week', () => {
    expect(weekListRange(WEEK, '2026-09-30')).toEqual({ from: '2026-10-05', to: '2026-10-11' })
  })

  it('is null for a past week', () => {
    expect(weekListRange(WEEK, '2026-10-12')).toBeNull()
  })
})
