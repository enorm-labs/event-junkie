import { describe, expect, it } from 'vitest'
import { inChristmasMarketSeason } from '@/lib/christmasMarkets'

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
