import { api, unwrap } from '@/api/client'
import type { PromoterDetail } from '@/api/types'
import { useAsync } from './useAsync'

/** Loads a single promoter by slug for the promoter detail page. Call `run()` to (re)fetch. */
export function usePromoter(slug: () => string) {
  return useAsync<PromoterDetail>(
    () => unwrap(api.GET('/api/promoters/{slug}', { params: { path: { slug: slug() } } })),
    'errors.subject.promoter',
  )
}
