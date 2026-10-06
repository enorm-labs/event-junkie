import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import SortControl, { type SortOption } from '@/components/SortControl.vue'

const options: SortOption[] = [
  {
    directions: [
      { value: '', ascending: true, label: 'A–Z' },
      { value: 'name,desc', ascending: false, label: 'Z–A' },
    ],
  },
  { label: 'Busiest', value: 'upcomingEvents,desc' },
]

function render(modelValue: string, list: SortOption[] = options) {
  const wrapper = mount(SortControl, { props: { options: list, modelValue } })
  return { wrapper, buttons: wrapper.findAll('button') }
}

describe('SortControl', () => {
  it('shows an icon in place of the label, and still names the group "Sort"', () => {
    const { wrapper } = render('')

    const icons = wrapper.findAll('svg')
    expect(icons.length).toBeGreaterThan(0)
    for (const icon of icons) expect(icon.attributes('aria-hidden')).toBe('true')
    const group = wrapper.get('[role="group"]')
    const label = wrapper.get(`#${group.attributes('aria-labelledby')}`)
    expect(label.text()).toBe('Sort')
    expect(label.classes()).toContain('sr-only')
  })

  it('presses the current order and shows its direction', () => {
    const { buttons } = render('name,desc')
    const [byName, byCount] = buttons

    expect(buttons.map((b) => b.attributes('aria-pressed'))).toEqual(['true', 'false'])
    expect(byName!.text()).toBe('Z–A')
    expect(byName!.find('svg').attributes('aria-hidden')).toBe('true')
    expect(byCount!.find('svg').exists()).toBe(false)
  })

  it('names the direction of a labelled two-way order', () => {
    const dated: SortOption[] = [
      {
        label: 'Date',
        directions: [
          { value: '', ascending: true, label: 'earliest first' },
          { value: 'eventDate,desc', ascending: false, label: 'latest first' },
        ],
      },
    ]
    const button = render('eventDate,desc', dated).buttons[0]!

    expect(button.text()).toBe('Date')
    expect(button.attributes('aria-label')).toBe('Date, latest first')
  })

  it('flips the active two-way order', async () => {
    const asc = render('')
    await asc.buttons[0]!.trigger('click')
    expect(asc.wrapper.emitted('update:modelValue')).toEqual([['name,desc']])

    const desc = render('name,desc')
    await desc.buttons[0]!.trigger('click')
    expect(desc.wrapper.emitted('update:modelValue')).toEqual([['']])
  })

  it('picks the first direction of an inactive two-way order', async () => {
    const { wrapper, buttons } = render('upcomingEvents,desc')
    const byName = buttons[0]!

    expect(byName.text()).toBe('A–Z')
    expect(byName.find('svg').exists()).toBe(false)
    await byName.trigger('click')
    expect(wrapper.emitted('update:modelValue')).toEqual([['']])
  })

  it('emits a one-way order once, and nothing when it is pressed again', async () => {
    const inactive = render('')
    await inactive.buttons[1]!.trigger('click')
    expect(inactive.wrapper.emitted('update:modelValue')).toEqual([['upcomingEvents,desc']])

    const active = render('upcomingEvents,desc')
    await active.buttons[1]!.trigger('click')
    expect(active.wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
