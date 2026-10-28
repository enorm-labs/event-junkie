import { ref } from 'vue'
import {
  deleteSearch,
  fetchSearches,
  prepareCsrf,
  renameSearch,
  type SavedSearch,
} from '@/api/account'
import { searchEvents } from '@/composables/useEvents'
import { searchParams } from '@/lib/savedSearch'

/**
 * The account's saved searches and how many events each matches from today (#3890). The Following
 * page loads them once and shows them twice (#4099): chips to run them beside the filters, a list
 * to rename and delete them on the follows tab. Both read this state, so a change shows in both.
 * The counts come from the public list, one small request per search.
 */
export function useSavedSearches() {
  const searches = ref<SavedSearch[]>([])
  const counts = ref<Record<string, number>>({})

  async function load() {
    try {
      searches.value = await fetchSearches()
    } catch {
      // A help beside the follows, not the page: without it the page still works.
      searches.value = []
    }
    for (const search of searches.value) void count(search)
  }

  async function count(search: SavedSearch) {
    try {
      const page = await searchEvents({ ...searchParams(search.query), size: 1 })
      counts.value = { ...counts.value, [search.id]: page.totalElements ?? 0 }
    } catch {
      // The search stays, without its count.
    }
  }

  async function rename(search: SavedSearch, name: string) {
    await prepareCsrf()
    await renameSearch(search.id, name)
    search.name = name
  }

  async function remove(search: SavedSearch) {
    await prepareCsrf()
    await deleteSearch(search.id)
    searches.value = searches.value.filter((s) => s.id !== search.id)
  }

  return { searches, counts, load, rename, remove }
}

export type SavedSearches = ReturnType<typeof useSavedSearches>
