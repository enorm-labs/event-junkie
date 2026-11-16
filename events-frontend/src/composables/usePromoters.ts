import { api, unwrap } from '@/api/client'
import type { PromoterPage } from '@/api/types'
import { useAsync } from './useAsync'

/** Query parameters accepted by the promoter list endpoint (`GET /promoters`). */
export interface PromoterSearchParams {
  q?: string
  /** Promoter kind slugs; a promoter of any of them matches (#5091). */
  kind?: string[]
  page?: number
  size?: number
  /** `name` or `upcomingEvents`, with a direction: `upcomingEvents,desc`. */
  sort?: string[]
}

/**
 * Paged promoter search for the promoters overview page. `params` is read lazily on each `run()`,
 * so callers re-run after changing the search term, the sort or the page.
 */
export function usePromoterSearch(params: () => PromoterSearchParams) {
  return useAsync<PromoterPage>(
    () => unwrap(api.GET('/api/promoters', { params: { query: params() } })),
    'errors.subject.promoters',
    () => `/api/promoters?${JSON.stringify(params())}`,
  )
}
