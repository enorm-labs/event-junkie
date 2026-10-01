import { api, unwrap } from '@/api/client'
import type { VenuePage, VenueSummary } from '@/api/types'
import type { ErrorSubjectKey } from '@/i18n'
import { useAsync } from './useAsync'

/** Query parameters accepted by the venue list endpoint (`GET /venues`). */
export interface VenueSearchParams {
  q?: string
  district?: string
  page?: number
  size?: number
  sort?: string[]
}

/**
 * Paged venue search for the venues overview page. `params` is read lazily on each `run()`,
 * so callers re-run after changing the search term or the page.
 */
export function useVenueSearch(
  params: () => VenueSearchParams,
  subjectKey: ErrorSubjectKey = 'errors.subject.venues',
) {
  return useAsync<VenuePage>(
    () => unwrap(api.GET('/api/venues', { params: { query: params() } })),
    subjectKey,
    () => `/api/venues?${JSON.stringify(params())}`,
  )
}

/**
 * Every venue, name-sorted, for the events filter dropdown. Page by page: one large `size` is
 * capped at 100, and venue 101 would be missing without a sign (#2246).
 */
export function useAllVenues() {
  return useAsync<VenueSummary[]>(
    () => fetchAllVenues({}),
    'errors.subject.venues',
    () => '/api/venues?all',
  )
}

/** The BFF's largest page; a larger `size` is silently capped to it. */
const MAX_PAGE_SIZE = 100

/**
 * Every venue matching `params`, page by page, for the venues map: a pin missing because it sat on
 * a second page would read as a venue that does not exist. Keyed by slug, because a venue added
 * between two page reads shifts the next page by one and repeats a row.
 */
export async function fetchAllVenues(
  params: Omit<VenueSearchParams, 'page' | 'size'>,
): Promise<VenueSummary[]> {
  const venues = new Map<string, VenueSummary>()
  for (let page = 0; ; page++) {
    const result = await unwrap(
      api.GET('/api/venues', { params: { query: { ...params, page, size: MAX_PAGE_SIZE } } }),
    )
    for (const venue of result.content ?? []) venues.set(venue.slug ?? '', venue)
    if (page + 1 >= (result.totalPages ?? 0)) return [...venues.values()]
  }
}
