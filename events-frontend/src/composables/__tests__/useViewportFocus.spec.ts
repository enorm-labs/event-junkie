import { afterEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick } from 'vue'

/**
 * The composable exists because Tailwind 4 compiles `group-hover:` inside `@media (hover: hover)`,
 * so the poster reveal is dead code on a phone. Both halves of that are asserted here: a pointer
 * device must be left to `:hover`, and a touch device must get the observer instead.
 */

type ObserverEntry = { target: Element; isIntersecting: boolean }

function stubMatchMedia(hover: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({ matches: query === '(hover: hover)' ? hover : false })),
  )
}

function stubIntersectionObserver() {
  const observed = new Set<Element>()
  const options: IntersectionObserverInit[] = []
  let notify: ((entries: ObserverEntry[]) => void) | undefined

  class FakeObserver {
    constructor(callback: (entries: ObserverEntry[]) => void, init?: IntersectionObserverInit) {
      notify = callback
      options.push(init ?? {})
    }
    observe(element: Element) {
      observed.add(element)
    }
    unobserve(element: Element) {
      observed.delete(element)
    }
    disconnect() {
      observed.clear()
    }
  }

  vi.stubGlobal('IntersectionObserver', FakeObserver)

  return {
    observed,
    options,
    constructed: () => notify !== undefined,
    fire: (target: Element, isIntersecting: boolean) => notify?.([{ target, isIntersecting }]),
  }
}

// A fresh module per test: the observer is shared by every card on a page, so it is module state.
async function load() {
  vi.resetModules()
  return (await import('@/composables/useViewportFocus')).useViewportFocus
}

describe('useViewportFocus', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('observes nothing on a device that can hover', async () => {
    stubMatchMedia(true)
    const observer = stubIntersectionObserver()
    const useViewportFocus = await load()
    const scope = effectScope()

    await scope.run(async () => {
      const { el, focused } = useViewportFocus()
      el.value = document.createElement('div')
      await nextTick()
      expect(observer.constructed()).toBe(false)
      expect(focused.value).toBe(false)
    })

    scope.stop()
  })

  it('focuses an element while it crosses the middle of a touch viewport', async () => {
    stubMatchMedia(false)
    const observer = stubIntersectionObserver()
    const useViewportFocus = await load()
    const scope = effectScope()

    await scope.run(async () => {
      const { el, focused } = useViewportFocus()
      const element = document.createElement('div')
      el.value = element
      await nextTick()

      expect(observer.observed.has(element)).toBe(true)
      // The band is the decision: 45% off each edge leaves the middle tenth, so one card at a time.
      expect(observer.options[0]?.rootMargin).toBe('-45% 0px -45% 0px')

      observer.fire(element, true)
      expect(focused.value).toBe(true)

      observer.fire(element, false)
      expect(focused.value).toBe(false)
    })

    scope.stop()
  })

  it('stops observing when the card is unmounted', async () => {
    stubMatchMedia(false)
    const observer = stubIntersectionObserver()
    const useViewportFocus = await load()
    const scope = effectScope()
    const element = document.createElement('div')

    await scope.run(async () => {
      const { el } = useViewportFocus()
      el.value = element
      await nextTick()
    })

    scope.stop()
    expect(observer.observed.has(element)).toBe(false)
  })
})
