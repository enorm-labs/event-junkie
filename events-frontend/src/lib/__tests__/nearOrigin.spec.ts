import { describe, expect, it } from 'vitest'

import type { Origin } from '@/composables/useLocation'
import { coarseAccuracy, describeOrigin } from '@/lib/nearOrigin'

const POSITION = { latitude: 52.5112, longitude: 13.4432 }
const t = (key: string) => `<${key}>`

describe('describeOrigin', () => {
  it('is empty without an origin', () => {
    expect(describeOrigin(null, t)).toBe('')
  })

  it('words the device and the map origins through the messages', () => {
    expect(describeOrigin({ ...POSITION, source: 'device' }, t)).toBe('<map.near.yourLocation>')
    expect(describeOrigin({ ...POSITION, source: 'map' }, t)).toBe('<map.near.pickedPoint>')
  })

  it("names a venue origin by the venue's own name", () => {
    expect(describeOrigin({ ...POSITION, source: 'venue', label: 'Berghain' }, t)).toBe('Berghain')
    expect(describeOrigin({ ...POSITION, source: 'venue' }, t)).toBe('')
  })
})

describe('coarseAccuracy', () => {
  const device = (accuracyKm?: number): Origin => ({ ...POSITION, source: 'device', accuracyKm })

  it('is null without an origin or an accuracy', () => {
    expect(coarseAccuracy(null, 1, 'en')).toBeNull()
    expect(coarseAccuracy(device(), 1, 'en')).toBeNull()
    expect(coarseAccuracy(device(0), 1, 'en')).toBeNull()
  })

  it('is null while the fix is within the radius, the radius itself included', () => {
    expect(coarseAccuracy(device(0.4), 1, 'en')).toBeNull()
    expect(coarseAccuracy(device(1), 1, 'en')).toBeNull()
  })

  it('formats a fix coarser than the radius as a spoken distance', () => {
    expect(coarseAccuracy(device(1.26), 1, 'en')).toBe('1.3 km')
    expect(coarseAccuracy(device(1.26), 1, 'de')).toBe('1,3 km')
    expect(coarseAccuracy(device(0.62), 0.5, 'en')).toBe('600 m')
  })
})
