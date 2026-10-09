// "Surprise me" (#2722): one random event tonight in a small room. The pick happens on the device;
// nothing about it is stored or sent.

import { isPastEvent, type EventSpan } from './format'

/** A venue whose largest room holds at most this many visitors is small. A venue with no capacity is not. */
export const SMALL_ROOM_CAPACITY = 250

type Candidate = EventSpan & {
  slug?: string
  status?: string
  venue?: { capacity?: number | null }
}

/**
 * The events a surprise picks from: in a small room, not over, and going ahead where the venue said.
 * A cancelled or postponed event is not on tonight, and a relocated one is no longer in the room
 * whose capacity qualified it.
 */
export function surpriseCandidates<T extends Candidate>(events: readonly T[]): T[] {
  return events.filter(
    (event) =>
      !!event.slug &&
      event.venue?.capacity != null &&
      event.venue.capacity <= SMALL_ROOM_CAPACITY &&
      (event.status ?? 'SCHEDULED') === 'SCHEDULED' &&
      !isPastEvent(event),
  )
}

/**
 * One candidate at random, never `previous` while another is left. Undefined when there is none.
 * `random` returns a number in [0, 1), as `Math.random` does.
 */
export function pickSurprise<T extends { slug?: string }>(
  candidates: readonly T[],
  previous?: string,
  random: () => number = Math.random,
): T | undefined {
  const others = candidates.filter((event) => event.slug !== previous)
  const pool = others.length ? others : candidates
  return pool[Math.floor(random() * pool.length)]
}
