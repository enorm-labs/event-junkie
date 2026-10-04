import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  activePresetKey,
  DATE_PRESETS,
  nextSevenDays,
  thisWeekend,
  tonight,
} from '@/lib/dateRanges'

/** Freezes the clock at midday Berlin time on the given date, so the day never straddles. */
function freezeOn(isoDate: string) {
  vi.useFakeTimers()
  vi.setSystemTime(new Date(`${isoDate}T12:00:00Z`))
}

describe('date range presets', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('tonight is today alone', () => {
    freezeOn('2026-08-05') // Wednesday
    expect(tonight()).toEqual({ from: '2026-08-05', to: '2026-08-05' })
  })

  it('next 7 days spans today plus six more', () => {
    freezeOn('2026-08-05')
    expect(nextSevenDays()).toEqual({ from: '2026-08-05', to: '2026-08-11' })
  })

  it('this weekend reaches forward to Fri–Sun from earlier in the week', () => {
    freezeOn('2026-08-03') // Monday
    expect(thisWeekend()).toEqual({ from: '2026-08-07', to: '2026-08-09' })

    freezeOn('2026-08-06') // Thursday
    expect(thisWeekend()).toEqual({ from: '2026-08-07', to: '2026-08-09' })
  })

  it('this weekend starts today once it is under way, never in the past', () => {
    freezeOn('2026-08-07') // Friday
    expect(thisWeekend()).toEqual({ from: '2026-08-07', to: '2026-08-09' })

    freezeOn('2026-08-08') // Saturday — Friday is gone, so the range starts today
    expect(thisWeekend()).toEqual({ from: '2026-08-08', to: '2026-08-09' })

    freezeOn('2026-08-09') // Sunday — what's left of the weekend is today
    expect(thisWeekend()).toEqual({ from: '2026-08-09', to: '2026-08-09' })
  })

  it('rolls a weekend across a month boundary', () => {
    freezeOn('2026-09-28') // Monday, weekend lands in October
    expect(thisWeekend()).toEqual({ from: '2026-10-02', to: '2026-10-04' })
  })

  it('presses Tonight alone on a Sunday, where it and This weekend are the same day', () => {
    freezeOn('2026-08-09') // Sunday
    expect(thisWeekend()).toEqual(tonight())
    expect(activePresetKey(tonight())).toBe('dateRange.tonight')
  })

  it('reads a range as the preset it equals, and anything else as none', () => {
    freezeOn('2026-08-05') // Wednesday
    expect(activePresetKey(thisWeekend())).toBe('dateRange.thisWeekend')
    expect(activePresetKey(nextSevenDays())).toBe('dateRange.next7Days')
    expect(activePresetKey({ from: '2026-08-05', to: '2026-08-06' })).toBeUndefined()
    expect(activePresetKey({ from: '', to: '' })).toBeUndefined()
  })

  it('never produces an inverted range, whatever the day', () => {
    // Walk a full week so every weekday is covered by the from <= to invariant.
    for (let day = 3; day <= 9; day++) {
      freezeOn(`2026-08-0${day}`)
      for (const preset of DATE_PRESETS) {
        const { from, to } = preset.range()
        expect(from <= to, `${preset.key} on 2026-08-0${day}`).toBe(true)
      }
    }
  })
})
