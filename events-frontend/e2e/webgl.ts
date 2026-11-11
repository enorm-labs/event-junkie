import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * Waits until the map has drawn `drawn` or said it cannot draw, and skips the test in a browser without WebGL. The map
 * then shows its fallback text, so nothing a map test asserts can be there.
 */
export async function skipWithoutWebGL(page: Page, drawn: Locator): Promise<void> {
  const unavailable = page.getByText(/cannot draw the map/)
  await expect(drawn.or(unavailable)).toBeVisible()
  test.skip(await unavailable.isVisible(), 'no WebGL in this browser')
}
