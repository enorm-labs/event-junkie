import { describe, expect, it } from 'vitest'

import { circlePolygon, distanceKm, formatDistance } from '@/lib/geo'

const BERGHAIN = { latitude: 52.5112, longitude: 13.4432 }
const BRANDENBURG_GATE = { latitude: 52.5163, longitude: 13.3777 }

describe('distanceKm', () => {
  it('measures a known Berlin distance', () => {
    expect(distanceKm(BERGHAIN, BRANDENBURG_GATE)).toBeCloseTo(4.4685, 3)
  })

  it('is symmetric, and zero from a point to itself', () => {
    expect(distanceKm(BRANDENBURG_GATE, BERGHAIN)).toBeCloseTo(
      distanceKm(BERGHAIN, BRANDENBURG_GATE),
    )
    expect(distanceKm(BERGHAIN, BERGHAIN)).toBe(0)
  })
})

describe('formatDistance', () => {
  it('says metres below a kilometre, rounded to 50 and never zero', () => {
    expect(formatDistance(0, 'en')).toBe('50 m')
    expect(formatDistance(0.33, 'en')).toBe('350 m')
    expect(formatDistance(0.94, 'en')).toBe('950 m')
  })

  it('says kilometres with one decimal in the locale', () => {
    expect(formatDistance(0.95, 'en')).toBe('1 km')
    expect(formatDistance(1.24, 'en')).toBe('1.2 km')
    expect(formatDistance(1.24, 'de')).toBe('1,2 km')
    expect(formatDistance(12, 'de')).toBe('12 km')
  })
})

describe('circlePolygon', () => {
  it('is a closed ring whose every point lies at the radius', () => {
    const ring = circlePolygon(BERGHAIN, 2)
    expect(ring[0]).toEqual(ring[ring.length - 1])
    for (const [longitude, latitude] of ring) {
      expect(distanceKm(BERGHAIN, { latitude, longitude })).toBeCloseTo(2, 2)
    }
  })
})
