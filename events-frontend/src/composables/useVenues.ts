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
 * Loads every venue (name-sorted) to populate the events filter dropdown. The list endpoint is
 * paged; we request a size large enough to hold all tracked venues in a single call.
 */
export function useAllVenues() {
  return useAsync<VenueSummary[]>(
    async () => {
      const page = await unwrap(api.GET('/api/venues', { params: { query: { size: 500 } } }))
      return page.content ?? []
    },
    'errors.subject.venues',
    () => '/api/venues?size=500',
  )
}
