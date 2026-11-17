import { afterEach, describe, expect, it, vi } from 'vitest'
import { scrollMotion } from '@/lib/motion'

function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: reduce && query === '(prefers-reduced-motion: reduce)',
  }))
}

describe('scrollMotion', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('scrolls smoothly when the OS allows motion', () => {
    stubReducedMotion(false)
    expect(scrollMotion()).toBe('smooth')
  })

  it('jumps when the OS asks for reduced motion', () => {
    stubReducedMotion(true)
    expect(scrollMotion()).toBe('auto')
  })

  it('scrolls smoothly where matchMedia does not exist', () => {
    vi.stubGlobal('matchMedia', undefined)
    expect(scrollMotion()).toBe('smooth')
  })
})
