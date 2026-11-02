import { describe, expect, it } from 'vitest'

import type { LineupEntry } from '@/api/types'
import { runningOrder, setStates } from '@/lib/runningOrder'

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

describe('setStates', () => {
  const at = (iso: string) => Date.parse(iso)
  const names = (floors: ReturnType<typeof runningOrder>, now: number) => {
    const states = setStates(floors!, now)
    return Object.fromEntries(
      floors!.flatMap((floor) =>
        floor.sets.map((set) => [set.entry.artist?.name, states.get(set) ?? null]),
      ),
    )
  }

  it('marks the set playing now live, the earlier ones past and the later ones upcoming', () => {
    const floors = runningOrder([
      entry('Opener', 'Berghain', '2026-09-26T23:59:00+02:00', '2026-09-27T04:30:00+02:00'),
      entry('Peak', 'Berghain', '2026-09-27T04:30:00+02:00', '2026-09-27T08:30:00+02:00'),
      entry('Closer', 'Berghain', '2026-09-27T08:30:00+02:00', '2026-09-27T12:00:00+02:00'),
      entry('Untimed', 'Berghain'),
    ])

    expect(names(floors, at('2026-09-27T05:00:00+02:00'))).toEqual({
      Opener: 'past',
      Peak: 'live',
      Closer: 'upcoming',
      Untimed: null,
    })
  })

  it('ends a set at its end time even before the next one starts', () => {
    const floors = runningOrder([
      entry('A', null, '2026-09-26T20:00:00+02:00', '2026-09-26T20:45:00+02:00'),
      entry('B', null, '2026-09-26T21:30:00+02:00', '2026-09-26T22:30:00+02:00'),
    ])

    expect(names(floors, at('2026-09-26T21:00:00+02:00'))).toEqual({ A: 'past', B: 'upcoming' })
  })

  it('runs a set without an end until the next later start on its floor', () => {
    const floors = runningOrder([
      entry('A', null, '2026-09-26T20:00:00+02:00'),
      entry('B', null, '2026-09-26T20:00:00+02:00'),
      entry('C', null, '2026-09-26T21:00:00+02:00'),
    ])

    expect(names(floors, at('2026-09-26T20:30:00+02:00'))).toEqual({
      A: 'live',
      B: 'live',
      C: 'upcoming',
    })
    expect(names(floors, at('2026-09-26T21:00:00+02:00'))).toEqual({
      A: 'past',
      B: 'past',
      C: 'live',
    })
  })

  it('keeps the last set without an end live, because the caller asks only while the night is on', () => {
    const floors = runningOrder([entry('Last', null, '2026-09-26T23:00:00+02:00')])

    expect(names(floors, at('2026-09-27T05:00:00+02:00'))).toEqual({ Last: 'live' })
  })

  it('reads each floor on its own clock', () => {
    const floors = runningOrder([
      entry('Main', 'Berghain', '2026-09-26T23:59:00+02:00', '2026-09-27T04:30:00+02:00'),
      entry('Bar', 'Panorama Bar', '2026-09-27T02:00:00+02:00'),
      entry('Bar late', 'Panorama Bar', '2026-09-27T06:00:00+02:00'),
    ])

    expect(names(floors, at('2026-09-27T03:00:00+02:00'))).toEqual({
      Main: 'live',
      Bar: 'live',
      'Bar late': 'upcoming',
    })
  })
})
