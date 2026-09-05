import { api, unwrap } from '@/api/client'
import type { GenreTag } from '@/api/types'
import { useAsync } from './useAsync'

/** Loads all genre tags (alphabetical) for the events filter dropdown. */
export function useGenres() {
  return useAsync<GenreTag[]>(
    () => unwrap(api.GET('/api/genres')),
    undefined,
    () => '/api/genres',
  )
}
