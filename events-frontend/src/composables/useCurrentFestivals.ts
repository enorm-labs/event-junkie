import { shallowRef } from 'vue'

import { api, unwrap } from '@/api/client'
import type { Festival } from '@/api/types'

/**
 * The festivals the home page announces: up to 3, running today or starting within 14 days,
 * running ones first (#3272). A failure leaves the list empty: a missing notice needs no error
 * message on a page that shows its own.
 */
export function useCurrentFestivals() {
  const festivals = shallowRef<Festival[]>([])

  async function run() {
    try {
      const answer = await unwrap(api.GET('/api/festivals/current'))
      // Anything but a list, such as a stub or a BFF from before the list, shows nothing.
      festivals.value = Array.isArray(answer) ? answer : []
    } catch {
      festivals.value = []
    }
  }

  return { festivals, run }
}
