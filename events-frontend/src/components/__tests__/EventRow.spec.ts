import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { mount } from '@vue/test-utils'
import EventRow from '@/components/EventRow.vue'
import type { EventSummary } from '@/api/types'
import { todayIso } from '@/lib/format'

/**
 * The compact row carries the card's text and none of its picture. "None" is the assertion that
 * matters: a row that rendered a hidden image would still cost the visitor the bytes (#1371).
 */

const event: EventSummary = {
  slug: 'tonight-show',
  title: 'Tonight Show',
  eventDate: '2026-06-30',
  startTime: '20:00',
  soldOut: true,
  priceCurrency: 'EUR',
  pricePresale: 25,
  eventType: 'CLUB_NIGHT',
  genreTags: ['Punk'],
  imageUrl: '/api/images/abc/192.jpg',
  imageSources: [{ type: 'image/avif', srcset: '/api/images/abc/192.avif 192w' }],
  venue: { slug: 'lido', name: 'Lido', city: 'Berlin' },
}

const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

describe('EventRow', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-06-15T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders no image at all, even when the event has one', () => {
    const wrapper = mount(EventRow, { props: { event }, global: { stubs } })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('picture').exists()).toBe(false)
    expect(wrapper.find('source').exists()).toBe(false)
  })

  it('carries the title, the venue, the time and the price', () => {
    const wrapper = mount(EventRow, { props: { event }, global: { stubs } })

    expect(wrapper.text()).toContain('Tonight Show')
    expect(wrapper.text()).toContain('Lido')
    expect(wrapper.text()).toContain('20:00')
    expect(wrapper.text()).toContain('25')
  })

  it('sets the date without the year, which the title needs the room for', () => {
    const wrapper = mount(EventRow, { props: { event }, global: { stubs } })

    expect(wrapper.text()).toContain('Tue 30 Jun')
    expect(wrapper.text()).not.toContain('30 Jun 2026')
  })

  it('links to the event detail route, locale-prefixed', () => {
    const wrapper = mount(EventRow, { props: { event }, global: { stubs } })

    expect(wrapper.get('a').attributes('href')).toBe('/en/events/tonight-show')
  })

  it('shows the same one state word as the card, with past winning', () => {
    const soldOut = mount(EventRow, { props: { event }, global: { stubs } })
    expect(soldOut.text()).toContain('Sold out')

    const past = mount(EventRow, {
      props: { event: { ...event, eventDate: '2026-06-14' } },
      global: { stubs },
    })
    expect(past.text()).toContain('Past')
    expect(past.text()).not.toContain('Sold out')
  })

  it('marks an event happening today as live', () => {
    const wrapper = mount(EventRow, {
      props: { event: { ...event, eventDate: todayIso() } },
      global: { stubs },
    })

    expect(wrapper.text()).toContain('Live tonight')
  })

  it('titles the row h3 by default and honours an overridden level', () => {
    const wrapper = mount(EventRow, { props: { event }, global: { stubs } })
    expect(wrapper.get('h3').text()).toBe('Tonight Show')

    const onList = mount(EventRow, { props: { event, as: 'h2' }, global: { stubs } })
    expect(onList.get('h2').text()).toBe('Tonight Show')
  })

  // The row takes the same fallback as the card (#1383).
  it('shows doors when there is no start, and a note when there is neither', () => {
    const doors = mount(EventRow, {
      props: { event: { ...event, startTime: null, doorsTime: '19:00:00' } },
      global: { stubs },
    })
    expect(doors.text()).toContain('Doors 19:00')

    const neither = mount(EventRow, {
      props: { event: { ...event, startTime: null, doorsTime: null } },
      global: { stubs },
    })
    expect(neither.text()).toContain('Time not announced')
  })
})
