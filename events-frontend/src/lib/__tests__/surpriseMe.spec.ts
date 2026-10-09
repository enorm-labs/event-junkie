import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { pickSurprise, SMALL_ROOM_CAPACITY, surpriseCandidates } from '@/lib/surpriseMe'

// 21:00 Berlin on 9 Oct 2026.
const NOW = new Date('2026-10-09T19:00:00Z')

const event = (slug: string, capacity: number | null | undefined, extra: object = {}) => ({
  slug,
  eventDate: '2026-10-09',
  status: 'SCHEDULED',
  venue: { capacity },
  ...extra,
})

describe('surpriseCandidates', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })
  afterEach(() => vi.useRealTimers())

  it('keeps a room up to the threshold and drops a larger one', () => {
    const slugs = surpriseCandidates([
      event('small', 80),
      event('edge', SMALL_ROOM_CAPACITY),
      event('large', SMALL_ROOM_CAPACITY + 1),
    ]).map((e) => e.slug)
    expect(slugs).toEqual(['small', 'edge'])
  })

  it('drops a venue with no capacity', () => {
    expect(surpriseCandidates([event('null', null), event('missing', undefined)])).toEqual([])
  })

  it('drops an event that has ended', () => {
    const ended = event('ended', 100, { startTime: '18:00', endTime: '20:30' })
    const later = event('later', 100, { startTime: '18:00', endTime: '23:00' })
    expect(surpriseCandidates([ended, later]).map((e) => e.slug)).toEqual(['later'])
  })

  it('keeps an event over midnight and a weekender in its second night', () => {
    const overMidnight = event('over-midnight', 100, {
      startTime: '23:00',
      endDate: '2026-10-10',
      endTime: '06:00',
    })
    const weekender = event('weekender', 100, { eventDate: '2026-10-08', endDate: '2026-10-11' })
    expect(surpriseCandidates([overMidnight, weekender]).map((e) => e.slug)).toEqual([
      'over-midnight',
      'weekender',
    ])
  })

  it('keeps only a scheduled event', () => {
    const slugs = surpriseCandidates([
      event('scheduled', 100),
      event('no-status', 100, { status: undefined }),
      event('relocated', 100, { status: 'RELOCATED' }),
      event('cancelled', 100, { status: 'CANCELLED' }),
      event('postponed', 100, { status: 'POSTPONED' }),
    ]).map((e) => e.slug)
    expect(slugs).toEqual(['scheduled', 'no-status'])
  })
})

describe('pickSurprise', () => {
  const candidates = [{ slug: 'a' }, { slug: 'b' }, { slug: 'c' }]

  it('returns undefined when nothing qualifies', () => {
    expect(pickSurprise([])).toBeUndefined()
  })

  it('picks by the random number', () => {
    expect(pickSurprise(candidates, undefined, () => 0)?.slug).toBe('a')
    expect(pickSurprise(candidates, undefined, () => 0.99)?.slug).toBe('c')
  })

  it('never picks the previous one while another is left', () => {
    for (const r of [0, 0.34, 0.5, 0.67, 0.99]) {
      expect(pickSurprise(candidates, 'a', () => r)?.slug).not.toBe('a')
    }
  })

  it('picks the same one again when it is the only one', () => {
    expect(pickSurprise([{ slug: 'a' }], 'a')?.slug).toBe('a')
  })
})
