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
  /** Any of these venues; sent as a repeated parameter. */
  venue?: string[]
  /** Any of these districts; sent as a repeated parameter. */
  district?: string[]
  /** Any of these venue types (`VENUE_TYPES`); sent as a repeated parameter. */
  venueType?: string[]
  artist?: string
  promoter?: string
  /** Any of these styles (genre tags); sent as a repeated parameter. */
  genre?: string[]
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
  /** Every one of these event features (`EVENT_FEATURES`); sent as a repeated parameter. */
  feature?: string[]
  /** The slug of a festival an operator linked the events to (#3273). */
  festival?: string
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
  | 'festival'
>

/** Today's events for the Home "Today" section. */
export function useTodayEvents() {
  return useAsync<EventSummary[]>(
    () => unwrap(api.GET('/api/events/today')),
    'errors.subject.todaysEvents',
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
 * The events list as it grows by "Show more events": pages `firstPage` to `page` of one search, in
 * one page. `query` is the search without its page, so the next load can tell a longer list of the
 * same search from a new one.
 */
export type GrownEventPage = EventPage & { firstPage: number; query: string }

/**
 * How many pages a link to a later page reloads before it. A shared `?page=5` or a back navigation
 * shows the whole list the visitor had, so the scroll position is restored. A crawler's
 * `?page=300` starts the list at that page instead of sending 300 requests.
 */
export const GROW_RELOAD_PAGES = 10

/**
 * The next state of a growing events list. When `previous` is the same search one page shorter,
 * only the new page is fetched and appended; otherwise the pages up to `params.page` are fetched
 * again. An event that moved across a page boundary between two fetches is listed once.
 */
export async function growEventSearch(
  params: EventSearchParams,
  previous: GrownEventPage | null,
): Promise<GrownEventPage> {
  const target = params.page ?? 0
  const query = JSON.stringify({ ...params, page: undefined })
  const appending = previous?.query === query && previous.page === target - 1
  const firstPage = appending ? previous.firstPage : target < GROW_RELOAD_PAGES ? 0 : target
  const start = appending ? target : firstPage
  const fetched = await Promise.all(
    Array.from({ length: target - start + 1 }, (_, i) =>
      searchEvents({ ...params, page: start + i }),
    ),
  )
  const latest = fetched[fetched.length - 1] as EventPage
  const pages = appending ? [previous, ...fetched] : fetched
  const seen = new Set<string | undefined>()
  const content = pages
    .flatMap((page) => page.content ?? [])
    .filter((event) => !seen.has(event.slug) && seen.add(event.slug))
  return { ...latest, lead: pages[0]?.lead, content, firstPage, query }
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
