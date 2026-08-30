import { api, unwrap } from '@/api/client'
import type { EventDetail } from '@/api/types'
import { useAsync } from './useAsync'

/** Loads a single event by slug for the event detail page. Call `run()` to (re)fetch. */
export function useEvent(slug: () => string) {
  return useAsync<EventDetail>(
    () => unwrap(api.GET('/api/events/{slug}', { params: { path: { slug: slug() } } })),
    'errors.subject.event',
  )
}
