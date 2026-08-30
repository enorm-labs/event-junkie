import { api, unwrap } from '@/api/client'
import type { ArtistDetail } from '@/api/types'
import { useAsync } from './useAsync'

/** Loads a single artist by slug for the artist detail page. Call `run()` to (re)fetch. */
export function useArtist(slug: () => string) {
  return useAsync<ArtistDetail>(
    () => unwrap(api.GET('/api/artists/{slug}', { params: { path: { slug: slug() } } })),
    'errors.subject.artist',
  )
}
