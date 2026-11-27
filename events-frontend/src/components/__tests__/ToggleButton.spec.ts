import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import ToggleButton from '@/components/ToggleButton.vue'

describe('ToggleButton', () => {
  it('shows a pressed toggle as pressed, to the eye and to a screen reader', () => {
    const wrapper = mount(ToggleButton, { props: { pressed: true }, slots: { default: 'Free' } })
    expect(wrapper.attributes('aria-pressed')).toBe('true')
    expect(wrapper.attributes('data-variant')).toBe('default')
  })

  it('shows a released toggle as released', () => {
    const wrapper = mount(ToggleButton, { props: { pressed: false }, slots: { default: 'Free' } })
    expect(wrapper.attributes('aria-pressed')).toBe('false')
    expect(wrapper.attributes('data-variant')).toBe('outline')
  })

  it('is a plain button, so it never submits a form', () => {
    const wrapper = mount(ToggleButton, { props: { pressed: false } })
    expect(wrapper.attributes('type')).toBe('button')
  })

  it('passes size, class and listeners through to the button', async () => {
    const wrapper = mount(ToggleButton, {
      props: { pressed: false },
      attrs: { size: 'sm', class: 'flex-1', 'data-testid': 'chip' },
    })
    expect(wrapper.attributes('data-size')).toBe('sm')
    expect(wrapper.classes()).toContain('flex-1')
    expect(wrapper.attributes('data-testid')).toBe('chip')
    await wrapper.trigger('click')
    expect(wrapper.emitted('click')).toHaveLength(1)
  })
})
