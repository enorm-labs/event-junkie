import { ref } from 'vue'
import type { Position } from '@/lib/geo'
import { venuePosition } from '@/lib/mapPins'
import type { VenueSummary } from '@/api/types'
import { useLocation } from '@/composables/useLocation'

/**
 * The three ways a map view sets the "near" origin, and the pick-on-map toggle they share: a tap or the
 * map's centre, a venue chosen by name, or the device. Any of them ends picking.
 */
export function useOriginPicker(center: () => Position | null) {
  const { locate, set, clear: clearOrigin } = useLocation()
  const picking = ref(false)

  function pick(position: Position) {
    set({ ...position, source: 'map' })
    picking.value = false
  }

  function pickCenter() {
    const position = center()
    if (position) pick(position)
  }

  function pickVenue(slug: string, venue: VenueSummary | undefined) {
    const position = venuePosition(venue)
    if (!venue || !position) return
    picking.value = false
    set({ ...position, source: 'venue', label: venue.name ?? '', slug })
  }

  // Only "Use my location" asks the browser: its prompt is the consent (LEGAL.md §7.4a).
  function useDevice() {
    picking.value = false
    locate()
  }

  function clear() {
    picking.value = false
    clearOrigin()
  }

  return { picking, pick, pickCenter, pickVenue, useDevice, clear }
}
