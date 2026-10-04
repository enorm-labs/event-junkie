import type { Locator, Page } from '@playwright/test'

/** Tailwind's `md`: below it the section links and GitHub sit in HeaderMenu's sheet (#2291). */
const MD_PX = 768

/**
 * A link from HeaderMenu's sheet: a section, or GitHub. On a phone-width viewport the burger is opened first, so a
 * test reads the same in every Playwright project.
 */
export async function sectionLink(
  page: Page,
  name: string,
  { nav = 'Main', menu = 'Menu' }: { nav?: string; menu?: string } = {},
): Promise<Locator> {
  const header = page.getByRole('navigation', { name: nav })
  if ((page.viewportSize()?.width ?? MD_PX) < MD_PX) {
    await header.getByRole('button', { name: menu, exact: true }).click()
  }
  return header.getByRole('link', { name, exact: true })
}

/**
 * One option in the header's display settings (#2568): Theme (Light, Dark) or Events (Posters, Compact). The same
 * button at every width; the popover is opened first unless it already is.
 */
export async function displaySetting(page: Page, group: string, option: string): Promise<Locator> {
  const popover = page.getByRole('dialog', { name: 'Display settings' })
  if (!(await popover.isVisible())) {
    await page
      .getByRole('navigation', { name: 'Main' })
      .getByRole('button', { name: 'Display settings', exact: true })
      .click()
  }
  return popover.getByRole('group', { name: group, exact: true }).getByRole('button', { name: option, exact: true })
}
