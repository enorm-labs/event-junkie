import { describe, expect, it } from 'vitest'

import type { EventSummary, VenueSummary } from '@/api/types'
import { groupByVenue, nearby, venuePin } from '@/lib/mapPins'

const ASTRA: VenueSummary = {
  slug: 'astra',
  name: 'Astra',
  latitude: 52.507242,
  longitude: 13.451803,
}
const LIDO: VenueSummary = { slug: 'lido', name: 'Lido', latitude: 52.499, longitude: 13.444 }
const NOWHERE: VenueSummary = { slug: 'nowhere', name: 'Nowhere', latitude: null, longitude: null }

function event(slug: string, venue: VenueSummary): EventSummary {
  return { slug, title: slug, eventDate: '2026-10-01', venue }
}

describe('groupByVenue', () => {
  it('puts the busiest venue first, then sorts by name', () => {
    const groups = groupByVenue([
      event('a', LIDO),
      event('b', ASTRA),
      event('c', LIDO),
      event('d', { ...ASTRA, slug: 'zukunft', name: 'Zukunft' }),
    ])
    expect(groups.map((group) => [group.venue.slug, group.events.length])).toEqual([
      ['lido', 2],
      ['astra', 1],
      ['zukunft', 1],
    ])
  })

  it('leaves out a venue without a coordinate, which cannot be pinned', () => {
    expect(groupByVenue([event('a', NOWHERE), event('b', ASTRA)]).map((g) => g.venue.slug)).toEqual(
      ['astra'],
    )
  })

  it('keeps a coordinate of zero, which is a value and not a missing one', () => {
    const zero = { ...ASTRA, latitude: 0, longitude: 0 }
    expect(groupByVenue([event('a', zero)])).toHaveLength(1)
  })
})

describe('venuePin', () => {
  it('carries the label and the badge the view chose', () => {
    expect(venuePin(ASTRA, 'Astra: 3 events', '3')).toEqual({
      slug: 'astra',
      latitude: 52.507242,
      longitude: 13.451803,
      label: 'Astra: 3 events',
      badge: '3',
    })
  })

  it('is null for a venue without a coordinate', () => {
    expect(venuePin(NOWHERE, 'Nowhere')).toBeNull()
  })
})

describe('nearby', () => {
  // Astra and Lido are about 1.06 km apart in Friedrichshain-Kreuzberg; the origin sits on Lido.
  const origin = { latitude: LIDO.latitude!, longitude: LIDO.longitude! }

  it('keeps the venues within the radius, nearest first, with the distance', () => {
    const groups = groupByVenue([event('a', ASTRA), event('b', LIDO)])
    const result = nearby(groups, origin, 2)
    expect(result.map((group) => group.venue.slug)).toEqual(['lido', 'astra'])
    expect(result[0]!.distanceKm).toBe(0)
    expect(result[1]!.distanceKm).toBeCloseTo(1.06, 2)
  })

  it('leaves out a venue beyond the radius', () => {
    const groups = groupByVenue([event('a', ASTRA), event('b', LIDO)])
    expect(nearby(groups, origin, 1).map((group) => group.venue.slug)).toEqual(['lido'])
  })
})
