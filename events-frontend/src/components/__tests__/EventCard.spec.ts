import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { mount } from '@vue/test-utils'
import EventCard from '@/components/EventCard.vue'
import type { EventSummary } from '@/api/types'
import { todayIso } from '@/lib/format'

const event: EventSummary = {
  slug: 'tonight-show',
  title: 'Tonight Show',
  subtitle: 'with support',
  eventDate: '2026-06-30',
  startTime: '20:00',
  soldOut: true,
  priceCurrency: 'EUR',
  pricePresale: 25,
  eventType: 'CLUB_NIGHT',
  genreTags: ['Punk'],
  venue: { slug: 'lido', name: 'Lido', city: 'Berlin' },
}

// Stub RouterLink to a plain anchor so we can assert the target without a full router.
const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

describe('EventCard', () => {
  // The card reads its date against the clock, so the fixture needs a fixed one — otherwise the
  // suite starts failing on the day `eventDate` becomes history.
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-06-15T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the event title and venue name', () => {
    const wrapper = mount(EventCard, { props: { event }, global: { stubs } })
    expect(wrapper.text()).toContain('Tonight Show')
    expect(wrapper.text()).toContain('Lido')
  })

  it('offers the cached formats the API returned for the poster', () => {
    // The wiring is what breaks silently: a card that dropped `imageSources` still renders a
    // perfectly good <img>, and every visitor quietly downloads JPEG instead of AVIF (ADR-019).
    const wrapper = mount(EventCard, {
      props: {
        event: {
          ...event,
          imageUrl: '/api/images/abc/192.jpg',
          imageSources: [{ type: 'image/avif', srcset: '/api/images/abc/192.avif 192w' }],
        },
      },
      global: { stubs },
    })

    expect(wrapper.get('source').attributes('type')).toBe('image/avif')
    // The slot the card actually draws: the whole viewport below `sm`, where the poster bleeds
    // through the page shell's padding, and about 474 px in the two-column grid above it. Without
    // this the browser reads the srcset widths against the wrong number.
    expect(wrapper.get('source').attributes('sizes')).toBe('(min-width: 640px) 474px, 100vw')
    // The box is reserved before the bytes arrive, so a portrait flyer landing in a 3:2 slot does
    // not push the text below it (#1245).
    expect(wrapper.get('picture').classes()).toContain('aspect-[3/2]')
  })

  it('draws the title as the poster when the event has no image', () => {
    // Not an edge case: one upcoming event in nine has no flyer, so this is a normal card rather
    // than a fallback nobody sees.
    const wrapper = mount(EventCard, {
      props: { event: { ...event, imageUrl: null, imageSources: [] } },
      global: { stubs },
    })

    expect(wrapper.find('picture').exists()).toBe(false)
    expect(wrapper.get('[aria-hidden="true"]').text()).toBe('Tonight Show')
  })

  it('links to the event detail route', () => {
    const wrapper = mount(EventCard, { props: { event }, global: { stubs } })
    // Locale-prefixed: every in-app link carries the active locale (ADR-013 §Decision 2).
    expect(wrapper.get('a').attributes('href')).toBe('/en/events/tonight-show')
  })

  it('titles the card h3 by default and honours an overridden level', () => {
    // h3 suits a grid sitting under a section h2 (home, detail pages); /events has no section
    // heading, so it asks for h2 to keep the outline from skipping a level (axe `heading-order`).
    const byDefault = mount(EventCard, { props: { event }, global: { stubs } })
    expect(byDefault.get('h3').text()).toBe('Tonight Show')

    const onAListPage = mount(EventCard, {
      props: { event, as: 'h2' },
      global: { stubs },
    })
    expect(onAListPage.get('h2').text()).toBe('Tonight Show')
    expect(onAListPage.find('h3').exists()).toBe(false)
  })

  it('shows a sold-out badge when the event is sold out', () => {
    const wrapper = mount(EventCard, { props: { event }, global: { stubs } })
    expect(wrapper.text()).toContain('Sold out')
  })

  it('shows a Free badge for a free event, not when sold out', () => {
    const free = mount(EventCard, {
      props: { event: { ...event, soldOut: false, free: true } },
      global: { stubs },
    })
    expect(free.text()).toContain('Free')

    // Sold out takes precedence — a sold-out event never shows the Free badge.
    const both = mount(EventCard, {
      props: { event: { ...event, soldOut: true, free: true } },
      global: { stubs },
    })
    expect(both.text()).toContain('Sold out')
    expect(both.text()).not.toContain('Free')
  })

  it('spells the clipped title and subtitle out as tooltips', () => {
    const wrapper = mount(EventCard, { props: { event }, global: { stubs } })
    expect(wrapper.get('h3').attributes('title')).toBe('Tonight Show @ Lido')
    expect(wrapper.get('p').attributes('title')).toBe('with support')
  })

  it('falls back to the bare title when the event has no venue', () => {
    const wrapper = mount(EventCard, {
      props: { event: { ...event, venue: undefined } },
      global: { stubs },
    })
    expect(wrapper.get('h3').attributes('title')).toBe('Tonight Show')
  })

  it('marks an event happening today as live', () => {
    const wrapper = mount(EventCard, {
      props: { event: { ...event, eventDate: todayIso() } },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Live tonight')
  })

  it('shows the event type as a readable pill alongside the genres', () => {
    const wrapper = mount(EventCard, { props: { event }, global: { stubs } })
    expect(wrapper.text()).toContain('Club night')
    expect(wrapper.text()).toContain('Punk')
  })

  it('omits the catch-all OTHER type rather than spending a pill on it', () => {
    const wrapper = mount(EventCard, {
      props: { event: { ...event, eventType: 'OTHER' } },
      global: { stubs },
    })
    expect(wrapper.text()).not.toContain('Other')
  })

  it('marks an event that has already happened as past', () => {
    const wrapper = mount(EventCard, {
      props: { event: { ...event, eventDate: '2026-06-14' } },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Past')
  })

  it("does not call today's event past — it may still be running", () => {
    const wrapper = mount(EventCard, {
      props: { event: { ...event, eventDate: todayIso() } },
      global: { stubs },
    })
    expect(wrapper.text()).not.toContain('Past')
  })

  it('shows Past instead of Sold out once the event has happened', () => {
    // One badge slot: "Sold out" on a past gig is stale, not informative.
    const wrapper = mount(EventCard, {
      props: { event: { ...event, eventDate: '2026-06-14', soldOut: true, free: false } },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Past')
    expect(wrapper.text()).not.toContain('Sold out')
  })

  it('does not mark an event on another day as live', () => {
    const wrapper = mount(EventCard, {
      props: { event: { ...event, eventDate: '2099-12-31' } },
      global: { stubs },
    })
    expect(wrapper.text()).not.toContain('Live tonight')
  })

  it('bleeds the poster to both edges below sm, and keeps the text inset', () => {
    // The page shell is `p-4 sm:p-8`, so `-mx-4` reaches the viewport edge on a phone and does
    // nothing from `sm` up, where the grid has two columns. Only the poster box moves: the card's
    // own text keeps the shell's inset.
    const wrapper = mount(EventCard, {
      props: { event: { ...event, imageUrl: '/api/images/abc/192.jpg' } },
      global: { stubs },
    })

    const poster = wrapper.get('picture').element.parentElement
    expect(poster?.className).toContain('-mx-4')
    expect(poster?.className).toContain('sm:mx-0')
    // The named group the touch reveal hangs off — see `useViewportFocus`.
    expect(poster?.className).toContain('group/poster')
  })

  it('bleeds a title poster the same way, and hands it the reveal group', () => {
    // One upcoming event in nine has no flyer, so a title poster is a normal card rather than a
    // fallback: it gets the same edge, and the same scroll reveal on its hairline and title.
    const wrapper = mount(EventCard, { props: { event }, global: { stubs } })

    const poster = wrapper.get('[aria-hidden="true"]').element.parentElement?.parentElement
    expect(poster?.className).toContain('group/poster')
    expect(poster?.className).toContain('-mx-4')
    expect(poster?.className).toContain('sm:mx-0')
  })

  // One event in ten has no start time on staging, and most of those venues never publish one.
  // The slot still says something: doors when they exist, labelled, and otherwise that nothing
  // was announced — an empty slot reads as our bug (#1383).
  it('shows the doors time, labelled, when there is no start time', () => {
    const wrapper = mount(EventCard, {
      props: { event: { ...event, startTime: null, doorsTime: '19:00:00' } },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Doors 19:00')
  })

  it('says the time was not announced when there is neither', () => {
    const wrapper = mount(EventCard, {
      props: { event: { ...event, startTime: null, doorsTime: null } },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Time not announced')
  })

  it('shows the start alone when both are published', () => {
    const wrapper = mount(EventCard, {
      props: { event: { ...event, doorsTime: '19:00:00' } },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('20:00')
    expect(wrapper.text()).not.toContain('Doors')
  })

  // The BFF sends its own guess when the venue published nothing; the card shows the number the
  // list sorted by, marked as a guess, and says so in words for a screen reader (#1384).
  it('marks an assumed start as a guess and explains it', () => {
    const wrapper = mount(EventCard, {
      props: {
        event: { ...event, startTime: null, doorsTime: null, assumedStartTime: '23:00:00' },
      },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('~23:00')
    expect(wrapper.text()).toContain('Not announced, estimated from the event type')
    expect(wrapper.get('[title="Not announced, estimated from the event type"]').text()).toContain(
      '~23:00',
    )
  })

  it('shows doors, not the guess, when both are sent', () => {
    const wrapper = mount(EventCard, {
      props: {
        event: { ...event, startTime: null, doorsTime: '19:00:00', assumedStartTime: '23:00:00' },
      },
      global: { stubs },
    })
    expect(wrapper.text()).toContain('Doors 19:00')
    expect(wrapper.text()).not.toContain('~')
    expect(wrapper.find('[title="Not announced, estimated from the event type"]').exists()).toBe(
      false,
    )
  })
})
