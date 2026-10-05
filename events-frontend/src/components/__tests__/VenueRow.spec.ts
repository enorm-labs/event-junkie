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

  it('appends the types and capacity to the where line', () => {
    const wrapper = mount(VenueRow, {
      props: { venue: { ...venue, venueTypes: ['bar'], capacity: 80 } },
      global: { stubs },
    })

    expect(wrapper.text()).toContain('Cuvrystr. 7 · Kreuzberg · Bar · ~80 people')
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

  it('shows the upcoming count when the venue list sent one, and nothing when it did not', () => {
    const counted = mount(VenueRow, {
      props: { venue: { ...venue, upcomingEventCount: 3 } },
      global: { stubs },
    })
    const embedded = mount(VenueRow, { props: { venue }, global: { stubs } })

    expect(counted.text()).toContain('3 upcoming events')
    expect(embedded.text()).not.toContain('upcoming')
  })

  it('puts both counts on a line under the meta line, as the card does (#2694)', () => {
    const wrapper = mount(VenueRow, {
      props: { venue: { ...venue, upcomingEventCount: 220, upcomingNext30DaysCount: 12 } },
      global: { stubs },
    })

    const lines = wrapper.findAll('p').map((line) => line.text())
    expect(lines[lines.length - 1]).toBe('12 events in the next 30 days · 220 upcoming overall')
    expect(wrapper.findAll('span.whitespace-nowrap')).toHaveLength(2)
  })

  it('shows a distance instead of the counts', () => {
    const wrapper = mount(VenueRow, {
      props: { venue: { ...venue, upcomingEventCount: 3 }, distance: '1.2 km' },
      global: { stubs },
    })

    expect(wrapper.text()).toContain('1.2 km')
    expect(wrapper.text()).not.toContain('upcoming')
  })
})
