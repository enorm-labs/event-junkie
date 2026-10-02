import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import SortControl from '@/components/SortControl.vue'

const options = [
  { value: '', label: 'A–Z' },
  { value: 'upcomingEvents,desc', label: 'Most upcoming' },
]

describe('SortControl', () => {
  it('names the group with its visible label and presses the current order', () => {
    const wrapper = mount(SortControl, { props: { options, modelValue: '' } })

    const group = wrapper.get('[role="group"]')
    const label = wrapper.get(`#${group.attributes('aria-labelledby')}`)
    expect(label.text()).toBe('Sort')
    const pressed = wrapper.findAll('button').map((b) => b.attributes('aria-pressed'))
    expect(pressed).toEqual(['true', 'false'])
  })

  it('emits the order picked, and nothing for the one already pressed', async () => {
    const wrapper = mount(SortControl, { props: { options, modelValue: '' } })

    const [byName, byCount] = wrapper.findAll('button')
    await byName!.trigger('click')
    await byCount!.trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([['upcomingEvents,desc']])
  })
})
