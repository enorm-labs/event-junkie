import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import EventMetaLine from '@/components/EventMetaLine.vue'

describe('EventMetaLine', () => {
  const items = [{ text: 'Mi., 7. Okt. 2026' }, { text: '20:00' }, { text: 'Kantine am Berghain' }]

  it('puts one separator between two items, none first or last', () => {
    const wrapper = mount(EventMetaLine, { props: { items } })
    const children = wrapper.find('p').element.children
    const separators = wrapper.findAll('[data-meta-separator]')

    expect(separators).toHaveLength(2)
    expect(children[0]!.hasAttribute('data-meta-separator')).toBe(false)
    expect(children[children.length - 1]!.hasAttribute('data-meta-separator')).toBe(false)
    separators.forEach((separator) => expect(separator.attributes('aria-hidden')).toBe('true'))
  })

  it('keeps the dot on the line of the item before it', () => {
    const wrapper = mount(EventMetaLine, { props: { items } })

    expect(wrapper.text()).toBe('Mi., 7. Okt. 2026 · 20:00 · Kantine am Berghain')
  })

  it('renders a status as a pill and keeps a state word coloured', () => {
    const wrapper = mount(EventMetaLine, {
      props: {
        items: [
          { text: 'Fr., 9. Okt.' },
          { text: 'Abgesagt', badge: true },
          { text: 'Eintritt frei', class: 'text-success' },
        ],
      },
    })

    expect(wrapper.find('.rounded-full').text()).toBe('Abgesagt')
    expect(wrapper.find('span.text-success').text()).toBe('Eintritt frei')
    expect(wrapper.findAll('[data-meta-separator]')).toHaveLength(2)
  })
})
