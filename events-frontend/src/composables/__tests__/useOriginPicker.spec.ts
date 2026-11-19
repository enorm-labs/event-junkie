import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { Position } from '@/lib/geo'
import { useLocation } from '@/composables/useLocation'
import { useOriginPicker } from '@/composables/useOriginPicker'

const CENTRE = { latitude: 52.52, longitude: 13.405 }
const VENUE = { slug: 'v1', name: 'Hall', latitude: 52.5, longitude: 13.4 }

const getCurrentPosition = vi.fn<Geolocation['getCurrentPosition']>()

describe('useOriginPicker', () => {
  beforeEach(() => {
    getCurrentPosition.mockReset()
    vi.stubGlobal('navigator', { geolocation: { getCurrentPosition } })
    useLocation().clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function picker(center: () => Position | null = () => CENTRE) {
    const result = useOriginPicker(center)
    result.picking.value = true
    return result
  }

  it('sets a tapped position as a map origin and ends picking', () => {
    const { picking, pick } = picker()
    pick({ latitude: 52.4, longitude: 13.3 })
    expect(useLocation().origin.value).toEqual({ latitude: 52.4, longitude: 13.3, source: 'map' })
    expect(picking.value).toBe(false)
  })

  it("takes the map's centre, and does nothing while the map has none", () => {
    const none = picker(() => null)
    none.pickCenter()
    expect(useLocation().origin.value).toBeNull()
    expect(none.picking.value).toBe(true)

    const { picking, pickCenter } = picker()
    pickCenter()
    expect(useLocation().origin.value).toEqual({ ...CENTRE, source: 'map' })
    expect(picking.value).toBe(false)
  })

  it('sets a venue with a coordinate as the origin, under its name and slug', () => {
    const { picking, pickVenue } = picker()
    pickVenue('v1', VENUE)
    expect(useLocation().origin.value).toEqual({
      latitude: 52.5,
      longitude: 13.4,
      source: 'venue',
      label: 'Hall',
      slug: 'v1',
    })
    expect(picking.value).toBe(false)
  })

  it('ignores a venue it cannot place, and keeps picking', () => {
    const { picking, pickVenue } = picker()
    pickVenue('v1', undefined)
    pickVenue('v2', { slug: 'v2', name: 'No pin' })
    expect(useLocation().origin.value).toBeNull()
    expect(picking.value).toBe(true)
  })

  it('asks the browser only on useDevice, and ends picking', () => {
    const { picking, useDevice } = picker()
    expect(getCurrentPosition).not.toHaveBeenCalled()
    useDevice()
    expect(getCurrentPosition).toHaveBeenCalledOnce()
    expect(picking.value).toBe(false)
  })

  it('clears the origin and ends picking', () => {
    const { picking, pick, clear } = picker()
    pick(CENTRE)
    picking.value = true
    clear()
    expect(useLocation().origin.value).toBeNull()
    expect(picking.value).toBe(false)
  })
})
