import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import DisplaySettings from '@/components/DisplaySettings.vue'
import { useCompactView } from '@/composables/useCompactView'

let wrapper: VueWrapper | undefined

async function open() {
  wrapper = mount(DisplaySettings, { attachTo: document.body })
  await wrapper.get('button').trigger('click')
  await flushPromises()
}

/** The popover renders into a portal, so its controls are looked up on the document. */
function group(name: string): HTMLElement {
  const found = [...document.body.querySelectorAll<HTMLElement>('[role="group"]')].find(
    (g) => document.getElementById(g.getAttribute('aria-labelledby') ?? '')?.textContent === name,
  )
  if (!found) throw new Error(`no group labelled ${name}`)
  return found
}

function pressed(name: string): Record<string, string | null> {
  return Object.fromEntries(
    [...group(name).querySelectorAll('button')].map((b) => [
      b.textContent?.trim(),
      b.getAttribute('aria-pressed'),
    ]),
  )
}

function option(groupName: string, label: string): HTMLButtonElement {
  const found = [...group(groupName).querySelectorAll('button')].find(
    (b) => b.textContent?.trim() === label,
  )
  if (!found) throw new Error(`no option ${label} in ${groupName}`)
  return found
}

/** An OS colour scheme that starts at `dark` and can be flipped, as a system switch would. */
function stubOsScheme(dark: boolean) {
  const listeners = new Set<() => void>()
  const query = {
    matches: dark,
    addEventListener: (_: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) => listeners.delete(listener),
  }
  vi.stubGlobal('matchMedia', () => query)
  return (next: boolean) => {
    query.matches = next
    listeners.forEach((listener) => listener())
  }
}

const isDark = () => document.documentElement.classList.contains('dark')

describe('DisplaySettings', () => {
  beforeEach(() => {
    // The state the pre-paint script leaves for a first-time visitor on a light OS: System, posters.
    document.documentElement.classList.remove('dark')
    const { compact, toggle } = useCompactView()
    if (compact.value) toggle()
    localStorage.clear()
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.unstubAllGlobals()
  })

  it('is an icon button named for what it does, with no controls until opened', () => {
    wrapper = mount(DisplaySettings)

    const trigger = wrapper.get('button')
    expect(trigger.attributes('aria-label')).toBe('Display settings')
    expect(trigger.attributes('title')).toBe('Display settings')
    expect(document.body.querySelectorAll('[role="group"]')).toHaveLength(0)
  })

  it('presses the current theme and view, and only those', async () => {
    await open()

    // The popover takes its accessible name from the trigger.
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog?.getAttribute('aria-labelledby')).toBe(wrapper!.get('button').attributes('id'))

    expect(pressed('Theme')).toEqual({ Light: 'false', Dark: 'false', System: 'true' })
    expect(pressed('Events')).toEqual({ Posters: 'true', Compact: 'false' })
  })

  it('presses a stored theme over System', async () => {
    localStorage.setItem('theme', 'dark')
    await open()

    expect(pressed('Theme')).toEqual({ Light: 'false', Dark: 'true', System: 'false' })
  })

  it('switches the theme on the document and stores it', async () => {
    await open()

    option('Theme', 'Dark').click()
    await flushPromises()
    expect(isDark()).toBe(true)
    expect(localStorage.getItem('theme')).toBe('dark')

    option('Theme', 'Light').click()
    await flushPromises()
    expect(isDark()).toBe(false)
    expect(localStorage.getItem('theme')).toBe('light')
    expect(pressed('Theme')).toEqual({ Light: 'true', Dark: 'false', System: 'false' })
  })

  it('switches back to System by forgetting the choice and following the OS', async () => {
    stubOsScheme(true)
    localStorage.setItem('theme', 'light')
    await open()

    option('Theme', 'System').click()
    await flushPromises()

    expect(isDark()).toBe(true)
    expect(localStorage.getItem('theme')).toBeNull()
    expect(pressed('Theme')).toEqual({ Light: 'false', Dark: 'false', System: 'true' })
  })

  it('follows a live OS switch under System, and only under System', async () => {
    const flipOs = stubOsScheme(false)
    await open()

    flipOs(true)
    expect(isDark()).toBe(true)

    option('Theme', 'Light').click()
    await flushPromises()
    flipOs(false)
    flipOs(true)
    expect(isDark()).toBe(false)
  })

  it('switches to the compact view on the document and stores it', async () => {
    await open()

    option('Events', 'Compact').click()
    await flushPromises()

    expect(document.documentElement.classList.contains('compact')).toBe(true)
    expect(localStorage.getItem('view')).toBe('compact')
    expect(pressed('Events')).toEqual({ Posters: 'false', Compact: 'true' })
  })

  it('leaves the state alone when the pressed option is picked again', async () => {
    await open()

    option('Theme', 'System').click()
    option('Events', 'Posters').click()
    await flushPromises()

    expect(isDark()).toBe(false)
    expect(document.documentElement.classList.contains('compact')).toBe(false)
    expect(localStorage.length).toBe(0)
  })
})
