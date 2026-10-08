import { describe, expect, it } from 'vitest'
import { venueClosure } from '@/lib/venueClosure'

describe('venueClosure', () => {
  it('is nothing for a venue with no closure', () => {
    expect(venueClosure(null, '2026-10-08')).toBeNull()
    expect(venueClosure(undefined, '2026-10-08')).toBeNull()
  })

  it('is closing through the last day and closed from the day after', () => {
    expect(venueClosure('2026-10-31', '2026-10-08')?.state).toBe('closing')
    expect(venueClosure('2026-10-31', '2026-10-31')?.state).toBe('closing')
    expect(venueClosure('2026-10-31', '2026-11-01')).toEqual({
      state: 'closed',
      lastDay: '2026-10-31',
    })
  })
})
