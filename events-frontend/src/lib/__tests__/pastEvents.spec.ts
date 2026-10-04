import { describe, expect, it } from 'vitest'

import type { EventSummary } from '@/api/types'
import { withoutUpcoming } from '@/lib/pastEvents'

const running: EventSummary = {
  slug: 'heidegluhen-35',
  eventDate: '2026-10-03',
  endDate: '2026-10-04',
}
const over: EventSummary = { slug: 'last-week', eventDate: '2026-09-27' }
const next: EventSummary = { slug: 'next-week', eventDate: '2026-10-11' }

describe('withoutUpcoming', () => {
  it('drops a past event the upcoming list also holds', () => {
    expect(withoutUpcoming([running, over], [running, next])).toEqual([over])
  })

  it('keeps the past list when nothing upcoming is loaded', () => {
    expect(withoutUpcoming([running, over], null)).toEqual([running, over])
  })

  it('returns an empty list when the past list has not loaded', () => {
    expect(withoutUpcoming(undefined, [next])).toEqual([])
  })
})
