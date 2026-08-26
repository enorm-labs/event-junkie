import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  eventLabel,
  formatDate,
  humaniseEventType,
  isPastEvent,
  todayIso,
  tomorrowIso,
  yesterdayIso,
} from '@/lib/format'

describe('formatDate locale handling', () => {
  it('uses day-before-month for English, not US ordering', () => {
    // Regression guard. Phase 1 made formatDate locale-aware and passed the bare UI locale `en`,
    // which Intl resolves to US conventions — "Jun 12, 2026". A Berlin audience reads
    // "12 Jun 2026". The UI locale is mapped to a formatting tag (INTL_LOCALES) to prevent this.
    expect(formatDate('2026-06-12', 'en-GB')).toBe('Fri, 12 Jun 2026')
  })

  it('formats German dates in German', () => {
    expect(formatDate('2026-06-12', 'de-DE')).toContain('12. Juni 2026')
  })

  it('returns the input unchanged when it is not an ISO date', () => {
    expect(formatDate('not-a-date', 'en-GB')).toBe('not-a-date')
  })
})

describe('humaniseEventType', () => {
  it('reads a single-word constant as a capitalised word', () => {
    expect(humaniseEventType('CONCERT')).toBe('Concert')
  })

  it('turns an underscored constant into a sentence-case phrase', () => {
    expect(humaniseEventType('CLUB_NIGHT')).toBe('Club night')
  })

  it('is empty for a missing type, so callers never render a stray label', () => {
    expect(humaniseEventType(null)).toBe('')
    expect(humaniseEventType(undefined)).toBe('')
  })
})

describe('eventLabel', () => {
  it('joins the title and venue with an @', () => {
    expect(eventLabel('The Adicts', 'Lido')).toBe('The Adicts @ Lido')
  })

  it('omits the @ when the venue is missing, rather than dangling it', () => {
    expect(eventLabel('The Adicts', undefined)).toBe('The Adicts')
    expect(eventLabel('The Adicts', null)).toBe('The Adicts')
    expect(eventLabel('The Adicts', '')).toBe('The Adicts')
  })

  it('degrades to the venue alone when the title is missing, never a leading @', () => {
    expect(eventLabel(undefined, 'Lido')).toBe('Lido')
    expect(eventLabel('', 'Lido')).toBe('Lido')
    expect(eventLabel(undefined, undefined)).toBe('')
  })
})

describe('date helpers', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('tomorrowIso is the calendar day after todayIso', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-07T12:00:00Z'))

    expect(todayIso()).toBe('2026-07-07')
    expect(tomorrowIso()).toBe('2026-07-08')
  })

  it('rolls over month and year boundaries', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-12-31T12:00:00Z'))

    expect(tomorrowIso()).toBe('2027-01-01')
  })

  it('advances by one calendar day across the spring DST shift', () => {
    // Europe/Berlin springs forward on 2026-03-29; adding a day to the calendar date
    // (not 24h to a timestamp) must still land on the 29th.
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-03-28T12:00:00Z'))

    expect(tomorrowIso()).toBe('2026-03-29')
  })

  it('yesterdayIso is the calendar day before todayIso, across a year boundary', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-07T12:00:00Z'))
    expect(yesterdayIso()).toBe('2026-07-06')

    vi.setSystemTime(new Date('2026-01-01T12:00:00Z'))
    expect(yesterdayIso()).toBe('2025-12-31')
  })
})

describe('isPastEvent', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it("treats today's event as still to come, and yesterday's as past", () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-07T12:00:00Z'))

    // The boundary the BFF and the importer both use: `>= today` is upcoming.
    expect(isPastEvent('2026-07-06')).toBe(true)
    expect(isPastEvent('2026-07-07')).toBe(false)
    expect(isPastEvent('2026-07-08')).toBe(false)
  })

  it('is false for a missing date rather than throwing', () => {
    expect(isPastEvent(null)).toBe(false)
    expect(isPastEvent(undefined)).toBe(false)
    expect(isPastEvent('')).toBe(false)
  })

  it('reads the Berlin calendar day, not UTC', () => {
    // 23:30 UTC on the 6th is already the 7th in Berlin, so the 6th has passed.
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-06T23:30:00Z'))

    expect(isPastEvent('2026-07-06')).toBe(true)
  })
})
