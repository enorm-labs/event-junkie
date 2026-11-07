import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ErrorLine from '@/components/ErrorLine.vue'

describe('ErrorLine', () => {
  it('announces its text as an alert', () => {
    const view = mount(ErrorLine, { slots: { default: 'That did not work.' } })

    expect(view.get('p').attributes('role')).toBe('alert')
    expect(view.text()).toBe('That did not work.')
  })

  it('passes an id and a class through to the line', () => {
    const view = mount(ErrorLine, { attrs: { id: 'sign-in-problem', class: 'pt-3' } })

    expect(view.get('p').attributes('id')).toBe('sign-in-problem')
    expect(view.get('p').classes()).toEqual(['text-body', 'text-destructive', 'pt-3'])
  })
})
