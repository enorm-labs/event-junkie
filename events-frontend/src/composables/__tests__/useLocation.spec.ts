import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useLocation } from '@/composables/useLocation'

const PERMISSION_DENIED = 1
const POSITION_UNAVAILABLE = 2

type Success = (position: {
  coords: { latitude: number; longitude: number; accuracy: number }
}) => void
type Failure = (error: { code: number; PERMISSION_DENIED: number }) => void

const getCurrentPosition =
  vi.fn<(success: Success, failure: Failure, options: PositionOptions) => void>()

describe('useLocation', () => {
  beforeEach(() => {
    getCurrentPosition.mockReset()
    vi.stubGlobal('navigator', { geolocation: { getCurrentPosition } })
    useLocation().clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('asks the browser only when locate() is called', () => {
    useLocation()
    expect(getCurrentPosition).not.toHaveBeenCalled()
  })

  it('keeps the device position, with its accuracy in kilometres', () => {
    getCurrentPosition.mockImplementation((success) =>
      success({ coords: { latitude: 52.5, longitude: 13.4, accuracy: 1500 } }),
    )
    const { origin, state, locate } = useLocation()
    locate()
    expect(origin.value).toEqual({
      latitude: 52.5,
      longitude: 13.4,
      source: 'device',
      accuracyKm: 1.5,
    })
    expect(state.value).toBe('idle')
  })

  it('shares the origin between two callers, so a visit elsewhere keeps it', () => {
    useLocation().set({ latitude: 52.5, longitude: 13.4, source: 'map' })
    expect(useLocation().origin.value?.source).toBe('map')
  })

  it('tells a refusal from a failure', () => {
    const { state, locate } = useLocation()
    getCurrentPosition.mockImplementation((_success, failure) =>
      failure({ code: PERMISSION_DENIED, PERMISSION_DENIED }),
    )
    locate()
    expect(state.value).toBe('denied')

    getCurrentPosition.mockImplementation((_success, failure) =>
      failure({ code: POSITION_UNAVAILABLE, PERMISSION_DENIED }),
    )
    locate()
    expect(state.value).toBe('unavailable')
  })

  it('is unavailable in a browser without the API', () => {
    vi.stubGlobal('navigator', {})
    const { state, locate } = useLocation()
    locate()
    expect(state.value).toBe('unavailable')
  })

  it('leaves a manual origin in place when locating fails', () => {
    const { origin, locate, set } = useLocation()
    set({ latitude: 52.5, longitude: 13.4, source: 'venue', label: 'Berghain' })
    getCurrentPosition.mockImplementation((_success, failure) =>
      failure({ code: PERMISSION_DENIED, PERMISSION_DENIED }),
    )
    locate()
    expect(origin.value?.label).toBe('Berghain')
  })
})
