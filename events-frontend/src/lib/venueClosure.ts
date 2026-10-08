import { todayIso } from '@/lib/format'

/**
 * Where a venue stands on its closure (ADR-046). `closedOn` is its last day open, so the venue is
 * `closing` through that day and `closed` from the day after, the same cut the BFF's lists make.
 */
export type VenueClosure = { state: 'closing' | 'closed'; lastDay: string }

export function venueClosure(
  closedOn?: string | null,
  today: string = todayIso(),
): VenueClosure | null {
  if (!closedOn) return null
  return { state: closedOn < today ? 'closed' : 'closing', lastDay: closedOn }
}
