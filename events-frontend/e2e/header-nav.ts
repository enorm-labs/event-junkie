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
 * The compact-view toggle. Below `md` it is in HeaderMenu's sheet, which leaves the header row room for the search
 * button (#2514), so the burger is opened first.
 */
export async function compactToggle(page: Page, name: RegExp, { menu = 'Menu' }: { menu?: string } = {}): Promise<Locator> {
  const header = page.getByRole('navigation', { name: 'Main' })
  if ((page.viewportSize()?.width ?? MD_PX) < MD_PX) {
    await header.getByRole('button', { name: menu, exact: true }).click()
  }
  return header.getByRole('button', { name })
}
