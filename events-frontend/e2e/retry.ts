import { expect, type Locator, type Page } from '@playwright/test'

/**
 * Moves the page's clock past the retry window of a failing request (#3569) until `locator` shows.
 * A retry waits for its fetch before it schedules the next one, so one long jump would fire only
 * the first. Call `page.clock.install()` before `page.goto`.
 */
export async function outlastRetries(page: Page, locator: Locator): Promise<void> {
  await expect
    .poll(async () => {
      await page.clock.runFor(30_000)
      return locator.isVisible()
    })
    .toBe(true)
}
