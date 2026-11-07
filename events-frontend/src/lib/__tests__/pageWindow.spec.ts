import { describe, expect, it } from 'vitest'
import { pageWindow } from '@/lib/pageWindow'

/** The window as the pager shows it: one-based numbers, a gap as `…`. */
const shown = (current: number, total: number) =>
  pageWindow(current, total).map((slot) => ('gap' in slot ? '…' : slot.page + 1))

describe('pageWindow', () => {
  it('names the first, the last, the current page and its neighbours', () => {
    expect(shown(9, 22)).toEqual([1, '…', 9, 10, 11, '…', 22])
  })

  it('fills a gap that would stand for one page with its number', () => {
    expect(shown(3, 22)).toEqual([1, 2, 3, 4, 5, '…', 22])
    expect(shown(18, 22)).toEqual([1, '…', 18, 19, 20, 21, 22])
  })

  it('lists every page of a short list', () => {
    expect(shown(0, 3)).toEqual([1, 2, 3])
    expect(shown(0, 1)).toEqual([1])
  })

  it('keeps its gaps apart by key', () => {
    const gaps = pageWindow(9, 22).filter((slot) => 'gap' in slot)
    expect(new Set(gaps.map((slot) => ('gap' in slot ? slot.gap : ''))).size).toBe(2)
  })
})
