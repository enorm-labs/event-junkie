/**
 * Sharing a page with the browser's own means: the native share sheet where there is one, the
 * clipboard everywhere. No third-party widget, and nothing is stored on the device.
 */

export type ShareOutcome = 'shared' | 'copied' | 'cancelled' | 'failed'

type ShareNavigator = Pick<Navigator, 'clipboard'> & Partial<Pick<Navigator, 'share'>>

/** Whether the browser offers a native share sheet. */
export function canShareNatively(nav: ShareNavigator | undefined = globalThis.navigator): boolean {
  return typeof nav?.share === 'function'
}

/** Writes the link to the clipboard. `failed` where the Clipboard API is missing or refused. */
export async function copyLink(
  url: string,
  nav: ShareNavigator | undefined = globalThis.navigator,
): Promise<'copied' | 'failed'> {
  try {
    if (!nav?.clipboard?.writeText) return 'failed'
    await nav.clipboard.writeText(url)
    return 'copied'
  } catch {
    return 'failed'
  }
}

/**
 * Opens the native share sheet, and copies the link when there is none or it fails. Closing the
 * sheet is the visitor's choice, not a failure, so it copies nothing.
 */
export async function shareLink(
  data: { title?: string; url: string },
  nav: ShareNavigator | undefined = globalThis.navigator,
): Promise<ShareOutcome> {
  const share = nav?.share
  if (typeof share !== 'function') return copyLink(data.url, nav)
  try {
    await share.call(nav, data)
    return 'shared'
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return 'cancelled'
    return copyLink(data.url, nav)
  }
}
