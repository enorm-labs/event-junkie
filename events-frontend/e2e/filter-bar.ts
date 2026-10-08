import type { Page } from '@playwright/test'

/**
 * Opens the filter bar's "More filters" section when it is closed. From `sm` up it starts closed
 * unless the URL sets one of its filters (#2347); below `sm` it is a sheet that never opens by
 * itself (#2890). A test that reaches past search and the presets calls this first.
 */
export async function openMoreFilters(page: Page): Promise<void> {
  // "Filters" below `sm`, where the first row has no room for "More".
  const toggle = page.getByRole('button', { name: /^(More filters|Filters)\b/ })
  if ((await toggle.getAttribute('aria-expanded')) === 'false') await toggle.click()
}

/**
 * Closes the sheet, where there is one: while it is open the page behind it is hidden from the
 * accessibility tree, so its results cannot be asserted. The inline section stays as it is.
 */
export async function closeFilterSheet(page: Page): Promise<void> {
  const close = page.getByRole('dialog').getByRole('button', { name: 'Close filters' })
  if (await close.isVisible()) await close.click()
}
