import { afterEach, describe, expect, it, vi } from 'vitest'
import { listQuery, savedQuery, searchParams, singleFilter } from '@/lib/savedSearch'

afterEach(() => vi.useRealTimers())

/** Noon in Berlin on [date], so the day is the same in every time zone the suite runs in. */
function at(date: string) {
  vi.useFakeTimers()
  vi.setSystemTime(new Date(`${date}T10:00:00Z`))
}

describe('savedQuery', () => {
  it('keeps the filters and turns a preset range into `when`', () => {
    at('2026-10-28') // a Wednesday: this weekend is Friday to Sunday
    expect(
      savedQuery(
        { genre: ['techno'], free: true, q: undefined },
        { from: '2026-10-30', to: '2026-11-01' },
      ),
    ).toEqual({ query: 'genre=techno&free=true&when=weekend', datesDropped: false })
  })

  it('drops a hand-picked range and says so', () => {
    at('2026-10-28')
    expect(savedQuery({ genre: ['techno'] }, { from: '2026-11-05', to: '2026-11-09' })).toEqual({
      query: 'genre=techno',
      datesDropped: true,
    })
  })

  it('is null when nothing narrows the list', () => {
    expect(savedQuery({ free: false, q: '' }, {})).toBeNull()
  })
})

describe('listQuery and searchParams', () => {
  it("turn `when` into the day's own range, opened on another day", () => {
    at('2026-11-06') // a Friday: this weekend starts today
    expect(listQuery('genre=techno&genre=house&when=weekend')).toEqual({
      genre: ['techno', 'house'],
      from: '2026-11-06',
      to: '2026-11-08',
    })
  })

  it('type the values for the API, and keep a running night in', () => {
    at('2026-11-06')
    expect(searchParams('free=true&maxPrice=10&district=neukoelln&when=today')).toEqual({
      free: true,
      maxPrice: 10,
      district: ['neukoelln'],
      from: '2026-11-06',
      to: '2026-11-06',
      running: true,
    })
  })
})

describe('singleFilter', () => {
  it('finds one genre or one district alone', () => {
    expect(singleFilter({ genre: ['techno'], q: undefined }, {})).toEqual({
      key: 'genre',
      value: 'techno',
    })
    expect(singleFilter({ district: ['kreuzberg'] }, {})).toEqual({
      key: 'district',
      value: 'kreuzberg',
    })
  })

  it('is null for two values, another filter or a date', () => {
    expect(singleFilter({ genre: ['techno', 'house'] }, {})).toBeNull()
    expect(singleFilter({ genre: ['techno'], free: true }, {})).toBeNull()
    expect(singleFilter({ genre: ['techno'] }, { from: '2026-11-06', to: '2026-11-06' })).toBeNull()
  })
})
