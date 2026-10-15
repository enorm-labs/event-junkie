import { describe, expect, it, vi } from 'vitest'

import {
  canPromptInstall,
  holdInstallPrompt,
  isInstalledApp,
  promptInstall,
} from '@/lib/installPrompt'

/** A stand-in for Chromium's `beforeinstallprompt`, which jsdom does not know. */
function installEvent() {
  const event = new Event('beforeinstallprompt', { cancelable: true }) as Event & {
    prompt: () => Promise<void>
  }
  event.prompt = vi.fn<() => Promise<void>>(() => Promise.resolve())
  return event
}

describe('installPrompt', () => {
  const target = new EventTarget() as unknown as Window
  holdInstallPrompt(target)

  it("cancels the browser's own install bar and keeps the offer", () => {
    const event = installEvent()
    target.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(canPromptInstall()).toBe(true)
  })

  it('replays the kept offer once, then has none left', async () => {
    const event = installEvent()
    target.dispatchEvent(event)
    await promptInstall()
    expect(event.prompt).toHaveBeenCalledOnce()
    expect(canPromptInstall()).toBe(false)
    await promptInstall()
    expect(event.prompt).toHaveBeenCalledOnce()
  })

  it('drops the offer once the app is installed', () => {
    target.dispatchEvent(installEvent())
    target.dispatchEvent(new Event('appinstalled'))
    expect(canPromptInstall()).toBe(false)
  })

  it('knows the installed app by its display mode, and a browser tab by its absence', () => {
    const withMode = (matches: boolean) =>
      ({ matchMedia: () => ({ matches }), navigator: {} }) as unknown as Window
    expect(isInstalledApp(withMode(true))).toBe(true)
    expect(isInstalledApp(withMode(false))).toBe(false)
    expect(isInstalledApp({ navigator: { standalone: true } } as unknown as Window)).toBe(true)
    expect(isInstalledApp({ navigator: {} } as unknown as Window)).toBe(false)
  })
})
