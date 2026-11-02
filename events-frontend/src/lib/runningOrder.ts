import type { LineupEntry } from '@/api/types'
import { addDays } from '@/lib/format'

/** One set of a running order: the lineup entry and its clock times as the venue printed them. */
export interface RunningOrderSet {
  entry: LineupEntry
  start: string | null
  end: string | null
  /** The night's date (`YYYY-MM-DD`) when this set opens a new night on a multi-night bill. */
  opensNight: string | null
}

export interface RunningOrderFloor {
  stage: string | null
  sets: RunningOrderSet[]
}

/** A set before this belongs to the night before, as a 04:30 set does on a Saturday (#299). */
const NIGHT_ENDS = '06:00'

/**
 * The BFF sends a set time in Berlin time with its offset (`2026-09-26T23:59:00+02:00`), so the
 * date and the clock are read off the string: converting through `Date` would show a visitor
 * abroad their own clock instead of the one on the venue's poster.
 */
function datePart(iso: string): string {
  return iso.slice(0, 10)
}

function clockPart(iso: string): string {
  return iso.slice(11, 16)
}

function nightOf(iso: string): string {
  return clockPart(iso) < NIGHT_ENDS ? addDays(datePart(iso), -1) : datePart(iso)
}

/**
 * The lineup as a running order: one floor per stage, in the order the lineup first names it, and
 * each floor's sets by start time, the untimed ones last in billing order. `null` when no entry
 * has a time, which is every venue but a few, and the page shows the plain lineup instead.
 *
 * A bill that runs across several nights, like a Klubnacht from Saturday to Monday, marks the
 * first set of each night on each floor, so a reader can tell Sunday's 20:30 from Saturday's.
 */
export function runningOrder(lineup: readonly LineupEntry[]): RunningOrderFloor[] | null {
  if (!lineup.some((entry) => entry.setStart)) return null

  const floors = new Map<string | null, LineupEntry[]>()
  for (const entry of lineup) {
    const stage = entry.stage ?? null
    floors.set(stage, [...(floors.get(stage) ?? []), entry])
  }

  const nights = new Set(
    lineup.flatMap((entry) => (entry.setStart ? [nightOf(entry.setStart)] : [])),
  )
  const multiNight = nights.size > 1

  return [...floors].map(([stage, entries]) => {
    const timed = entries
      .filter((entry) => entry.setStart)
      .sort((a, b) => Date.parse(a.setStart!) - Date.parse(b.setStart!))
    const untimed = entries.filter((entry) => !entry.setStart)
    let night: string | null = null
    const sets = [...timed, ...untimed].map((entry): RunningOrderSet => {
      const setNight = entry.setStart ? nightOf(entry.setStart) : null
      const opensNight = multiNight && setNight && setNight !== night ? setNight : null
      if (setNight) night = setNight
      return {
        entry,
        start: entry.setStart ? clockPart(entry.setStart) : null,
        end: entry.setEnd ? clockPart(entry.setEnd) : null,
        opensNight,
      }
    })
    return { stage, sets }
  })
}

/** Where a set stands against the clock: over, playing, or still to come. */
export type SetState = 'past' | 'live' | 'upcoming'

/**
 * Each timed set's state at `now`, for a night that is on. A set without an end runs until the
 * next later start on its floor, and the last one until the event ends, which the caller decides
 * by asking only while the event is on. An untimed set gets no state.
 */
export function setStates(
  floors: readonly RunningOrderFloor[],
  now: number,
): Map<RunningOrderSet, SetState> {
  const states = new Map<RunningOrderSet, SetState>()
  for (const { sets } of floors) {
    const starts = sets.map((set) => (set.entry.setStart ? Date.parse(set.entry.setStart) : NaN))
    sets.forEach((set, index) => {
      const start = starts[index]!
      if (Number.isNaN(start)) return
      const next = starts.slice(index + 1).find((later) => later > start)
      const end = set.entry.setEnd ? Date.parse(set.entry.setEnd) : next
      if (now < start) states.set(set, 'upcoming')
      else states.set(set, end === undefined || now < end ? 'live' : 'past')
    })
  }
  return states
}
