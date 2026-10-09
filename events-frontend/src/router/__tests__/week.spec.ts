import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import router from '@/router'

describe('the week routes', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    // Friday 9 October 2026, 20:00 in Berlin: week 41.
    vi.setSystemTime(new Date('2026-10-09T18:00:00Z'))
  })

  afterEach(() => vi.useRealTimers())

  it('redirects /week to this week, in the same locale', async () => {
    await router.push('/de/week')
    expect(router.currentRoute.value.fullPath).toBe('/de/week/2026-41')
  })

  it('opens a real week as it is', async () => {
    await router.push('/en/week/2026-53')
    expect(router.currentRoute.value.name).toBe('week')
    expect(router.currentRoute.value.params.isoWeek).toBe('2026-53')
  })

  it('sends a week the year does not have, or a malformed one, to this week', async () => {
    await router.push('/en/week/2025-53')
    expect(router.currentRoute.value.fullPath).toBe('/en/week/2026-41')
    await router.push('/en/week/next')
    expect(router.currentRoute.value.fullPath).toBe('/en/week/2026-41')
  })
})
