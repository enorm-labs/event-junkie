import { inject, type InjectionKey, provide } from 'vue'
import type { GenreTag } from '@/api/types'
import { useGenres } from './useGenres'
import { useAllVenues } from './useVenues'

/** The genre tags and venues the filter bar offers; the bar runs both loads when it mounts. */
export interface FilterLists {
  genres: ReturnType<typeof useGenres>
  venues: ReturnType<typeof useAllVenues>
}

const FILTER_LISTS: InjectionKey<FilterLists> = Symbol('filterLists')

/**
 * Creates the lists for a view and its filter bar, so a view that names its filters (#2772) reads
 * the bar's loads rather than repeating them.
 */
export function provideFilterLists(): FilterLists {
  const lists = { genres: useGenres(), venues: useAllVenues() }
  provide(FILTER_LISTS, lists)
  return lists
}

/** The lists a parent view provided, or the bar's own when no view did. */
export function useFilterLists(): FilterLists {
  return inject(FILTER_LISTS, null) ?? { genres: useGenres(), venues: useAllVenues() }
}

/**
 * The families the bar shows as chosen: the URL's, or, for a link from before families existed
 * carrying only `genre=`, the family of that style.
 */
export function activeFamilies(
  families: readonly string[],
  genre: string | undefined,
  tags: readonly GenreTag[],
): string[] {
  if (families.length) return [...families]
  const family = tags.find((tag) => tag.slug === genre)?.family
  return family ? [family] : []
}
