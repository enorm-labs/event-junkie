import type { EventSummary } from '@/api/types'

/**
 * The past events that are not also upcoming. The past query bounds the start date and the
 * upcoming one the end date, so an event that started yesterday and runs today matches both
 * (#2560). It belongs under Upcoming.
 */
export function withoutUpcoming(
  past: EventSummary[] | null | undefined,
  upcoming: EventSummary[] | null | undefined,
): EventSummary[] {
  const upcomingSlugs = new Set((upcoming ?? []).map((event) => event.slug))
  return (past ?? []).filter((event) => !upcomingSlugs.has(event.slug))
}
