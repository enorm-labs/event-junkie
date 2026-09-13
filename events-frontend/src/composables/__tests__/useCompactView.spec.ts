import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * The preference has to survive a reload without a flash, so the document class is the source of
 * truth and `localStorage` is only how it gets there. Both directions are asserted here.
 */

// A fresh module per test: the flag is module state, shared by the header toggle and every view.
async function load() {
  vi.resetModules()
  return (await import('@/composables/useCompactView')).useCompactView
}

describe('useCompactView', () => {
  afterEach(() => {
    document.documentElement.classList.remove('compact')
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('starts from the class the pre-paint script already applied', async () => {
    document.documentElement.classList.add('compact')
    const useCompactView = await load()

    expect(useCompactView().compact.value).toBe(true)
  })

  it('defaults to the poster view', async () => {
    const useCompactView = await load()

    expect(useCompactView().compact.value).toBe(false)
  })

  it('toggles the document class and stores the choice', async () => {
    const useCompactView = await load()
    const { compact, toggle } = useCompactView()

    toggle()
    expect(compact.value).toBe(true)
    expect(document.documentElement.classList.contains('compact')).toBe(true)
    expect(localStorage.getItem('view')).toBe('compact')

    toggle()
    expect(compact.value).toBe(false)
    expect(document.documentElement.classList.contains('compact')).toBe(false)
    expect(localStorage.getItem('view')).toBe('poster')
  })

  it('shares one answer between callers', async () => {
    const useCompactView = await load()
    const header = useCompactView()
    const view = useCompactView()

    header.toggle()
    expect(view.compact.value).toBe(true)
  })

  it('still switches the view when storage throws', async () => {
    // Private mode, or a browser with site data blocked. Persistence is best-effort; the toggle
    // is not — a control that appears to do nothing is worse than a forgotten preference.
    const useCompactView = await load()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    const { compact, toggle } = useCompactView()

    expect(() => toggle()).not.toThrow()
    expect(compact.value).toBe(true)
    expect(document.documentElement.classList.contains('compact')).toBe(true)
  })
})
