import { describe, expect, it } from 'vitest'

import type { LineupEntry } from '@/api/types'
import { runningOrder } from '@/lib/runningOrder'

function entry(
  name: string,
  stage: string | null,
  setStart?: string,
  setEnd?: string,
): LineupEntry {
  return {
    artist: { name, slug: name.toLowerCase() },
    role: 'DJ',
    billingOrder: 0,
    stage,
    setStart,
    setEnd,
  }
}

describe('runningOrder', () => {
  it('is null for a lineup without a single set time', () => {
    expect(runningOrder([entry('A', 'Berghain'), entry('B', null)])).toBeNull()
  })

  it('groups by floor in lineup order and sorts each floor by start, untimed sets last', () => {
    const floors = runningOrder([
      entry('Late', 'Berghain', '2026-09-27T04:30:00+02:00', '2026-09-27T08:30:00+02:00'),
      entry('Bar', 'Panorama Bar', '2026-09-26T23:59:00+02:00'),
      entry('Unscheduled', 'Berghain'),
      entry('Opener', 'Berghain', '2026-09-26T23:59:00+02:00', '2026-09-27T04:30:00+02:00'),
    ])!

    expect(floors.map((floor) => floor.stage)).toEqual(['Berghain', 'Panorama Bar'])
    expect(floors[0]!.sets.map((set) => set.entry.artist?.name)).toEqual([
      'Opener',
      'Late',
      'Unscheduled',
    ])
    expect(floors[0]!.sets[0]).toMatchObject({ start: '23:59', end: '04:30' })
    expect(floors[1]!.sets[0]).toMatchObject({ start: '23:59', end: null })
  })

  it("keeps the venue's clock, whatever the offset", () => {
    const floors = runningOrder([entry('Winter', null, '2026-12-05T23:00:00+01:00')])!
    expect(floors[0]!.sets[0]!.start).toBe('23:00')
  })

  it('marks nothing on a one-night bill, even past midnight', () => {
    const floors = runningOrder([
      entry('A', null, '2026-09-24T22:00:00+02:00'),
      entry('B', null, '2026-09-25T02:30:00+02:00'),
    ])!
    expect(floors[0]!.sets.map((set) => set.opensNight)).toEqual([null, null])
  })

  it('marks the first set of each night on a bill that runs for days', () => {
    const floors = runningOrder([
      entry('Sat', 'Berghain', '2026-09-26T23:59:00+02:00'),
      entry('Sat late', 'Berghain', '2026-09-27T04:30:00+02:00'),
      entry('Sun', 'Berghain', '2026-09-27T08:30:00+02:00'),
      entry('Sun late', 'Berghain', '2026-09-28T00:30:00+02:00'),
    ])!
    expect(floors[0]!.sets.map((set) => set.opensNight)).toEqual([
      '2026-09-26',
      null,
      '2026-09-27',
      null,
    ])
  })
})
