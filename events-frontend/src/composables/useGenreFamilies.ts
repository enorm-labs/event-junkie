import { shallowRef } from 'vue'
import { api, unwrap } from '@/api/client'

/** Genre tag name to family slug, filled once per page load and shared by every caller. */
const familyByName = shallowRef(new Map<string, string>())
let requested = false

/**
 * The genre families of an event's tags, for the title poster. An event carries tag names, and
 * only `/api/genres` knows each tag's family. Every card on a page asks, so this fetches once and
 * shares the answer; until it arrives, or when it fails, a poster falls back to the event type.
 */
export function useGenreFamilies() {
  if (!requested) {
    requested = true
    unwrap(api.GET('/api/genres'))
      .then((tags) => {
        familyByName.value = new Map(
          tags.flatMap((tag) => (tag.name && tag.family ? [[tag.name, tag.family] as const] : [])),
        )
      })
      .catch(() => {
        // The poster is decoration and the filter bar reports its own failure; allow a retry.
        requested = false
      })
  }

  return (tags?: readonly string[] | null): string[] =>
    (tags ?? []).flatMap((tag) => familyByName.value.get(tag) ?? [])
}
