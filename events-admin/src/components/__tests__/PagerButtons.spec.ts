import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PagerButtons from '@/components/PagerButtons.vue'

function pager(props: { hasPrevious: boolean; hasNext: boolean; loading: boolean }) {
  const wrapper = mount(PagerButtons, { props })
  const button = (label: string) => wrapper.find(`button[aria-label="${label}"]`)
  return { wrapper, previous: button('Previous page'), next: button('Next page') }
}

describe('PagerButtons', () => {
  it('disables the direction that has no page', () => {
    const first = pager({ hasPrevious: false, hasNext: true, loading: false })
    expect(first.previous.attributes('disabled')).toBeDefined()
    expect(first.next.attributes('disabled')).toBeUndefined()

    const last = pager({ hasPrevious: true, hasNext: false, loading: false })
    expect(last.previous.attributes('disabled')).toBeUndefined()
    expect(last.next.attributes('disabled')).toBeDefined()
  })

  it('disables both while a page loads', () => {
    const { previous, next } = pager({ hasPrevious: true, hasNext: true, loading: true })
    expect(previous.attributes('disabled')).toBeDefined()
    expect(next.attributes('disabled')).toBeDefined()
  })

  it('never submits an enclosing form', () => {
    const { previous, next } = pager({ hasPrevious: true, hasNext: true, loading: false })
    expect(previous.attributes('type')).toBe('button')
    expect(next.attributes('type')).toBe('button')
  })

  it('emits one event per click', async () => {
    const { wrapper, previous, next } = pager({ hasPrevious: true, hasNext: true, loading: false })
    await previous.trigger('click')
    await next.trigger('click')
    await next.trigger('click')
    expect(wrapper.emitted('previous')).toHaveLength(1)
    expect(wrapper.emitted('next')).toHaveLength(2)
  })
})
