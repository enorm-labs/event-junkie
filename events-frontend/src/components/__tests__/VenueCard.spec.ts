import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import VenueCard from '@/components/VenueCard.vue'
import type { VenueSummary } from '@/api/types'

const venue: VenueSummary = {
  slug: 'lido',
  name: 'Lido',
  city: 'Berlin',
  address: 'Cuvrystr. 7',
  district: 'kreuzberg',
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
    expect(wrapper.text()).toContain('Kreuzberg')
    expect(wrapper.text()).not.toContain('kreuzberg')
  })

  it('draws the name as the poster when the venue has no image', () => {
    const wrapper = mount(VenueCard, {
      props: { venue: { ...venue, imageUrl: null, imageSources: [] } },
      global: { stubs },
    })

    expect(wrapper.find('picture').exists()).toBe(false)
    expect(wrapper.get('[aria-hidden="true"]').text()).toBe('Lido')
  })

  it('bleeds the poster to both edges below sm, like the event card', () => {
    // Same shell, same reasoning as `EventCard.spec.ts`: `-mx-4` cancels the page's `p-4` on a
    // phone, and the card's text keeps the inset.
    const wrapper = mount(VenueCard, { props: { venue }, global: { stubs } })

    const poster = wrapper.get('picture').element.parentElement
    expect(poster?.className).toContain('-mx-4')
    expect(poster?.className).toContain('sm:mx-0')
    expect(poster?.className).toContain('group/poster')
  })

  it('bleeds a title poster the same way', () => {
    const wrapper = mount(VenueCard, {
      props: { venue: { ...venue, imageUrl: null, imageSources: [] } },
      global: { stubs },
    })

    const poster = wrapper.get('[aria-hidden="true"]').element.parentElement?.parentElement
    expect(poster?.className).toContain('group/poster')
    expect(poster?.className).toContain('-mx-4')
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
      props: { venue, as: 'h2' },
      global: { stubs },
    })
    expect(onAListPage.get('h2').text()).toBe('Lido')
    expect(onAListPage.find('h3').exists()).toBe(false)
  })

  it('carries the image credit as a title, because the card has no room for a caption', () => {
    const wrapper = mount(VenueCard, {
      props: {
        venue: {
          ...venue,
          imageAttribution: 'Photographer Name, via Wikimedia Commons',
          imageLicenceId: 'CC-BY-SA-4.0',
          imageSourceUrl: 'https://commons.wikimedia.org/wiki/File:Example.jpg',
        },
      },
      global: { stubs },
    })

    expect(wrapper.get('img').attributes('title')).toBe(
      'Photo: Photographer Name, via Wikimedia Commons · CC BY-SA 4.0',
    )
  })

  it('leaves the title off an image with no credit', () => {
    const wrapper = mount(VenueCard, { props: { venue }, global: { stubs } })
    expect(wrapper.get('img').attributes('title')).toBeUndefined()
  })

  it('falls back to the city when address and district are missing', () => {
    const wrapper = mount(VenueCard, {
      props: { venue: { slug: 'x', name: 'Somewhere', city: 'Berlin' } },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Berlin')
  })
})
