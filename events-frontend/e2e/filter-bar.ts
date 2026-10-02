import type { Page } from '@playwright/test'

/**
 * Opens the filter bar's "More filters" section when it is closed. It starts closed unless the URL
 * sets one of its filters (#2347), so a test that reaches past search and dates calls this first.
 */
export async function openMoreFilters(page: Page): Promise<void> {
  const toggle = page.getByRole('button', { name: /^More filters/ })
  if ((await toggle.getAttribute('aria-expanded')) === 'false') await toggle.click()
}
