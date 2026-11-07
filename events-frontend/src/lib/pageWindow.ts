/** One slot of a numbered pager: a page, zero-based, or a gap that stands for the pages it hides. */
export type PageSlot = { page: number } | { gap: string }

/**
 * The pages a pager names: the first, the last, the current one and its neighbours, with a gap
 * between runs. A gap never stands for a single page, which its number fills as well.
 */
export function pageWindow(current: number, total: number): PageSlot[] {
  const shown = new Set([0, total - 1, current - 1, current, current + 1])
  const slots: PageSlot[] = []
  let previous = -1
  for (let page = 0; page < total; page++) {
    if (!shown.has(page)) continue
    if (page === previous + 2) slots.push({ page: previous + 1 })
    else if (page > previous + 2) slots.push({ gap: `gap-${page}` })
    slots.push({ page })
    previous = page
  }
  return slots
}
