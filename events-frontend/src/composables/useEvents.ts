import { api, unwrap } from '@/api/client'
import type { EventPage, EventSummary } from '@/api/types'
import type { ErrorSubjectKey } from '@/i18n'
import { isOnNow, todayIso } from '@/lib/format'
import { useAsync } from './useAsync'

/** Query parameters accepted by the event search endpoint (`GET /events`). */
export interface EventSearchParams {
  from?: string
  to?: string
  /** With `from`, keeps an event that started earlier and still runs on `from` (#2674). */
  running?: boolean
  /** Any of these types; sent as a repeated parameter. */
  eventType?: string[]
  venue?: string
  /** Any of these districts; sent as a repeated parameter. */
  district?: string[]
  /** Any of these venue types (`VENUE_TYPES`); sent as a repeated parameter. */
  venueType?: string[]
  artist?: string
  promoter?: string
  genre?: string
  /** Any of these genre families; sent as a repeated parameter. */
  family?: string[]
  minPrice?: number
  maxPrice?: number
  q?: string
  excludeSoldOut?: boolean
  free?: boolean
  /** Any of these slots (`TIMES_OF_DAY`); sent as a repeated parameter. */
  timeOfDay?: string[]
  /** Any of these spoken-language codes; sent as a repeated parameter. An unknown language drops out. */
  language?: string[]
  /** Every one of these party features (`PARTY_FEATURES`); sent as a repeated parameter. */
  feature?: string[]
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
  | 'venueType'
  | 'genre'
  | 'family'
  | 'minPrice'
  | 'maxPrice'
  | 'excludeSoldOut'
  | 'free'
  | 'timeOfDay'
  | 'language'
  | 'feature'
>

/** Today's events for the Home "Tonight" section. */
export function useTodayEvents() {
  return useAsync<EventSummary[]>(
    () => unwrap(api.GET('/api/events/today')),
    'errors.subject.tonightsEvents',
    () => '/api/events/today',
  )
}

/** First page of upcoming events from a given start date (inclusive), for the Home feed. */
export function useUpcomingEvents(from: string, size = 12) {
  return useAsync<EventSummary[]>(
    async () => {
      const page = await unwrap(api.GET('/api/events', { params: { query: { from, size } } }))
      return page.content ?? []
    },
    'errors.subject.upcomingEvents',
    () => `/api/events?from=${from}&size=${size}`,
  )
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
  return unwrap(api.GET('/api/events/calendar', { params: { query: { ...filters, from, to } } }))
}

/** One page of the event search. */
export function searchEvents(params: EventSearchParams): Promise<EventPage> {
  return unwrap(api.GET('/api/events', { params: { query: params } }))
}

/**
 * The events running at this moment, as one page. It reads the whole of today unpaged, because
 * `isOnNow` over one page of 20 would miss most of the evening.
 */
export async function fetchOnNowPage(filters: EventFilterValues): Promise<EventPage> {
  const today = todayIso()
  const content = (await fetchCalendarEvents(today, today, filters)).filter(isOnNow)
  return { content, page: 0, size: content.length, totalElements: content.length, totalPages: 1 }
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
    () => searchEvents(params()),
    subjectKey,
    () => `/api/events?${JSON.stringify(params())}`,
  )
}
