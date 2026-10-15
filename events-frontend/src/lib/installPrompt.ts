import { shallowRef } from 'vue'

/** Chromium's install event; not in `lib.dom`, because no other engine ships it. */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
}

const deferred = shallowRef<BeforeInstallPromptEvent | null>(null)

/**
 * Holds Chrome's install offer, so Chrome shows no "Install app" bar of its own (#2727).
 * The footer link replays the held offer on a click. Call it before mount: Chrome fires early.
 */
export function holdInstallPrompt(target: Window = window): void {
  target.addEventListener('beforeinstallprompt', (event) => {
    event.preventDefault()
    deferred.value = event as BeforeInstallPromptEvent
  })
  target.addEventListener('appinstalled', () => {
    deferred.value = null
  })
}

/** Whether the browser has offered to install the site, so a click can open its own dialog. */
export function canPromptInstall(): boolean {
  return deferred.value !== null
}

/** Opens the browser's install dialog. An offer can be shown once, so it is dropped afterwards. */
export async function promptInstall(): Promise<void> {
  const event = deferred.value
  deferred.value = null
  await event?.prompt()
}

/** Whether the page runs as the installed app, where an "Install" link has nothing left to do. */
export function isInstalledApp(target: Window = window): boolean {
  const iosStandalone = (target.navigator as Navigator & { standalone?: boolean }).standalone
  return (
    target.matchMedia?.('(display-mode: standalone)').matches === true || iosStandalone === true
  )
}
