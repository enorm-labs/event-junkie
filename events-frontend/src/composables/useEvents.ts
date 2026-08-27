import { api, unwrap } from '@/api/client'
import type { EventPage, EventSummary } from '@/api/types'
import type { ErrorSubjectKey } from '@/i18n'
import { useAsync } from './useAsync'

/** Query parameters accepted by the event search endpoint (`GET /events`). */
export interface EventSearchParams {
  from?: string
  to?: string
  eventType?: string
  venue?: string
  district?: string
  artist?: string
  promoter?: string
  genre?: string
  minPrice?: number
  maxPrice?: number
  q?: string
  excludeSoldOut?: boolean
  free?: boolean
  page?: number
  size?: number
  sort?: string[]
}

/**
 * An inclusive event-date range, as ISO `YYYY-MM-DD` strings. Deliberately kept out of
 * {@link EventFilterValues}: the calendar owns its own `from`/`to` (the visible window), so it
 * must not be able to receive a range from the filter bar. Only the list view reads this.
 */
export type EventDateRange = Pick<EventSearchParams, 'from' | 'to'>

/**
 * The criteria the shared `EventFilterBar` controls — the subset of {@link EventSearchParams}
 * that is neither a date range nor pagination. The events list and the calendar send exactly
 * these, which is why both can share one component (see `useEventFilters`).
 */
export type EventFilterValues = Pick<
  EventSearchParams,
  | 'q'
  | 'eventType'
  | 'venue'
  | 'district'
  | 'genre'
  | 'minPrice'
  | 'maxPrice'
  | 'excludeSoldOut'
  | 'free'
>

/** Today's events for the Home "Tonight" section. */
export function useTodayEvents() {
  return useAsync<EventSummary[]>(
    () => unwrap(api.GET('/events/today')),
    'errors.subject.tonightsEvents',
  )
}

/** First page of upcoming events from a given start date (inclusive), for the Home feed. */
export function useUpcomingEvents(from: string, size = 12) {
  return useAsync<EventSummary[]>(async () => {
    const page = await unwrap(api.GET('/events', { params: { query: { from, size } } }))
    return page.content ?? []
  }, 'errors.subject.upcomingEvents')
}

/**
 * Fetches events within an inclusive date range for the calendar. The BFF caps the range at
 * 92 days; standard month/week views stay well within that. `filters` accepts the same criteria
 * as the search endpoint, so the calendar and the list narrow their results identically.
 */
export function fetchCalendarEvents(
  from: string,
  to: string,
  filters: EventFilterValues = {},
): Promise<EventSummary[]> {
  return unwrap(api.GET('/events/calendar', { params: { query: { ...filters, from, to } } }))
}

/**
 * Paged event search for the events list and the venue/artist detail feeds. `params` is read
 * lazily on each `run()`, so callers re-run after changing filters or the page.
 */
export function useEventSearch(
  params: () => EventSearchParams,
  subjectKey: ErrorSubjectKey = 'errors.subject.events',
) {
  return useAsync<EventPage>(
    () => unwrap(api.GET('/events', { params: { query: params() } })),
    subjectKey,
  )
}
