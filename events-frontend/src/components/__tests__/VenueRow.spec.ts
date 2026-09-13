import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import VenueRow from '@/components/VenueRow.vue'
import type { VenueSummary } from '@/api/types'

const venue: VenueSummary = {
  slug: 'lido',
  name: 'Lido',
  address: 'Cuvrystr. 7',
  city: 'Berlin',
  district: 'kreuzberg',
  imageUrl: 'https://example.com/lido.jpg',
}

const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

describe('VenueRow', () => {
  it('renders no image at all, even when the venue has one', () => {
    const wrapper = mount(VenueRow, { props: { venue }, global: { stubs } })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('picture').exists()).toBe(false)
  })

  it('carries the name and one where line, with the district label rather than the slug', () => {
    const wrapper = mount(VenueRow, { props: { venue }, global: { stubs } })

    expect(wrapper.text()).toContain('Lido')
    expect(wrapper.text()).toContain('Cuvrystr. 7')
    expect(wrapper.text()).toContain('Kreuzberg')
    expect(wrapper.text()).not.toContain('kreuzberg')
  })

  it('falls back to the city when there is no address or district', () => {
    const wrapper = mount(VenueRow, {
      props: { venue: { ...venue, address: null, district: null } },
      global: { stubs },
    })

    expect(wrapper.text()).toContain('Berlin')
  })

  it('links to the venue detail route, locale-prefixed', () => {
    const wrapper = mount(VenueRow, { props: { venue }, global: { stubs } })

    expect(wrapper.get('a').attributes('href')).toBe('/en/venues/lido')
  })
})
