import { describe, expect, it } from 'vitest'

import { berlinDay, berlinToday, formatBerlin, fromBerlinLocal, toBerlinLocal } from '../berlinTime'

describe('Berlin time for the pick field', () => {
  it('shows an instant as Berlin wall-clock time, summer and winter', () => {
    expect(toBerlinLocal('2026-10-20T16:00:00Z')).toBe('2026-10-20T18:00')
    expect(toBerlinLocal('2026-12-01T17:00:00Z')).toBe('2026-12-01T18:00')
    expect(toBerlinLocal('2026-12-31T23:30:00Z')).toBe('2027-01-01T00:30')
    expect(toBerlinLocal(null)).toBe('')
    expect(toBerlinLocal(undefined)).toBe('')
  })

  it('reads a Berlin time back as the instant, summer and winter', () => {
    expect(fromBerlinLocal('2026-10-20T18:00')).toBe('2026-10-20T16:00:00.000Z')
    expect(fromBerlinLocal('2026-12-01T18:00')).toBe('2026-12-01T17:00:00.000Z')
    expect(fromBerlinLocal('2027-01-01T00:30')).toBe('2026-12-31T23:30:00.000Z')
  })

  it('takes the offset of the side of a DST change the time is on', () => {
    // Clocks go back at 03:00 CEST on 2026-10-25, to 02:00 CET.
    expect(fromBerlinLocal('2026-10-25T01:30')).toBe('2026-10-24T23:30:00.000Z')
    expect(fromBerlinLocal('2026-10-25T03:30')).toBe('2026-10-25T02:30:00.000Z')
    // Clocks go forward at 02:00 CET on 2026-03-29, to 03:00 CEST.
    expect(fromBerlinLocal('2026-03-29T01:30')).toBe('2026-03-29T00:30:00.000Z')
    expect(fromBerlinLocal('2026-03-29T03:30')).toBe('2026-03-29T01:30:00.000Z')
  })

  it('round-trips every hour across both DST changes of 2026', () => {
    for (const start of ['2026-03-28T22:00:00Z', '2026-10-24T22:00:00Z']) {
      for (let hour = 0; hour < 8; hour++) {
        const instant = new Date(Date.parse(start) + hour * 3_600_000).toISOString()
        const local = toBerlinLocal(instant)
        // 02:xx on the autumn night happens twice; either reading shows the same wall-clock time.
        expect(toBerlinLocal(fromBerlinLocal(local))).toBe(local)
      }
    }
  })

  it('maps a time the spring change skips to an instant after the change', () => {
    expect(fromBerlinLocal('2026-03-29T02:30')).toBe('2026-03-29T01:30:00.000Z')
  })

  it('formats an instant for a table cell', () => {
    expect(formatBerlin('2026-10-20T16:00:00Z')).toBe('2026-10-20 18:00')
  })

  it('is the Berlin calendar day, not the UTC one', () => {
    expect(berlinToday(new Date('2026-10-14T22:30:00Z'))).toBe('2026-10-15')
    expect(berlinToday(new Date('2026-10-14T21:30:00Z'))).toBe('2026-10-14')
    expect(berlinDay('2026-12-31T23:30:00Z')).toBe('2027-01-01')
  })
})
