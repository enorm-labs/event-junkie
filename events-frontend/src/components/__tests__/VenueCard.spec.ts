import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import VenueCard from '@/components/VenueCard.vue'
import type { VenueSummary } from '@/api/types'

const venue: VenueSummary = {
  slug: 'lido',
  name: 'Lido',
  city: 'Berlin',
  address: 'Cuvrystr. 7',
  district: 'friedrichshain-kreuzberg',
  imageUrl: 'https://example.com/lido.jpg',
}

// Stub RouterLink to a plain anchor so we can assert the target without a full router.
const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

describe('VenueCard', () => {
  it('renders the venue name and address', () => {
    const wrapper = mount(VenueCard, { props: { venue }, global: { stubs } })
    expect(wrapper.text()).toContain('Lido')
    expect(wrapper.text()).toContain('Cuvrystr. 7')
  })

  it('shows the human-readable district label, not the slug', () => {
    const wrapper = mount(VenueCard, { props: { venue }, global: { stubs } })
    expect(wrapper.text()).toContain('Friedrichshain-Kreuzberg')
    expect(wrapper.text()).not.toContain('friedrichshain-kreuzberg')
  })

  it('links to the venue detail route', () => {
    const wrapper = mount(VenueCard, { props: { venue }, global: { stubs } })
    // Locale-prefixed: every in-app link carries the active locale (ADR-013 §Decision 2).
    expect(wrapper.get('a').attributes('href')).toBe('/en/venues/lido')
  })

  it('titles the card h3 by default and honours an overridden level', () => {
    // h3 suits a grid sitting under a section h2; /venues has no section heading, so it asks
    // for h2 to keep the outline from skipping a level (axe `heading-order`).
    const byDefault = mount(VenueCard, { props: { venue }, global: { stubs } })
    expect(byDefault.get('h3').text()).toBe('Lido')

    const onAListPage = mount(VenueCard, {
      props: { venue, headingAs: 'h2' },
      global: { stubs },
    })
    expect(onAListPage.get('h2').text()).toBe('Lido')
    expect(onAListPage.find('h3').exists()).toBe(false)
  })

  it('falls back to the city when address and district are missing', () => {
    const wrapper = mount(VenueCard, {
      props: { venue: { slug: 'x', name: 'Somewhere', city: 'Berlin' } },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Berlin')
  })
})
