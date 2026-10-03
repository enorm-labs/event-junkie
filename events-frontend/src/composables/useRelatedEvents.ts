import { api, unwrap } from '@/api/client'
import type { EventSummary } from '@/api/types'
import { useAsync } from './useAsync'

/** Loads the upcoming events like the one at `slug`, for its detail page (#359). Call `run()` to (re)fetch. */
export function useRelatedEvents(slug: () => string) {
  return useAsync<EventSummary[]>(
    () => unwrap(api.GET('/api/events/{slug}/related', { params: { path: { slug: slug() } } })),
    undefined,
    () => `/api/events/${slug()}/related`,
  )
}
