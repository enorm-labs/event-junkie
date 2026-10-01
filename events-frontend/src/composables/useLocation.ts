import { readonly, ref } from 'vue'
import type { Position } from '@/lib/geo'

/** Where "near" is measured from, and how the visitor chose it. */
export interface Origin extends Position {
  /** `device` from the Geolocation API, `map` from a tap or the map's centre, `venue` from a venue picked by name. */
  source: 'device' | 'map' | 'venue'
  /** The venue's name for `venue`; the view words the other two. */
  label?: string
  /** The venue's slug for `venue`. */
  slug?: string
  /** The device's reported accuracy in kilometres, which can be larger than the radius. */
  accuracyKm?: number
}

export type LocateState = 'idle' | 'locating' | 'denied' | 'unavailable'

// Held for the tab, in memory only: never in the URL, never in storage, never sent (LEGAL.md §7.4a).
// Module scope, so a visit to a venue page and back keeps it.
const origin = ref<Origin | null>(null)
const state = ref<LocateState>('idle')

/** A browser waits for the visitor's answer without limit; past this the request counts as failed. */
const TIMEOUT_MS = 15_000
/** A fix up to a minute old is still where the visitor is. */
const MAXIMUM_AGE_MS = 60_000

/**
 * The visitor's chosen origin for "near me". `locate()` asks the browser, and only on a click: the
 * browser's own prompt is the consent, and asking on page load would be asking out of context.
 */
export function useLocation() {
  function locate() {
    if (!('geolocation' in navigator)) {
      state.value = 'unavailable'
      return
    }
    state.value = 'locating'
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        origin.value = {
          latitude: coords.latitude,
          longitude: coords.longitude,
          source: 'device',
          accuracyKm: coords.accuracy / 1000,
        }
        state.value = 'idle'
      },
      (error) => {
        // A Permissions-Policy that forbids the API also reports PERMISSION_DENIED.
        state.value = error.code === error.PERMISSION_DENIED ? 'denied' : 'unavailable'
      },
      { enableHighAccuracy: false, timeout: TIMEOUT_MS, maximumAge: MAXIMUM_AGE_MS },
    )
  }

  function set(next: Origin) {
    origin.value = next
    state.value = 'idle'
  }

  function clear() {
    origin.value = null
    state.value = 'idle'
  }

  return { origin: readonly(origin), state: readonly(state), locate, set, clear }
}
