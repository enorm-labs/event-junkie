import { afterEach, describe, expect, it, vi } from 'vitest'

import type { EventSummary } from '@/api/types'
import { toCalendarInput } from '@/lib/calendarEvent'

function summary(overrides: Partial<EventSummary>): EventSummary {
  return {
    id: 1,
    slug: 'astra-night',
    title: 'Astra Night',
    eventType: 'CONCERT',
    status: 'SCHEDULED',
    eventDate: '2026-09-18',
    venue: { id: 1, slug: 'astra', name: 'Astra', city: 'Berlin' },
    ...overrides,
  } as EventSummary
}

describe('toCalendarInput', () => {
  afterEach(() => vi.useRealTimers())

  it('maps a single day with a start to a timed entry without an end', () => {
    const input = toCalendarInput(summary({ startTime: '20:00:00' }))
    expect(input.start).toBe('2026-09-18T20:00:00')
    expect(input).not.toHaveProperty('end')
    expect(input.url).toBe('/events/astra-night')
    expect(input.extendedProps).toEqual({ slug: 'astra-night', venue: 'Astra' })
  })

  it('maps a timeless single day to an all-day entry', () => {
    const input = toCalendarInput(summary({}))
    expect(input.start).toBe('2026-09-18')
    expect(input).not.toHaveProperty('end')
  })

  it('ends a timed event at its stated end, on the same day or the next', () => {
    const sameDay = toCalendarInput(
      summary({ startTime: '19:00:00', endDate: '2026-09-18', endTime: '22:00:00' }),
    )
    expect(sameDay.end).toBe('2026-09-18T22:00:00')

    const overnight = toCalendarInput(
      summary({ startTime: '23:00:00', endDate: '2026-09-19', endTime: '06:00:00' }),
    )
    expect(overnight.start).toBe('2026-09-18T23:00:00')
    expect(overnight.end).toBe('2026-09-19T06:00:00')
  })

  it('draws a run without times as an all-day bar through its closing day', () => {
    // FullCalendar's all-day `end` is exclusive, so the closing day needs the day after it.
    const run = toCalendarInput(summary({ eventType: 'EXHIBITION', endDate: '2026-10-31' }))
    expect(run.start).toBe('2026-09-18')
    expect(run.end).toBe('2026-11-01')
  })

  it('keeps a run with a start but no end time all-day, so the bar covers every day', () => {
    const run = toCalendarInput(summary({ startTime: '10:00:00', endDate: '2026-09-20' }))
    expect(run.start).toBe('2026-09-18T10:00:00')
    expect(run.end).toBe('2026-09-21')
  })

  it('marks a run live on every day it is on, and past once it has closed', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-25T12:00:00Z'))
    expect(toCalendarInput(summary({ endDate: '2026-10-31' })).className).toBe('fc-event-live')
    expect(toCalendarInput(summary({ endDate: '2026-09-20' })).className).toBe('fc-event-past')
    expect(toCalendarInput(summary({ eventDate: '2026-09-25' })).className).toBe('fc-event-live')
    expect(toCalendarInput(summary({ eventDate: '2026-09-30' })).className).toBeUndefined()
  })
})
