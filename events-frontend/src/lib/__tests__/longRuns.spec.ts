import { afterEach, describe, expect, it, vi } from 'vitest'

import { foldsOn, isClosing, isLiveNow, isLongRun, splitDayList } from '@/lib/longRuns'

// 3–18 Oct: sixteen days, the Panorama Bar exhibition's shape.
const exhibition = { slug: 'exhibition', eventDate: '2026-10-03', endDate: '2026-10-18' }
// A Sisyphos weekend: five days, the night's main event every night.
const weekend = { slug: 'weekend', eventDate: '2026-10-02', endDate: '2026-10-06' }
const tonight = { slug: 'tonight', eventDate: '2026-10-07' }

describe('isLongRun', () => {
  it('starts above seven calendar days, counted inclusive', () => {
    expect(isLongRun({ eventDate: '2026-10-01', endDate: '2026-10-07' })).toBe(false)
    expect(isLongRun({ eventDate: '2026-10-01', endDate: '2026-10-08' })).toBe(true)
  })

  it('needs an end date', () => {
    expect(isLongRun(tonight)).toBe(false)
  })
})

describe('foldsOn', () => {
  it('keeps a 16-day run a card on its opening day', () => {
    expect(foldsOn(exhibition, '2026-10-03')).toBe(false)
  })

  it('folds a 16-day run on day 5', () => {
    expect(foldsOn(exhibition, '2026-10-07')).toBe(true)
  })

  it('folds up to the day before its last three', () => {
    expect(foldsOn(exhibition, '2026-10-15')).toBe(true)
    expect(foldsOn(exhibition, '2026-10-16')).toBe(false)
  })

  it('keeps a 16-day run a card on day 15, and says it closes', () => {
    expect(foldsOn(exhibition, '2026-10-17')).toBe(false)
    expect(isClosing(exhibition, '2026-10-17')).toBe(true)
  })

  it('never folds a 5-day party', () => {
    expect(foldsOn(weekend, '2026-10-04')).toBe(false)
    expect(isClosing(weekend, '2026-10-06')).toBe(false)
  })
})

describe('isClosing', () => {
  it('covers the last three days of a long run and nothing outside them', () => {
    expect(isClosing(exhibition, '2026-10-15')).toBe(false)
    expect(isClosing(exhibition, '2026-10-16')).toBe(true)
    expect(isClosing(exhibition, '2026-10-18')).toBe(true)
    expect(isClosing(exhibition, '2026-10-19')).toBe(false)
  })
})

describe('splitDayList', () => {
  it('moves a run that opened before the visible day into the block, keeping the order', () => {
    const { cards, alsoRunning } = splitDayList([exhibition, weekend, tonight], '2026-10-07')

    expect(cards.map((event) => event.slug)).toEqual(['weekend', 'tonight'])
    expect(alsoRunning.map((event) => event.slug)).toEqual(['exhibition'])
  })

  it('folds nothing on the opening day', () => {
    expect(splitDayList([exhibition, tonight], '2026-10-03').alsoRunning).toEqual([])
  })
})

describe('isLiveNow', () => {
  afterEach(() => vi.useRealTimers())

  it('is a weekender in its run and a started event, never a run of weeks', () => {
    // 14:00 in Berlin on Wednesday 7 October.
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-07T12:00:00Z'))
    expect(isLiveNow({ eventDate: '2026-10-06', endDate: '2026-10-08' })).toBe(true)
    expect(isLiveNow({ ...tonight, startTime: '10:00' })).toBe(true)
    expect(isLiveNow({ ...tonight, startTime: '20:00' })).toBe(false)
    expect(isLiveNow(exhibition)).toBe(false)
  })
})
