import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import MapPanel from '@/components/MapPanel.vue'

const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

describe('MapPanel', () => {
  it('links the heading and shows the subtitle for one venue', () => {
    const wrapper = mount(MapPanel, {
      props: { title: 'Lido', to: '/en/venues/lido', subtitle: 'Cuvrystr. 7 · Kreuzberg' },
      slots: { default: '<p>body</p>' },
      global: { stubs },
    })

    expect(wrapper.get('h2 a').attributes('href')).toBe('/en/venues/lido')
    expect(wrapper.get('h2').text()).toBe('Lido')
    expect(wrapper.text()).toContain('Cuvrystr. 7 · Kreuzberg')
    expect(wrapper.text()).toContain('body')
  })

  it('renders a plain heading and no subtitle without them', () => {
    const wrapper = mount(MapPanel, { props: { title: '3 venues here' }, global: { stubs } })

    expect(wrapper.find('h2 a').exists()).toBe(false)
    expect(wrapper.get('h2').text()).toBe('3 venues here')
    expect(wrapper.findAll('p')).toHaveLength(0)
  })

  it('emits close from the close button', async () => {
    const wrapper = mount(MapPanel, { props: { title: 'Lido' }, global: { stubs } })

    await wrapper.get('button').trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
